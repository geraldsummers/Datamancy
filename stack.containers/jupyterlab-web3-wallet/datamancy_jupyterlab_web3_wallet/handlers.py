"""
HTTP handlers for Web3 wallet communication between frontend and kernel.
"""

import json
import tornado
from jupyter_server.base.handlers import APIHandler
from jupyter_server.utils import url_path_join


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
            data = self.get_json_body()
            operation = data.get("operation")

            if operation == "get_wallet_info":
                # Frontend will respond with wallet info
                self.finish(json.dumps({
                    "status": "ok",
                    "message": "Request wallet info from frontend"
                }))

            elif operation == "sign_transaction":
                tx_data = data.get("transaction")
                # Store pending transaction for frontend to sign
                # Frontend polls for pending transactions and signs them
                self.finish(json.dumps({
                    "status": "ok",
                    "tx_id": self._generate_tx_id(),
                    "message": "Transaction queued for signing"
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
        # This would check if wallet is connected via the frontend
        self.finish(json.dumps({
            "connected": False,  # TODO: Check actual state
            "address": None,
            "chainId": None
        }))

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
        # Return wallet info that can be used by the kernel
        self.finish(json.dumps({
            "status": "ok",
            "wallet": {
                "connected": True,  # TODO: Get from actual state
                "address": "0x...",  # TODO: Get from actual state
                "chainId": 1,  # TODO: Get from actual state
                "provider": "metamask"
            }
        }))


def setup_handlers(web_app):
    """Setup the web3 wallet HTTP handlers."""
    host_pattern = ".*$"
    base_url = web_app.settings["base_url"]

    # Route for wallet operations
    wallet_route = url_path_join(base_url, "datamancy", "web3-wallet")

    # Route for magic command
    magic_route = url_path_join(base_url, "datamancy", "web3-wallet", "magic")

    handlers = [
        (wallet_route, Web3WalletHandler),
        (magic_route, Web3WalletMagicHandler)
    ]

    web_app.add_handlers(host_pattern, handlers)
