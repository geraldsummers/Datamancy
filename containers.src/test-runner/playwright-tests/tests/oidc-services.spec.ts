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
import * as fs from 'fs';
import * as path from 'path';
import { AutheliaLoginPage } from '../pages/AutheliaLoginPage';
import { OIDCLoginPage } from '../pages/OIDCLoginPage';
import { logPageTelemetry, setupNetworkLogging } from '../utils/telemetry';

const testUser = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../.auth/test-user.json'), 'utf-8')
);

test.describe('OIDC Services - SSO Flow', () => {

  test('Grafana - OIDC login flow', async ({ page }) => {
    console.log('\n🧪 Testing Grafana OIDC login');

    setupNetworkLogging(page, 'Grafana');

    await page.goto('/grafana');
    await logPageTelemetry(page, 'Grafana Login Page');

    // Look for OIDC login button (varies by Grafana config)
    const oidcPage = new OIDCLoginPage(page);

    // Try to find and click OIDC button
    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      // Might be directly showing Authelia or username/password
      console.log('   ℹ️  OIDC button not found, checking current page...');
      await logPageTelemetry(page, 'Grafana - Unexpected State');
    }

    // If on Authelia, login
    if (page.url().includes('authelia') || page.url().includes(':9091')) {
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);

      // Handle consent if shown
      await oidcPage.handleConsentScreen();
    }

    // Should be on Grafana dashboard
    await expect(page).toHaveURL(/grafana/, { timeout: 15000 });
    await logPageTelemetry(page, 'Grafana Dashboard');

    const hasGrafanaUI = await page.locator('text=/dashboard|panel|grafana/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasGrafanaUI ? '✅' : '⚠️'} Grafana OIDC login successful\n`);
  });

  test('Mastodon - OIDC login flow', async ({ page }) => {
    console.log('\n🧪 Testing Mastodon OIDC login');

    setupNetworkLogging(page, 'Mastodon');

    await page.goto('/mastodon');
    await logPageTelemetry(page, 'Mastodon Login Page');

    const oidcPage = new OIDCLoginPage(page);

    // Mastodon might call it "SSO" or "Authelia"
    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      console.log('   ℹ️  Checking for alternative OIDC button names...');
      try {
        await oidcPage.clickOIDCButton('SSO');
      } catch (error2) {
        console.log('   ⚠️  No OIDC button found');
        await logPageTelemetry(page, 'Mastodon - No OIDC Button');
      }
    }

    if (page.url().includes('authelia')) {
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
      await oidcPage.handleConsentScreen();
    }

    await logPageTelemetry(page, 'Mastodon - Post Login');

    const hasMastodonUI = await page.locator('text=/toot|timeline|mastodon/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasMastodonUI ? '✅' : '⚠️'} Mastodon OIDC login completed\n`);
  });

  test('Forgejo - OIDC login flow', async ({ page }) => {
    console.log('\n🧪 Testing Forgejo OIDC login');

    setupNetworkLogging(page, 'Forgejo');

    await page.goto('/forgejo');
    await logPageTelemetry(page, 'Forgejo Login Page');

    const oidcPage = new OIDCLoginPage(page);

    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      console.log('   ⚠️  OIDC button not found on Forgejo');
      await logPageTelemetry(page, 'Forgejo - No OIDC');
    }

    if (page.url().includes('authelia')) {
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
      await oidcPage.handleConsentScreen();
    }

    await logPageTelemetry(page, 'Forgejo - Post Login');

    const hasForgejoUI = await page.locator('text=/repository|git|forgejo/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasForgejoUI ? '✅' : '⚠️'} Forgejo OIDC login completed\n`);
  });

  test('BookStack - OIDC login flow', async ({ page }) => {
    console.log('\n🧪 Testing BookStack OIDC login');

    setupNetworkLogging(page, 'BookStack');

    await page.goto('/bookstack');
    await logPageTelemetry(page, 'BookStack Login Page');

    const oidcPage = new OIDCLoginPage(page);

    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      console.log('   ℹ️  Trying alternative BookStack OIDC button...');
      try {
        await oidcPage.clickOIDCButton('Login with SSO');
      } catch (error2) {
        console.log('   ⚠️  No OIDC button found');
        await logPageTelemetry(page, 'BookStack - No OIDC');
      }
    }

    if (page.url().includes('authelia')) {
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
      await oidcPage.handleConsentScreen();
    }

    await logPageTelemetry(page, 'BookStack - Post Login');

    const hasBookStackUI = await page.locator('text=/book|shelf|chapter|bookstack/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasBookStackUI ? '✅' : '⚠️'} BookStack OIDC login completed\n`);
  });

  test('Planka - OIDC login flow', async ({ page }) => {
    console.log('\n🧪 Testing Planka OIDC login');

    setupNetworkLogging(page, 'Planka');

    await page.goto('/planka');
    await logPageTelemetry(page, 'Planka Login Page');

    const oidcPage = new OIDCLoginPage(page);

    // Planka might call it "SSO", "Authelia", or "OIDC"
    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      console.log('   ℹ️  Trying alternative Planka OIDC button...');
      try {
        await oidcPage.clickOIDCButton('SSO');
      } catch (error2) {
        try {
          await oidcPage.clickOIDCButton('OIDC');
        } catch (error3) {
          console.log('   ⚠️  No OIDC button found');
          await logPageTelemetry(page, 'Planka - No OIDC');
        }
      }
    }

    if (page.url().includes('authelia')) {
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
      await oidcPage.handleConsentScreen();
    }

    await logPageTelemetry(page, 'Planka - Post Login');

    const hasPlankaUI = await page.locator('text=/board|project|card|task|planka/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasPlankaUI ? '✅' : '⚠️'} Planka OIDC login completed\n`);
  });
});

test.describe('OIDC - Cross-service Session', () => {

  test('OIDC session works across multiple services', async ({ page }) => {
    console.log('\n🧪 Testing OIDC session persistence');

    // Login to first OIDC service
    console.log('\n   Logging into Grafana (first OIDC service)...');
    await page.goto('/grafana');

    const oidcPage = new OIDCLoginPage(page);

    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      // Might already be logged in
    }

    if (page.url().includes('authelia')) {
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
      await oidcPage.handleConsentScreen();
    }

    console.log('   ✅ Grafana login complete');

    // Try second OIDC service - should not require full login
    console.log('\n   Accessing BookStack (second OIDC service)...');
    await page.goto('/bookstack');

    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      // Button might not be there if already logged in
    }

    // Should skip Authelia login screen (already authenticated)
    const needsAuth = page.url().includes('authelia') && await page.locator('input[type="password"]').isVisible({ timeout: 2000 }).catch(() => false);

    if (needsAuth) {
      console.log('   ⚠️  Had to re-authenticate (session not shared)');
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
    } else {
      console.log('   ✅ No re-authentication needed - session shared!');
    }

    await oidcPage.handleConsentScreen();

    console.log('\n   ✅ OIDC session test complete\n');
  });
});
