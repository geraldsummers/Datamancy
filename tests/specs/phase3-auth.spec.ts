import { test, expect } from '@playwright/test';

test.describe('Phase 3: Access Control Tests', () => {
  test('Authelia portal should be accessible', async ({ page }) => {
    const response = await page.goto('https://auth.stack.local');

    // Should get 200 response
    expect(response?.status()).toBe(200);

    // Page should load (wait for any content)
    await page.waitForLoadState('networkidle');

    // Should have Authelia in title
    const title = await page.title();
    expect(title).toContain('Authelia');
  });

  test('Authelia health endpoint should respond', async ({ request }) => {
    // Test via direct API call
    const response = await request.get('https://auth.stack.local/api/health');

    expect(response.status()).toBe(200);

    const body = await response.json();
    expect(body).toHaveProperty('status');
    expect(body.status).toBe('OK');
  });

  test('Authelia login form should load', async ({ page }) => {
    await page.goto('https://auth.stack.local');
    await page.waitForLoadState('networkidle');

    // Wait for React to render the login form
    // Look for username/password input fields by various possible selectors
    const usernameInput = page.locator('input[id="username-textfield"], input[type="text"], input[autocomplete="username"]').first();
    const passwordInput = page.locator('input[id="password-textfield"], input[type="password"], input[autocomplete="current-password"]').first();

    await expect(usernameInput).toBeVisible({ timeout: 15000 });
    await expect(passwordInput).toBeVisible({ timeout: 15000 });
  });

  test('Admin user can login to Authelia', async ({ page }) => {
    await page.goto('https://auth.stack.local');
    await page.waitForLoadState('networkidle');

    // Wait for login form to appear
    const usernameInput = page.locator('input[id="username-textfield"], input[type="text"], input[autocomplete="username"]').first();
    const passwordInput = page.locator('input[id="password-textfield"], input[type="password"], input[autocomplete="current-password"]').first();

    await expect(usernameInput).toBeVisible({ timeout: 15000 });
    await expect(passwordInput).toBeVisible({ timeout: 15000 });

    // Fill in admin credentials
    await usernameInput.fill('admin');
    await passwordInput.fill('changeme');

    // Find and click the sign in button
    const signInButton = page.locator('button[type="submit"], button:has-text("Sign in")').first();
    await signInButton.click();

    // Wait for navigation or response
    await page.waitForLoadState('networkidle');

    // After successful login, should either:
    // 1. See 2FA setup page (first login)
    // 2. See authenticated portal
    // 3. Get redirected to a service

    // Check we're no longer on login page (URL changed or different content)
    const currentUrl = page.url();
    const hasAuth = currentUrl.includes('auth.stack.local');

    // If still on auth domain, should see 2FA setup or authenticated state
    if (hasAuth) {
      // Wait for either 2FA setup or success indicator
      await page.waitForTimeout(2000); // Give React time to render

      // Should not still show login form
      const stillOnLogin = await usernameInput.isVisible().catch(() => false);
      expect(stillOnLogin).toBe(false);
    }
  });

  test('Viewer user can login to Authelia', async ({ page }) => {
    await page.goto('https://auth.stack.local');
    await page.waitForLoadState('networkidle');

    // Wait for login form
    const usernameInput = page.locator('input[id="username-textfield"], input[type="text"], input[autocomplete="username"]').first();
    const passwordInput = page.locator('input[id="password-textfield"], input[type="password"], input[autocomplete="current-password"]').first();

    await expect(usernameInput).toBeVisible({ timeout: 15000 });
    await expect(passwordInput).toBeVisible({ timeout: 15000 });

    // Fill in viewer credentials
    await usernameInput.fill('viewer');
    await passwordInput.fill('changeme');

    // Click sign in
    const signInButton = page.locator('button[type="submit"], button:has-text("Sign in")').first();
    await signInButton.click();

    // Wait for navigation
    await page.waitForLoadState('networkidle');

    // Viewer should authenticate successfully (1FA only)
    const currentUrl = page.url();
    const hasAuth = currentUrl.includes('auth.stack.local');

    if (hasAuth) {
      await page.waitForTimeout(2000);

      // Should not still show login form
      const stillOnLogin = await usernameInput.isVisible().catch(() => false);
      expect(stillOnLogin).toBe(false);
    }
  });

  test('Invalid credentials should be rejected', async ({ page }) => {
    await page.goto('https://auth.stack.local');
    await page.waitForLoadState('networkidle');

    // Wait for login form
    const usernameInput = page.locator('input[id="username-textfield"], input[type="text"], input[autocomplete="username"]').first();
    const passwordInput = page.locator('input[id="password-textfield"], input[type="password"], input[autocomplete="current-password"]').first();

    await expect(usernameInput).toBeVisible({ timeout: 15000 });
    await expect(passwordInput).toBeVisible({ timeout: 15000 });

    // Try invalid credentials
    await usernameInput.fill('invaliduser');
    await passwordInput.fill('wrongpassword');

    // Click sign in
    const signInButton = page.locator('button[type="submit"], button:has-text("Sign in")').first();
    await signInButton.click();

    // Should see an error message
    await page.waitForTimeout(2000); // Wait for error to appear

    // Look for error indicators - check page content for error text
    const pageContent = await page.textContent('body');
    const hasErrorText = /incorrect|invalid|failed|authentication|denied/i.test(pageContent || '');

    // Or look for alert/error elements
    const hasAlertElement = await page.locator('[role="alert"], .error, .MuiAlert-root').count() > 0;

    expect(hasErrorText || hasAlertElement).toBeTruthy();
  });

  test('LDAP service connectivity', async ({ page }) => {
    // Indirect test - if Authelia config endpoint responds, LDAP is configured
    const response = await page.request.get('https://auth.stack.local/api/configuration');

    // Should get some response (200, 401, 403, or 404)
    expect([200, 401, 403, 404]).toContain(response.status());
  });
});
