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
      page.locator('input[autocomplete="username"]')
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
      page.locator('input[autocomplete="current-password"]')
    ).or(
      page.locator('input[type="password"]').first()
    );

    this.submitButton = page.locator('#sign-in-button').or(
      page.locator('button[type="submit"]')
    ).or(
      page.getByRole('button', { name: /sign in|login|submit/i })
    ).first();
  }

  private isAuthUrl(href: string): boolean {
    try {
      const parsed = new URL(href);
      return parsed.hostname.startsWith('auth.')
        || parsed.hostname.includes('authelia')
        || parsed.port === '9091';
    } catch {
      return /auth\.|authelia|:9091/i.test(href);
    }
  }

  /**
   * Login with Authelia (forward auth)
   */
  async login(username: string, password: string) {
    console.log(`\n🔐 Logging in with Authelia as: ${username}`);

    // Log page structure before interacting
    await logPageTelemetry(this.page, 'Authelia Login Page');

    // Allow Authelia SPA to finish rendering before interacting with form fields.
    const usernameReady = await this.usernameInput
      .first()
      .waitFor({ state: 'visible', timeout: 20000 })
      .then(() => true)
      .catch(() => false);

    if (!usernameReady) {
      const bodyText = await this.page.textContent('body').catch(() => '') || '';
      if (/loading/i.test(bodyText)) {
        await this.page.waitForTimeout(3000);
        await this.page.reload({ waitUntil: 'domcontentloaded' }).catch(() => {});
      }
      await this.usernameInput.first().waitFor({ state: 'visible', timeout: 20000 });
    }

    await this.passwordInput.first().waitFor({ state: 'visible', timeout: 20000 });
    await this.submitButton.waitFor({ state: 'visible', timeout: 20000 });

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

    // Click submit and wait for auth to complete (API, consent screen, or service redirect depending on flow)
    const responsePromise = this.page
      .waitForResponse((resp) => resp.url().includes('/api/firstfactor'), { timeout: 15000 })
      .catch(() => null);
    await this.submitButton.click();
    const response = await responsePromise;
    if (response) {
      console.log(`   ✓ Submit clicked and auth response received (${response.status()})`);
    } else {
      console.log('   ⚠️  Auth API response not observed, waiting for redirect');
    }

    // Some OIDC flows remain on auth host for consent, but the login form itself should disappear.
    await this.page
      .waitForFunction(() => {
        const usernameField = document.querySelector('#username-textfield, input[name="username"], input[id="username"], input[autocomplete="username"]');
        const passwordField = document.querySelector('#password-textfield, input[name="password"], input[id="password"], input[autocomplete="current-password"]');
        const signInButton = document.querySelector('#sign-in-button, button[type="submit"]');
        const poweredByAuthelia = /powered by authelia/i.test(document.body?.innerText ?? '');
        return !usernameField || !passwordField || !signInButton || !poweredByAuthelia;
      }, { timeout: 20000 })
      .catch(() => {});

    if (this.isAuthUrl(this.page.url())) {
      const formStillVisible = await this.usernameInput.first().isVisible().catch(() => false)
        && await this.passwordInput.first().isVisible().catch(() => false)
        && await this.submitButton.isVisible().catch(() => false);
      if (formStillVisible) {
        console.log('   ⚠️  Login form remained visible after submit; retrying once with Enter');
        await this.passwordInput.first().press('Enter').catch(() => {});
      }
    }

    // Wait for navigation away from the auth host when it happens.
    await this.page
      .waitForURL((url) => !this.isAuthUrl(url.toString()), {
        timeout: 20000,
      })
      .catch(() => {});

    console.log(`   ✓ Redirected to: ${this.page.url()}\n`);
  }

  /**
   * Check if currently on Authelia login page
   */
  async isOnLoginPage(): Promise<boolean> {
    return this.isAuthUrl(this.page.url());
  }
}
