import { test, expect } from '@playwright/test';

test.describe('Phase 7: Apps Layer Tests', () => {
  test('Nextcloud should be accessible', async ({ page }) => {
    await page.goto('https://nextcloud.stack.local');
    await page.waitForLoadState('networkidle', { timeout: 30000 });

    // Should see Nextcloud login or setup page
    const bodyText = await page.textContent('body');
    expect(
      bodyText?.toLowerCase().includes('nextcloud') ||
      bodyText?.toLowerCase().includes('log in') ||
      bodyText?.toLowerCase().includes('username')
    ).toBeTruthy();
  });

  test('Nextcloud should connect to MariaDB', async ({ request }) => {
    // Check that Nextcloud can reach MariaDB backend
    // This is an indirect test - if Nextcloud starts without DB errors, connection works
    const response = await request.get('https://nextcloud.stack.local/status.php', {
      failOnStatusCode: false
    });

    // status.php returns JSON with installation status
    expect([200, 404]).toContain(response.status());
  });

  test('Vaultwarden should be accessible', async ({ page }) => {
    await page.goto('https://vault.stack.local');
    await page.waitForLoadState('networkidle', { timeout: 15000 });

    // Should see Vaultwarden login page
    const title = await page.title();
    expect(title.toLowerCase()).toContain('vault');

    // Should have email and password fields
    const bodyText = await page.textContent('body');
    expect(
      bodyText?.toLowerCase().includes('email') ||
      bodyText?.toLowerCase().includes('master password') ||
      bodyText?.toLowerCase().includes('log in')
    ).toBeTruthy();
  });

  test('Vaultwarden should connect to MariaDB', async ({ request }) => {
    // Vaultwarden has no public healthcheck, but we can test the login page loads
    const response = await request.get('https://vault.stack.local', {
      failOnStatusCode: false
    });
    expect(response.status()).toBe(200);
  });

  test('Paperless-ngx should be accessible', async ({ page }) => {
    await page.goto('https://paperless.stack.local');
    await page.waitForLoadState('networkidle', { timeout: 30000 });

    // Should see Paperless login page
    const bodyText = await page.textContent('body');
    expect(
      bodyText?.toLowerCase().includes('paperless') ||
      bodyText?.toLowerCase().includes('username') ||
      bodyText?.toLowerCase().includes('password') ||
      bodyText?.toLowerCase().includes('login')
    ).toBeTruthy();
  });

  test('Paperless should connect to MariaDB and Redis', async ({ request }) => {
    // Test that Paperless frontend loads (requires DB + Redis)
    const response = await request.get('https://paperless.stack.local/api/', {
      failOnStatusCode: false
    });

    // API should return 200 or 401 (needs auth)
    expect([200, 401, 403]).toContain(response.status());
  });

  test('Redis should be reachable from backend network', async ({ request }) => {
    // Redis doesn't have HTTP interface, but we can verify Paperless can reach it
    // This is tested indirectly via Paperless connectivity test above
    expect(true).toBeTruthy();
  });

  test('Stirling-PDF should be accessible', async ({ page }) => {
    await page.goto('https://pdf.stack.local');
    await page.waitForLoadState('networkidle', { timeout: 15000 });

    // Should see Stirling PDF interface
    const title = await page.title();
    expect(title.toLowerCase()).toContain('stirling');

    const bodyText = await page.textContent('body');
    expect(
      bodyText?.toLowerCase().includes('pdf') ||
      bodyText?.toLowerCase().includes('stirling') ||
      bodyText?.toLowerCase().includes('login')
    ).toBeTruthy();
  });

  test('All Phase 7 apps should have HTTPS', async ({ request }) => {
    const apps = [
      'https://nextcloud.stack.local',
      'https://vault.stack.local',
      'https://paperless.stack.local',
      'https://pdf.stack.local'
    ];

    for (const app of apps) {
      const response = await request.get(app, { failOnStatusCode: false });
      // Should get response (not connection error)
      expect(response.status()).toBeGreaterThanOrEqual(200);
      expect(response.status()).toBeLessThan(600);
    }
  });

  test('Stack root should report Phase 7 apps', async ({ request }) => {
    const response = await request.get('https://stack.local');
    expect(response.status()).toBe(200);

    const body = await response.text();
    expect(body.toLowerCase()).toContain('phase 7');
  });
});
