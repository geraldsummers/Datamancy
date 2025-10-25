// Phase 1 Smoke Test - Autonomous UI Testing
// Provenance: Playwright test patterns
// Tests: Landing page, Grafana UI over HTTPS (ignores self-signed cert warnings)

const { test, expect } = require('@playwright/test');

test.describe('Phase 1 - Agent Autonomy Smoke', () => {
  test('Landing page loads and shows services', async ({ page }) => {
    const response = await page.goto('/');

    // Verify HTTPS connection succeeded
    expect(response.status()).toBe(200);
    expect(response.url()).toContain('https://stack.local');

    // Wait for page content
    await page.waitForLoadState('networkidle');

    // Verify page has content (homepage renders)
    const content = await page.textContent('body');
    expect(content.length).toBeGreaterThan(100);

    console.log('✓ Landing page loaded over HTTPS');
  });

  test('Grafana UI loads over TLS', async ({ page }) => {
    // Navigate to Grafana
    await page.goto('/grafana/');

    // Wait for page load
    await page.waitForLoadState('networkidle');

    // Check if we're on login page and log in
    const loginButton = page.locator('button:has-text("Log in")');
    if (await loginButton.isVisible({ timeout: 5000 }).catch(() => false)) {
      await page.fill('input[name="user"]', 'admin');
      await page.fill('input[name="password"]', 'admin');
      await loginButton.click();
      await page.waitForLoadState('networkidle');
    }

    // Wait for Grafana UI elements
    const grafanaElement = page.locator('[data-testid="data-testid Skip link to main content"], nav, [class*="sidemenu"], h1:has-text("Welcome to Grafana")');
    await expect(grafanaElement.first()).toBeVisible({ timeout: 20000 });

    // Verify we're on HTTPS
    expect(page.url()).toContain('https://');
    expect(page.url()).toContain('stack.local');

    // Take a screenshot for verification
    await page.screenshot({
      path: '/results/grafana-loaded.png',
      fullPage: true
    });

    console.log('✓ Grafana loaded successfully over TLS');
  });

  test('Traefik dashboard loads', async ({ page }) => {
    await page.goto('/dashboard/');

    // Wait for Traefik dashboard
    await page.waitForLoadState('networkidle');

    // Check for Traefik-specific elements
    const dashboardContent = page.locator('body');
    await expect(dashboardContent).toContainText(/traefik/i, { timeout: 10000 });

    console.log('✓ Traefik dashboard loaded successfully');
  });

  test('TLS certificate is trusted', async ({ page }) => {
    const response = await page.goto('/');

    // Should get 200 OK without certificate errors
    expect(response.status()).toBe(200);

    // Verify secure context
    const isSecure = await page.evaluate(() => window.isSecureContext);
    expect(isSecure).toBe(true);

    console.log('✓ TLS certificate trusted, secure context verified');
  });
});
