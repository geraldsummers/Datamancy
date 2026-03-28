"""
Hyperliquid Worker
Handles Hyperliquid exchange operations via SDK
"""
from flask import Flask, request, jsonify, g, Response
import logging
import os
import hmac
import requests
import inspect
from eth_account import Account
from hyperliquid.info import Info
from hyperliquid.exchange import Exchange
from hyperliquid.utils import constants
from decimal import Decimal
from datetime import datetime, timezone
from prometheus_client import Counter, Histogram, Gauge, generate_latest, CONTENT_TYPE_LATEST
from time import monotonic, perf_counter
from threading import Lock
from waitress import serve

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Configuration
# Note: Vault removed - using ephemeral user credentials
IS_MAINNET = os.getenv('HYPERLIQUID_MAINNET', 'false').lower() == 'true'
DEFAULT_HYPERLIQUID_API_URL = constants.MAINNET_API_URL if IS_MAINNET else constants.TESTNET_API_URL
HYPERLIQUID_API_URL = os.getenv('HYPERLIQUID_API_URL', DEFAULT_HYPERLIQUID_API_URL).strip() or DEFAULT_HYPERLIQUID_API_URL
MAX_ORDER_SIZE = Decimal(os.getenv('HYPERLIQUID_MAX_ORDER_SIZE', '1000'))
MAX_ORDER_NOTIONAL_USD = Decimal(os.getenv('HYPERLIQUID_MAX_ORDER_NOTIONAL_USD', '1000000'))
WORKER_SHARED_TOKEN = os.getenv('WORKER_SHARED_TOKEN', '').strip()
WORKER_HTTP_THREADS = max(2, int(os.getenv('HYPERLIQUID_WORKER_THREADS', '8')))
INFO_CLIENT_TTL_SECONDS = max(30, int(os.getenv('HYPERLIQUID_INFO_CLIENT_TTL_SECONDS', '300')))
MARKETS_CACHE_TTL_SECONDS = max(15, int(os.getenv('HYPERLIQUID_MARKETS_CACHE_TTL_SECONDS', '60')))

if not WORKER_SHARED_TOKEN:
    logger.warning("WORKER_SHARED_TOKEN is not set; sensitive endpoints will reject requests")

REQUEST_COUNTER = Counter(
    "hyperliquid_worker_http_requests_total",
    "HTTP requests handled by hyperliquid-worker",
    ["endpoint", "method", "status"]
)
REQUEST_LATENCY = Histogram(
    "hyperliquid_worker_http_request_duration_seconds",
    "Request latency for hyperliquid-worker endpoints",
    ["endpoint", "method"]
)
HYPERLIQUID_MAINNET_GAUGE = Gauge(
    "hyperliquid_worker_mainnet_mode",
    "Whether worker is configured for Hyperliquid mainnet (1) or testnet (0)"
)
HYPERLIQUID_MAINNET_GAUGE.set(1 if IS_MAINNET else 0)

_info_client_lock = Lock()
_cached_info_client = None
_cached_info_client_built_at = 0.0
_markets_cache_lock = Lock()
_cached_markets_payload = None
_cached_markets_built_at = 0.0


@app.before_request
def _record_request_start():
    g._request_start_ts = perf_counter()


@app.after_request
def _record_request_metrics(response):
    endpoint = request.path or "unknown"
    method = request.method or "UNKNOWN"
    status = str(response.status_code)
    REQUEST_COUNTER.labels(endpoint=endpoint, method=method, status=status).inc()
    start_ts = getattr(g, "_request_start_ts", None)
    if start_ts is not None:
        REQUEST_LATENCY.labels(endpoint=endpoint, method=method).observe(max(perf_counter() - start_ts, 0.0))
    return response


def require_worker_auth():
    if not WORKER_SHARED_TOKEN:
        return jsonify({"error": "Worker auth is not configured"}), 503

    provided = request.headers.get("X-Worker-Token", "")
    if not hmac.compare_digest(provided, WORKER_SHARED_TOKEN):
        return jsonify({"error": "Unauthorized worker request"}), 401
    return None


def request_json_payload():
    """Return a parsed JSON object or an empty dict for validation-first handlers."""
    payload = request.get_json(silent=True)
    return payload if isinstance(payload, dict) else {}


def parse_positive_decimal(raw_value, field_name: str):
    """Parse a positive decimal request field or return an error message tuple."""
    try:
        parsed = Decimal(str(raw_value))
    except Exception:
        return None, f"Invalid {field_name}"
    if parsed <= 0:
        return None, f"{field_name} must be > 0"
    return parsed, None


def parse_bool(raw_value, field_name: str, default: bool = False):
    if raw_value is None:
        return default, None
    if isinstance(raw_value, bool):
        return raw_value, None
    if isinstance(raw_value, str):
        normalized = raw_value.strip().lower()
        if normalized in {"true", "1", "yes", "y", "on"}:
            return True, None
        if normalized in {"false", "0", "no", "n", "off"}:
            return False, None
    return None, f"Invalid {field_name}"


def decimal_to_bps_ratio(value: Decimal) -> Decimal:
    return value / Decimal("10000")


def derive_limit_price_from_slippage(side: str, reference_price: Decimal, max_slippage_bps: Decimal) -> Decimal:
    slippage_ratio = decimal_to_bps_ratio(max_slippage_bps)
    if side == "BUY":
        return reference_price * (Decimal("1") + slippage_ratio)
    return reference_price * (Decimal("1") - slippage_ratio)


def ensure_limit_price_within_slippage(symbol: str, side: str, limit_price: Decimal, max_slippage_bps: Decimal):
    reference_price = resolve_reference_price(symbol=symbol, order_type="MARKET", explicit_price=None)
    max_limit = derive_limit_price_from_slippage(side=side, reference_price=reference_price, max_slippage_bps=max_slippage_bps)
    if side == "BUY" and limit_price > max_limit:
        raise ValueError(
            f"Limit price exceeds slippage guard (limitPrice={limit_price}, maxAllowed={max_limit}, maxSlippageBps={max_slippage_bps})"
        )
    if side == "SELL" and limit_price < max_limit:
        raise ValueError(
            f"Limit price exceeds slippage guard (limitPrice={limit_price}, minAllowed={max_limit}, maxSlippageBps={max_slippage_bps})"
        )


def build_limit_order_type(post_only: bool, cancel_after_ms: int | None):
    if post_only:
        return {"limit": {"tif": "Alo"}}
    if cancel_after_ms is not None and cancel_after_ms <= 1500:
        return {"limit": {"tif": "Ioc"}}
    return {"limit": {"tif": "Gtc"}}


def _supports_param(fn, name: str) -> bool:
    try:
        return name in inspect.signature(fn).parameters
    except (TypeError, ValueError):
        return False


def submit_limit_order(
    exchange: Exchange,
    symbol: str,
    is_buy: bool,
    size_float: float,
    limit_px: float,
    order_type: dict,
    reduce_only: bool
):
    base_kwargs = {
        "is_buy": is_buy,
        "sz": size_float,
        "limit_px": limit_px,
        "order_type": order_type
    }
    symbol_keys = ["name", "symbol"] if _supports_param(exchange.order, "name") else ["symbol", "name"]
    reduce_options = [True, False] if reduce_only else [False]
    last_type_error = None

    for symbol_key in symbol_keys:
        for include_reduce_only in reduce_options:
            kwargs = dict(base_kwargs)
            kwargs[symbol_key] = symbol
            if include_reduce_only:
                kwargs["reduce_only"] = True
            try:
                return exchange.order(**kwargs)
            except TypeError as exc:
                last_type_error = exc

    if last_type_error is not None:
        raise last_type_error
    raise RuntimeError("Unable to submit limit order")


def submit_market_order(
    exchange: Exchange,
    symbol: str,
    is_buy: bool,
    size_float: float,
    reduce_only: bool,
    slippage: float | None = None
):
    # Slippage-constrained market orders are executed as IOC limit orders for deterministic bounds.
    if slippage is not None and hasattr(exchange, "order"):
        slippage_bps = Decimal(str(slippage)) * Decimal("10000")
        reference_price = resolve_reference_price(symbol=symbol, order_type="MARKET", explicit_price=None)
        limit_px = derive_limit_price_from_slippage(
            side="BUY" if is_buy else "SELL",
            reference_price=reference_price,
            max_slippage_bps=slippage_bps
        )
        return submit_limit_order(
            exchange=exchange,
            symbol=symbol,
            is_buy=is_buy,
            size_float=size_float,
            limit_px=float(limit_px),
            order_type={"limit": {"tif": "Ioc"}},
            reduce_only=reduce_only
        )

    if reduce_only and hasattr(exchange, "market_close"):
        market_kwargs = {"sz": size_float}
        if slippage is not None and _supports_param(exchange.market_close, "slippage"):
            market_kwargs["slippage"] = slippage
        symbol_keys = ["coin", "name", "symbol"]
        last_type_error = None
        for symbol_key in symbol_keys:
            kwargs = dict(market_kwargs)
            kwargs[symbol_key] = symbol
            try:
                return exchange.market_close(**kwargs)
            except TypeError as exc:
                last_type_error = exc
        if last_type_error is not None:
            raise last_type_error

    if hasattr(exchange, "market_order"):
        market_kwargs = {
            "is_buy": is_buy,
            "sz": size_float
        }
        if reduce_only and _supports_param(exchange.market_order, "reduce_only"):
            market_kwargs["reduce_only"] = True
        if slippage is not None and _supports_param(exchange.market_order, "slippage"):
            market_kwargs["slippage"] = slippage

        symbol_keys = ["name", "symbol"] if _supports_param(exchange.market_order, "name") else ["symbol", "name"]
        last_type_error = None
        for symbol_key in symbol_keys:
            kwargs = dict(market_kwargs)
            kwargs[symbol_key] = symbol
            try:
                return exchange.market_order(**kwargs)
            except TypeError as exc:
                last_type_error = exc
        if last_type_error is not None:
            raise last_type_error

    if hasattr(exchange, "market_open"):
        open_kwargs = {
            "is_buy": is_buy,
            "sz": size_float
        }
        if slippage is not None and _supports_param(exchange.market_open, "slippage"):
            open_kwargs["slippage"] = slippage
        symbol_keys = ["name", "symbol"] if _supports_param(exchange.market_open, "name") else ["symbol", "name"]
        last_type_error = None
        for symbol_key in symbol_keys:
            kwargs = dict(open_kwargs)
            kwargs[symbol_key] = symbol
            try:
                return exchange.market_open(**kwargs)
            except TypeError as exc:
                last_type_error = exc
        if last_type_error is not None:
            raise last_type_error

    raise RuntimeError("No compatible market order method found on exchange client")


def parse_hyperliquid_status(status_info: dict) -> str:
    raw = status_info.get("status")
    if isinstance(raw, str) and raw.strip():
        return raw.strip().upper()
    if isinstance(status_info.get("filled"), dict):
        return "FILLED"
    if isinstance(status_info.get("resting"), dict):
        return "PENDING"
    return "UNKNOWN"


def parse_order_id(status_info: dict, filled: dict) -> str:
    oid = filled.get("oid")
    if oid is None and isinstance(status_info.get("resting"), dict):
        oid = status_info["resting"].get("oid")
    if oid is None:
        oid = status_info.get("oid")
    if oid is None:
        return "unknown"
    return str(oid)


def parse_fill_price(filled: dict):
    for key in ("px", "avgPx", "price"):
        value = filled.get(key)
        if value is not None and str(value).strip():
            return str(value)
    return None


def parse_filled_size(status_info: dict, filled: dict, requested_size: Decimal) -> Decimal:
    for key in ("totalSz", "sz", "filledSz", "size"):
        value = filled.get(key)
        if value is None:
            continue
        parsed, err = parse_positive_decimal(value, key)
        if err is None and parsed is not None:
            return parsed
    status = parse_hyperliquid_status(status_info)
    if status == "FILLED":
        return requested_size
    return Decimal("0")

def parse_hyperliquid_key(api_key: str) -> dict:
    """Parse Hyperliquid API key into address and private key"""
    normalized = (api_key or "").strip()
    if not normalized:
        raise ValueError("hyperliquidKey is empty")

    if ":" in normalized:
        address, private_key = normalized.split(":", 1)
        parsed_address = address.strip() or None
        parsed_private_key = private_key.strip()
    else:
        parsed_address = None
        parsed_private_key = normalized

    if not parsed_private_key:
        raise ValueError("Missing private key in hyperliquidKey")
    return {"address": parsed_address, "private_key": parsed_private_key}


def derive_evm_address(private_key: str) -> str:
    key = private_key.strip()
    if not key:
        raise ValueError("Missing private key in hyperliquidKey")
    if not key.startswith("0x"):
        key = f"0x{key}"
    try:
        return Account.from_key(key).address
    except Exception as exc:
        raise ValueError("Unable to derive address from hyperliquidKey private key") from exc


def resolve_account_address(creds: dict) -> str:
    derived_address = derive_evm_address(creds["private_key"])
    explicit_address = creds.get("address")
    return explicit_address or derived_address


def resolve_reference_price(symbol: str, order_type: str, explicit_price: Decimal | None) -> Decimal:
    if explicit_price is not None:
        return explicit_price

    if order_type != "MARKET":
        raise ValueError("Price required to estimate order notional")

    mids = get_info_client().all_mids() or {}
    symbol_candidates = [
        symbol,
        str(symbol).upper(),
        str(symbol).lower(),
        str(symbol).replace("-PERP", ""),
        str(symbol).replace("-PERP", "").upper(),
        str(symbol).replace("-PERP", "").lower(),
    ]
    for candidate in symbol_candidates:
        mid_raw = mids.get(candidate)
        if mid_raw is None:
            continue
        mid_price, price_error = parse_positive_decimal(mid_raw, "marketPrice")
        if price_error is None and mid_price is not None:
            return mid_price

    raise ValueError(f"Unable to estimate market notional for symbol '{symbol}'")


def ensure_order_notional_within_limit(symbol: str, order_type: str, size_decimal: Decimal, price_decimal: Decimal | None):
    reference_price = resolve_reference_price(symbol=symbol, order_type=order_type, explicit_price=price_decimal)
    notional = size_decimal * reference_price
    if notional > MAX_ORDER_NOTIONAL_USD:
        raise ValueError(
            f"Order notional exceeds max allowed (requestedNotionalUsd={notional}, maxNotionalUsd={MAX_ORDER_NOTIONAL_USD})"
        )

def get_exchange_client(hyperliquid_key: str) -> Exchange:
    """Get authenticated Exchange client using ephemeral credentials"""
    creds = parse_hyperliquid_key(hyperliquid_key)
    private_key = creds["private_key"].strip()
    explicit_address = creds.get("address")
    spot_meta = None if IS_MAINNET else {"universe": [], "tokens": []}

    # Legacy SDK constructor compatibility.
    legacy_variants = []
    if explicit_address:
        legacy_variants.extend([
            {
                "address": explicit_address,
                "private_key": private_key,
                "skip_ws": True,
                "base_url": HYPERLIQUID_API_URL
            },
            {
                "address": explicit_address,
                "private_key": private_key,
                "skip_ws": True
            }
        ])
    legacy_variants.extend([
        {
            "private_key": private_key,
            "skip_ws": True,
            "base_url": HYPERLIQUID_API_URL
        },
        {
            "private_key": private_key,
            "skip_ws": True
        }
    ])
    for kwargs in legacy_variants:
        try:
            return Exchange(**kwargs)
        except TypeError:
            pass

    account_address = resolve_account_address(creds)
    wallet_key = private_key if private_key.startswith("0x") else f"0x{private_key}"
    wallet = Account.from_key(wallet_key)

    modern_variants = [
        {
            "wallet": wallet,
            "base_url": HYPERLIQUID_API_URL,
            "account_address": account_address,
            "spot_meta": spot_meta
        },
        {
            "wallet": wallet,
            "base_url": HYPERLIQUID_API_URL,
            "account_address": account_address
        },
        {
            "wallet": wallet,
            "base_url": HYPERLIQUID_API_URL
        }
    ]
    last_type_error = None
    for kwargs in modern_variants:
        try:
            return Exchange(**kwargs)
        except TypeError as exc:
            last_type_error = exc

    if last_type_error is not None:
        raise last_type_error
    raise RuntimeError("Unable to initialize Hyperliquid Exchange client")

def get_info_client() -> Info:
    """Get Info client for market data queries"""
    global _cached_info_client, _cached_info_client_built_at
    now = monotonic()
    with _info_client_lock:
        if _cached_info_client is not None and (now - _cached_info_client_built_at) < INFO_CLIENT_TTL_SECONDS:
            return _cached_info_client

        spot_meta = None if IS_MAINNET else {"universe": [], "tokens": []}
        fresh_client = Info(base_url=HYPERLIQUID_API_URL, skip_ws=True, spot_meta=spot_meta)
        _cached_info_client = fresh_client
        _cached_info_client_built_at = now
        return fresh_client


def load_markets_payload(force_refresh: bool = False) -> dict:
    global _cached_markets_payload, _cached_markets_built_at
    now = monotonic()
    with _markets_cache_lock:
        if (
            not force_refresh
            and _cached_markets_payload is not None
            and (now - _cached_markets_built_at) < MARKETS_CACHE_TTL_SECONDS
        ):
            return _cached_markets_payload

        try:
            info = get_info_client()
            meta = info.meta() or {}
            universe = meta.get("universe", []) if isinstance(meta, dict) else []

            markets_payload = []
            for entry in universe:
                if isinstance(entry, dict):
                    symbol = entry.get("name") or entry.get("coin")
                    if symbol:
                        markets_payload.append(entry | {"symbol": symbol})

            payload = {
                "markets": markets_payload,
                "count": len(markets_payload),
                "mainnet": IS_MAINNET,
                "cached": False,
                "fetchedAt": datetime.now(timezone.utc).isoformat()
            }
            _cached_markets_payload = payload
            _cached_markets_built_at = now
            return payload
        except Exception:
            if _cached_markets_payload is not None:
                logger.warning("Markets query failed; serving stale cached catalog", exc_info=True)
                stale_payload = dict(_cached_markets_payload)
                stale_payload["cached"] = True
                return stale_payload
            raise


@app.route('/metrics', methods=['GET'])
def metrics():
    return Response(generate_latest(), mimetype=CONTENT_TYPE_LATEST)


@app.route('/markets', methods=['GET'])
def markets():
    """Get available markets from Hyperliquid meta feed."""
    try:
        return jsonify(load_markets_payload())
    except Exception as e:
        logger.error(f"Markets query failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/order', methods=['POST'])
def submit_order():
    """Submit order to Hyperliquid - uses ephemeral credentials"""
    try:
        auth_error = require_worker_auth()
        if auth_error:
            return auth_error

        data = request_json_payload()
        username = data.get('username')
        symbol = data.get('symbol')
        side = str(data.get('side', '')).upper()  # "BUY" or "SELL"
        order_type = str(data.get('type', '')).upper()  # "MARKET" or "LIMIT"
        size = data.get('size')
        price = data.get('price')  # Required for LIMIT orders
        max_slippage_bps = data.get('maxSlippageBps')
        cancel_after_ms = data.get('cancelAfterMs')
        reduce_only, reduce_only_error = parse_bool(data.get('reduceOnly'), "reduceOnly", default=False)
        if reduce_only_error:
            return jsonify({"error": reduce_only_error}), 400
        post_only, post_only_error = parse_bool(data.get('postOnly'), "postOnly", default=False)
        if post_only_error:
            return jsonify({"error": post_only_error}), 400
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400
        if not symbol:
            return jsonify({"error": "Missing symbol"}), 400
        if side not in {"BUY", "SELL"}:
            return jsonify({"error": f"Unsupported side: {side}"}), 400
        if order_type not in {"MARKET", "LIMIT"}:
            return jsonify({"error": f"Unsupported order type: {order_type}"}), 400
        if post_only and order_type != "LIMIT":
            return jsonify({"error": "postOnly is only valid for LIMIT orders"}), 400

        size_decimal, size_error = parse_positive_decimal(size, "size")
        if size_error:
            return jsonify({"error": size_error}), 400
        if size_decimal > MAX_ORDER_SIZE:
            return jsonify({
                "error": "Order size exceeds max allowed",
                "maxSize": str(MAX_ORDER_SIZE),
                "requestedSize": str(size_decimal)
            }), 400

        slippage_decimal = None
        if max_slippage_bps is not None:
            slippage_decimal, slippage_error = parse_positive_decimal(max_slippage_bps, "maxSlippageBps")
            if slippage_error:
                return jsonify({"error": slippage_error}), 400
            if slippage_decimal > Decimal("500"):
                return jsonify({"error": "maxSlippageBps is unreasonably high"}), 400

        if cancel_after_ms is not None:
            try:
                cancel_after_ms = int(cancel_after_ms)
            except Exception:
                return jsonify({"error": "Invalid cancelAfterMs"}), 400
            if cancel_after_ms < 100:
                return jsonify({"error": "cancelAfterMs must be >= 100"}), 400
            if cancel_after_ms > 600000:
                return jsonify({"error": "cancelAfterMs must be <= 600000"}), 400

        price_decimal = None
        if order_type == "LIMIT":
            if not price:
                return jsonify({"error": "Price required for limit orders"}), 400
            price_decimal, price_error = parse_positive_decimal(price, "price")
            if price_error:
                return jsonify({"error": price_error}), 400
            if slippage_decimal is not None:
                try:
                    ensure_limit_price_within_slippage(
                        symbol=symbol,
                        side=side,
                        limit_price=price_decimal,
                        max_slippage_bps=slippage_decimal
                    )
                except ValueError as exc:
                    return jsonify({"error": str(exc)}), 400

        try:
            ensure_order_notional_within_limit(
                symbol=symbol,
                order_type=order_type,
                size_decimal=size_decimal,
                price_decimal=price_decimal,
            )
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400

        logger.info(
            f"Order: {username} {side} {size} {symbol} @ {price} ({order_type}) "
            f"reduceOnly={reduce_only} postOnly={post_only} maxSlippageBps={slippage_decimal} cancelAfterMs={cancel_after_ms}"
        )

        try:
            exchange = get_exchange_client(hyperliquid_key)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400

        # Convert side to Hyperliquid format: true for buy, false for sell
        is_buy = side == "BUY"
        size_float = float(size_decimal)

        if order_type == "MARKET":
            requested_slippage = None
            if slippage_decimal is not None:
                # Exchange SDK expects fractional slippage (e.g. 0.0035 = 35 bps).
                requested_slippage = float(decimal_to_bps_ratio(slippage_decimal))
            result = submit_market_order(
                exchange=exchange,
                symbol=symbol,
                is_buy=is_buy,
                size_float=size_float,
                reduce_only=reduce_only,
                slippage=requested_slippage
            )
        else:
            tif = build_limit_order_type(post_only=post_only, cancel_after_ms=cancel_after_ms)
            result = submit_limit_order(
                exchange=exchange,
                symbol=symbol,
                is_buy=is_buy,
                size_float=size_float,
                limit_px=float(price_decimal),
                order_type=tif,
                reduce_only=reduce_only
            )

        logger.info(f"Order result: {result}")

        # Parse result
        if result.get('status') == 'ok':
            response = result['response']
            statuses = response.get('data', {}).get('statuses', [])
            if statuses:
                status_info = statuses[0] if isinstance(statuses[0], dict) else {}
                filled = status_info.get('filled', {})
                parsed_status = parse_hyperliquid_status(status_info)
                filled_size = parse_filled_size(status_info, filled, size_decimal)
                fill_price = parse_fill_price(filled)
                executed_notional_usd = None
                if fill_price is not None:
                    fill_price_decimal, fill_price_error = parse_positive_decimal(fill_price, "fillPrice")
                    if fill_price_error is None and fill_price_decimal is not None and filled_size > Decimal("0"):
                        executed_notional_usd = str((fill_price_decimal * filled_size).normalize())
                return jsonify({
                    "orderId": parse_order_id(status_info, filled),
                    "symbol": symbol,
                    "side": side,
                    "type": order_type,
                    "size": size,
                    "price": price,
                    "status": parsed_status,
                    "fillPrice": fill_price,
                    "filledSize": str(filled_size.normalize()),
                    "executedNotionalUsd": executed_notional_usd,
                    "reduceOnly": reduce_only,
                    "postOnly": post_only,
                    "maxSlippageBps": str(slippage_decimal) if slippage_decimal is not None else None,
                    "cancelAfterMs": cancel_after_ms,
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                    "raw": result
                })

        return jsonify({
            "error": "Order failed",
            "details": result
        }), 500

    except Exception as e:
        logger.error(f"Order failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/cancel/<order_id>', methods=['POST'])
def cancel_order(order_id):
    """Cancel Hyperliquid order - uses ephemeral credentials"""
    try:
        auth_error = require_worker_auth()
        if auth_error:
            return auth_error

        data = request_json_payload()
        username = data.get('username')
        symbol = data.get('symbol')  # Required for cancellation
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400
        if not symbol:
            return jsonify({"error": "Missing symbol"}), 400
        try:
            parsed_order_id = int(order_id)
        except Exception:
            return jsonify({"error": "Invalid orderId"}), 400

        logger.info(f"Cancel order: {order_id} for {username}")

        try:
            exchange = get_exchange_client(hyperliquid_key)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        result = exchange.cancel(symbol=symbol, oid=parsed_order_id)

        logger.info(f"Cancel result: {result}")

        return jsonify({
            "status": "cancelled" if result.get('status') == 'ok' else "failed",
            "orderId": order_id,
            "raw": result
        })

    except Exception as e:
        logger.error(f"Cancel failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/cancel-all', methods=['POST'])
def cancel_all():
    """Cancel all open orders - uses ephemeral credentials"""
    try:
        auth_error = require_worker_auth()
        if auth_error:
            return auth_error

        data = request_json_payload()
        username = data.get('username')
        symbol = data.get('symbol')  # Optional, cancels all if not provided
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400

        logger.info(f"Cancel all orders for {username} (symbol: {symbol})")

        try:
            exchange = get_exchange_client(hyperliquid_key)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        result = exchange.cancel_all_orders(symbol=symbol)

        return jsonify({
            "status": "success" if result.get('status') == 'ok' else "failed",
            "raw": result
        })

    except Exception as e:
        logger.error(f"Cancel all failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/positions', methods=['POST'])
def get_positions():
    """Get user positions - uses ephemeral credentials"""
    try:
        auth_error = require_worker_auth()
        if auth_error:
            return auth_error

        data = request_json_payload()
        username = data.get('user') or data.get('username')
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey parameter"}), 400

        logger.info(f"Get positions for {username}")

        try:
            creds = parse_hyperliquid_key(hyperliquid_key)
            address = resolve_account_address(creds)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        info = get_info_client()

        # Get user state which includes positions
        user_state = info.user_state(address)

        positions = []
        if user_state and 'assetPositions' in user_state:
            for pos in user_state['assetPositions']:
                position = pos.get('position', {})
                if float(position.get('szi', 0)) != 0:  # Non-zero position
                    positions.append({
                        "symbol": position.get('coin'),
                        "size": position.get('szi'),
                        "entryPrice": position.get('entryPx'),
                        "unrealizedPnl": position.get('unrealizedPnl'),
                        "returnOnEquity": position.get('returnOnEquity'),
                        "leverage": position.get('leverage', {}).get('value'),
                        "liquidationPrice": position.get('liquidationPx'),
                        "marginUsed": position.get('marginUsed')
                    })

        logger.info(f"Found {len(positions)} positions")
        return jsonify(positions)

    except Exception as e:
        logger.error(f"Get positions failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/balance', methods=['POST'])
def get_balance():
    """Get user balance - uses ephemeral credentials"""
    try:
        auth_error = require_worker_auth()
        if auth_error:
            return auth_error

        data = request_json_payload()
        username = data.get('user') or data.get('username')
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey parameter"}), 400

        logger.info(f"Get balance for {username}")

        try:
            creds = parse_hyperliquid_key(hyperliquid_key)
            address = resolve_account_address(creds)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        info = get_info_client()

        user_state = info.user_state(address)

        if user_state and 'marginSummary' in user_state:
            margin = user_state['marginSummary']
            return jsonify({
                "accountValue": margin.get('accountValue'),
                "totalMarginUsed": margin.get('totalMarginUsed'),
                "totalNtlPos": margin.get('totalNtlPos'),
                "totalRawUsd": margin.get('totalRawUsd'),
                "withdrawable": margin.get('withdrawable')
            })

        return jsonify({
            "accountValue": "0.0",
            "withdrawable": "0.0"
        })

    except Exception as e:
        logger.error(f"Get balance failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/orders', methods=['POST'])
def get_orders():
    """Get open orders - uses ephemeral credentials"""
    try:
        auth_error = require_worker_auth()
        if auth_error:
            return auth_error

        data = request_json_payload()
        username = data.get('user') or data.get('username')
        symbol_filter = str(data.get('symbol') or '').strip()
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey parameter"}), 400

        logger.info(f"Get orders for {username}")

        try:
            creds = parse_hyperliquid_key(hyperliquid_key)
            address = resolve_account_address(creds)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        info = get_info_client()

        open_orders = info.open_orders(address)

        orders = []
        if open_orders:
            for order in open_orders:
                symbol = order.get('coin')
                if symbol_filter and symbol != symbol_filter:
                    continue
                orders.append({
                    "orderId": order.get('oid'),
                    "symbol": symbol,
                    "side": "BUY" if order.get('side') == 'B' else "SELL",
                    "size": order.get('sz'),
                    "price": order.get('limitPx'),
                    "timestamp": order.get('timestamp')
                })

        return jsonify(orders)

    except Exception as e:
        logger.error(f"Get orders failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/close', methods=['POST'])
def close_position():
    """Close position by symbol - uses ephemeral credentials"""
    try:
        auth_error = require_worker_auth()
        if auth_error:
            return auth_error

        data = request_json_payload()
        username = data.get('username')
        symbol = data.get('symbol')
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400
        if not symbol:
            return jsonify({"error": "Missing symbol"}), 400

        logger.info(f"Close position for {username}: {symbol}")

        # Get current position to determine size
        try:
            creds = parse_hyperliquid_key(hyperliquid_key)
            address = resolve_account_address(creds)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        info = get_info_client()
        user_state = info.user_state(address)

        position_size = None
        if user_state and 'assetPositions' in user_state:
            for pos in user_state['assetPositions']:
                position = pos.get('position', {})
                if position.get('coin') == symbol:
                    position_size = float(position.get('szi', 0))
                    break

        if position_size is None or position_size == 0:
            return jsonify({"error": "No position found"}), 404

        # Close with market order in opposite direction
        try:
            exchange = get_exchange_client(hyperliquid_key)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        is_buy = position_size < 0  # If short, buy to close

        result = exchange.market_order(
            symbol=symbol,
            is_buy=is_buy,
            sz=abs(position_size)
        )

        logger.info(f"Close result: {result}")

        return jsonify({
            "status": "closed" if result.get('status') == 'ok' else "failed",
            "symbol": symbol,
            "size": position_size,
            "raw": result
        })

    except Exception as e:
        logger.error(f"Close position failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500


@app.route('/close-all', methods=['POST'])
def close_all_positions():
    """Close all non-zero positions for the user."""
    try:
        auth_error = require_worker_auth()
        if auth_error:
            return auth_error

        data = request_json_payload()
        username = data.get('username') or data.get('user')
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400

        try:
            creds = parse_hyperliquid_key(hyperliquid_key)
            address = resolve_account_address(creds)
            exchange = get_exchange_client(hyperliquid_key)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400

        info = get_info_client()
        user_state = info.user_state(address)
        positions = []

        if user_state and 'assetPositions' in user_state:
            for pos in user_state['assetPositions']:
                position = pos.get('position', {})
                symbol = position.get('coin')
                size_raw = position.get('szi', 0)
                try:
                    size = float(size_raw)
                except Exception:
                    size = 0.0
                if symbol and size != 0.0:
                    positions.append((symbol, size))

        if not positions:
            return jsonify({
                "status": "no_positions",
                "closed": 0,
                "details": []
            })

        closed = []
        for symbol, size in positions:
            is_buy = size < 0  # short -> buy to close
            result = exchange.market_order(
                symbol=symbol,
                is_buy=is_buy,
                sz=abs(size)
            )
            closed.append({
                "symbol": symbol,
                "size": size,
                "status": "closed" if result.get("status") == "ok" else "failed",
                "raw": result
            })

        return jsonify({
            "status": "completed",
            "username": username,
            "closed": len(closed),
            "details": closed
        })

    except Exception as e:
        logger.error(f"Close-all failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/health', methods=['GET'])
def health():
    """Health check"""
    try:
        catalog = load_markets_payload()
        api_status = "healthy" if catalog.get("count", 0) >= 0 else "degraded"
    except Exception as e:
        logger.error(f"API unhealthy: {e}")
        api_status = "unhealthy"

    return jsonify({
        "status": "healthy" if api_status == "healthy" else "degraded",
        "service": "hyperliquid-worker",
        "api": api_status,
        "mainnet": IS_MAINNET,
        "apiUrl": HYPERLIQUID_API_URL
    })

if __name__ == '__main__':
    logger.info(f"Starting Hyperliquid Worker - {'MAINNET' if IS_MAINNET else 'TESTNET'}")
    serve(app, host='0.0.0.0', port=8082, threads=WORKER_HTTP_THREADS)
