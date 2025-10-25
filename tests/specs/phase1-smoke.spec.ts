// Provenance: Playwright Test v1.45
// Purpose: Phase 1 smoke tests - validate core services via single front door
// Architecture: All tests use https://stack.local with path-based routing

import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const { oidcLogin, loadCredentials } = require('../helpers/oidc-login.js');

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
      // Complete OIDC login flow (Grafana is protected by OAuth2-Proxy)
      const creds = loadCredentials('agent');
      await oidcLogin(page, '/grafana/', {
        username: creds.username,
        password: creds.password,
        timeout: 30000
      });

      // Wait for Grafana to load
      await page.waitForSelector('[data-testid="data-testid Skip link"], [data-testid="data-testid Nav toolbar"], nav', { timeout: 15000 });

      // Verify we're on Grafana
      const pageContent = await page.content();
      expect(pageContent).toContain('Grafana');

      // Check for Grafana UI elements (navbar or welcome page)
      const hasNavbar = await page.locator('[data-testid="data-testid Nav toolbar"], nav, [class*="navbar"]').count();
      expect(hasNavbar).toBeGreaterThan(0);

      console.log('✓ Grafana UI loaded successfully after OIDC login');
      recordTestCompletion(serviceName, 'pass');
    } catch (err) {
      recordTestCompletion(serviceName, 'fail');
      throw err;
    }
  });

  test('Traefik Dashboard loads at /dashboard/', async ({ page }) => {
    const serviceName = 'traefik';

    try {
      // Complete OIDC login flow (Traefik Dashboard is protected by OAuth2-Proxy)
      const creds = loadCredentials('agent');
      await oidcLogin(page, '/dashboard/', {
        username: creds.username,
        password: creds.password,
        timeout: 30000
      });

      // Traefik dashboard should show
      await page.waitForSelector('text=/Dashboard|Traefik|HTTP Routers/i', { timeout: 10000 });

      // Verify Traefik branding or routers section
      const hasTraefikBrand = await page.locator('text=/Traefik|Routers|Services|Middlewares|HTTP/i').count();
      expect(hasTraefikBrand).toBeGreaterThan(0);

      console.log('✓ Traefik Dashboard loaded successfully after OIDC login');
      recordTestCompletion(serviceName, 'pass');
    } catch (err) {
      recordTestCompletion(serviceName, 'fail');
      throw err;
    }
  });

  test('Homepage (Landing) loads at /', async ({ page }) => {
    const serviceName = 'homepage';

    try {
      // Complete OIDC login flow (Homepage is protected by OAuth2-Proxy)
      const creds = loadCredentials('agent');
      await oidcLogin(page, '/', {
        username: creds.username,
        password: creds.password,
        timeout: 30000
      });

      // Homepage should load
      await page.waitForLoadState('networkidle', { timeout: 10000 });

      // Check for typical Homepage elements
      const bodyText = await page.textContent('body');
      expect(bodyText).toBeTruthy();
      expect(bodyText.length).toBeGreaterThan(100);

      console.log('✓ Homepage loaded successfully after OIDC login');
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
