// Smoke Test - Happy Path Validation
// Provenance: Standard smoke test pattern for web applications
// Purpose: Verify complete stack works end-to-end (DNS → TLS → HTTP → DOM)

const { test, expect } = require('../fixtures/base');

test.describe('Smoke Test - Happy Path', () => {
  test('DNS resolution works for test.local domain', async ({ health }) => {
    await health.checkDNS('grafana.test.local');
  });

  test('Grafana UI loads and login form appears', async ({ page, health, correlationId }) => {
    console.log(`Running smoke test with correlation ID: ${correlationId}`);

    // Readiness gate: wait for HTTP 200
    await health.checkHTTP(page, 'https://grafana.test.local');

    // DOM gate: login form must be present
    await health.checkSelector(page, 'input[name="user"]');
    await health.checkSelector(page, 'input[name="password"]');
    await health.checkSelector(page, 'button[type="submit"]');

    // Verify page title
    const title = await page.title();
    expect(title).toContain('Grafana');

    console.log('✅ Smoke test passed: Grafana login form loaded successfully');
  });

  test('Grafana login works with credentials', async ({ page, health }) => {
    // Navigate and wait for form
    await health.checkHTTP(page, 'https://grafana.test.local');
    await health.checkSelector(page, 'input[name="user"]');

    // Fill in credentials (from docker-compose: admin/admin)
    await page.fill('input[name="user"]', 'admin');
    await page.fill('input[name="password"]', 'admin');

    // Submit form
    await page.click('button[type="submit"]');

    // Wait for successful login - should redirect to home or show skip button
    await page.waitForLoadState('networkidle');

    // Check we're logged in (either at /dashboards or showing skip password change)
    const url = page.url();
    const isLoggedIn = url.includes('/dashboards') ||
                       url.includes('/?orgId=') ||
                       await page.locator('text=Skip').isVisible().catch(() => false);

    expect(isLoggedIn).toBeTruthy();
    console.log(`✅ Grafana login successful: ${url}`);

    // Take screenshot for proof
    await page.screenshot({ path: '/results/grafana-logged-in.png', fullPage: true });
  });

  test('Browser can navigate between services via Traefik', async ({ page }) => {
    // Navigate to multiple pages to verify Traefik routing
    await page.goto('https://grafana.test.local');
    await page.waitForLoadState('networkidle');
    let title = await page.title();
    expect(title).toContain('Grafana');

    await page.goto('https://home.test.local');
    await page.waitForLoadState('networkidle');
    title = await page.title();
    expect(title).toMatch(/Datamancy|Homepage/);  // Homepage title varies

    console.log('✅ Multi-service navigation via Traefik passed');
  });
});
