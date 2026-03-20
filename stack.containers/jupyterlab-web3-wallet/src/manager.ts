import { IStateDB } from '@jupyterlab/statedb';
import { BrowserProvider, JsonRpcSigner } from 'ethers';
import WalletConnectProvider from '@walletconnect/web3-provider';

export interface IWalletInfo {
  address: string;
  chainId: number;
  provider: 'metamask' | 'walletconnect' | 'brave';
  connected: boolean;
}

/**
 * Manages Web3 wallet connections for JupyterLab
 */
export class Web3WalletManager {
  private _provider: BrowserProvider | null = null;
  private _signer: JsonRpcSigner | null = null;
  private _walletInfo: IWalletInfo | null = null;
  private _state: IStateDB;
  private _stateKey = 'web3-wallet:connection';
  private _backendWalletPath = '/datamancy/web3-wallet';

  constructor(state: IStateDB) {
    this._state = state;
  }

  /**
   * Check if wallet is connected
   */
  get isConnected(): boolean {
    return this._walletInfo?.connected ?? false;
  }

  /**
   * Get current wallet info
   */
  get walletInfo(): IWalletInfo | null {
    return this._walletInfo;
  }

  /**
   * Get the current signer
   */
  get signer(): JsonRpcSigner | null {
    return this._signer;
  }

  /**
   * Connect to a Web3 wallet
   */
  async connectWallet(type: 'metamask' | 'walletconnect' | 'auto' = 'auto'): Promise<void> {
    try {
      if (type === 'auto') {
        // Try MetaMask/Brave first
        if ((window as any).ethereum) {
          await this._connectMetaMask();
        } else {
          // Fallback to WalletConnect
          await this._connectWalletConnect();
        }
      } else if (type === 'metamask') {
        await this._connectMetaMask();
      } else if (type === 'walletconnect') {
        await this._connectWalletConnect();
      }

      // Save connection state
      await this._saveState();
      await this._syncBackendState();

      console.log('Web3 wallet connected:', this._walletInfo);
    } catch (error) {
      console.error('Failed to connect wallet:', error);
      throw error;
    }
  }

  /**
   * Connect to MetaMask/Brave wallet
   */
  private async _connectMetaMask(): Promise<void> {
    const ethereum = (window as any).ethereum;
    if (!ethereum) {
      throw new Error('MetaMask or Brave Wallet not found. Please install a Web3 wallet extension.');
    }

    // Request account access
    await ethereum.request({ method: 'eth_requestAccounts' });

    // Create provider and signer
    this._provider = new BrowserProvider(ethereum);
    this._signer = await this._provider.getSigner();

    const address = await this._signer.getAddress();
    const network = await this._provider.getNetwork();
    const chainId = Number(network.chainId);

    // Detect if Brave
    const isBrave = (navigator as any).brave && await (navigator as any).brave.isBrave();

    this._walletInfo = {
      address,
      chainId,
      provider: isBrave ? 'brave' : 'metamask',
      connected: true
    };

    // Listen for account changes
    ethereum.on('accountsChanged', (accounts: string[]) => {
      if (accounts.length === 0) {
        this.disconnectWallet();
      } else {
        this._walletInfo = {
          ...this._walletInfo!,
          address: accounts[0]
        };
        void this._saveState();
        void this._syncBackendState();
      }
    });

    // Listen for chain changes
    ethereum.on('chainChanged', (chainId: string) => {
      this._walletInfo = {
        ...this._walletInfo!,
        chainId: parseInt(chainId, 16)
      };
      void this._saveState();
      void this._syncBackendState();
    });
  }

  /**
   * Connect via WalletConnect
   */
  private async _connectWalletConnect(): Promise<void> {
    const runtimeInfuraId =
      (window as any).__DATAMANCY_WALLETCONNECT_INFURA_ID__ ||
      (globalThis as any).process?.env?.WALLETCONNECT_INFURA_ID;
    if (!runtimeInfuraId) {
      throw new Error(
        'WalletConnect requires an Infura project ID. Set window.__DATAMANCY_WALLETCONNECT_INFURA_ID__.'
      );
    }

    const wcProvider = new WalletConnectProvider({
      infuraId: runtimeInfuraId,
      qrcode: true
    });

    await wcProvider.enable();

    this._provider = new BrowserProvider(wcProvider as any);
    this._signer = await this._provider.getSigner();

    const address = await this._signer.getAddress();
    const network = await this._provider.getNetwork();
    const chainId = Number(network.chainId);

    this._walletInfo = {
      address,
      chainId,
      provider: 'walletconnect',
      connected: true
    };

    // Listen for disconnection
    wcProvider.on('disconnect', () => {
      this.disconnectWallet();
    });
  }

  /**
   * Disconnect wallet
   */
  async disconnectWallet(): Promise<void> {
    this._provider = null;
    this._signer = null;
    this._walletInfo = null;

    await this._state.remove(this._stateKey);
    await this._syncBackendState();
    console.log('Web3 wallet disconnected');
  }

  /**
   * Sign a transaction
   */
  async signTransaction(tx: any): Promise<string> {
    if (!this._signer) {
      throw new Error('Wallet not connected');
    }

    const signedTx = await this._signer.signTransaction(tx);
    return signedTx;
  }

  /**
   * Send a transaction
   */
  async sendTransaction(tx: any): Promise<string> {
    if (!this._signer) {
      throw new Error('Wallet not connected');
    }

    const response = await this._signer.sendTransaction(tx);
    return response.hash;
  }

  /**
   * Sign a message
   */
  async signMessage(message: string): Promise<string> {
    if (!this._signer) {
      throw new Error('Wallet not connected');
    }

    return await this._signer.signMessage(message);
  }

  /**
   * Switch to a different chain
   */
  async switchChain(chainId: number): Promise<void> {
    const ethereum = (window as any).ethereum;
    if (!ethereum) {
      throw new Error('Wallet not available');
    }

    try {
      await ethereum.request({
        method: 'wallet_switchEthereumChain',
        params: [{ chainId: `0x${chainId.toString(16)}` }],
      });
    } catch (error: any) {
      // Chain doesn't exist, try adding it
      if (error.code === 4902) {
        throw new Error('Chain not configured in wallet. Please add it manually.');
      }
      throw error;
    }
  }

  /**
   * Restore connection from previous session
   */
  async restoreConnection(): Promise<void> {
    try {
      const savedState = await this._state.fetch(this._stateKey) as IWalletInfo | null;

      if (savedState?.connected) {
        // Try to reconnect silently
        if ((window as any).ethereum) {
          const ethereum = (window as any).ethereum;
          const accounts = await ethereum.request({ method: 'eth_accounts' });

          if (accounts.length > 0) {
            await this._connectMetaMask();
          }
        }
      }
      await this._syncBackendState();
    } catch (error) {
      console.warn('Failed to restore wallet connection:', error);
      await this._syncBackendState();
    }
  }

  /**
   * Save current state
   */
  private async _saveState(): Promise<void> {
    if (this._walletInfo) {
      await this._state.save(this._stateKey, this._walletInfo);
    }
  }

  private async _postWalletOperation(operation: string, payload: any = {}): Promise<void> {
    const response = await fetch(this._backendWalletPath, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({
        operation,
        ...payload
      })
    });

    if (!response.ok) {
      throw new Error(`Wallet backend request failed: ${response.status}`);
    }
  }

  private async _syncBackendState(): Promise<void> {
    const wallet = this._walletInfo
      ? this._walletInfo
      : {
          connected: false,
          address: null,
          chainId: null,
          provider: null
        };
    try {
      await this._postWalletOperation('update_wallet_state', { wallet });
    } catch (error) {
      console.warn('Failed to sync wallet state to backend:', error);
    }
  }
}
