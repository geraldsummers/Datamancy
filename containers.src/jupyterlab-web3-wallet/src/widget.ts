import { Widget } from '@lumino/widgets';
import { Web3WalletManager } from './manager';

/**
 * A widget that displays Web3 wallet connection status and controls
 */
export class Web3WalletWidget extends Widget {
  private _manager: Web3WalletManager;
  private _statusDiv: HTMLDivElement;
  private _addressDiv: HTMLDivElement;
  private _chainDiv: HTMLDivElement;
  private _connectBtn: HTMLButtonElement;
  private _disconnectBtn: HTMLButtonElement;

  constructor(manager: Web3WalletManager) {
    super();
    this._manager = manager;
    this.addClass('web3-wallet-widget');
    this.title.label = 'Web3 Wallet';
    this.title.closable = true;

    // Create UI elements
    this.node.innerHTML = `
      <div class="web3-wallet-container">
        <div class="web3-wallet-header">
          <h2>🦊 Web3 Wallet</h2>
        </div>

        <div class="web3-wallet-status">
          <div class="status-indicator"></div>
          <span class="status-text">Not Connected</span>
        </div>

        <div class="web3-wallet-info">
          <div class="info-row">
            <span class="info-label">Address:</span>
            <span class="info-value address-value">-</span>
          </div>
          <div class="info-row">
            <span class="info-label">Chain:</span>
            <span class="info-value chain-value">-</span>
          </div>
        </div>

        <div class="web3-wallet-actions">
          <button class="connect-btn jp-mod-styled jp-mod-accept">
            Connect Wallet
          </button>
          <button class="disconnect-btn jp-mod-styled jp-mod-warn" style="display: none;">
            Disconnect
          </button>
        </div>

        <div class="web3-wallet-help">
          <p><strong>Supported Wallets:</strong></p>
          <ul>
            <li>🦊 MetaMask</li>
            <li>🦁 Brave Wallet</li>
            <li>🔗 WalletConnect (any mobile wallet)</li>
          </ul>
          <p style="margin-top: 1em;">
            Once connected, your Kotlin notebooks can use <code>%walletConnect</code>
            to interact with your wallet for signing transactions.
          </p>
        </div>
      </div>
    `;

    // Get references to UI elements
    this._statusDiv = this.node.querySelector('.status-text') as HTMLDivElement;
    this._addressDiv = this.node.querySelector('.address-value') as HTMLDivElement;
    this._chainDiv = this.node.querySelector('.chain-value') as HTMLDivElement;
    this._connectBtn = this.node.querySelector('.connect-btn') as HTMLButtonElement;
    this._disconnectBtn = this.node.querySelector('.disconnect-btn') as HTMLButtonElement;

    // Attach event handlers
    this._connectBtn.onclick = () => this._handleConnect();
    this._disconnectBtn.onclick = () => this._handleDisconnect();

    // Update UI based on current state
    this._updateUI();
  }

  private async _handleConnect(): Promise<void> {
    try {
      this._connectBtn.disabled = true;
      this._connectBtn.textContent = 'Connecting...';

      await this._manager.connectWallet();
      this._updateUI();
    } catch (error) {
      console.error('Failed to connect wallet:', error);
      alert(`Failed to connect wallet: ${(error as Error).message}`);
    } finally {
      this._connectBtn.disabled = false;
      this._connectBtn.textContent = 'Connect Wallet';
    }
  }

  private async _handleDisconnect(): Promise<void> {
    try {
      await this._manager.disconnectWallet();
      this._updateUI();
    } catch (error) {
      console.error('Failed to disconnect wallet:', error);
    }
  }

  private _updateUI(): void {
    const walletInfo = this._manager.walletInfo;
    const isConnected = this._manager.isConnected;

    if (isConnected && walletInfo) {
      // Update status
      this._statusDiv.textContent = 'Connected';
      const indicator = this.node.querySelector('.status-indicator') as HTMLDivElement;
      indicator.style.backgroundColor = '#4caf50';

      // Update info
      this._addressDiv.textContent = this._truncateAddress(walletInfo.address);
      this._addressDiv.title = walletInfo.address;
      this._chainDiv.textContent = this._getChainName(walletInfo.chainId);

      // Update buttons
      this._connectBtn.style.display = 'none';
      this._disconnectBtn.style.display = 'block';
    } else {
      // Update status
      this._statusDiv.textContent = 'Not Connected';
      const indicator = this.node.querySelector('.status-indicator') as HTMLDivElement;
      indicator.style.backgroundColor = '#757575';

      // Clear info
      this._addressDiv.textContent = '-';
      this._chainDiv.textContent = '-';

      // Update buttons
      this._connectBtn.style.display = 'block';
      this._disconnectBtn.style.display = 'none';
    }
  }

  private _truncateAddress(address: string): string {
    return `${address.substring(0, 6)}...${address.substring(address.length - 4)}`;
  }

  private _getChainName(chainId: number): string {
    const chains: Record<number, string> = {
      1: 'Ethereum Mainnet',
      5: 'Goerli Testnet',
      11155111: 'Sepolia Testnet',
      137: 'Polygon',
      8453: 'Base',
      10: 'Optimism',
      42161: 'Arbitrum One',
      43114: 'Avalanche C-Chain'
    };

    return chains[chainId] || `Chain ID: ${chainId}`;
  }
}
