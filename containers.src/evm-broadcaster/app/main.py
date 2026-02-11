"""
EVM Broadcaster Worker
Handles L2 transfers with Web3Signer integration and nonce management
"""
from flask import Flask, request, jsonify
import logging
import os
import requests
from web3 import Web3
import psycopg2
from psycopg2.extras import RealDictCursor
from decimal import Decimal
from datetime import datetime

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Configuration
# Note: Vault/Web3Signer removed - using ephemeral user credentials
POSTGRES_HOST = os.getenv('POSTGRES_HOST', 'postgres')
POSTGRES_PORT = int(os.getenv('POSTGRES_PORT', 5432))
POSTGRES_DB = os.getenv('POSTGRES_DB', 'txgateway')
POSTGRES_USER = os.getenv('POSTGRES_USER', 'txgateway')
POSTGRES_PASSWORD = os.getenv('POSTGRES_PASSWORD')

# Chain configurations
CHAINS = {
    'base': {
        'chain_id': 8453,
        'rpc_url': os.getenv('BASE_RPC_URL', 'https://mainnet.base.org'),
        'usdc': '0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913',
        'usdt': '0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2',
    },
    'arbitrum': {
        'chain_id': 42161,
        'rpc_url': os.getenv('ARBITRUM_RPC_URL', 'https://arb1.arbitrum.io/rpc'),
        'usdc': '0xaf88d065e77c8cC2239327C5EDb3A432268e5831',
        'usdt': '0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9',
    },
    'optimism': {
        'chain_id': 10,
        'rpc_url': os.getenv('OPTIMISM_RPC_URL', 'https://mainnet.optimism.io'),
        'usdc': '0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85',
        'usdt': '0x94b008aA00579c1307B0EF2c499aD98a8ce58e58',
    }
}

# ERC-20 ABI (minimal)
ERC20_ABI = [
    {"constant": False, "inputs": [{"name": "_to", "type": "address"}, {"name": "_value", "type": "uint256"}],
     "name": "transfer", "outputs": [{"name": "", "type": "bool"}], "type": "function"},
    {"constant": True, "inputs": [{"name": "_owner", "type": "address"}],
     "name": "balanceOf", "outputs": [{"name": "balance", "type": "uint256"}], "type": "function"},
    {"constant": True, "inputs": [], "name": "decimals", "outputs": [{"name": "", "type": "uint8"}], "type": "function"}
]


def get_db_connection():
    """Get PostgreSQL connection"""
    return psycopg2.connect(
        host=POSTGRES_HOST, port=POSTGRES_PORT, database=POSTGRES_DB,
        user=POSTGRES_USER, password=POSTGRES_PASSWORD
    )


def derive_evm_address(private_key: str) -> str:
    """Derive EVM address from private key"""
    from eth_account import Account
    # Ensure private key has 0x prefix
    if not private_key.startswith('0x'):
        private_key = '0x' + private_key
    account = Account.from_key(private_key)
    return account.address


def allocate_nonce(chain_id: int, from_address: str) -> int:
    """Allocate next nonce with IMPLICIT CANCELLATION support"""
    conn = get_db_connection()
    try:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            # Check for stuck transactions (older than 5 min)
            cur.execute("""
                SELECT nonce, tx_hash FROM evm_pending_txs
                WHERE chain_id = %s AND from_address = %s
                  AND status = 'submitted'
                  AND submitted_at < NOW() - INTERVAL '5 minutes'
                ORDER BY nonce ASC LIMIT 1
            """, (chain_id, from_address))
            stuck_tx = cur.fetchone()

            if stuck_tx:
                # IMPLICIT CANCELLATION: Reuse stuck nonce
                logger.info(f"Reusing stuck nonce {stuck_tx['nonce']} (tx {stuck_tx['tx_hash']})")
                return stuck_tx['nonce']

            # Allocate fresh nonce
            cur.execute("""
                INSERT INTO evm_nonces (chain_id, from_address, nonce, last_updated)
                VALUES (%s, %s, 0, NOW())
                ON CONFLICT (chain_id, from_address)
                DO UPDATE SET nonce = evm_nonces.nonce + 1, last_updated = NOW()
                RETURNING nonce
            """, (chain_id, from_address))
            result = cur.fetchone()
            conn.commit()
            return result['nonce']
    finally:
        conn.close()


def build_transaction(chain: str, from_addr: str, to_addr: str, amount: str, token: str, nonce: int) -> dict:
    """Build transaction payload"""
    cfg = CHAINS[chain.lower()]
    w3 = Web3(Web3.HTTPProvider(cfg['rpc_url']))
    gas_price = w3.eth.gas_price

    if token.upper() == 'ETH':
        return {
            'from': from_addr, 'to': to_addr, 'value': w3.to_wei(Decimal(amount), 'ether'),
            'gas': 21000, 'gasPrice': gas_price, 'nonce': nonce, 'chainId': cfg['chain_id']
        }
    else:
        token_addr = cfg.get(token.lower())
        if not token_addr:
            raise ValueError(f"Token {token} not supported on {chain}")
        contract = w3.eth.contract(address=token_addr, abi=ERC20_ABI)
        decimals = contract.functions.decimals().call()
        amount_wei = int(Decimal(amount) * (10 ** decimals))
        data = contract.encode_abi('transfer', [to_addr, amount_wei])
        return {
            'from': from_addr, 'to': token_addr, 'value': 0, 'gas': 100000,
            'gasPrice': gas_price, 'nonce': nonce, 'data': data, 'chainId': cfg['chain_id']
        }


def sign_transaction(tx: dict, private_key: str) -> str:
    """Sign transaction with ephemeral private key"""
    from eth_account import Account
    from web3 import Web3

    # Ensure private key has 0x prefix
    if not private_key.startswith('0x'):
        private_key = '0x' + private_key

    account = Account.from_key(private_key)
    signed_tx = account.sign_transaction(tx)
    return signed_tx.rawTransaction.hex()


def broadcast_tx(chain: str, signed_tx: str) -> str:
    """Broadcast to L2 RPC"""
    cfg = CHAINS[chain.lower()]
    w3 = Web3(Web3.HTTPProvider(cfg['rpc_url']))
    tx_hash = w3.eth.send_raw_transaction(signed_tx)
    return tx_hash.hex()


def track_pending(user: str, chain_id: int, nonce: int, from_addr: str, tx_hash: str, gas_price: int):
    """Track pending transaction"""
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            # Mark replaced txs
            cur.execute("""
                UPDATE evm_pending_txs SET status = 'replaced', replaced_by_tx_hash = %s
                WHERE chain_id = %s AND from_address = %s AND nonce = %s AND status = 'submitted'
            """, (tx_hash, chain_id, from_addr, nonce))
            if cur.rowcount > 0:
                logger.info(f"Marked {cur.rowcount} txs as replaced by {tx_hash}")

            # Insert new
            cur.execute("""
                INSERT INTO evm_pending_txs
                (user_id, chain_id, nonce, from_address, tx_hash, status,
                 original_gas_price, current_gas_price, submitted_at)
                VALUES (%s, %s, %s, %s, %s, 'submitted', %s, %s, NOW())
            """, (user, chain_id, nonce, from_addr, tx_hash, str(gas_price), str(gas_price)))
            conn.commit()
    finally:
        conn.close()


@app.route('/submit', methods=['POST'])
def submit_transfer():
    """Submit EVM transfer - uses ephemeral credentials"""
    try:
        data = request.json
        username = data.get('username')
        to_address = data.get('toAddress')
        amount = data.get('amount')
        token = data.get('token', 'ETH')
        chain = data.get('chain', 'base')
        evm_private_key = data.get('evmPrivateKey')

        if not evm_private_key:
            return jsonify({"error": "Missing evmPrivateKey in request"}), 400

        logger.info(f"Transfer: {username} -> {to_address}, {amount} {token} on {chain}")

        if chain.lower() not in CHAINS:
            return jsonify({"error": f"Unsupported chain: {chain}"}), 400

        from_address = derive_evm_address(evm_private_key)
        cfg = CHAINS[chain.lower()]

        # Allocate nonce (implicit cancellation)
        nonce = allocate_nonce(cfg['chain_id'], from_address)
        logger.info(f"Allocated nonce {nonce}")

        # Build, sign, broadcast
        tx = build_transaction(chain, from_address, to_address, amount, token, nonce)
        signed_tx = sign_transaction(tx, evm_private_key)
        tx_hash = broadcast_tx(chain, signed_tx)
        logger.info(f"Broadcast: {tx_hash}")

        # Track
        track_pending(username, cfg['chain_id'], nonce, from_address, tx_hash, tx['gasPrice'])

        return jsonify({
            "txHash": tx_hash, "from": from_address, "to": to_address,
            "amount": amount, "token": token, "chain": chain, "nonce": nonce,
            "status": "submitted", "timestamp": datetime.utcnow().isoformat()
        })
    except Exception as e:
        logger.error(f"Transfer failed: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500


@app.route('/status/<tx_hash>', methods=['GET'])
def get_status(tx_hash):
    """Get transaction status"""
    try:
        chain = request.args.get('chain', 'base')
        cfg = CHAINS[chain.lower()]
        w3 = Web3(Web3.HTTPProvider(cfg['rpc_url']))
        try:
            receipt = w3.eth.get_transaction_receipt(tx_hash)
            return jsonify({
                "txHash": tx_hash, "status": "confirmed" if receipt['status'] == 1 else "failed",
                "blockNumber": receipt['blockNumber'], "gasUsed": receipt['gasUsed'],
                "confirmations": w3.eth.block_number - receipt['blockNumber']
            })
        except Exception:
            return jsonify({"txHash": tx_hash, "status": "pending"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route('/health', methods=['GET'])
def health():
    """Health check"""
    try:
        conn = get_db_connection()
        conn.close()
        db_status = "healthy"
    except Exception as e:
        logger.error(f"DB unhealthy: {e}")
        db_status = "unhealthy"
    return jsonify({
        "status": "healthy" if db_status == "healthy" else "degraded",
        "service": "evm-broadcaster", "database": db_status, "chains": list(CHAINS.keys())
    })


@app.route('/chains', methods=['GET'])
def list_chains():
    """List supported chains"""
    return jsonify({"chains": list(CHAINS.keys())})


@app.route('/tokens', methods=['GET'])
def list_tokens():
    """List supported tokens across all chains"""
    tokens = set()
    for chain_config in CHAINS.values():
        tokens.add('ETH')  # All chains support ETH
        tokens.update([k.upper() for k in chain_config.keys() if k in ['usdc', 'usdt']])
    return jsonify({"tokens": sorted(list(tokens))})


if __name__ == '__main__':
    logger.info(f"Starting EVM Broadcaster - Chains: {list(CHAINS.keys())}")
    app.run(host='0.0.0.0', port=8081)
