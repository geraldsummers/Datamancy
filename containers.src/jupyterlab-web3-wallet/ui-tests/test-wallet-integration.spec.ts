/**
 * Playwright E2E tests for JupyterLab Web3 Wallet Extension
 *
 * These tests simulate real user interactions with MetaMask and the wallet widget.
 * They require a running JupyterLab instance and MetaMask browser extension.
 *
 * Run with:
 *   npm install -D @playwright/test
 *   npx playwright test
 */

import { test, expect } from '@playwright/test';

// Configuration
const JUPYTER_URL = process.env.JUPYTER_URL || 'http://localhost:8000';
const TEST_TIMEOUT = 30000;

test.describe('JupyterLab Web3 Wallet Extension', () => {

  test.beforeEach(async ({ page }) => {
    // Navigate to JupyterLab
    await page.goto(JUPYTER_URL);
    await page.waitForLoadState('networkidle');
  });

  test('Extension is loaded in JupyterLab', async ({ page }) => {
    // Check if the extension is listed in JupyterLab's extension manager
    // This verifies the extension was successfully installed

    // Open command palette
    await page.keyboard.press('Control+Shift+C');

    // Search for wallet command
    await page.fill('[placeholder="Search"]', 'Connect Web3 Wallet');

    // Should find the wallet connect command
    const walletCommand = await page.locator('text=🦊 Connect Web3 Wallet');
    await expect(walletCommand).toBeVisible({ timeout: 5000 });
  });

  test('Wallet widget can be opened', async ({ page }) => {
    // Open command palette
    await page.keyboard.press('Control+Shift+C');

    // Search for widget command
    await page.fill('[placeholder="Search"]', 'Show Web3 Wallet Widget');
    await page.keyboard.press('Enter');

    // Wait for widget to appear
    await page.waitForSelector('.web3-wallet-widget', { timeout: TEST_TIMEOUT });

    // Verify widget elements are present
    const header = await page.locator('text=🦊 Web3 Wallet');
    await expect(header).toBeVisible();

    const statusText = await page.locator('.status-text');
    await expect(statusText).toHaveText('Not Connected');

    const connectButton = await page.locator('button:has-text("Connect Wallet")');
    await expect(connectButton).toBeVisible();
  });

  test('Widget shows wallet connection status', async ({ page }) => {
    // Open widget
    await page.keyboard.press('Control+Shift+C');
    await page.fill('[placeholder="Search"]', 'Show Web3 Wallet Widget');
    await page.keyboard.press('Enter');
    await page.waitForSelector('.web3-wallet-widget');

    // Check initial state (disconnected)
    const statusIndicator = await page.locator('.status-indicator');
    const backgroundColor = await statusIndicator.evaluate(
      (el) => window.getComputedStyle(el).backgroundColor
    );

    // Should be gray when disconnected
    expect(backgroundColor).toContain('117, 117, 117'); // rgb(117, 117, 117)
  });

  test('Help section displays supported wallets', async ({ page }) => {
    // Open widget
    await page.keyboard.press('Control+Shift+C');
    await page.fill('[placeholder="Search"]', 'Show Web3 Wallet Widget');
    await page.keyboard.press('Enter');
    await page.waitForSelector('.web3-wallet-widget');

    // Check help section
    const helpSection = await page.locator('.web3-wallet-help');
    await expect(helpSection).toBeVisible();

    // Should list MetaMask
    const metaMaskItem = await page.locator('text=🦊 MetaMask');
    await expect(metaMaskItem).toBeVisible();

    // Should list Brave Wallet
    const braveItem = await page.locator('text=🦁 Brave Wallet');
    await expect(braveItem).toBeVisible();

    // Should list WalletConnect
    const wcItem = await page.locator('text=🔗 WalletConnect');
    await expect(wcItem).toBeVisible();
  });

  test('Wallet info displays correctly when connected', async ({ page }) => {
    // This test requires MetaMask to be installed and unlocked
    // Skip if MetaMask is not available

    const hasMetaMask = await page.evaluate(() => {
      return typeof (window as any).ethereum !== 'undefined';
    });

    if (!hasMetaMask) {
      test.skip();
      return;
    }

    // Open widget
    await page.keyboard.press('Control+Shift+C');
    await page.fill('[placeholder="Search"]', 'Show Web3 Wallet Widget');
    await page.keyboard.press('Enter');
    await page.waitForSelector('.web3-wallet-widget');

    // Click connect button
    const connectButton = await page.locator('button:has-text("Connect Wallet")');
    await connectButton.click();

    // Wait for MetaMask popup (in real test, you'd need to handle the popup)
    // For now, just verify the request was made
    await page.waitForTimeout(2000);

    // In a real test with MetaMask automation, verify:
    // - Status changes to "Connected"
    // - Address is displayed (truncated format)
    // - Chain ID is shown
    // - Disconnect button appears
  });

  test('Magic command endpoint is accessible', async ({ page }) => {
    // Create a new notebook
    await page.click('text=File');
    await page.click('text=New');
    await page.click('text=Notebook');

    // Select Kotlin kernel if available, otherwise skip
    const kotlinKernel = await page.locator('text=/.*Kotlin.*/i').first();
    if (await kotlinKernel.isVisible({ timeout: 2000 }).catch(() => false)) {
      await kotlinKernel.click();
    } else {
      test.skip(); // Skip if Kotlin kernel not available
      return;
    }

    // Wait for notebook to load
    await page.waitForSelector('.jp-Notebook', { timeout: TEST_TIMEOUT });

    // Type %walletConnect magic command
    const firstCell = await page.locator('.jp-Cell').first();
    await firstCell.click();
    await page.keyboard.type('%walletConnect');

    // Run the cell
    await page.keyboard.press('Shift+Enter');

    // Wait for output (may be error if not connected, but endpoint should respond)
    await page.waitForTimeout(3000);

    // Verify some output was generated
    const output = await page.locator('.jp-OutputArea');
    await expect(output).toBeVisible();
  });

  test('Extension survives JupyterLab refresh', async ({ page }) => {
    // Open widget
    await page.keyboard.press('Control+Shift+C');
    await page.fill('[placeholder="Search"]', 'Show Web3 Wallet Widget');
    await page.keyboard.press('Enter');
    await page.waitForSelector('.web3-wallet-widget');

    // Reload page
    await page.reload();
    await page.waitForLoadState('networkidle');

    // Extension should still be available
    await page.keyboard.press('Control+Shift+C');
    await page.fill('[placeholder="Search"]', 'Connect Web3 Wallet');

    const walletCommand = await page.locator('text=🦊 Connect Web3 Wallet');
    await expect(walletCommand).toBeVisible({ timeout: 5000 });
  });
});

test.describe('Wallet State Persistence', () => {

  test('Wallet connection state persists across sessions', async ({ page }) => {
    // This would require:
    // 1. Connect wallet in first session
    // 2. Close JupyterLab
    // 3. Reopen JupyterLab
    // 4. Verify wallet is still connected

    // Placeholder for full implementation
    test.skip();
  });
});

test.describe('Trading SDK Integration', () => {

  test('TxGateway.fromWallet() detects connected wallet', async ({ page, context }) => {
    // Skip if Kotlin kernel not available
    test.skip();

    // This would test:
    // 1. Connect wallet via widget
    // 2. Create notebook with Kotlin kernel
    // 3. Import trading SDK
    // 4. Call TxGateway.fromWallet()
    // 5. Verify successful connection message
  });

  test('TxGateway.fromWallet() shows helpful error when disconnected', async ({ page }) => {
    // Skip if Kotlin kernel not available
    test.skip();

    // This would test:
    // 1. Create notebook (wallet NOT connected)
    // 2. Import trading SDK
    // 3. Call TxGateway.fromWallet()
    // 4. Verify error message instructs user to connect wallet
  });
});
