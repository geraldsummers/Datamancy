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
  const stackAdminUser = process.env.STACK_ADMIN_USER;
  const stackAdminPassword = process.env.STACK_ADMIN_PASSWORD;
  const stackAdminEmail = process.env.STACK_ADMIN_EMAIL || (stackAdminUser ? `${stackAdminUser}@datamancy.net` : undefined);

  // Use Datamancy domain for auth setup
  // Even though we're inside Docker, we use the full domain so Caddy's
  // TLS certificates are valid (Caddy has certs for *.datamancy.net)
  // Tests will ignore HTTPS errors via ignoreHTTPSErrors: true
  const domain = process.env.DOMAIN || 'datamancy.net';
  const grafanaUrl = `https://grafana.${domain}`;

  console.log(`🔍 Debug: Domain = ${domain}`);
  console.log(`🔍 Debug: Grafana URL = ${grafanaUrl}`);

  // Create LDAP client
  const ldapClient = new LDAPClient({
    url: ldapUrl,
    adminDn: ldapAdminDn,
    adminPassword: ldapAdminPassword,
  });

  // Generate ephemeral test user (preferred)
  const username = LDAPClient.generateUsername('playwright');
  const password = LDAPClient.generatePassword();
  const email = `${username}@${domain}`;

  let testUser = {
    username,
    password,
    email,
    groups: ['users'], // Can add 'admins' if needed for certain tests
    managed: true,
  };

  console.log(`\n📋 Test User Details:`);
  console.log(`   Username: ${username}`);
  console.log(`   Email:    ${email}`);
  console.log(`   Groups:   ${testUser.groups.join(', ')}`);
  console.log();

  // Create user in LDAP (preferred) and verify credentials
  try {
    await ldapClient.createUser(testUser);
    const canAuth = await ldapClient.verifyUserCredentials(testUser.username, testUser.password);
    if (!canAuth) {
      throw new Error('LDAP user credential verification failed');
    }
    console.log('✅ LDAP user provisioned successfully\n');
  } catch (error) {
    if (stackAdminUser && stackAdminPassword) {
      console.warn('⚠️  Failed to provision or verify LDAP test user. Falling back to STACK_ADMIN_USER.');
      testUser = {
        username: stackAdminUser,
        password: stackAdminPassword,
        email: stackAdminEmail || `${stackAdminUser}@datamancy.net`,
        groups: ['admins', 'users'],
        managed: false,
      };
      console.log(`   Using fallback user: ${testUser.username}`);
    } else {
      console.error('❌ Failed to provision LDAP user:', error);
      throw error;
    }
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

  const browser = await launchChromiumWithRetry();
  const context = await browser.newContext({
    ignoreHTTPSErrors: true,  // Trust self-signed certificates
    bypassCSP: true,  // Bypass Content Security Policy
    acceptDownloads: false
  });
  const page = await context.newPage();

  try {
    // Navigate directly to Authelia login page with a service redirect
    // This ensures cookies are set for .datamancy.net domain (wildcard)
    // Using a dummy redirect URL that will work after login
    const autheliaUrl = `https://auth.${domain}/?rd=https://datamancy.net/`;
    console.log(`   🔍 Connecting to Authelia: ${autheliaUrl}`);

    // Navigate to Authelia
    const response = await page.goto(autheliaUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });

    console.log(`   Response status: ${response?.status()}`);
    console.log(`   Current URL: ${page.url()}`);

    // Wait a moment for any redirects to complete
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {
      console.log('   ⚠️  Network idle timeout, continuing...');
    });

    console.log(`   Final URL after redirects: ${page.url()}`);

    // Check if we're on Authelia login page
    const isOnAuthPage = page.url().toString().includes('auth.') ||
                         page.url().toString().includes('authelia') ||
                         page.url().toString().includes(':9091');

    if (!isOnAuthPage) {
      console.log('   ❌ ERROR: Not redirected to Authelia!');
      console.log(`   Current URL: ${page.url()}`);
      await page.screenshot({ path: 'test-results/no-authelia-redirect.png', fullPage: true });
      throw new Error('Forward auth redirect to Authelia did not occur');
    }

    console.log('   ✓ Redirected to Authelia login page');

    // Log page structure for debugging
    await logPageStructure(page, 'Authelia Login Page');

    // Fill in login form
    console.log('   📝 Filling login form...');

    // Wait for username field to be visible (Authelia can briefly sit on a loading shell)
    const usernameLocator = page.locator(
      '#username-textfield, input[name="username"], input[id="username"], input[autocomplete="username"], input[type="text"]'
    ).first();
    const usernameVisible = await usernameLocator
      .waitFor({ state: 'visible', timeout: 20000 })
      .then(() => true)
      .catch(() => false);
    if (!usernameVisible) {
      const bodyText = await page.textContent('body').catch(() => '') || '';
      if (/loading/i.test(bodyText)) {
        console.log('   ⚠️  Authelia still loading, reloading page once...');
        await page.reload({ waitUntil: 'domcontentloaded' }).catch(() => {});
      }
      await usernameLocator.waitFor({ state: 'visible', timeout: 20000 });
    }

    // Try multiple selector strategies for username
    const usernameField = page.locator('#username-textfield').or(
      page.locator('input[name="username"]')
    ).or(
      page.locator('input[id="username"]')
    ).or(
      page.locator('input[autocomplete="username"]')
    ).or(
      page.locator('input[type="text"]').first()
    ).first();

    // Try multiple selector strategies for password
    const passwordField = page.locator('#password-textfield').or(
      page.locator('input[name="password"]')
    ).or(
      page.locator('input[id="password"]')
    ).or(
      page.locator('input[autocomplete="current-password"]')
    ).or(
      page.locator('input[type="password"]').first()
    ).first();

    await usernameField.fill(testUser.username);
    console.log(`   ✓ Username entered: ${testUser.username}`);

    await passwordField.fill(testUser.password);
    console.log('   ✓ Password entered');

    // Find and click submit button
    const submitButton = page.locator('#sign-in-button').or(
      page.locator('button[type="submit"]')
    ).or(
      page.getByRole('button', { name: /sign in|login|submit/i })
    ).first();

    console.log('   🖱️  Clicking sign in button...');
    await submitButton.click();
    console.log('   ✓ Submit button clicked');

    // Wait for redirect back to Grafana
    console.log('   ⏳ Waiting for redirect back to Grafana...');
    await page.waitForURL((url) => {
      const urlStr = url.toString();
      return !urlStr.includes('auth.') && !urlStr.includes('authelia') && !urlStr.includes(':9091');
    }, {
      timeout: 30000,
    });

    console.log(`   ✓ Authenticated successfully`);
    console.log(`   Final URL: ${page.url()}`);

    // Verify we're no longer on auth page
    if (!page.url().includes('auth.')) {
      console.log('   ✅ Successfully authenticated and redirected!\n');
    } else {
      console.log(`   ⚠️  Still on auth page: ${page.url()}\n`);
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

async function launchChromiumWithRetry() {
  const launchArgs = {
    args: [
      '--ignore-certificate-errors',
      '--ignore-certificate-errors-spki-list',
      '--disable-features=IsolateOrigins,site-per-process',
      '--disable-gpu',
    ]
  };

  let lastError: unknown = null;
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      if (attempt > 1) {
        console.log(`   ⚠️  Chromium launch retry ${attempt}/2...`);
      }
      return await chromium.launch(launchArgs);
    } catch (error) {
      lastError = error;
      const message = String((error as Error)?.message || error);
      const recoverable =
        message.includes('Target page, context or browser has been closed') ||
        message.includes('SIGSEGV') ||
        message.includes('browserType.launch');
      if (!recoverable || attempt === 2) {
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
  }

  throw lastError;
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
