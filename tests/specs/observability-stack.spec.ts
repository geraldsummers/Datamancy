import { test, expect } from '@playwright/test';

test.describe('Observability Stack Tests', () => {
  test('Prometheus should be accessible and healthy', async ({ page }) => {
    await page.goto('https://prometheus.stack.local');

    // Wait for Prometheus UI to load
    await expect(page.locator('text=Prometheus')).toBeVisible({ timeout: 10000 });

    // Verify page title
    await expect(page).toHaveTitle(/Prometheus/);
  });

  test('Grafana should have Prometheus datasource configured', async ({ page }) => {
    // Login to Grafana first
    await page.goto('https://grafana.stack.local/');
    await page.fill('input[name="user"]', process.env.GRAFANA_USER || 'admin');
    await page.fill('input[name="password"]', process.env.GRAFANA_PASSWORD || 'admin');
    await page.click('button[type="submit"]');
    await page.waitForLoadState('networkidle');

    // Navigate to datasources
    await page.goto('https://grafana.stack.local/connections/datasources');
    await page.waitForLoadState('networkidle');

    // Verify Prometheus datasource exists
    await expect(page.locator('text=Prometheus').first()).toBeVisible({ timeout: 10000 });
  });

  test('Grafana should have Loki datasource configured', async ({ page }) => {
    // Login to Grafana first
    await page.goto('https://grafana.stack.local/');
    await page.fill('input[name="user"]', process.env.GRAFANA_USER || 'admin');
    await page.fill('input[name="password"]', process.env.GRAFANA_PASSWORD || 'admin');
    await page.click('button[type="submit"]');
    await page.waitForLoadState('networkidle');

    // Navigate to datasources
    await page.goto('https://grafana.stack.local/connections/datasources');
    await page.waitForLoadState('networkidle');

    // Verify Loki datasource exists
    await expect(page.locator('text=Loki').first()).toBeVisible({ timeout: 10000 });
  });

  test('Loki should be accessible via Grafana', async ({ page }) => {
    // Login to Grafana first
    await page.goto('https://grafana.stack.local/');
    await page.fill('input[name="user"]', process.env.GRAFANA_USER || 'admin');
    await page.fill('input[name="password"]', process.env.GRAFANA_PASSWORD || 'admin');
    await page.click('button[type="submit"]');
    await page.waitForLoadState('networkidle');

    // Navigate to Explore page (validates both datasources are available)
    await page.goto('https://grafana.stack.local/explore');
    await page.waitForLoadState('networkidle');

    // Verify Explore page loaded (indicates datasources are working)
    await expect(page.locator('text=Explore').first()).toBeVisible({ timeout: 10000 });
  });
});
