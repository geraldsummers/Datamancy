/**
 * Page Object for OIDC Login Flow
 *
 * Used by services with explicit OIDC integration:
 * - Grafana
 * - Mastodon
 * - Forgejo
 * - BookStack
 */

import { Page } from '@playwright/test';
import { logPageTelemetry } from '../utils/telemetry';

export class OIDCLoginPage {
  readonly page: Page;

  constructor(page: Page) {
    this.page = page;
  }

  /**
   * Click OIDC login button (varies by service)
   */
  async clickOIDCButton(buttonText: string = 'Authelia') {
    console.log(`\n🔗 Initiating OIDC login with: ${buttonText}`);

    await logPageTelemetry(this.page, 'Service Login Page (pre-OIDC)');

    // Find OIDC button - try multiple strategies
    const oidcButton = this.page.getByRole('button', { name: new RegExp(buttonText, 'i') }).or(
      this.page.getByRole('link', { name: new RegExp(buttonText, 'i') })
    ).or(
      this.page.locator(`button:has-text("${buttonText}")`).first()
    ).or(
      this.page.locator(`a:has-text("${buttonText}")`).first()
    );

    await oidcButton.click();
    console.log(`   ✓ Clicked OIDC button: ${buttonText}`);

    // Wait for Authelia page to load
    await this.page.waitForURL((url) => url.toString().includes('authelia') || url.toString().includes(':9091'), {
      timeout: 10000,
    });

    console.log(`   ✓ Redirected to Authelia: ${this.page.url()}\n`);
  }

  /**
   * Complete Authelia consent screen (if shown)
   */
  async handleConsentScreen() {
    console.log('🔍 Checking for OIDC consent screen...');

    await logPageTelemetry(this.page, 'Potential Consent Screen');

    // Look for consent/authorize button
    const consentButton = this.page.getByRole('button', { name: /accept|authorize|consent|allow/i }).or(
      this.page.locator('button[type="submit"]').filter({ hasText: /accept|authorize|consent|allow/i })
    );

    const isVisible = await consentButton.isVisible().catch(() => false);

    if (isVisible) {
      console.log('   ✓ Consent screen detected');
      await consentButton.click();
      console.log('   ✓ Consent granted\n');

      // Wait for redirect back to service
      await this.page.waitForTimeout(2000);
    } else {
      console.log('   ℹ️  No consent screen (already granted or not required)\n');
    }
  }
}
