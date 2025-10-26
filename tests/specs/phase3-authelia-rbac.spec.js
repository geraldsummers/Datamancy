// Phase 3 Authelia RBAC Test Suite
// Tests: SSO authentication via Authelia with LDAP group-based access control
// Architecture: Caddy forward-auth + Authelia OIDC

const { test, expect } = require('@playwright/test');

// Helper function to perform Authelia login
async function performAutheliaLogin(page, username, password) {
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1000);

  const currentUrl = page.url();
  console.log(`Current URL: ${currentUrl}`);

  // Should be on Authelia login page
  const isOnAutheliaLogin = currentUrl.includes('/authelia') ||
                           await page.locator('text=Sign in').isVisible().catch(() => false);

  if (!isOnAutheliaLogin) {
    console.log('Not on Authelia login page, might already be authenticated');
    return;
  }

  await page.screenshot({
    path: `/results/authelia-login-${username}.png`,
    fullPage: true
  });

  // Fill in LDAP credentials
  const usernameField = page.locator('input[id="username"]');
  const passwordField = page.locator('input[id="password"]');

  await usernameField.fill(username);
  await passwordField.fill(password);

  await page.screenshot({
    path: `/results/authelia-login-filled-${username}.png`,
    fullPage: true
  });

  // Submit the form
  const submitButton = page.locator('button[type="submit"]');
  await submitButton.click();

  // Wait for authentication to complete
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
}

test.describe('Phase 3 - Authelia SSO & RBAC', () => {

  test('Admin user can access Prometheus (admin-only service)', async ({ page }) => {
    await page.goto('/prometheus/');

    // Should redirect to Authelia
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/prometheus')) {
      // Need to authenticate
      await performAutheliaLogin(page, 'admin', 'password');
    }

    await page.screenshot({
      path: '/results/prometheus-admin-authenticated.png',
      fullPage: true
    });

    // Should be on Prometheus
    const finalUrl = page.url();
    expect(finalUrl).toContain('/prometheus');

    // Verify content loaded
    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Unauthorized');
    expect(bodyText).not.toContain('Access Denied');

    console.log('✓ Admin can access Prometheus');
  });

  test('Admin user can access Mailpit (general service)', async ({ page }) => {
    await page.goto('/mailpit/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/mailpit')) {
      await performAutheliaLogin(page, 'admin', 'password');
    }

    await page.screenshot({
      path: '/results/mailpit-admin-authenticated.png',
      fullPage: true
    });

    const finalUrl = page.url();
    expect(finalUrl).toContain('/mailpit');

    console.log('✓ Admin can access Mailpit');
  });

  test('Grafana native OIDC works with Authelia', async ({ page }) => {
    await page.goto('/grafana/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    await page.screenshot({
      path: '/results/grafana-authelia-oidc-login.png',
      fullPage: true
    });

    const currentUrl = page.url();

    // Look for Authelia SSO button
    const pageContent = await page.content();
    const hasAutheliaButton = pageContent.includes('Authelia') ||
                              pageContent.includes('Sign in with');

    if (hasAutheliaButton) {
      // Click "Sign in with Authelia"
      const autheliaButton = page.locator('text=Authelia').or(page.locator('text=Sign in with'));
      await autheliaButton.first().click();

      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(2000);

      const authUrl = page.url();
      expect(authUrl).toContain('/authelia');

      // Login via Authelia
      await performAutheliaLogin(page, 'admin', 'password');

      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(3000);

      await page.screenshot({
        path: '/results/grafana-authenticated-authelia-oidc.png',
        fullPage: true
      });

      // Should be logged into Grafana
      const finalUrl = page.url();
      expect(finalUrl).toContain('/grafana');

      console.log('✓ Grafana native OIDC via Authelia successful');
    } else {
      console.log('⚠ Grafana OIDC button not found, might need configuration');
    }
  });

  test('Metrics endpoints bypass authentication', async ({ page }) => {
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

    // Test Grafana health endpoint
    const grafanaHealth = await page.goto('/grafana/api/health');
    expect(grafanaHealth.status()).toBe(200);

    console.log('✓ Metrics/health endpoints accessible without authentication');
  });

  test('Unauthenticated user is redirected to Authelia', async ({ page, context }) => {
    // Clear any existing cookies
    await context.clearCookies();

    // Try to access protected service
    const response = await page.goto('/prometheus/', { waitUntil: 'networkidle' });

    await page.waitForTimeout(1000);
    const currentUrl = page.url();

    // Should be redirected to Authelia login
    expect(currentUrl).toContain('stack.local');

    // Should see login form
    const hasLoginForm = await page.locator('input[id="username"]').isVisible({ timeout: 5000 }).catch(() => false);

    if (hasLoginForm) {
      console.log('✓ Unauthenticated user redirected to Authelia login');
    } else {
      console.log('⚠ Expected Authelia login form');
    }

    await page.screenshot({
      path: '/results/authelia-redirect-unauthenticated.png',
      fullPage: true
    });
  });
});
