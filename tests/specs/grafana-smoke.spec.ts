import { test, expect } from '@playwright/test';

test.describe('Grafana Smoke Test', () => {
  test('should load Grafana login page', async ({ page }) => {
    await page.goto('/');

    // Wait for login form
    await expect(page.locator('input[name="user"]')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('input[name="password"]')).toBeVisible();

    // Verify page title
    await expect(page).toHaveTitle(/Grafana/);
  });

  test('should login successfully', async ({ page }) => {
    await page.goto('/');

    // Fill login form
    await page.fill('input[name="user"]', process.env.GRAFANA_USER || 'admin');
    await page.fill('input[name="password"]', process.env.GRAFANA_PASSWORD || 'admin');
    await page.click('button[type="submit"]');

    // Wait for navigation away from login
    await page.waitForLoadState('networkidle');

    // Verify we're logged in (login form no longer visible)
    await expect(page.locator('input[name="user"]')).not.toBeVisible();
  });

  test('should access dashboards', async ({ page }) => {
    // Login first
    await page.goto('/');
    await page.fill('input[name="user"]', process.env.GRAFANA_USER || 'admin');
    await page.fill('input[name="password"]', process.env.GRAFANA_PASSWORD || 'admin');
    await page.click('button[type="submit"]');
    await page.waitForLoadState('networkidle');

    // Navigate to dashboards
    await page.goto('/dashboards');
    await page.waitForLoadState('networkidle');

    // Should see dashboards page
    await expect(page.locator('text=Dashboards').first()).toBeVisible({ timeout: 10000 });
  });
});
