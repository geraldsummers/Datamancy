// Phase 2 - SSO Authentication Flow Test
// Tests the complete OAuth2 flow through OAuth2-Proxy -> Dex -> LDAP

import { test, expect } from '@playwright/test';

test('Prometheus should redirect to Dex login and authenticate', async ({ page }) => {
  // Navigate to protected Prometheus endpoint
  await page.goto('https://stack.local/prometheus/');

  // Should be redirected to Dex login page
  await expect(page).toHaveURL(/.*dex.*/, { timeout: 10000 });

  // Fill in LDAP credentials
  await page.fill('input[name="login"]', 'admin');
  await page.fill('input[name="password"]', 'admin_password');

  // Submit login form
  await page.click('button[type="submit"]');

  // Should be redirected back to Prometheus
  await expect(page).toHaveURL(/.*prometheus.*/, { timeout: 15000 });

  // Verify we can see Prometheus UI
  await expect(page.locator('text=Prometheus')).toBeVisible({ timeout: 10000 });
});
