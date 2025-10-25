// Phase 1 HTTPS Validation - Browser-based
// Validates: HTTPS connections work, path routing, services respond

const { test, expect } = require('@playwright/test');

test.describe('Phase 1 - HTTPS Validation', () => {
  test('Homepage loads over HTTPS', async ({ page }) => {
    const response = await page.goto('/');
    expect(response.status()).toBe(200);
    expect(response.url()).toContain('https://stack.local');
    const content = await page.textContent('body');
    expect(content.length).toBeGreaterThan(100);
    console.log('✓ Homepage: HTTPS working');
  });

  test('Grafana loads over HTTPS', async ({ page }) => {
    const response = await page.goto('/grafana/');
    expect(response.status()).toBe(200);
    expect(response.url()).toContain('https://stack.local/grafana');
    console.log('✓ Grafana: HTTPS working');
  });

  test('Traefik dashboard loads over HTTPS', async ({ page }) => {
    const response = await page.goto('/dashboard/');
    expect(response.status()).toBe(200);
    expect(response.url()).toContain('https://stack.local/dashboard');
    console.log('✓ Traefik: HTTPS working');
  });
});
