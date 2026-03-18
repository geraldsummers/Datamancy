"""
Hyperliquid Worker
Handles Hyperliquid exchange operations via SDK
"""
from flask import Flask, request, jsonify
import logging
import os
import requests
from eth_account import Account
from hyperliquid.info import Info
from hyperliquid.exchange import Exchange
from hyperliquid.utils import constants
from decimal import Decimal
from datetime import datetime, timezone

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Configuration
# Note: Vault removed - using ephemeral user credentials
IS_MAINNET = os.getenv('HYPERLIQUID_MAINNET', 'true').lower() == 'true'
MAX_ORDER_SIZE = Decimal(os.getenv('HYPERLIQUID_MAX_ORDER_SIZE', '1000'))
MAX_ORDER_NOTIONAL_USD = Decimal(os.getenv('HYPERLIQUID_MAX_ORDER_NOTIONAL_USD', '1000000'))


def parse_positive_decimal(raw_value, field_name: str):
    """Parse a positive decimal request field or return an error message tuple."""
    try:
        parsed = Decimal(str(raw_value))
    except Exception:
        return None, f"Invalid {field_name}"
    if parsed <= 0:
        return None, f"{field_name} must be > 0"
    return parsed, None

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
    return creds.get("address") or derive_evm_address(creds["private_key"])


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
    base_url = constants.MAINNET_API_URL if IS_MAINNET else constants.TESTNET_API_URL
    return Exchange(
        address=creds.get("address"),
        private_key=creds["private_key"],
        base_url=base_url,
        skip_ws=True
    )

def get_info_client() -> Info:
    """Get Info client for market data queries"""
    base_url = constants.MAINNET_API_URL if IS_MAINNET else constants.TESTNET_API_URL
    return Info(base_url=base_url, skip_ws=True)

@app.route('/order', methods=['POST'])
def submit_order():
    """Submit order to Hyperliquid - uses ephemeral credentials"""
    try:
        data = request.json
        username = data.get('username')
        symbol = data.get('symbol')
        side = str(data.get('side', '')).upper()  # "BUY" or "SELL"
        order_type = str(data.get('type', '')).upper()  # "MARKET" or "LIMIT"
        size = data.get('size')
        price = data.get('price')  # Required for LIMIT orders
        max_slippage_bps = data.get('maxSlippageBps')
        cancel_after_ms = data.get('cancelAfterMs')
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400
        if not symbol:
            return jsonify({"error": "Missing symbol"}), 400
        if side not in {"BUY", "SELL"}:
            return jsonify({"error": f"Unsupported side: {side}"}), 400
        if order_type not in {"MARKET", "LIMIT"}:
            return jsonify({"error": f"Unsupported order type: {order_type}"}), 400

        size_decimal, size_error = parse_positive_decimal(size, "size")
        if size_error:
            return jsonify({"error": size_error}), 400
        if size_decimal > MAX_ORDER_SIZE:
            return jsonify({
                "error": "Order size exceeds max allowed",
                "maxSize": str(MAX_ORDER_SIZE),
                "requestedSize": str(size_decimal)
            }), 400

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

        price_decimal = None
        if order_type == "LIMIT":
            if not price:
                return jsonify({"error": "Price required for limit orders"}), 400
            price_decimal, price_error = parse_positive_decimal(price, "price")
            if price_error:
                return jsonify({"error": price_error}), 400

        try:
            ensure_order_notional_within_limit(
                symbol=symbol,
                order_type=order_type,
                size_decimal=size_decimal,
                price_decimal=price_decimal,
            )
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400

        logger.info(f"Order: {username} {side} {size} {symbol} @ {price} ({order_type})")

        try:
            exchange = get_exchange_client(hyperliquid_key)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400

        # Convert side to Hyperliquid format: true for buy, false for sell
        is_buy = side == "BUY"
        size_float = float(size_decimal)

        if order_type == "MARKET":
            # Market order
            result = exchange.market_order(
                symbol=symbol,
                is_buy=is_buy,
                sz=size_float
            )
        else:
            # Limit order
            result = exchange.order(
                symbol=symbol,
                is_buy=is_buy,
                sz=size_float,
                limit_px=float(price_decimal),
                order_type={"limit": {"tif": "Gtc"}}  # Good-til-cancelled
            )

        logger.info(f"Order result: {result}")

        # Parse result
        if result.get('status') == 'ok':
            response = result['response']
            statuses = response.get('data', {}).get('statuses', [])
            if statuses:
                status_info = statuses[0]
                filled = status_info.get('filled', {})
                return jsonify({
                    "orderId": str(filled.get('oid', 'unknown')),
                    "symbol": symbol,
                    "side": side,
                    "type": order_type,
                    "size": size,
                    "price": price,
                    "status": status_info.get('status', 'UNKNOWN'),
                    "fillPrice": filled.get('px'),
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
        data = request.json
        username = data.get('username')
        symbol = data.get('symbol')  # Required for cancellation
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400

        logger.info(f"Cancel order: {order_id} for {username}")

        try:
            exchange = get_exchange_client(hyperliquid_key)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        result = exchange.cancel(symbol=symbol, oid=int(order_id))

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
        data = request.json
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

@app.route('/positions', methods=['GET', 'POST'])
def get_positions():
    """Get user positions - uses ephemeral credentials"""
    try:
        if request.method == 'POST':
            data = request.json or {}
            username = data.get('user') or data.get('username')
            hyperliquid_key = data.get('hyperliquidKey')
        else:
            username = request.args.get('user')
            hyperliquid_key = request.args.get('hyperliquidKey')

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

@app.route('/balance', methods=['GET', 'POST'])
def get_balance():
    """Get user balance - uses ephemeral credentials"""
    try:
        if request.method == 'POST':
            data = request.json or {}
            username = data.get('user') or data.get('username')
            hyperliquid_key = data.get('hyperliquidKey')
        else:
            username = request.args.get('user')
            hyperliquid_key = request.args.get('hyperliquidKey')

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

@app.route('/orders', methods=['GET'])
def get_orders():
    """Get open orders - uses ephemeral credentials"""
    try:
        username = request.args.get('user')
        hyperliquid_key = request.args.get('hyperliquidKey')

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
                orders.append({
                    "orderId": order.get('oid'),
                    "symbol": order.get('coin'),
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
        data = request.json
        username = data.get('username')
        symbol = data.get('symbol')
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400

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

@app.route('/health', methods=['GET'])
def health():
    """Health check"""
    try:
        # Test connection to Hyperliquid API
        info = get_info_client()
        meta = info.meta()
        api_status = "healthy" if meta else "degraded"
    except Exception as e:
        logger.error(f"API unhealthy: {e}")
        api_status = "unhealthy"

    return jsonify({
        "status": "healthy" if api_status == "healthy" else "degraded",
        "service": "hyperliquid-worker",
        "api": api_status,
        "mainnet": IS_MAINNET
    })

if __name__ == '__main__':
    logger.info(f"Starting Hyperliquid Worker - {'MAINNET' if IS_MAINNET else 'TESTNET'}")
    app.run(host='0.0.0.0', port=8082)
