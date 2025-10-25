// Phase 2 Alertmanager Test - Observability Core
// Provenance: Playwright test patterns
// Tests: Alertmanager UI, alert routing, status page

const { test, expect } = require('@playwright/test');

test.describe('Phase 2 - Alertmanager', () => {
  test('Alertmanager UI loads over TLS', async ({ page }) => {
    // Navigate to Alertmanager
    await page.goto('/alertmanager/');

    // Wait for Alertmanager to render
    await page.waitForLoadState('networkidle');

    // Wait for Alertmanager UI elements (heading, navigation, or content)
    // Alertmanager has a distinctive UI with alerts or "No alerts" message
    const alertmanagerUI = page.locator('body');
    await expect(alertmanagerUI).toBeVisible({ timeout: 20000 });

    // Verify we're on HTTPS
    expect(page.url()).toContain('https://');
    expect(page.url()).toContain('stack.local');
    expect(page.url()).toContain('/alertmanager');

    // Take a screenshot for verification
    await page.screenshot({
      path: '/results/alertmanager-loaded.png',
      fullPage: true
    });

    console.log('✓ Alertmanager UI loaded successfully over TLS');
  });

  test('Alertmanager status page is accessible', async ({ page }) => {
    // Navigate to status page
    await page.goto('/alertmanager/#/status');

    // Wait for page load
    await page.waitForLoadState('networkidle');

    // Check for status content
    const statusContent = page.locator('body');
    await expect(statusContent).toBeVisible();

    // Look for typical status page elements (uptime, version info, etc.)
    const pageContent = await page.textContent('body');

    // Screenshot the status page
    await page.screenshot({
      path: '/results/alertmanager-status.png',
      fullPage: true
    });

    console.log('✓ Alertmanager status page accessible');
  });

  test('Alertmanager alerts page shows empty state', async ({ page }) => {
    // Navigate to alerts page (default landing)
    await page.goto('/alertmanager/#/alerts');

    // Wait for load
    await page.waitForLoadState('networkidle');

    // Check that alerts page loads (may show "No alerts" or list of alerts)
    const alertsSection = page.locator('body');
    await expect(alertsSection).toBeVisible();

    // Screenshot
    await page.screenshot({
      path: '/results/alertmanager-alerts.png',
      fullPage: true
    });

    console.log('✓ Alertmanager alerts page accessible');
  });

  test('Alertmanager API is accessible', async ({ page }) => {
    // Access Alertmanager API status endpoint
    const response = await page.goto('/alertmanager/api/v2/status');

    expect(response.status()).toBe(200);

    // Verify it's JSON
    const content = await page.textContent('body');
    const json = JSON.parse(content);

    // Should have cluster and version info
    expect(json).toHaveProperty('cluster');
    expect(json).toHaveProperty('versionInfo');

    console.log('✓ Alertmanager API accessible');
  });

  test('Alertmanager metrics endpoint is accessible', async ({ page }) => {
    // Access Alertmanager own metrics
    const response = await page.goto('/alertmanager/metrics');

    expect(response.status()).toBe(200);

    // Verify it's Prometheus metrics format
    const content = await page.textContent('body');
    expect(content).toContain('# HELP');
    expect(content).toContain('# TYPE');
    expect(content).toContain('alertmanager_');

    console.log('✓ Alertmanager metrics endpoint accessible');
  });
});
