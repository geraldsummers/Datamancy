/**
 * Global setup - runs once before all tests
 *
 * Creates a single test user in LDAP that will be used across all tests
 * Authenticates with Authelia and saves auth state for reuse
 */

import { chromium, FullConfig } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { LDAPClient } from '../utils/ldap-client';

async function globalSetup(config: FullConfig) {
  console.log('\n╔═══════════════════════════════════════════════════════════════════════════╗');
  console.log('║  Playwright Global Setup - LDAP User Provisioning                        ║');
  console.log('╚═══════════════════════════════════════════════════════════════════════════╝\n');

  // Configuration from environment
  const ldapUrl = process.env.LDAP_URL || 'ldap://localhost:10389';
  const ldapAdminDn = process.env.LDAP_ADMIN_DN || 'cn=admin,dc=datamancy,dc=net';
  const ldapAdminPassword = process.env.LDAP_ADMIN_PASSWORD || 'admin';
  // Read from env var (set by Kotlin wrapper) or config, fallback to localhost
  const baseUrl = process.env.BASE_URL || (config as any).use?.baseURL || 'http://localhost';

  console.log(`🔍 Debug: BASE_URL env var = ${process.env.BASE_URL || 'NOT SET'}`);
  console.log(`🔍 Debug: DOMAIN env var = ${process.env.DOMAIN || 'NOT SET'}`);
  console.log(`🔍 Debug: baseURL from config = ${(config as any).use?.baseURL || 'NOT SET'}`);
  console.log(`🔍 Debug: Final baseURL = ${baseUrl}`);

  // Create LDAP client
  const ldapClient = new LDAPClient({
    url: ldapUrl,
    adminDn: ldapAdminDn,
    adminPassword: ldapAdminPassword,
  });

  // Generate ephemeral test user
  const username = LDAPClient.generateUsername('playwright');
  const password = LDAPClient.generatePassword();
  const email = `${username}@datamancy.test`;

  const testUser = {
    username,
    password,
    email,
    groups: ['users'], // Can add 'admins' if needed for certain tests
  };

  console.log(`\n📋 Test User Details:`);
  console.log(`   Username: ${username}`);
  console.log(`   Email:    ${email}`);
  console.log(`   Groups:   ${testUser.groups.join(', ')}`);
  console.log();

  // Create user in LDAP
  try {
    await ldapClient.createUser(testUser);
    console.log('✅ LDAP user provisioned successfully\n');
  } catch (error) {
    console.error('❌ Failed to provision LDAP user:', error);
    throw error;
  }

  // Save credentials to file for tests to use
  const authDir = path.join(__dirname, '../.auth');
  if (!fs.existsSync(authDir)) {
    fs.mkdirSync(authDir, { recursive: true });
  }

  const credsPath = path.join(authDir, 'test-user.json');
  fs.writeFileSync(
    credsPath,
    JSON.stringify(testUser, null, 2)
  );
  console.log(`💾 Credentials saved to: ${credsPath}\n`);

  // Perform initial authentication with Authelia to get session
  console.log('🔐 Authenticating with Authelia...\n');

  const browser = await chromium.launch({
    args: ['--ignore-certificate-errors']
  });
  const context = await browser.newContext({
    ignoreHTTPSErrors: true,  // Trust self-signed certificates
    bypassCSP: true  // Bypass Content Security Policy
  });
  const page = await context.newPage();

  try {
    // Navigate to a protected page to trigger auth
    // Use subdomain route (grafana.domain) but connect to Caddy internally to avoid NAT hairpin
    const domain = process.env.DOMAIN || 'datamancy.net';
    // Use HTTP for internal Docker network communication - Caddy serves HTTP internally
    // External HTTPS is handled by Caddy for public access
    const protocol = 'http';
    const caddyBase = baseUrl.startsWith('http') ? baseUrl : `${protocol}://${baseUrl}`;
    const grafanaUrl = `${caddyBase}/grafana`;

    console.log(`   🔍 Grafana URL: ${grafanaUrl}`);

    // For internal Caddy routing, we need to set the Host header to match the subdomain
    await page.route('**/*', route => {
      const headers = route.request().headers();
      if (baseUrl.includes('caddy') && domain) {
        headers['Host'] = `grafana.${domain}`;
      }
      route.continue({ headers });
    });

    await page.goto(grafanaUrl, { waitUntil: 'networkidle', timeout: 30000 });

    console.log(`   Current URL: ${page.url()}`);

    // Check if redirected to Authelia login
    if (page.url().toString().includes('authelia') || page.url().toString().includes(':9091')) {
      console.log('   ✓ Redirected to Authelia login page');

      // Log page structure for debugging
      await logPageStructure(page, 'Authelia Login Page');

      // Fill in login form
      console.log('   📝 Filling login form...');

      // Try multiple selector strategies
      const usernameField = page.locator('input[name="username"]').or(
        page.locator('input[id="username"]')
      ).or(
        page.locator('input[type="text"]').first()
      );

      const passwordField = page.locator('input[name="password"]').or(
        page.locator('input[id="password"]')
      ).or(
        page.locator('input[type="password"]').first()
      );

      await usernameField.fill(username);
      console.log(`   ✓ Username entered: ${username}`);

      await passwordField.fill(password);
      console.log('   ✓ Password entered');

      // Find and click submit button
      const submitButton = page.locator('button[type="submit"]').or(
        page.getByRole('button', { name: /sign in|login|submit/i })
      ).first();

      await submitButton.click();
      console.log('   ✓ Submit button clicked');

      // Wait for redirect back to original page
      await page.waitForURL((url) => !url.toString().includes('authelia') && !url.toString().includes(':9091'), {
        timeout: 15000,
      });

      console.log(`   ✓ Authenticated successfully`);
      console.log(`   Final URL: ${page.url()}\n`);
    } else {
      console.log('   ⚠️  No Authelia redirect detected - may already be authenticated\n');
    }

    // Save authentication state
    const storageStatePath = path.join(authDir, 'authelia-session.json');
    await context.storageState({ path: storageStatePath });
    console.log(`💾 Auth state saved to: ${storageStatePath}\n`);

  } catch (error) {
    console.error('❌ Authentication failed:', error);
    await page.screenshot({ path: 'test-results/auth-failure.png', fullPage: true });
    throw error;
  } finally {
    await browser.close();
  }

  console.log('✅ Global setup complete!\n');
}

/**
 * Log page structure for debugging selector issues
 */
async function logPageStructure(page: any, title: string) {
  console.log(`\n   📊 ${title} Structure:`);
  console.log('   ' + '─'.repeat(70));

  try {
    // Get all form elements
    const inputs = await page.locator('input').all();
    console.log(`   Form Inputs (${inputs.length}):`);
    for (const input of inputs) {
      const type = await input.getAttribute('type').catch(() => 'unknown');
      const name = await input.getAttribute('name').catch(() => '');
      const id = await input.getAttribute('id').catch(() => '');
      const placeholder = await input.getAttribute('placeholder').catch(() => '');
      console.log(`     - type="${type}" name="${name}" id="${id}" placeholder="${placeholder}"`);
    }

    // Get all buttons
    const buttons = await page.locator('button').all();
    console.log(`   Buttons (${buttons.length}):`);
    for (const button of buttons) {
      const type = await button.getAttribute('type').catch(() => '');
      const text = await button.textContent().catch(() => '');
      console.log(`     - type="${type}" text="${text.trim()}"`);
    }

    // Get page title
    const pageTitle = await page.title();
    console.log(`   Page Title: "${pageTitle}"`);

  } catch (error) {
    console.warn('   ⚠️  Could not extract full page structure:', error);
  }

  console.log('   ' + '─'.repeat(70) + '\n');
}

export default globalSetup;
