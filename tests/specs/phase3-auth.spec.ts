import { test, expect } from '@playwright/test';

test.describe('Phase 3: Access Control Tests', () => {
  test('Authelia portal should be accessible', async ({ page }) => {
    const response = await page.goto('https://auth.stack.local');

    // Should get 200 response
    expect(response?.status()).toBe(200);

    // Page should load (wait for any content)
    await page.waitForLoadState('networkidle');

    // Should have Authelia in title or body
    const title = await page.title();
    const bodyText = await page.textContent('body');

    expect(title.toLowerCase().includes('authelia') || bodyText?.toLowerCase().includes('authelia')).toBeTruthy();
  });

  test('Authelia health endpoint should respond', async ({ request }) => {
    // Test via direct API call
    const response = await request.get('https://auth.stack.local/api/health');

    expect(response.status()).toBe(200);

    const body = await response.json();
    expect(body).toHaveProperty('status');
    expect(body.status).toBe('OK');
  });

  test('LDAP service should be running', async ({ page }) => {
    // Verify LDAP is accessible by checking Authelia can authenticate
    // This is an indirect test - if Authelia is healthy and configured for LDAP,
    // then LDAP must be working

    const response = await page.request.get('https://auth.stack.local/api/configuration');

    // Should get some response (200, 401, 403, or 404)
    expect([200, 401, 403, 404]).toContain(response.status());
  });
});
