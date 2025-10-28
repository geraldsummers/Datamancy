import { test, expect } from '@playwright/test';

test.describe('Nextcloud SSO Authentication', () => {
  test('should login to Nextcloud using Authelia SSO', async ({ page }) => {
    // Navigate to Nextcloud
    console.log('Navigating to Nextcloud...');
    await page.goto('https://nextcloud.stack.local', {
      waitUntil: 'networkidle',
      timeout: 30000
    });

    // Take screenshot of initial Nextcloud login page
    await page.screenshot({
      path: 'screenshots/nextcloud-01-login-page.png',
      fullPage: true
    });

    // Look for the OIDC login button (Authelia)
    console.log('Looking for Authelia SSO button...');
    const ssoButton = page.locator('a[href*="user_oidc"], button:has-text("Authelia"), a:has-text("Log in with Authelia")').first();

    // Wait for the button to be visible with timeout
    await ssoButton.waitFor({ state: 'visible', timeout: 10000 });
    await page.screenshot({
      path: 'screenshots/nextcloud-02-sso-button-visible.png',
      fullPage: true
    });

    // Click the SSO button
    console.log('Clicking Authelia SSO button...');
    await ssoButton.click();

    // Wait for redirect to Authelia
    console.log('Waiting for Authelia login page...');
    await page.waitForURL('**/auth.stack.local/**', { timeout: 10000 });
    await page.screenshot({
      path: 'screenshots/nextcloud-03-authelia-login.png',
      fullPage: true
    });

    // Enter credentials
    console.log('Entering credentials...');
    const usernameField = page.locator('input#username-textfield, input[placeholder*="Username"], input[type="text"]').first();
    const passwordField = page.locator('input#password-textfield, input[placeholder*="Password"], input[type="password"]').first();

    await usernameField.fill('user');
    await passwordField.fill('TestPassword123');
    await page.screenshot({
      path: 'screenshots/nextcloud-04-credentials-entered.png',
      fullPage: true
    });

    // Submit the login form
    console.log('Submitting login form...');
    const signInButton = page.locator('button:has-text("SIGN IN"), button:has-text("Sign in"), input[type="submit"]').first();
    await signInButton.click();

    // Wait for consent page and accept
    console.log('Waiting for consent page...');
    const consentAcceptButton = page.locator('button:has-text("ACCEPT"), button:has-text("Accept")').first();
    await consentAcceptButton.waitFor({ state: 'visible', timeout: 10000 });
    await page.screenshot({
      path: 'screenshots/nextcloud-04a-consent-page.png',
      fullPage: true
    });
    console.log('Accepting consent...');
    await consentAcceptButton.click();

    // Wait for redirect back to Nextcloud
    console.log('Waiting for redirect to Nextcloud...');
    await page.waitForURL('**/nextcloud.stack.local/**', { timeout: 15000 });

    // Wait for Nextcloud dashboard to load
    await page.waitForLoadState('networkidle', { timeout: 15000 });

    // Take screenshot of successful login
    await page.screenshot({
      path: 'screenshots/nextcloud-05-logged-in.png',
      fullPage: true
    });

    // Verify we're logged in by checking for user menu or dashboard elements
    console.log('Verifying successful login...');
    const userMenuOrDashboard = page.locator(
      '#user-menu, .app-menu, [data-id="files"], #app-navigation, .header-menu'
    ).first();

    await expect(userMenuOrDashboard).toBeVisible({ timeout: 10000 });

    console.log('Successfully logged into Nextcloud via Authelia SSO!');

    // Take final screenshot
    await page.screenshot({
      path: 'screenshots/nextcloud-06-dashboard-final.png',
      fullPage: true
    });
  });
});
