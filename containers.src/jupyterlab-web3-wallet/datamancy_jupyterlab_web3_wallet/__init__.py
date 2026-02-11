"""
JupyterLab Web3 Wallet Extension

Provides Web3 wallet integration (MetaMask, WalletConnect) for Jupyter notebooks.
"""

from ._version import __version__
from .handlers import setup_handlers


def _jupyter_labextension_paths():
    """Return metadata for the JupyterLab extension."""
    return [{
        "src": "labextension",
        "dest": "@datamancy/jupyterlab-web3-wallet"
    }]


def _jupyter_server_extension_points():
    """Return a list of dictionaries with metadata describing the extension."""
    return [{
        "module": "datamancy_jupyterlab_web3_wallet"
    }]


def _load_jupyter_server_extension(server_app):
    """Load the JupyterLab extension."""
    setup_handlers(server_app.web_app)
    server_app.log.info("JupyterLab Web3 Wallet extension loaded.")
