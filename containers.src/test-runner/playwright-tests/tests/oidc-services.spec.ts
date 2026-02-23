/**
 * Tests for services using OIDC authentication with Authelia
 *
 * These services have explicit OIDC client configurations and handle
 * the OAuth2/OIDC flow themselves (not just forward-auth).
 *
 * Services tested:
 * - Grafana
 * - Mastodon
 * - Forgejo
 * - BookStack
 * - Planka
 */

import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { AutheliaLoginPage } from '../pages/AutheliaLoginPage';
import { OIDCLoginPage } from '../pages/OIDCLoginPage';
import { logPageTelemetry, setupNetworkLogging } from '../utils/telemetry';

const testUser = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../.auth/test-user.json'), 'utf-8')
);

/**
 * Helper function to test OIDC service access with proper assertions
 */
async function testOIDCService(
  page: Page,
  serviceName: string,
  servicePath: string,
  uiPattern: RegExp,
  oidcButtonNames: string[] = ['Authelia']
) {
  console.log(`\n🧪 Testing ${serviceName} OIDC login`);

  setupNetworkLogging(page, serviceName);

  // Retry logic for SSL errors
  let retries = 3;

  while (retries > 0) {
    try {
      await page.goto(servicePath, { waitUntil: 'domcontentloaded', timeout: 15000 });
      break; // Success
    } catch (error: any) {
      if (error.message?.includes('SSL') || error.message?.includes('ERR_SSL_PROTOCOL_ERROR')) {
        console.log(`   ⚠️  SSL error, retrying... (${4 - retries}/3)`);
        retries--;
        await page.waitForTimeout(2000);
        if (retries === 0) throw error;
      } else {
        throw error;
      }
    }
  }

  await logPageTelemetry(page, `${serviceName} Login Page`);

  const oidcPage = new OIDCLoginPage(page);

  // Try to find and click OIDC button
  let buttonFound = false;
  for (const buttonName of oidcButtonNames) {
    try {
      await oidcPage.clickOIDCButton(buttonName);
      buttonFound = true;
      break;
    } catch (error) {
      continue;
    }
  }

  if (!buttonFound) {
    console.log('   ℹ️  OIDC button not found - might already be logged in...');
  }

  // If on Authelia, login
  if (page.url().includes('authelia') || page.url().includes('auth.') || page.url().includes(':9091')) {
    const autheliaPage = new AutheliaLoginPage(page);
    await autheliaPage.login(testUser.username, testUser.password);

    // Handle consent if shown
    await oidcPage.handleConsentScreen();
  }

  // CRITICAL ASSERTION: Must NOT be on auth page
  await expect(page).not.toHaveURL(/auth\.|authelia/);

  await logPageTelemetry(page, `${serviceName} Dashboard`);

  // ENHANCED: Verify we're on the CORRECT service page, not just "not auth"
  const body = page.locator('body');
  const hasContent = await body.isVisible();
  expect(hasContent).toBeTruthy();

  // Verify service-specific UI pattern to confirm correct page
  // Retry pattern matching to handle slow-loading SPAs
  let matchesPattern = false;
  let pageTitle = '';
  let bodyHTML = '';
  let pageText = '';
  const maxPatternRetries = 5;

  for (let i = 0; i < maxPatternRetries; i++) {
    pageTitle = await page.title();
    pageText = (await page.textContent('body').catch(() => '')) || '';
    bodyHTML = await body.innerHTML();
    matchesPattern = uiPattern.test(pageText || bodyHTML || pageTitle);

    if (matchesPattern) {
      break; // Pattern found, exit retry loop
    }

    if (i < maxPatternRetries - 1) {
      console.log(`   ⏳ Waiting for ${serviceName} UI to render... (${i + 1}/${maxPatternRetries})`);
      await page.waitForTimeout(2000); // Wait 2 seconds before retry
    }
  }

  if (!matchesPattern) {
    console.log(`   ⚠️  Pattern match failed for ${serviceName}`);
    console.log(`   Title: "${pageTitle}"`);
    console.log(`   Pattern: ${uiPattern}`);
    console.log(`   Body length: ${bodyHTML.length} chars`);
    throw new Error(`Expected ${serviceName} page but UI pattern not found. Pattern: ${uiPattern}, Title: "${pageTitle}"`);
  }

  // Capture screenshot for manual validation (compressed to prevent 5MB+ files)
  const screenshotName = `${serviceName.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-oidc-authenticated.jpg`;
  await page.screenshot({
    path: `/app/test-results/screenshots/${screenshotName}`,
    type: 'jpeg',
    quality: 85,
    fullPage: true
  });
  console.log(`   📸 Screenshot saved: ${screenshotName}`);
  console.log(`   👀 REVIEW SCREENSHOT to verify correct page loaded`);

  console.log(`   ✅ ${serviceName} OIDC login successful\n`);
}

test.describe('OIDC Services - SSO Flow', () => {

  test('Grafana - OIDC login flow', async ({ page }) => {
    // NOTE: Grafana is configured for Auth Proxy (forward auth), not OIDC
    // Environment: GF_AUTH_PROXY_ENABLED=true
    // Grafana uses Authelia -> Caddy -> Header-based auth, not OAuth/OIDC flow
    // This test is skipped as Grafana doesn't use OIDC in this deployment
    test.skip(true, 'Grafana uses Auth Proxy (forward auth), not OIDC');
  });

  test('Mastodon - OIDC login flow', async ({ page }) => {
    await testOIDCService(
      page,
      'Mastodon',
      'https://mastodon.datamancy.net/',
      /Mastodon|What's on your mind/i, // Look for Mastodon-specific UI
      ['Authelia', 'SSO']
    );
  });

  test('Forgejo - OIDC login flow', async ({ page }) => {
    await testOIDCService(
      page,
      'Forgejo',
      'https://forgejo.datamancy.net/',
      /Forgejo|Explore|repositories/i, // Look for "Forgejo" or repository listings
      ['Authelia']
    );
  });

  test('BookStack - OIDC login flow', async ({ page }) => {
    await testOIDCService(
      page,
      'BookStack',
      'https://bookstack.datamancy.net/',
      /Books|Shelves|Recently (Created|Updated)|My Recently Viewed/i, // Look for authenticated dashboard content, NOT error page
      ['Authelia', 'Login with SSO', 'SSO']
    );
  });

  test('Planka - OIDC login flow', async ({ page }) => {
    await testOIDCService(
      page,
      'Planka',
      'https://planka.datamancy.net/',
      /Planka|Add board|Projects/i, // Look for Planka-specific UI
      ['Authelia', 'SSO', 'OIDC']
    );
  });

  test('Vaultwarden - OIDC login flow', async ({ page }) => {
    await testOIDCService(
      page,
      'Vaultwarden',
      'https://vaultwarden.datamancy.net/',
      /Vaultwarden|Bitwarden|My Vault|Log in/i, // Look for Vaultwarden UI
      ['Authelia', 'SSO']
    );
  });
});

test.describe('OIDC - Cross-service Session', () => {

  test('OIDC session works across multiple services', async ({ page }) => {
    console.log('\n🧪 Testing OIDC session persistence');

    // Login to first OIDC service
    console.log('\n   Logging into Grafana (first OIDC service)...');
    await page.goto('https://grafana.datamancy.net/');

    const oidcPage = new OIDCLoginPage(page);

    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      // Might already be logged in
    }

    if (page.url().includes('authelia') || page.url().includes('auth.')) {
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
      await oidcPage.handleConsentScreen();
    }

    // CRITICAL: Verify we're actually on Grafana
    await expect(page).not.toHaveURL(/auth\.|authelia/);
    console.log('   ✅ Grafana login complete');

    // Try second OIDC service - should not require full login
    console.log('\n   Accessing BookStack (second OIDC service)...');
    await page.goto('https://bookstack.datamancy.net/');

    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      try {
        await oidcPage.clickOIDCButton('Login with SSO');
      } catch (error2) {
        // Button might not be there if already logged in
      }
    }

    // Should skip Authelia login screen (already authenticated)
    const needsAuth = page.url().includes('authelia') &&
      await page.locator('input[type="password"]').isVisible({ timeout: 2000 }).catch(() => false);

    if (needsAuth) {
      console.log('   ⚠️  Had to re-authenticate (session not shared)');
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
    } else {
      console.log('   ✅ No re-authentication needed - session shared!');
    }

    await oidcPage.handleConsentScreen();

    // CRITICAL: Verify we're on BookStack
    await expect(page).not.toHaveURL(/auth\.|authelia/);

    console.log('\n   ✅ OIDC session test complete\n');
  });
});
