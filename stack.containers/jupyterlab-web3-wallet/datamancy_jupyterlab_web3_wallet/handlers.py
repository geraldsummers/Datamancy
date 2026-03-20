"""
HTTP handlers for Web3 wallet communication between frontend and kernel.
"""

import json
from datetime import datetime, timezone
from threading import Lock
import tornado
from jupyter_server.base.handlers import APIHandler
from jupyter_server.utils import url_path_join


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _parse_chain_id(value):
    if value is None:
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        raw = value.strip()
        if not raw:
            return None
        try:
            return int(raw, 16) if raw.startswith("0x") else int(raw)
        except ValueError:
            return None
    return None


def _normalize_wallet_state(wallet):
    wallet = wallet if isinstance(wallet, dict) else {}
    connected = bool(wallet.get("connected", False))

    address = wallet.get("address")
    if isinstance(address, str):
        address = address.strip() or None
    else:
        address = None

    chain_id = _parse_chain_id(wallet.get("chainId"))
    provider = wallet.get("provider")
    if isinstance(provider, str):
        provider = provider.strip() or None
    else:
        provider = None

    if not connected:
        address = None
        chain_id = None
        provider = None

    return {
        "connected": connected,
        "address": address,
        "chainId": chain_id,
        "provider": provider,
        "updatedAt": _utc_now_iso(),
    }


_wallet_state = _normalize_wallet_state({})
_wallet_state_lock = Lock()
_pending_transactions = {}
_pending_tx_lock = Lock()


def _get_wallet_state():
    with _wallet_state_lock:
        return dict(_wallet_state)


def _set_wallet_state(wallet):
    normalized = _normalize_wallet_state(wallet)
    with _wallet_state_lock:
        _wallet_state.update(normalized)
        return dict(_wallet_state)


class Web3WalletHandler(APIHandler):
    """
    Handler for Web3 wallet operations.

    This provides an API bridge between the JupyterLab frontend (which has access
    to window.ethereum) and the Kotlin kernel (which needs to communicate with wallets).
    """

    @tornado.web.authenticated
    async def post(self):
        """Handle wallet operation requests from kernel."""
        try:
            data = self.get_json_body() or {}
            operation = data.get("operation")

            if operation == "get_wallet_info":
                self.finish(json.dumps({
                    "status": "ok",
                    "wallet": _get_wallet_state(),
                }))

            elif operation == "update_wallet_state":
                wallet = _set_wallet_state(data.get("wallet"))
                self.finish(json.dumps({
                    "status": "ok",
                    "wallet": wallet,
                }))

            elif operation == "sign_transaction":
                tx_data = data.get("transaction")
                tx_id = self._generate_tx_id()
                with _pending_tx_lock:
                    _pending_transactions[tx_id] = {
                        "txId": tx_id,
                        "status": "pending",
                        "requestedAt": _utc_now_iso(),
                        "transaction": tx_data,
                        "signedTransaction": None,
                        "txHash": None,
                        "error": None,
                    }
                self.finish(json.dumps({
                    "status": "ok",
                    "tx_id": tx_id,
                    "message": "Transaction queued for signing"
                }))

            elif operation == "submit_signed_transaction":
                tx_id = (data.get("tx_id") or "").strip()
                if not tx_id:
                    self.set_status(400)
                    self.finish(json.dumps({
                        "status": "error",
                        "message": "Missing tx_id",
                    }))
                    return

                signed_tx = data.get("signed_transaction")
                tx_hash = data.get("tx_hash")
                error = data.get("error")

                with _pending_tx_lock:
                    tx_record = _pending_transactions.get(tx_id)
                    if tx_record is None:
                        self.set_status(404)
                        self.finish(json.dumps({
                            "status": "error",
                            "message": "Unknown tx_id",
                        }))
                        return

                    if signed_tx:
                        tx_record["status"] = "signed"
                        tx_record["signedTransaction"] = signed_tx
                        tx_record["txHash"] = tx_hash
                        tx_record["error"] = None
                    else:
                        tx_record["status"] = "rejected"
                        tx_record["error"] = error or "Signing request rejected"
                    tx_record["updatedAt"] = _utc_now_iso()

                self.finish(json.dumps({
                    "status": "ok",
                    "tx_id": tx_id,
                }))

            elif operation == "get_pending_transactions":
                with _pending_tx_lock:
                    pending = [
                        dict(tx)
                        for tx in _pending_transactions.values()
                        if tx.get("status") == "pending"
                    ]
                self.finish(json.dumps({
                    "status": "ok",
                    "transactions": pending,
                }))

            else:
                self.set_status(400)
                self.finish(json.dumps({
                    "status": "error",
                    "message": f"Unknown operation: {operation}"
                }))

        except Exception as e:
            self.set_status(500)
            self.finish(json.dumps({
                "status": "error",
                "message": str(e)
            }))

    @tornado.web.authenticated
    async def get(self):
        """Get wallet connection status."""
        self.finish(json.dumps(_get_wallet_state()))

    def _generate_tx_id(self) -> str:
        """Generate a unique transaction ID."""
        import uuid
        return str(uuid.uuid4())


class Web3WalletMagicHandler(APIHandler):
    """
    Handler for the %walletConnect magic command.

    This provides a simple way for notebooks to check wallet status
    and request wallet operations.
    """

    @tornado.web.authenticated
    async def get(self):
        """Get current wallet connection status."""
        wallet = _get_wallet_state()
        self.finish(json.dumps({
            "status": "ok",
            "wallet": wallet
        }))


class Web3WalletTxStatusHandler(APIHandler):
    """Get transaction signing status by tx_id."""

    @tornado.web.authenticated
    async def get(self, tx_id: str):
        tx_id = (tx_id or "").strip()
        with _pending_tx_lock:
            tx = _pending_transactions.get(tx_id)
            if tx is None:
                self.set_status(404)
                self.finish(json.dumps({
                    "status": "error",
                    "message": "Unknown tx_id",
                }))
                return
            payload = dict(tx)

        self.finish(json.dumps({
            "status": "ok",
            "tx": payload,
        }))


def setup_handlers(web_app):
    """Setup the web3 wallet HTTP handlers."""
    host_pattern = ".*$"
    base_url = web_app.settings["base_url"]

    # Route for wallet operations
    wallet_route = url_path_join(base_url, "datamancy", "web3-wallet")

    # Route for magic command
    magic_route = url_path_join(base_url, "datamancy", "web3-wallet", "magic")
    tx_route = url_path_join(base_url, "datamancy", "web3-wallet", "tx", r"(.*)")

    handlers = [
        (wallet_route, Web3WalletHandler),
        (magic_route, Web3WalletMagicHandler),
        (tx_route, Web3WalletTxStatusHandler),
    ]

    web_app.add_handlers(host_pattern, handlers)
