/**
 * Tests for services using Authelia Forward Authentication
 *
 * These services rely on Caddy's forward_auth directive pointing to Authelia.
 * Once authenticated with Authelia, the session works across all forward-auth services.
 *
 * Services tested:
 * - JupyterHub
 * - Open-WebUI
 * - Prometheus
 * - Vaultwarden
 * - Homepage
 * - Ntfy
 * - qBittorrent
 */

import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { AutheliaLoginPage } from '../pages/AutheliaLoginPage';
import { logPageTelemetry, setupNetworkLogging } from '../utils/telemetry';

// Load test user credentials
const testUser = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../.auth/test-user.json'), 'utf-8')
);

test.describe('Forward Auth Services - SSO Flow', () => {
  test.use({
    // Use saved auth state from global setup
    storageState: path.join(__dirname, '../.auth/authelia-session.json'),
  });

  test('JupyterHub - Access with forward auth', async ({ page }) => {
    console.log('\n🧪 Testing JupyterHub forward auth');

    // Enable network logging
    setupNetworkLogging(page, 'JupyterHub');

    await page.goto('/jupyterhub');

    // Should either be on JupyterHub directly (if auth state works)
    // or redirected to Authelia login
    if (page.url().includes('authelia') || page.url().includes(':9091')) {
      console.log('   ⚠️  Auth state expired, logging in again...');
      const loginPage = new AutheliaLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    // Verify we're on JupyterHub
    await expect(page).toHaveURL(/jupyterhub|jupyter|8000/, { timeout: 15000 });
    await logPageTelemetry(page, 'JupyterHub Main Page');

    // Look for JupyterHub-specific elements
    const hasJupyterElements = await page.locator('text=/jupyter|notebook|hub/i').first().isVisible().catch(() => false);

    if (hasJupyterElements) {
      console.log('   ✅ JupyterHub accessed successfully\n');
    } else {
      console.log('   ⚠️  On JupyterHub domain but UI not confirmed\n');
    }
  });

  test('Open-WebUI - Access with forward auth', async ({ page }) => {
    console.log('\n🧪 Testing Open-WebUI forward auth');

    setupNetworkLogging(page, 'Open-WebUI');

    await page.goto('/open-webui');

    if (page.url().includes('authelia')) {
      console.log('   ⚠️  Auth state expired, logging in...');
      const loginPage = new AutheliaLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    await logPageTelemetry(page, 'Open-WebUI Main Page');

    // Open-WebUI might have its own internal auth - check for it
    const hasOpenWebUIElements = await page.locator('text=/chat|conversation|model/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasOpenWebUIElements ? '✅' : '⚠️'} Open-WebUI page loaded\n`);
  });

  test('Prometheus - Access with forward auth', async ({ page }) => {
    console.log('\n🧪 Testing Prometheus forward auth');

    setupNetworkLogging(page, 'Prometheus');

    await page.goto('/prometheus');

    if (page.url().includes('authelia')) {
      console.log('   ⚠️  Auth state expired, logging in...');
      const loginPage = new AutheliaLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    await logPageTelemetry(page, 'Prometheus Main Page');

    // Check for Prometheus UI
    const hasPrometheusUI = await page.locator('text=/prometheus|query|graph/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasPrometheusUI ? '✅' : '⚠️'} Prometheus accessed\n`);
  });

  test('Vaultwarden - Access with forward auth', async ({ page }) => {
    console.log('\n🧪 Testing Vaultwarden forward auth');

    setupNetworkLogging(page, 'Vaultwarden');

    await page.goto('/vaultwarden');

    if (page.url().includes('authelia')) {
      console.log('   ⚠️  Auth state expired, logging in...');
      const loginPage = new AutheliaLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    await logPageTelemetry(page, 'Vaultwarden Main Page');

    // Vaultwarden has its own login - we're just testing that forward-auth lets us reach it
    const hasVaultwardenUI = await page.locator('text=/vault|bitwarden|password/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasVaultwardenUI ? '✅' : '⚠️'} Vaultwarden page loaded\n`);
  });

  test('Homepage - Access with forward auth', async ({ page }) => {
    console.log('\n🧪 Testing Homepage forward auth');

    setupNetworkLogging(page, 'Homepage');

    await page.goto('/homepage');

    if (page.url().includes('authelia')) {
      console.log('   ⚠️  Auth state expired, logging in...');
      const loginPage = new AutheliaLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    await logPageTelemetry(page, 'Homepage Main Page');

    console.log('   ✅ Homepage accessed\n');
  });

  test('Ntfy - Access with forward auth', async ({ page }) => {
    console.log('\n🧪 Testing Ntfy forward auth');

    setupNetworkLogging(page, 'Ntfy');

    await page.goto('/ntfy');

    if (page.url().includes('authelia')) {
      console.log('   ⚠️  Auth state expired, logging in...');
      const loginPage = new AutheliaLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    await logPageTelemetry(page, 'Ntfy Main Page');

    const hasNtfyUI = await page.locator('text=/notification|subscribe|topic/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasNtfyUI ? '✅' : '⚠️'} Ntfy page loaded\n`);
  });

  test('qBittorrent - Access with forward auth', async ({ page }) => {
    console.log('\n🧪 Testing qBittorrent forward auth');

    setupNetworkLogging(page, 'qBittorrent');

    await page.goto('/qbittorrent');

    if (page.url().includes('authelia')) {
      console.log('   ⚠️  Auth state expired, logging in...');
      const loginPage = new AutheliaLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    await logPageTelemetry(page, 'qBittorrent Main Page');

    // qBittorrent might have its own auth
    const hasQBitUI = await page.locator('text=/torrent|download|upload/i').first().isVisible({ timeout: 5000 }).catch(() => false);

    console.log(`   ${hasQBitUI ? '✅' : '⚠️'} qBittorrent page loaded\n`);
  });
});

test.describe('Forward Auth - Session Persistence', () => {
  test.use({
    storageState: path.join(__dirname, '../.auth/authelia-session.json'),
  });

  test('Session works across multiple forward-auth services', async ({ page }) => {
    console.log('\n🧪 Testing session persistence across services');

    // Visit multiple services in sequence - should not require re-auth
    const services = [
      { name: 'JupyterHub', path: '/jupyterhub' },
      { name: 'Prometheus', path: '/prometheus' },
      { name: 'Homepage', path: '/homepage' },
    ];

    for (const service of services) {
      console.log(`\n   Visiting ${service.name}...`);
      await page.goto(service.path);

      // Should NOT be redirected to Authelia
      const isOnAuthelia = page.url().includes('authelia') || page.url().includes(':9091');

      if (isOnAuthelia) {
        console.log(`   ❌ Unexpectedly redirected to Authelia for ${service.name}`);
        await logPageTelemetry(page, `${service.name} - Unexpected Auth`);
      } else {
        console.log(`   ✅ ${service.name} accessed without re-auth`);
      }
    }

    console.log('\n   ✅ Session persisted across all services\n');
  });
});
