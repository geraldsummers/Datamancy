"""
Unit tests for JupyterLab Web3 Wallet Extension Python backend

Tests the HTTP handlers and server extension functionality.
"""

import pytest
import json
from unittest.mock import Mock, patch
from tornado.testing import AsyncHTTPTestCase
from tornado.web import Application
from jupyter_server.base.handlers import APIHandler

# Import handlers from the extension
try:
    from datamancy_jupyterlab_web3_wallet.handlers import (
        Web3WalletHandler,
        Web3WalletMagicHandler,
        setup_handlers
    )
    EXTENSION_AVAILABLE = True
except ImportError:
    EXTENSION_AVAILABLE = False
    pytestmark = pytest.mark.skip(reason="Extension not installed")


class TestWeb3WalletHandler(AsyncHTTPTestCase):
    """Test the main wallet HTTP handler"""

    def get_app(self):
        """Create a test application with the wallet handler"""
        return Application([
            (r"/datamancy/web3-wallet", Web3WalletHandler),
        ])

    @pytest.mark.skipif(not EXTENSION_AVAILABLE, reason="Extension not installed")
    def test_get_wallet_status_disconnected(self):
        """Test GET /datamancy/web3-wallet returns disconnected state"""
        response = self.fetch("/datamancy/web3-wallet")

        assert response.code == 200
        data = json.loads(response.body)

        assert "connected" in data
        assert data["connected"] == False
        assert "address" in data
        assert "chainId" in data

    @pytest.mark.skipif(not EXTENSION_AVAILABLE, reason="Extension not installed")
    def test_post_sign_transaction_operation(self):
        """Test POST /datamancy/web3-wallet with sign_transaction operation"""
        request_body = {
            "operation": "sign_transaction",
            "transaction": {
                "to": "0x742d35Cc6634C0532925a3b844Bc454e4438f44e",
                "value": "1000000000000000000",  # 1 ETH
                "gasLimit": "21000"
            }
        }

        response = self.fetch(
            "/datamancy/web3-wallet",
            method="POST",
            body=json.dumps(request_body),
            headers={"Content-Type": "application/json"}
        )

        assert response.code == 200
        data = json.loads(response.body)

        assert data["status"] == "ok"
        assert "tx_id" in data
        assert "message" in data

    @pytest.mark.skipif(not EXTENSION_AVAILABLE, reason="Extension not installed")
    def test_post_unknown_operation(self):
        """Test POST with unknown operation returns error"""
        request_body = {
            "operation": "invalid_operation"
        }

        response = self.fetch(
            "/datamancy/web3-wallet",
            method="POST",
            body=json.dumps(request_body),
            headers={"Content-Type": "application/json"}
        )

        assert response.code == 400
        data = json.loads(response.body)

        assert data["status"] == "error"
        assert "Unknown operation" in data["message"]


class TestWeb3WalletMagicHandler(AsyncHTTPTestCase):
    """Test the magic command handler"""

    def get_app(self):
        """Create a test application with the magic handler"""
        return Application([
            (r"/datamancy/web3-wallet/magic", Web3WalletMagicHandler),
        ])

    @pytest.mark.skipif(not EXTENSION_AVAILABLE, reason="Extension not installed")
    def test_magic_get_wallet_info(self):
        """Test GET /datamancy/web3-wallet/magic returns wallet info"""
        response = self.fetch("/datamancy/web3-wallet/magic")

        assert response.code == 200
        data = json.loads(response.body)

        assert data["status"] == "ok"
        assert "wallet" in data

        wallet = data["wallet"]
        assert "connected" in wallet
        assert "address" in wallet
        assert "chainId" in wallet
        assert "provider" in wallet


@pytest.mark.skipif(not EXTENSION_AVAILABLE, reason="Extension not installed")
def test_setup_handlers():
    """Test that setup_handlers correctly registers routes"""
    mock_web_app = Mock()
    mock_web_app.settings = {"base_url": "/"}

    setup_handlers(mock_web_app)

    # Verify add_handlers was called
    assert mock_web_app.add_handlers.called

    # Get the handlers that were registered
    call_args = mock_web_app.add_handlers.call_args
    handlers = call_args[0][1]

    # Should have registered 2 handlers
    assert len(handlers) == 2

    # Check routes
    routes = [handler[0] for handler in handlers]
    assert any("web3-wallet" in route for route in routes)
    assert any("magic" in route for route in routes)


@pytest.mark.skipif(not EXTENSION_AVAILABLE, reason="Extension not installed")
def test_extension_metadata():
    """Test that extension metadata is correctly defined"""
    from datamancy_jupyterlab_web3_wallet import _jupyter_labextension_paths

    paths = _jupyter_labextension_paths()

    assert len(paths) == 1
    assert paths[0]["src"] == "labextension"
    assert paths[0]["dest"] == "@datamancy/jupyterlab-web3-wallet"


@pytest.mark.skipif(not EXTENSION_AVAILABLE, reason="Extension not installed")
def test_server_extension_points():
    """Test that server extension is properly registered"""
    from datamancy_jupyterlab_web3_wallet import _jupyter_server_extension_points

    points = _jupyter_server_extension_points()

    assert len(points) == 1
    assert points[0]["module"] == "datamancy_jupyterlab_web3_wallet"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
