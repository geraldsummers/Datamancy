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
    const nameRegex = new RegExp(buttonText, 'i');
    const oidcButtonByText = this.page.getByRole('button', { name: nameRegex }).or(
      this.page.getByRole('link', { name: nameRegex })
    ).or(
      this.page.locator(`button:has-text("${buttonText}")`).first()
    ).or(
      this.page.locator(`a:has-text("${buttonText}")`).first()
    ).or(
      this.page.getByText(nameRegex).first()
    );

    let clicked = false;
    if (await oidcButtonByText.first().isVisible().catch(() => false)) {
      await oidcButtonByText.first().click({ force: true });
      clicked = true;
      console.log(`   ✓ Clicked OIDC button: ${buttonText}`);
    } else {
      // Fallback: look for explicit OIDC/OpenID/SSO links
      const oidcLinkByHref = this.page.locator(
        'a[href*="openid"], a[href*="oidc"], a[href*="oauth"], a[href*="sso"]'
      ).first();

      if (await oidcLinkByHref.isVisible().catch(() => false)) {
        await oidcLinkByHref.click();
        clicked = true;
        console.log('   ✓ Clicked OIDC link by href match');
      }
    }

    if (!clicked) {
      throw new Error(`OIDC button/link not found for: ${buttonText}`);
    }

    // Wait for Authelia page to load (auth.* or authelia container)
    await this.page.waitForURL(
      (url) => {
        const href = url.toString();
        return href.includes('authelia') || href.includes(':9091') || href.includes('auth.');
      },
      { timeout: 15000 }
    ).catch(() => {
      console.log('   ⚠️  No auth redirect detected yet (continuing)');
    });

    if (this.page.url().includes('auth.') || this.page.url().includes('authelia') || this.page.url().includes(':9091')) {
      console.log(`   ✓ Redirected to Authelia: ${this.page.url()}\n`);
    }
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
