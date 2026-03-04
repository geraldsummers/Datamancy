"""
Setup script for jupyterlab-web3-wallet Python package
"""

from setuptools import setup, find_packages
import os

HERE = os.path.abspath(os.path.dirname(__file__))

# Get version
version_ns = {}
with open(os.path.join(HERE, "datamancy_jupyterlab_web3_wallet", "_version.py")) as f:
    exec(f.read(), {}, version_ns)

setup(
    name="datamancy-jupyterlab-web3-wallet",
    version=version_ns["__version__"],
    description="JupyterLab extension for Web3 wallet integration",
    long_description="""
    A JupyterLab extension that provides seamless Web3 wallet integration.

    Features:
    - Connect to MetaMask, Brave Wallet, or WalletConnect
    - Sign transactions from Kotlin/Python notebooks
    - Persistent wallet connection across sessions
    - Toolbar widget for easy wallet management
    """,
    author="Datamancy",
    author_email="dev@datamancy.org",
    url="https://github.com/datamancy/jupyterlab-web3-wallet",
    license="MIT",
    platforms="Linux, Mac OS X, Windows",
    keywords=["Jupyter", "JupyterLab", "Web3", "Ethereum", "MetaMask", "WalletConnect"],
    classifiers=[
        "License :: OSI Approved :: MIT License",
        "Programming Language :: Python",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Framework :: Jupyter",
        "Framework :: Jupyter :: JupyterLab",
        "Framework :: Jupyter :: JupyterLab :: 4",
        "Framework :: Jupyter :: JupyterLab :: Extensions",
        "Framework :: Jupyter :: JupyterLab :: Extensions :: Prebuilt",
    ],
    packages=find_packages(),
    install_requires=[
        "jupyter_server>=2.0.0,<3",
    ],
    include_package_data=True,
    python_requires=">=3.8",
    data_files=[
        ("share/jupyter/labextensions/@datamancy/jupyterlab-web3-wallet", [
            "install.json",
        ]),
    ],
    zip_safe=False,
)
