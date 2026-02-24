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

    // Wait for page to stabilize after login
    await this.page.waitForTimeout(1000);

    // Check if we're on a consent/decision page (by URL or title)
    const url = this.page.url();
    const isConsentUrl = url.includes('/consent/') || url.includes('/decision');

    if (isConsentUrl) {
      console.log('   ✓ Consent screen URL detected');
      await logPageTelemetry(this.page, 'Consent Screen');

      // Look for consent/authorize button with multiple strategies
      const consentButton = this.page.getByRole('button', { name: /accept|authorize|consent|allow/i }).or(
        this.page.locator('button[type="submit"]').filter({ hasText: /accept|authorize|consent|allow/i })
      ).or(
        this.page.locator('button:has-text("Accept")').first()
      ).or(
        this.page.locator('button[id*="accept"], button[class*="accept"]').first()
      );

      try {
        // Wait for button to be visible and clickable
        await consentButton.waitFor({ state: 'visible', timeout: 5000 });
        console.log('   ✓ Consent button found');

        await consentButton.click();
        console.log('   ✓ Consent granted\n');

        // Wait for redirect back to service
        await this.page.waitForTimeout(2000);
      } catch (error) {
        console.log('   ⚠️  Consent button not found or not clickable');
        await logPageTelemetry(this.page, 'Consent Screen Error');
        throw new Error(`Failed to handle consent screen: ${error}`);
      }
    } else {
      await logPageTelemetry(this.page, 'Post-Login (No Consent)');
      console.log('   ℹ️  No consent screen (already granted or not required)\n');
    }
  }
}
