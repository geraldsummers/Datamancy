import {
  JupyterFrontEnd,
  JupyterFrontEndPlugin
} from '@jupyterlab/application';
import { ICommandPalette, MainAreaWidget, WidgetTracker } from '@jupyterlab/apputils';
import { IStateDB } from '@jupyterlab/statedb';
import { Web3WalletWidget } from './widget';
import { Web3WalletManager } from './manager';

/**
 * The command IDs used by the Web3 wallet extension.
 */
namespace CommandIDs {
  export const connectWallet = 'web3-wallet:connect';
  export const disconnectWallet = 'web3-wallet:disconnect';
  export const showWidget = 'web3-wallet:show-widget';
}

/**
 * Initialization data for the @datamancy/jupyterlab-web3-wallet extension.
 */
const plugin: JupyterFrontEndPlugin<Web3WalletManager> = {
  id: '@datamancy/jupyterlab-web3-wallet:plugin',
  autoStart: true,
  requires: [ICommandPalette, IStateDB],
  provides: Web3WalletManager,
  activate: async (
    app: JupyterFrontEnd,
    palette: ICommandPalette,
    state: IStateDB
  ): Promise<Web3WalletManager> => {
    console.log('JupyterLab extension @datamancy/jupyterlab-web3-wallet is activated!');

    // Create the wallet manager
    const manager = new Web3WalletManager(state);

    // Create widget tracker
    const tracker = new WidgetTracker<MainAreaWidget<Web3WalletWidget>>({
      namespace: 'web3-wallet'
    });

    // Add commands
    app.commands.addCommand(CommandIDs.connectWallet, {
      label: '🦊 Connect Web3 Wallet',
      caption: 'Connect MetaMask or WalletConnect',
      execute: async () => {
        await manager.connectWallet();
      }
    });

    app.commands.addCommand(CommandIDs.disconnectWallet, {
      label: 'Disconnect Web3 Wallet',
      caption: 'Disconnect current Web3 wallet',
      execute: async () => {
        await manager.disconnectWallet();
      },
      isEnabled: () => manager.isConnected
    });

    app.commands.addCommand(CommandIDs.showWidget, {
      label: 'Show Web3 Wallet Widget',
      caption: 'Show the Web3 wallet connection widget',
      execute: () => {
        // Create widget if it doesn't exist
        if (tracker.currentWidget) {
          app.shell.activateById(tracker.currentWidget.id);
          return;
        }

        const content = new Web3WalletWidget(manager);
        const widget = new MainAreaWidget({ content });
        widget.id = 'web3-wallet-widget';
        widget.title.label = 'Web3 Wallet';
        widget.title.closable = true;
        widget.title.icon = 'ethereum-icon'; // Custom icon

        tracker.add(widget);
        app.shell.add(widget, 'main');
      }
    });

    // Add commands to palette
    palette.addItem({
      command: CommandIDs.connectWallet,
      category: 'Web3'
    });

    palette.addItem({
      command: CommandIDs.disconnectWallet,
      category: 'Web3'
    });

    palette.addItem({
      command: CommandIDs.showWidget,
      category: 'Web3'
    });

    // Restore wallet connection from previous session
    await manager.restoreConnection();

    return manager;
  }
};

export default plugin;
export { Web3WalletManager };
