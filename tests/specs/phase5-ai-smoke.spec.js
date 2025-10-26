// Phase 5 - AI Services Smoke Test
// Validates LocalAI and LibreChat deployment

const { test, expect } = require('@playwright/test');

test.describe('Phase 5: AI Services', () => {
  test('LocalAI API is accessible', async ({ page }) => {
    await page.goto('https://stack.local/localai/', { waitUntil: 'networkidle' });

    // LocalAI web UI should load
    await expect(page).toHaveTitle(/LocalAI/i);

    // Should see navigation elements
    await expect(page.locator('text=Models')).toBeVisible({ timeout: 10000 });
  });

  test('LibreChat UI loads', async ({ page }) => {
    await page.goto('https://stack.local/librechat/', { waitUntil: 'networkidle' });

    // LibreChat should load (may redirect to login/SSO)
    const title = await page.title();
    console.log('LibreChat page title:', title);

    // Should see either LibreChat UI or Auth redirect
    const hasLibreChat = await page.locator('text=/LibreChat|Chat|Datamancy/i').count();
    const hasAuth = await page.locator('text=/Sign|Login|Authelia/i').count();

    expect(hasLibreChat + hasAuth).toBeGreaterThan(0);
  });

  test('LocalAI health endpoint responds', async ({ request }) => {
    const response = await request.get('https://stack.local/localai/readyz', {
      ignoreHTTPSErrors: true
    });

    expect(response.status()).toBe(200);
  });

  test('LibreChat health endpoint responds', async ({ request }) => {
    const response = await request.get('https://stack.local/librechat/api/health', {
      ignoreHTTPSErrors: true
    });

    expect([200, 302, 401]).toContain(response.status());
  });
});
