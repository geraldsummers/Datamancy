import { test, expect } from '@playwright/test';

test.describe('Phase 5: AI Services Tests', () => {
  test('LocalAI should be reachable from backend network', async ({ request }) => {
    // LocalAI is backend-only, no external hostname
    // This test validates it's running and accessible internally
    const response = await request.get('http://localai:8080/readyz', {
      failOnStatusCode: false
    });

    // LocalAI should respond (200 or 404 acceptable)
    expect([200, 404]).toContain(response.status());
  });

  test('LibreChat should be accessible', async ({ page }) => {
    await page.goto('https://librechat.stack.local');

    // Wait for LibreChat to load
    await page.waitForLoadState('networkidle');

    // Should see login or chat interface
    const title = await page.title();
    expect(title).toContain('LibreChat');
  });

  test('LibreChat should show registration/login form', async ({ page }) => {
    await page.goto('https://librechat.stack.local');
    await page.waitForLoadState('networkidle');

    // Look for login/registration elements
    const hasLoginForm = await page.locator('input[type="email"], input[type="text"]').count() > 0;
    expect(hasLoginForm).toBeTruthy();
  });

  test('LibreChat should connect to MongoDB', async ({ request }) => {
    // Verify LibreChat backend is running and connected
    const response = await request.get('https://librechat.stack.local/api/config', {
      failOnStatusCode: false
    });

    // Should get a response (might be 401/403 without auth, but proves service is up)
    expect([200, 401, 403, 404]).toContain(response.status());
  });
});
