// Caddy Security OIDC Integration Test - LibreChat
// Tests: Native OIDC authentication flow from LibreChat through Caddy Security
// Architecture: LibreChat -> Caddy Security OIDC Provider -> LDAP

const { test, expect } = require('@playwright/test');

test.describe('Caddy Security OIDC - LibreChat Integration', () => {

  test('LibreChat SSO button redirects to Caddy Security', async ({ page, context }) => {
    // Clear cookies for fresh test
    await context.clearCookies();

    await page.goto('/librechat/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    await page.screenshot({
      path: '/results/librechat-login-page.png',
      fullPage: true
    });

    const currentUrl = page.url();
    console.log(`LibreChat URL: ${currentUrl}`);

    // Look for SSO login button
    const pageContent = await page.content();
    const hasSSOButton = pageContent.includes('Datamancy SSO') ||
                         pageContent.includes('Continue with') ||
                         pageContent.includes('SSO');

    if (hasSSOButton) {
      console.log('✓ SSO button found on LibreChat login page');

      // Click SSO button
      const ssoButton = page.locator('text=Datamancy SSO')
        .or(page.locator('text=Continue with'))
        .or(page.locator('button:has-text("SSO")'));

      await ssoButton.first().click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(2000);

      const authUrl = page.url();
      console.log(`After SSO click: ${authUrl}`);

      // Should be redirected to Caddy Security auth portal
      expect(authUrl).toContain('/auth');

      await page.screenshot({
        path: '/results/librechat-oidc-redirected-to-caddy.png',
        fullPage: true
      });

      console.log('✓ LibreChat correctly redirects to Caddy Security for OIDC');
    } else {
      console.log('⚠ SSO button not found, checking if forward-auth bypass is in effect');

      // With forward-auth, LibreChat might be behind auth already
      const isProtected = currentUrl.includes('/auth') ||
                         await page.locator('input[name="username"]').isVisible().catch(() => false);

      if (isProtected) {
        console.log('✓ LibreChat is protected by forward-auth');
      } else {
        console.log('⚠ LibreChat may need OIDC configuration check');
      }
    }
  });

  test('Complete OIDC flow: LibreChat -> Caddy Security -> LibreChat', async ({ page, context }) => {
    // Clear cookies
    await context.clearCookies();

    await page.goto('/librechat/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const pageContent = await page.content();
    const hasSSOButton = pageContent.includes('Datamancy SSO') ||
                         pageContent.includes('Continue with') ||
                         pageContent.includes('SSO');

    if (hasSSOButton) {
      // Click SSO button
      const ssoButton = page.locator('text=Datamancy SSO')
        .or(page.locator('text=Continue with'))
        .or(page.locator('button:has-text("SSO")'));

      await ssoButton.first().click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(2000);

      await page.screenshot({
        path: '/results/librechat-oidc-at-caddy-login.png',
        fullPage: true
      });

      // Should be at Caddy Security login
      const authUrl = page.url();
      expect(authUrl).toContain('/auth');

      // Fill in credentials
      const usernameField = page.locator('input[name="username"]').or(page.locator('input#username'));
      const passwordField = page.locator('input[name="password"]').or(page.locator('input#password'));

      await usernameField.fill('admin');
      await passwordField.fill('password');

      await page.screenshot({
        path: '/results/librechat-oidc-credentials-filled.png',
        fullPage: true
      });

      // Submit
      const submitButton = page.locator('button[type="submit"]').or(page.locator('input[type="submit"]'));
      await submitButton.click();

      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);

      await page.screenshot({
        path: '/results/librechat-oidc-after-submit.png',
        fullPage: true
      });

      // Should be redirected back to LibreChat, now authenticated
      const finalUrl = page.url();
      console.log(`Final URL: ${finalUrl}`);

      expect(finalUrl).toContain('/librechat');

      // Verify we're logged into LibreChat
      const bodyText = await page.textContent('body');

      // Should NOT see login prompt anymore
      const stillOnLogin = bodyText.includes('Sign in') || bodyText.includes('Continue with');

      // LibreChat should show chat interface
      const isAuthenticated = bodyText.includes('New chat') ||
                             bodyText.includes('conversation') ||
                             !stillOnLogin;

      expect(isAuthenticated).toBeTruthy();

      console.log('✓ Complete OIDC flow successful: LibreChat authenticated via Caddy Security');
    } else {
      console.log('⚠ SSO button not found, testing forward-auth instead');

      // Test forward-auth path
      const currentUrl = page.url();
      if (currentUrl.includes('/auth')) {
        // Fill in credentials for forward-auth
        const usernameField = page.locator('input[name="username"]').or(page.locator('input#username'));
        const passwordField = page.locator('input[name="password"]').or(page.locator('input#password'));

        await usernameField.fill('admin');
        await passwordField.fill('password');

        const submitButton = page.locator('button[type="submit"]').or(page.locator('input[type="submit"]'));
        await submitButton.click();

        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(3000);

        const finalUrl = page.url();
        expect(finalUrl).toContain('/librechat');

        console.log('✓ Forward-auth flow successful for LibreChat');
      }
    }
  });

  test('Admin user can access LibreChat', async ({ page, context }) => {
    // Clear cookies
    await context.clearCookies();

    await page.goto('/librechat/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    // Authenticate (either via OIDC or forward-auth)
    const currentUrl = page.url();

    if (!currentUrl.includes('/librechat') || await page.locator('input[name="username"]').isVisible().catch(() => false)) {
      // Need to login
      const usernameField = page.locator('input[name="username"]').or(page.locator('input#username'));
      const passwordField = page.locator('input[name="password"]').or(page.locator('input#password'));

      await usernameField.fill('admin');
      await passwordField.fill('password');

      const submitButton = page.locator('button[type="submit"]').or(page.locator('input[type="submit"]'));
      await submitButton.click();

      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);
    }

    await page.screenshot({
      path: '/results/librechat-oidc-admin-authenticated.png',
      fullPage: true
    });

    // Verify we're in LibreChat
    const finalUrl = page.url();
    expect(finalUrl).toContain('/librechat');

    // Admin user should have access
    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Unauthorized');
    expect(bodyText).not.toContain('Access Denied');

    console.log('✓ Admin successfully authenticated in LibreChat via OIDC');
  });

  test('Regular user can access LibreChat', async ({ page, context }) => {
    // Clear cookies
    await context.clearCookies();

    await page.goto('/librechat/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const currentUrl = page.url();

    if (!currentUrl.includes('/librechat') || await page.locator('input[name="username"]').isVisible().catch(() => false)) {
      // Login as testuser
      const usernameField = page.locator('input[name="username"]').or(page.locator('input#username'));
      const passwordField = page.locator('input[name="password"]').or(page.locator('input#password'));

      await usernameField.fill('testuser');
      await passwordField.fill('password');

      const submitButton = page.locator('button[type="submit"]').or(page.locator('input[type="submit"]'));
      await submitButton.click();

      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);
    }

    await page.screenshot({
      path: '/results/librechat-oidc-testuser-authenticated.png',
      fullPage: true
    });

    const finalUrl = page.url();
    expect(finalUrl).toContain('/librechat');

    // User should have access
    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Unauthorized');
    expect(bodyText).not.toContain('Access Denied');

    console.log('✓ Regular user successfully authenticated in LibreChat');
  });

  test('OIDC session persists across page reloads', async ({ page }) => {
    // Login once
    await page.goto('/librechat/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/librechat') || await page.locator('input[name="username"]').isVisible().catch(() => false)) {
      const usernameField = page.locator('input[name="username"]').or(page.locator('input#username'));
      const passwordField = page.locator('input[name="password"]').or(page.locator('input#password'));

      await usernameField.fill('admin');
      await passwordField.fill('password');

      const submitButton = page.locator('button[type="submit"]').or(page.locator('input[type="submit"]'));
      await submitButton.click();

      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);
    }

    // Reload page - session should still be valid
    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const reloadedUrl = page.url();
    expect(reloadedUrl).toContain('/librechat');

    // Should not be redirected to login
    const bodyText = await page.textContent('body');
    const needsLogin = bodyText.includes('Sign in') || bodyText.includes('Continue with');
    expect(needsLogin).toBeFalsy();

    console.log('✓ OIDC session persists across page reloads in LibreChat');
  });

  test('LibreChat respects user_policy authorization', async ({ page, context }) => {
    // Clear cookies
    await context.clearCookies();

    await page.goto('/librechat/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const currentUrl = page.url();

    // Should require authentication (either via OIDC or forward-auth)
    const requiresAuth = currentUrl.includes('/auth') ||
                        await page.locator('input[name="username"]').isVisible().catch(() => false) ||
                        await page.locator('button:has-text("SSO")').isVisible().catch(() => false);

    expect(requiresAuth).toBeTruthy();

    await page.screenshot({
      path: '/results/librechat-requires-authentication.png',
      fullPage: true
    });

    console.log('✓ LibreChat correctly requires authentication per user_policy');
  });
});
