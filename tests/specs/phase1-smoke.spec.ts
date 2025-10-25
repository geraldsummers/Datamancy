// Provenance: Playwright Test v1.45
// Purpose: Phase 1 smoke tests - validate core services via single front door
// Architecture: All tests use https://stack.local with path-based routing

import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

// Freshness Rule: Record test completion timestamps
const recordTestCompletion = (serviceName: string, status: 'pass' | 'fail') => {
  const timestamp = new Date().toISOString();
  const result = {
    service: serviceName,
    status: status,
    timestamp: timestamp,
    epochMs: Date.now()
  };

  const resultsDir = '/results/freshness';
  const resultsFile = path.join(resultsDir, `${serviceName}.json`);

  try {
    if (!fs.existsSync(resultsDir)) {
      fs.mkdirSync(resultsDir, { recursive: true });
    }
    fs.writeFileSync(resultsFile, JSON.stringify(result, null, 2));
    console.log(`✓ Freshness timestamp recorded for ${serviceName}: ${timestamp}`);
  } catch (err) {
    console.error(`Failed to record freshness timestamp: ${err}`);
  }
};

test.describe('Phase 1: Core Services Smoke Tests', () => {

  test('Grafana UI loads at /grafana', async ({ page }) => {
    const serviceName = 'grafana';

    try {
      await page.goto('/grafana/');

      // Wait for Grafana to load
      await page.waitForSelector('[data-testid="data-testid Skip link"]', { timeout: 10000 });

      // Verify we're on Grafana
      await expect(page).toHaveTitle(/Grafana/);

      // Check for login or home page elements
      const hasLogin = await page.locator('input[name="user"]').isVisible().catch(() => false);
      const hasNavbar = await page.locator('[data-testid="data-testid NavToolbar"]').isVisible().catch(() => false);

      expect(hasLogin || hasNavbar).toBeTruthy();

      recordTestCompletion(serviceName, 'pass');
    } catch (err) {
      recordTestCompletion(serviceName, 'fail');
      throw err;
    }
  });

  test('Traefik Dashboard loads at /dashboard/', async ({ page }) => {
    const serviceName = 'traefik';

    try {
      await page.goto('/dashboard/');

      // Traefik dashboard should show
      await page.waitForSelector('text=Dashboard', { timeout: 5000 });

      // Verify Traefik branding or routers section
      const hasTraefikBrand = await page.locator('text=/Traefik|Routers|Services|Middlewares/i').count();
      expect(hasTraefikBrand).toBeGreaterThan(0);

      recordTestCompletion(serviceName, 'pass');
    } catch (err) {
      recordTestCompletion(serviceName, 'fail');
      throw err;
    }
  });

  test('Homepage (Landing) loads at /', async ({ page }) => {
    const serviceName = 'homepage';

    try {
      await page.goto('/');

      // Homepage should load within 5s
      await page.waitForLoadState('networkidle', { timeout: 5000 });

      // Check for typical Homepage elements
      const bodyText = await page.textContent('body');
      expect(bodyText).toBeTruthy();

      recordTestCompletion(serviceName, 'pass');
    } catch (err) {
      recordTestCompletion(serviceName, 'fail');
      throw err;
    }
  });

  test('Browserless service is healthy (internal)', async ({ request }) => {
    const serviceName = 'browserless';

    try {
      // Browserless is an internal service - not exposed via Traefik
      // Test via Docker network directly at http://browserless:3000
      // Requires token authentication
      const response = await request.get('http://browserless:3000/?token=browserless-token-2024');

      // 200 OK means service is up and authenticated
      expect(response.status()).toBe(200);

      recordTestCompletion(serviceName, 'pass');
    } catch (err) {
      recordTestCompletion(serviceName, 'fail');
      throw err;
    }
  });
});
