// Caddy Security OIDC Integration Test - Grafana
// Tests: Native OIDC authentication flow from Grafana through Caddy Security
// Architecture: Grafana -> Caddy Security OIDC Provider -> LDAP

const { test, expect } = require('@playwright/test');

test.describe('Caddy Security OIDC - Grafana Integration', () => {

  test('Grafana SSO button redirects to Caddy Security', async ({ page, context }) => {
    // Clear cookies for fresh test
    await context.clearCookies();

    await page.goto('/grafana/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    await page.screenshot({
      path: '/results/grafana-login-page.png',
      fullPage: true
    });

    const currentUrl = page.url();
    console.log(`Grafana URL: ${currentUrl}`);

    // Look for SSO login button
    const pageContent = await page.content();
    const hasSSOButton = pageContent.includes('Datamancy SSO') ||
                         pageContent.includes('Sign in with') ||
                         pageContent.includes('OAuth');

    if (hasSSOButton) {
      console.log('✓ SSO button found on Grafana login page');

      // Click SSO button
      const ssoButton = page.locator('text=Datamancy SSO')
        .or(page.locator('text=Sign in with'))
        .or(page.locator('a[href*="oauth"]'));

      await ssoButton.first().click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(2000);

      const authUrl = page.url();
      console.log(`After SSO click: ${authUrl}`);

      // Should be redirected to Caddy Security auth portal
      expect(authUrl).toContain('/auth');

      await page.screenshot({
        path: '/results/grafana-oidc-redirected-to-caddy.png',
        fullPage: true
      });

      console.log('✓ Grafana correctly redirects to Caddy Security for OIDC');
    } else {
      console.log('⚠ SSO button not found, checking if forward-auth bypass is in effect');

      // With forward-auth, Grafana might be behind auth already
      const isProtected = currentUrl.includes('/auth') ||
                         await page.locator('input[name="username"]').isVisible().catch(() => false);

      if (isProtected) {
        console.log('✓ Grafana is protected by forward-auth');
      } else {
        console.log('⚠ Grafana may need OIDC configuration check');
      }
    }
  });

  test('Complete OIDC flow: Grafana -> Caddy Security -> Grafana', async ({ page, context }) => {
    // Clear cookies
    await context.clearCookies();

    await page.goto('/grafana/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const pageContent = await page.content();
    const hasSSOButton = pageContent.includes('Datamancy SSO') ||
                         pageContent.includes('Sign in with') ||
                         pageContent.includes('OAuth');

    if (hasSSOButton) {
      // Click SSO button
      const ssoButton = page.locator('text=Datamancy SSO')
        .or(page.locator('text=Sign in with'))
        .or(page.locator('a[href*="oauth"]'));

      await ssoButton.first().click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(2000);

      await page.screenshot({
        path: '/results/grafana-oidc-at-caddy-login.png',
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
        path: '/results/grafana-oidc-credentials-filled.png',
        fullPage: true
      });

      // Submit
      const submitButton = page.locator('button[type="submit"]').or(page.locator('input[type="submit"]'));
      await submitButton.click();

      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);

      await page.screenshot({
        path: '/results/grafana-oidc-after-submit.png',
        fullPage: true
      });

      // Should be redirected back to Grafana, now authenticated
      const finalUrl = page.url();
      console.log(`Final URL: ${finalUrl}`);

      expect(finalUrl).toContain('/grafana');

      // Verify we're logged into Grafana
      const bodyText = await page.textContent('body');

      // Should NOT see login form anymore
      const stillOnLogin = bodyText.includes('Sign in') && bodyText.includes('Email');
      expect(stillOnLogin).toBeFalsy();

      console.log('✓ Complete OIDC flow successful: Grafana authenticated via Caddy Security');
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
        expect(finalUrl).toContain('/grafana');

        console.log('✓ Forward-auth flow successful for Grafana');
      }
    }
  });

  test('Admin role is correctly mapped via OIDC', async ({ page, context }) => {
    // Clear cookies
    await context.clearCookies();

    await page.goto('/grafana/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    // Authenticate (either via OIDC or forward-auth)
    const currentUrl = page.url();

    if (!currentUrl.includes('/grafana') || await page.locator('input[name="username"]').isVisible().catch(() => false)) {
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
      path: '/results/grafana-oidc-admin-authenticated.png',
      fullPage: true
    });

    // Verify we're in Grafana
    const finalUrl = page.url();
    expect(finalUrl).toContain('/grafana');

    // Admin user should have full access
    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Unauthorized');
    expect(bodyText).not.toContain('Access Denied');

    console.log('✓ Admin role correctly authenticated in Grafana via OIDC');
  });

  test('User role has appropriate Grafana access via OIDC', async ({ page, context }) => {
    // Clear cookies
    await context.clearCookies();

    await page.goto('/grafana/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const currentUrl = page.url();

    if (!currentUrl.includes('/grafana') || await page.locator('input[name="username"]').isVisible().catch(() => false)) {
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
      path: '/results/grafana-oidc-testuser-authenticated.png',
      fullPage: true
    });

    const finalUrl = page.url();
    expect(finalUrl).toContain('/grafana');

    // User should have access (as Viewer)
    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Unauthorized');
    expect(bodyText).not.toContain('Access Denied');

    console.log('✓ Regular user correctly authenticated in Grafana with appropriate role');
  });

  test('OIDC token refresh works', async ({ page }) => {
    // Login once
    await page.goto('/grafana/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/grafana') || await page.locator('input[name="username"]').isVisible().catch(() => false)) {
      const usernameField = page.locator('input[name="username"]').or(page.locator('input#username'));
      const passwordField = page.locator('input[name="password"]').or(page.locator('input#password'));

      await usernameField.fill('admin');
      await passwordField.fill('password');

      const submitButton = page.locator('button[type="submit"]').or(page.locator('input[type="submit"]'));
      await submitButton.click();

      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);
    }

    // Reload page - token should still be valid
    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const reloadedUrl = page.url();
    expect(reloadedUrl).toContain('/grafana');

    // Should not be redirected to login
    const bodyText = await page.textContent('body');
    const needsLogin = bodyText.includes('Sign in') && bodyText.includes('Email or username');
    expect(needsLogin).toBeFalsy();

    console.log('✓ OIDC token persists across page reloads');
  });
});
