// Caddy Security RBAC & Forward Auth Test Suite
// Tests: Authentication and role-based access control via caddy-security plugin
// Architecture: Caddy Security Plugin (LDAP backend + JWT token auth)

const { test, expect } = require('@playwright/test');

// Helper function to perform Caddy Security login
async function performCaddySecurityLogin(page, username, password) {
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1000);

  const currentUrl = page.url();
  console.log(`Current URL: ${currentUrl}`);

  // Should be on Caddy Security login page
  const isOnLoginPage = currentUrl.includes('/auth') ||
                        await page.locator('input[name="username"]').isVisible().catch(() => false);

  if (!isOnLoginPage) {
    console.log('Not on login page, might already be authenticated');
    return;
  }

  await page.screenshot({
    path: `/results/caddy-security-login-${username}.png`,
    fullPage: true
  });

  // Fill in LDAP credentials
  const usernameField = page.locator('input[name="username"]').or(page.locator('input#username'));
  const passwordField = page.locator('input[name="password"]').or(page.locator('input#password'));

  await usernameField.fill(username);
  await passwordField.fill(password);

  await page.screenshot({
    path: `/results/caddy-security-login-filled-${username}.png`,
    fullPage: true
  });

  // Submit the form
  const submitButton = page.locator('button[type="submit"]').or(page.locator('input[type="submit"]'));
  await submitButton.click();

  // Wait for authentication to complete
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
}

test.describe('Caddy Security - Forward Auth & RBAC', () => {

  test('Admin user can access Prometheus (admin-only service)', async ({ page }) => {
    await page.goto('/prometheus/');

    // Should redirect to Caddy Security auth portal
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/prometheus')) {
      // Need to authenticate
      await performCaddySecurityLogin(page, 'admin', 'password');
    }

    await page.screenshot({
      path: '/results/prometheus-admin-caddy-auth.png',
      fullPage: true
    });

    // Should be on Prometheus
    const finalUrl = page.url();
    expect(finalUrl).toContain('/prometheus');

    // Verify content loaded (not blocked)
    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Unauthorized');
    expect(bodyText).not.toContain('Access Denied');
    expect(bodyText).not.toContain('403');

    console.log('✓ Admin can access Prometheus');
  });

  test('Admin user can access Alertmanager (admin-only service)', async ({ page }) => {
    await page.goto('/alertmanager/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/alertmanager')) {
      await performCaddySecurityLogin(page, 'admin', 'password');
    }

    await page.screenshot({
      path: '/results/alertmanager-admin-caddy-auth.png',
      fullPage: true
    });

    const finalUrl = page.url();
    expect(finalUrl).toContain('/alertmanager');

    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Unauthorized');
    expect(bodyText).not.toContain('Access Denied');

    console.log('✓ Admin can access Alertmanager');
  });

  test('Admin user can access Loki (admin-only service)', async ({ page }) => {
    await page.goto('/loki/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/loki')) {
      await performCaddySecurityLogin(page, 'admin', 'password');
    }

    await page.screenshot({
      path: '/results/loki-admin-caddy-auth.png',
      fullPage: true
    });

    const finalUrl = page.url();
    expect(finalUrl).toContain('/loki');

    console.log('✓ Admin can access Loki');
  });

  test('Admin user can access Duplicati (admin-only service)', async ({ page }) => {
    await page.goto('/duplicati/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/duplicati')) {
      await performCaddySecurityLogin(page, 'admin', 'password');
    }

    await page.screenshot({
      path: '/results/duplicati-admin-caddy-auth.png',
      fullPage: true
    });

    const finalUrl = page.url();
    expect(finalUrl).toContain('/duplicati');

    console.log('✓ Admin can access Duplicati');
  });

  test('Regular user can access Grafana (user-level service)', async ({ page, context }) => {
    // Clear cookies for fresh test
    await context.clearCookies();

    await page.goto('/grafana/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/grafana')) {
      await performCaddySecurityLogin(page, 'testuser', 'password');
    }

    await page.screenshot({
      path: '/results/grafana-testuser-caddy-auth.png',
      fullPage: true
    });

    // Should be on Grafana
    const finalUrl = page.url();
    expect(finalUrl).toContain('/grafana');

    console.log('✓ Regular user can access Grafana');
  });

  test('Regular user can access Mailpit (user-level service)', async ({ page }) => {
    await page.goto('/mailpit/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/mailpit')) {
      await performCaddySecurityLogin(page, 'testuser', 'password');
    }

    await page.screenshot({
      path: '/results/mailpit-testuser-caddy-auth.png',
      fullPage: true
    });

    const finalUrl = page.url();
    expect(finalUrl).toContain('/mailpit');

    console.log('✓ Regular user can access Mailpit');
  });

  test('Regular user CANNOT access Prometheus (admin-only)', async ({ page, context }) => {
    // Clear cookies for fresh test
    await context.clearCookies();

    await page.goto('/prometheus/');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    // Login as testuser
    await performCaddySecurityLogin(page, 'testuser', 'password');

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    await page.screenshot({
      path: '/results/prometheus-testuser-denied.png',
      fullPage: true
    });

    const finalUrl = page.url();
    const bodyText = await page.textContent('body');

    // Should be blocked - either redirected away or shown access denied
    const isBlocked = !finalUrl.includes('/prometheus') ||
                      bodyText.includes('Unauthorized') ||
                      bodyText.includes('Access Denied') ||
                      bodyText.includes('403') ||
                      bodyText.includes('Forbidden');

    expect(isBlocked).toBeTruthy();

    console.log('✓ Regular user correctly denied access to Prometheus');
  });

  test('Metrics endpoints bypass authentication', async ({ page, context }) => {
    // Clear all cookies
    await context.clearCookies();

    // Test Prometheus metrics endpoint (should bypass auth)
    const prometheusMetrics = await page.goto('/prometheus/metrics');
    expect(prometheusMetrics.status()).toBe(200);

    const prometheusContent = await page.textContent('body');
    expect(prometheusContent).toContain('# HELP');
    expect(prometheusContent).toContain('# TYPE');

    // Test Prometheus health endpoint
    const prometheusHealth = await page.goto('/prometheus/-/healthy');
    expect(prometheusHealth.status()).toBe(200);

    // Test Alertmanager metrics endpoint
    const alertmanagerMetrics = await page.goto('/alertmanager/metrics');
    expect(alertmanagerMetrics.status()).toBe(200);

    const alertmanagerContent = await page.textContent('body');
    expect(alertmanagerContent).toContain('# HELP');

    // Test Alertmanager health endpoint
    const alertmanagerHealth = await page.goto('/alertmanager/-/healthy');
    expect(alertmanagerHealth.status()).toBe(200);

    // Test Grafana health endpoint
    const grafanaHealth = await page.goto('/grafana/api/health');
    expect(grafanaHealth.status()).toBe(200);

    // Test Loki metrics endpoint
    const lokiMetrics = await page.goto('/loki/metrics');
    expect(lokiMetrics.status()).toBe(200);

    // Test Loki ready endpoint
    const lokiReady = await page.goto('/loki/ready');
    expect(lokiReady.status()).toBe(200);

    console.log('✓ All metrics/health endpoints accessible without authentication');
  });

  test('Unauthenticated user is redirected to Caddy Security login', async ({ page, context }) => {
    // Clear any existing cookies
    await context.clearCookies();

    // Try to access protected service
    await page.goto('/prometheus/', { waitUntil: 'networkidle' });

    await page.waitForTimeout(1000);
    const currentUrl = page.url();

    // Should be redirected to auth portal
    expect(currentUrl).toContain('stack.local');

    // Should see login form
    const hasLoginForm = await page.locator('input[name="username"]').or(page.locator('input#username')).isVisible({ timeout: 5000 }).catch(() => false);

    expect(hasLoginForm).toBeTruthy();

    await page.screenshot({
      path: '/results/caddy-security-redirect-unauthenticated.png',
      fullPage: true
    });

    console.log('✓ Unauthenticated user redirected to Caddy Security login');
  });

  test('JWT token persists across sessions', async ({ page }) => {
    // Login once
    await page.goto('/prometheus/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    const currentUrl = page.url();
    if (!currentUrl.includes('/prometheus')) {
      await performCaddySecurityLogin(page, 'admin', 'password');
    }

    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    // Now try accessing another protected resource - should NOT need to login again
    await page.goto('/alertmanager/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    const finalUrl = page.url();

    // Should go directly to Alertmanager without login redirect
    expect(finalUrl).toContain('/alertmanager');

    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('username');
    expect(bodyText).not.toContain('password');

    console.log('✓ JWT token correctly persists across sessions');
  });

  test('Homepage is accessible without authentication', async ({ page, context }) => {
    // Clear cookies
    await context.clearCookies();

    const response = await page.goto('/');
    expect(response.status()).toBe(200);

    await page.waitForLoadState('networkidle');

    const currentUrl = page.url();
    expect(currentUrl).toContain('stack.local');
    expect(currentUrl).not.toContain('/auth');

    await page.screenshot({
      path: '/results/homepage-no-auth.png',
      fullPage: true
    });

    console.log('✓ Homepage accessible without authentication');
  });
});
