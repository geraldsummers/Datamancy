# JupyterLab Web3 Wallet Extension

A JupyterLab extension that enables seamless Web3 wallet integration for crypto trading and blockchain development in Jupyter notebooks.

## Features

- 🦊 **MetaMask Integration**: Connect directly to MetaMask browser extension
- 🦁 **Brave Wallet Support**: Native support for Brave browser's built-in wallet
- 🔗 **WalletConnect**: Connect any mobile wallet via QR code
- 💾 **Session Persistence**: Wallet stays connected across notebook sessions
- 🎨 **Clean UI**: Toolbar widget for easy wallet management
- 🔐 **Secure**: Private keys never leave your browser

## Installation

### From Source

```bash
cd stack.containers/jupyterlab-web3-wallet

# Install Python package
pip install -e .

# Install JavaScript dependencies
jlpm install

# Build extension
jlpm build

# Link extension to JupyterLab
jupyter labextension develop . --overwrite
```

### For Production

```bash
# Install from PyPI (once published)
pip install datamancy-jupyterlab-web3-wallet
```

## Usage

### 1. Connect Wallet

Open JupyterLab and:
- Click the command palette (Ctrl/Cmd + Shift + C)
- Search for "Connect Web3 Wallet"
- Or run: `%walletConnect` in a notebook cell

### 2. Use in Kotlin Notebooks

```kotlin
@file:DependsOn("org.datamancy:trading-sdk:1.0.0")

import org.datamancy.trading.*

// SDK automatically detects connected wallet
val tx = TxGateway.fromWallet()

// This will prompt MetaMask for approval
tx.evm.transfer(
    toAddress = "0x742d35Cc6634C0532925a3b844Bc454e4438f44e",
    amount = 100.toBigDecimal(),
    token = Token.USDC,
    chain = Chain.BASE
)
```

### 3. Use in Python Notebooks

```python
# Get wallet info
from datamancy_jupyterlab_web3_wallet import get_wallet

wallet = get_wallet()
print(f"Connected: {wallet.address}")
print(f"Chain: {wallet.chain_id}")
```

## Architecture

```
┌─────────────────────────────────────┐
│   JupyterLab Frontend (Browser)    │
│  ┌──────────────────────────────┐  │
│  │   Web3 Wallet Extension      │  │
│  │   - Detects MetaMask/Brave   │  │
│  │   - Shows connect UI         │  │
│  │   - Manages wallet state     │  │
│  └──────────┬───────────────────┘  │
│             │                       │
│             │ window.ethereum       │
│             ↓                       │
│  ┌──────────────────────────────┐  │
│  │   MetaMask / Brave Wallet    │  │
│  │   (Browser Extension)        │  │
│  └──────────┬───────────────────┘  │
└─────────────┼───────────────────────┘
              │ API calls
              ↓
   ┌──────────────────────────┐
   │  Python Server Extension │
   │  - HTTP handlers         │
   │  - Kernel communication  │
   └──────────┬───────────────┘
              │
              ↓
   ┌──────────────────────────┐
   │   Kotlin/Python Kernel   │
   │   - Trading SDK          │
   │   - Sign transactions    │
   └──────────────────────────┘
```

## Development

```bash
# Watch for changes
jlpm watch

# In another terminal, run JupyterLab
jupyter lab --watch
```

## Supported Chains

- Ethereum Mainnet
- Base
- Optimism
- Arbitrum
- Polygon
- Avalanche C-Chain

## License

MIT

## Contributing

Pull requests welcome! Please see CONTRIBUTING.md for guidelines.
