// Provenance: Playwright helper for OIDC authentication flow
// Purpose: Reusable login function for Dex OIDC to avoid duplication
// Architecture: Handles Grafana/Homepage/Traefik -> Dex -> LDAP flow

const fs = require('fs');
const path = require('path');

/**
 * Complete OIDC login flow through Dex
 * @param {import('@playwright/test').Page} page - Playwright page object
 * @param {string} serviceUrl - Service URL (e.g., '/grafana', '/dashboard/')
 * @param {Object} options - Login options
 * @param {string} options.username - LDAP username (default: 'ai-observer')
 * @param {string} options.password - LDAP password (default: 'password')
 * @param {number} options.timeout - Timeout in ms (default: 30000)
 */
async function oidcLogin(page, serviceUrl, options = {}) {
  const {
    username = 'ai-observer',
    password = 'password',
    timeout = 30000
  } = options;

  console.log(`[OIDC Login] Starting login flow for ${serviceUrl} as ${username}`);

  // Navigate to the service
  await page.goto(serviceUrl, { waitUntil: 'networkidle', timeout });

  // Wait for page to settle
  await page.waitForTimeout(2000);

  // Check for Grafana's native "Sign in with Dex" button
  const grafanaDexButton = page.locator('a:has-text("Sign in with Dex")').first();
  const grafanaDexCount = await grafanaDexButton.count();

  if (grafanaDexCount > 0) {
    console.log('[OIDC Login] Found Grafana native "Sign in with Dex" button, clicking');
    await grafanaDexButton.click();
    await page.waitForLoadState('networkidle', { timeout });
    await page.waitForTimeout(2000);
  }

  // Check if we're already logged in (no redirect to Dex or OAuth2)
  const currentUrl = page.url();
  if (!currentUrl.includes('/dex/') && !currentUrl.includes('/oauth2/') && !currentUrl.includes('login')) {
    console.log('[OIDC Login] Already authenticated, skipping login');
    return;
  }

  // Check if redirected to OAuth2-Proxy sign-in
  if (page.url().includes('/oauth2/sign_in')) {
    console.log('[OIDC Login] On OAuth2-Proxy sign-in page, clicking "Sign in with Dex"');

    // Click the OAuth provider button
    const signInButton = page.locator('a[href*="/oauth2/start"]').first();
    await signInButton.click({ timeout: 10000 });
    await page.waitForLoadState('networkidle', { timeout });
  }

  // Should now be on Dex auth page or connector selection
  await page.waitForTimeout(2000);

  if (page.url().includes('/dex/auth')) {
    console.log('[OIDC Login] On Dex auth page');

    // Check if there's an LDAP connector button to click
    const ldapButton = page.locator('a:has-text("LDAP")').first();
    const ldapButtonCount = await ldapButton.count();

    if (ldapButtonCount > 0) {
      console.log('[OIDC Login] Clicking LDAP connector');
      await ldapButton.click();
      await page.waitForLoadState('networkidle', { timeout });
      await page.waitForTimeout(1000);
    }

    // Look for login form
    console.log('[OIDC Login] Filling login form');

    // Dex uses "login" as the name attribute for username
    const usernameField = page.locator('input[name="login"], input[type="text"], input[type="email"]').first();
    const passwordField = page.locator('input[name="password"], input[type="password"]').first();

    await usernameField.waitFor({ state: 'visible', timeout: 10000 });
    await usernameField.fill(username);
    await passwordField.fill(password);

    // Submit the form
    const submitButton = page.locator('button[type="submit"]').first();
    await submitButton.click();

    console.log('[OIDC Login] Submitted credentials, waiting for redirect');

    // Wait for redirect back to original service
    await page.waitForLoadState('networkidle', { timeout });
    await page.waitForTimeout(2000);

    // Verify we're back at the service
    const finalUrl = page.url();
    console.log(`[OIDC Login] Login complete, final URL: ${finalUrl}`);

    if (finalUrl.includes('/dex/') || finalUrl.includes('error')) {
      throw new Error(`Login failed, still on: ${finalUrl}`);
    }
  } else {
    console.log('[OIDC Login] Not on expected Dex page, may already be authenticated');
  }
}

/**
 * Load test credentials from file
 * @param {string} accountType - Account type (agent, testuser, admin)
 * @returns {Object} Credentials object with username and password
 */
function loadCredentials(accountType = 'agent') {
  const credentialsPath = path.join(__dirname, '..', 'test-credentials.json');

  if (!fs.existsSync(credentialsPath)) {
    console.warn('Credentials file not found, using defaults');
    return { username: 'ai-observer', password: 'password' };
  }

  const credentials = JSON.parse(fs.readFileSync(credentialsPath, 'utf8'));
  return credentials[accountType] || credentials.agent;
}

module.exports = {
  oidcLogin,
  loadCredentials
};
