"""
Hyperliquid Worker
Handles Hyperliquid exchange operations via SDK
"""
from flask import Flask, request, jsonify
import logging
import os
import requests
from hyperliquid.info import Info
from hyperliquid.exchange import Exchange
from hyperliquid.utils import constants
from decimal import Decimal
from datetime import datetime

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Configuration
# Note: Vault removed - using ephemeral user credentials
IS_MAINNET = os.getenv('HYPERLIQUID_MAINNET', 'true').lower() == 'true'

def parse_hyperliquid_key(api_key: str) -> dict:
    """Parse Hyperliquid API key into address and private key"""
    # Hyperliquid API keys are typically the private key hex string
    # The address is derived from the private key
    # For now, expect format: "address:private_key" or just "private_key"
    if ":" in api_key:
        address, private_key = api_key.split(":", 1)
        return {"address": address, "private_key": private_key}
    else:
        # Just private key provided, derive address
        # For Hyperliquid, the private key is the credential
        return {"address": None, "private_key": api_key}

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
        side = data.get('side')  # "BUY" or "SELL"
        order_type = data.get('type')  # "MARKET" or "LIMIT"
        size = data.get('size')
        price = data.get('price')  # Required for LIMIT orders
        hyperliquid_key = data.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey in request"}), 400

        logger.info(f"Order: {username} {side} {size} {symbol} @ {price} ({order_type})")

        exchange = get_exchange_client(hyperliquid_key)

        # Convert side to Hyperliquid format: true for buy, false for sell
        is_buy = side.upper() == "BUY"

        # Convert size to float
        size_float = float(size)

        if order_type.upper() == "MARKET":
            # Market order
            result = exchange.market_order(
                symbol=symbol,
                is_buy=is_buy,
                sz=size_float
            )
        else:
            # Limit order
            if not price:
                return jsonify({"error": "Price required for limit orders"}), 400

            result = exchange.order(
                symbol=symbol,
                is_buy=is_buy,
                sz=size_float,
                limit_px=float(price),
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
                    "timestamp": datetime.utcnow().isoformat(),
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

        exchange = get_exchange_client(hyperliquid_key)
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

        exchange = get_exchange_client(hyperliquid_key)
        result = exchange.cancel_all_orders(symbol=symbol)

        return jsonify({
            "status": "success" if result.get('status') == 'ok' else "failed",
            "raw": result
        })

    except Exception as e:
        logger.error(f"Cancel all failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

@app.route('/positions', methods=['GET'])
def get_positions():
    """Get user positions - uses ephemeral credentials"""
    try:
        username = request.args.get('user')
        hyperliquid_key = request.args.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey parameter"}), 400

        logger.info(f"Get positions for {username}")

        creds = parse_hyperliquid_key(hyperliquid_key)
        info = get_info_client()

        # Get user state which includes positions
        # If address not provided, derive from private key
        address = creds.get("address") or creds["private_key"]  # TODO: proper address derivation
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

@app.route('/balance', methods=['GET'])
def get_balance():
    """Get user balance - uses ephemeral credentials"""
    try:
        username = request.args.get('user')
        hyperliquid_key = request.args.get('hyperliquidKey')

        if not hyperliquid_key:
            return jsonify({"error": "Missing hyperliquidKey parameter"}), 400

        logger.info(f"Get balance for {username}")

        creds = parse_hyperliquid_key(hyperliquid_key)
        info = get_info_client()

        address = creds.get("address") or creds["private_key"]  # TODO: proper address derivation
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

        creds = parse_hyperliquid_key(hyperliquid_key)
        info = get_info_client()

        address = creds.get("address") or creds["private_key"]  # TODO: proper address derivation
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
        creds = parse_hyperliquid_key(hyperliquid_key)
        info = get_info_client()
        address = creds.get("address") or creds["private_key"]  # TODO: proper address derivation
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
        exchange = get_exchange_client(hyperliquid_key)
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
