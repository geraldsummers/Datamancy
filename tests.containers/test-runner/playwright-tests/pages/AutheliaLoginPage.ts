/**
 * Page Object for Authelia Login (Forward Auth)
 *
 * Used by services that rely on Caddy forward-auth:
 * - JupyterHub
 * - Open-WebUI
 * - Prometheus
 * - Vaultwarden
 * - Homepage
 * - Ntfy
 * - etc.
 */

import { Page, Locator } from '@playwright/test';
import { logPageTelemetry } from '../utils/telemetry';

export class AutheliaLoginPage {
  readonly page: Page;
  readonly usernameInput: Locator;
  readonly passwordInput: Locator;
  readonly submitButton: Locator;

  constructor(page: Page) {
    this.page = page;

    // Authelia uses specific IDs - try them first, then fall back to generic selectors
    this.usernameInput = page.locator('#username-textfield').or(
      page.locator('input[name="username"]')
    ).or(
      page.locator('input[id="username"]')
    ).or(
      page.locator('input[placeholder*="username" i]')
    ).or(
      page.locator('input[type="text"]').first()
    );

    this.passwordInput = page.locator('#password-textfield').or(
      page.locator('input[name="password"]')
    ).or(
      page.locator('input[id="password"]')
    ).or(
      page.locator('input[type="password"]').first()
    );

    this.submitButton = page.locator('#sign-in-button').or(
      page.locator('button[type="submit"]')
    ).or(
      page.getByRole('button', { name: /sign in|login|submit/i })
    ).first();
  }

  /**
   * Login with Authelia (forward auth)
   */
  async login(username: string, password: string) {
    console.log(`\n🔐 Logging in with Authelia as: ${username}`);

    // Log page structure before interacting
    await logPageTelemetry(this.page, 'Authelia Login Page');

    // Fill credentials
    await this.usernameInput.fill(username);
    console.log('   ✓ Username entered');

    await this.passwordInput.fill(password);
    console.log('   ✓ Password entered');

    // Take screenshot before submit
    await this.page.screenshot({
      path: `test-results/authelia-before-submit-${Date.now()}.png`,
      fullPage: true,
    });

    // Click submit and wait for auth to complete (API or redirect depending on flow)
    const responsePromise = this.page
      .waitForResponse((resp) => resp.url().includes('/api/firstfactor') && resp.status() === 200, { timeout: 15000 })
      .catch(() => null);
    await this.submitButton.click();
    const response = await responsePromise;
    if (response) {
      console.log('   ✓ Submit clicked and auth response received');
    } else {
      console.log('   ⚠️  Auth API response not observed, waiting for redirect');
    }

    // Wait for navigation away from Authelia
    await this.page
      .waitForURL((url) => !url.toString().includes('authelia') && !url.toString().includes(':9091'), {
        timeout: 20000,
      })
      .catch(() => {});

    console.log(`   ✓ Redirected to: ${this.page.url()}\n`);
  }

  /**
   * Check if currently on Authelia login page
   */
  async isOnLoginPage(): Promise<boolean> {
    const url = this.page.url();
    return url.toString().includes('authelia') || url.toString().includes(':9091');
  }
}
