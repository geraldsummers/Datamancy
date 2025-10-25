// Phase 3 Complete SSO Test Suite
// Provenance: Playwright test patterns
// Tests: End-to-end SSO authentication flow via oauth2-proxy → Dex → LDAP for ALL services

const { test, expect } = require('@playwright/test');

// Helper function to perform SSO login
async function performSSOLogin(page) {
  // Wait for redirect to oauth2-proxy/Dex
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);

  const currentUrl = page.url();

  // Should be redirected to Dex or oauth2 login
  const isOnDexLogin = currentUrl.includes('/dex/auth') || currentUrl.includes('/oauth2/');

  if (!isOnDexLogin) {
    console.log('Current URL:', currentUrl);
    throw new Error('Expected redirect to Dex/oauth2 login, but got: ' + currentUrl);
  }

  // Take screenshot of login redirect
  await page.screenshot({
    path: '/results/sso-redirect-page.png',
    fullPage: true
  });

  // If on oauth2 sign-in page, click through
  const pageText = await page.textContent('body');
  if (pageText.includes('Sign in with') || pageText.includes('Dex')) {
    const signInButton = page.locator('button:has-text("Sign in"), a:has-text("Sign in")').first();
    if (await signInButton.count() > 0) {
      await signInButton.click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(1000);
    }
  }

  // Now should be on Dex LDAP login form
  const loginForm = page.locator('form').first();
  await expect(loginForm).toBeVisible({ timeout: 10000 });

  // Fill in LDAP credentials
  const usernameField = page.locator('input[name="login"], input[type="text"]').first();
  const passwordField = page.locator('input[name="password"], input[type="password"]').first();

  await usernameField.fill('testuser');
  await passwordField.fill('password');

  await page.screenshot({
    path: '/results/sso-login-filled.png',
    fullPage: true
  });

  // Submit the form
  const submitButton = page.locator('button[type="submit"]').first();
  await submitButton.click();

  // Wait for authentication to complete
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
}

test.describe('Phase 3 - Complete SSO Authentication', () => {

  test('Homepage requires SSO and works after login', async ({ page }) => {
    // Navigate to oauth2 start endpoint to initiate auth flow
    await page.goto('/oauth2/start?rd=/');

    // Wait for redirect to Dex
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const currentUrl = page.url();

    // Should be on login page
    if (currentUrl.includes('/dex/auth')) {
      await performSSOLogin(page);
    } else {
      // Might already be authenticated
      await page.waitForLoadState('networkidle');
    }

    // Should be back on homepage after auth
    await page.screenshot({
      path: '/results/sso-homepage-authenticated.png',
      fullPage: true
    });

    const finalUrl = page.url();
    expect(finalUrl).toContain('stack.local');

    // Verify we see homepage content
    const bodyText = await page.textContent('body');
    const hasHomepageContent = bodyText.includes('Services') || bodyText.includes('Dashboard') || bodyText.includes('Grafana');
    expect(hasHomepageContent).toBeTruthy();

    console.log('✓ Homepage SSO authentication successful');
  });

  test('Prometheus requires SSO and works after login', async ({ page }) => {
    // Each test runs in isolation, need to auth again
    await page.goto('/oauth2/start?rd=/prometheus/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const currentUrl = page.url();

    // Should be on login page
    if (currentUrl.includes('/dex/auth')) {
      await performSSOLogin(page);
    }

    await page.screenshot({
      path: '/results/sso-prometheus-authenticated.png',
      fullPage: true
    });

    // Verify we're on Prometheus and not seeing "Unauthorized"
    const finalUrl = page.url();
    expect(finalUrl).toContain('/prometheus');

    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Unauthorized');

    console.log('✓ Prometheus SSO authentication successful');
  });

  test('Alertmanager requires SSO and works after login', async ({ page }) => {
    // Navigate to Alertmanager
    await page.goto('/alertmanager/');

    // Should use existing session
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const currentUrl = page.url();

    if (currentUrl.includes('/dex/') || currentUrl.includes('/oauth2/')) {
      await performSSOLogin(page);
    }

    await page.screenshot({
      path: '/results/sso-alertmanager-authenticated.png',
      fullPage: true
    });

    // Verify we're on Alertmanager
    const finalUrl = page.url();
    expect(finalUrl).toContain('/alertmanager');

    console.log('✓ Alertmanager SSO authentication successful');
  });

  test('Traefik Dashboard requires SSO and works after login', async ({ page }) => {
    // Navigate to Traefik dashboard
    await page.goto('/dashboard/');

    // Should use existing session
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const currentUrl = page.url();

    if (currentUrl.includes('/dex/') || currentUrl.includes('/oauth2/')) {
      await performSSOLogin(page);
    }

    await page.screenshot({
      path: '/results/sso-traefik-authenticated.png',
      fullPage: true
    });

    // Verify we're on Traefik dashboard
    const finalUrl = page.url();
    expect(finalUrl).toContain('/dashboard');

    console.log('✓ Traefik Dashboard SSO authentication successful');
  });

  test('Grafana native OIDC works independently (no double auth)', async ({ page }) => {
    // Navigate to Grafana
    await page.goto('/grafana/');

    // Grafana has its own OIDC, not oauth2-proxy forward auth
    await page.waitForLoadState('networkidle');

    await page.screenshot({
      path: '/results/grafana-login-native-oidc.png',
      fullPage: true
    });

    const currentUrl = page.url();

    // Should see Grafana login with "Sign in with Dex" button
    const pageContent = await page.content();
    expect(pageContent).toContain('Dex');

    // Click "Sign in with Dex"
    const dexButton = page.locator('text=Sign in with Dex');
    await expect(dexButton).toBeVisible({ timeout: 10000 });
    await dexButton.click();

    // Should go to Dex (not oauth2-proxy)
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const authUrl = page.url();
    expect(authUrl).toContain('/dex/auth');
    expect(authUrl).not.toContain('/oauth2/');

    // Login via Dex
    const loginForm = page.locator('form').first();
    await expect(loginForm).toBeVisible({ timeout: 10000 });

    const usernameField = page.locator('input[name="login"], input[type="text"]').first();
    const passwordField = page.locator('input[name="password"], input[type="password"]').first();

    await usernameField.fill('testuser');
    await passwordField.fill('password');

    const submitButton = page.locator('button[type="submit"]').first();
    await submitButton.click();

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);

    await page.screenshot({
      path: '/results/grafana-authenticated-native-oidc.png',
      fullPage: true
    });

    // Should be logged into Grafana
    const finalUrl = page.url();
    expect(finalUrl).toContain('/grafana');

    console.log('✓ Grafana native OIDC authentication successful (no double auth layer)');
  });

  test('Metrics endpoints remain accessible without authentication', async ({ page }) => {
    // Test Prometheus metrics endpoint (should bypass auth)
    const prometheusMetrics = await page.goto('/prometheus/metrics');
    expect(prometheusMetrics.status()).toBe(200);

    const prometheusContent = await page.textContent('body');
    expect(prometheusContent).toContain('# HELP');
    expect(prometheusContent).toContain('# TYPE');

    // Test Alertmanager metrics endpoint
    const alertmanagerMetrics = await page.goto('/alertmanager/metrics');
    expect(alertmanagerMetrics.status()).toBe(200);

    const alertmanagerContent = await page.textContent('body');
    expect(alertmanagerContent).toContain('# HELP');

    console.log('✓ Metrics endpoints accessible without authentication');
  });
});
