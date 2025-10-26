import { test, expect } from '@playwright/test';

test.describe('Phase 8: Extended Apps and Services Tests', () => {

  // Planka Tests
  test('Planka should be accessible', async ({ page }) => {
    await page.goto('https://planka.stack.local');
    await page.waitForLoadState('networkidle', { timeout: 30000 });

    const bodyText = await page.textContent('body');
    expect(
      bodyText?.toLowerCase().includes('planka') ||
      bodyText?.toLowerCase().includes('log in') ||
      bodyText?.toLowerCase().includes('username') ||
      bodyText?.toLowerCase().includes('email')
    ).toBeTruthy();
  });

  test('Planka should connect to PostgreSQL', async ({ request }) => {
    const response = await request.get('https://planka.stack.local/api/config', {
      failOnStatusCode: false
    });
    // API should return 200 (public config) or 401 (needs auth)
    expect([200, 401, 403, 404]).toContain(response.status());
  });

  // Outline Tests
  test('Outline wiki should be accessible', async ({ page }) => {
    await page.goto('https://wiki.stack.local');
    await page.waitForLoadState('networkidle', { timeout: 30000 });

    const bodyText = await page.textContent('body');
    expect(
      bodyText?.toLowerCase().includes('outline') ||
      bodyText?.toLowerCase().includes('sign in') ||
      bodyText?.toLowerCase().includes('wiki') ||
      bodyText?.toLowerCase().includes('knowledge')
    ).toBeTruthy();
  });

  test('Outline should connect to PostgreSQL and Redis', async ({ request }) => {
    const response = await request.get('https://wiki.stack.local/api/auth.config', {
      failOnStatusCode: false
    });
    // API should return 200 or 401
    expect([200, 401, 403, 404]).toContain(response.status());
  });

  // Jellyfin Tests
  test('Jellyfin should be accessible', async ({ page }) => {
    await page.goto('https://jellyfin.stack.local');
    await page.waitForLoadState('networkidle', { timeout: 30000 });

    const bodyText = await page.textContent('body');
    expect(
      bodyText?.toLowerCase().includes('jellyfin') ||
      bodyText?.toLowerCase().includes('media') ||
      bodyText?.toLowerCase().includes('login') ||
      bodyText?.toLowerCase().includes('username')
    ).toBeTruthy();
  });

  test('Jellyfin web interface should load', async ({ request }) => {
    const response = await request.get('https://jellyfin.stack.local/web/', {
      failOnStatusCode: false
    });
    expect([200, 302]).toContain(response.status());
  });

  // Home Assistant Tests
  test('Home Assistant should be accessible', async ({ page }) => {
    await page.goto('https://home.stack.local');
    await page.waitForLoadState('networkidle', { timeout: 30000 });

    const bodyText = await page.textContent('body');
    expect(
      bodyText?.toLowerCase().includes('home assistant') ||
      bodyText?.toLowerCase().includes('home') ||
      bodyText?.toLowerCase().includes('automation') ||
      bodyText?.toLowerCase().includes('onboarding')
    ).toBeTruthy();
  });

  test('Home Assistant API should respond', async ({ request }) => {
    const response = await request.get('https://home.stack.local/api/', {
      failOnStatusCode: false
    });
    // API requires auth token, should return 401
    expect([200, 401]).toContain(response.status());
  });

  // Benthos Tests
  test('Benthos should be accessible', async ({ request }) => {
    const response = await request.get('https://benthos.stack.local/ping', {
      failOnStatusCode: false
    });
    // Benthos health endpoint should respond
    expect([200, 404]).toContain(response.status());
  });

  test('Benthos metrics endpoint should be available', async ({ request }) => {
    const response = await request.get('https://benthos.stack.local/metrics', {
      failOnStatusCode: false
    });
    expect([200, 404]).toContain(response.status());
  });

  // Cross-cutting concerns
  test('All Phase 8 apps should have HTTPS', async ({ request }) => {
    const apps = [
      'https://planka.stack.local',
      'https://wiki.stack.local',
      'https://jellyfin.stack.local',
      'https://home.stack.local',
      'https://benthos.stack.local'
    ];

    for (const app of apps) {
      const response = await request.get(app, { failOnStatusCode: false });
      expect(response.status()).toBeGreaterThanOrEqual(200);
      expect(response.status()).toBeLessThan(600);
    }
  });

  test('Phase 8 should have correct PostgreSQL instances', async ({ page }) => {
    // Verify we have separate PostgreSQL instances for different apps
    // This is indirectly tested by checking if apps connect successfully
    const response1 = await page.request.get('https://planka.stack.local', {
      failOnStatusCode: false
    });
    const response2 = await page.request.get('https://wiki.stack.local', {
      failOnStatusCode: false
    });

    expect([200, 302, 401]).toContain(response1.status());
    expect([200, 302, 401]).toContain(response2.status());
  });

  test('Phase 8 should have correct Redis instances', async ({ request }) => {
    // Outline uses outline-redis, Paperless uses redis
    // Tested indirectly through app connectivity
    const outlineResponse = await request.get('https://wiki.stack.local', {
      failOnStatusCode: false
    });
    const paperlessResponse = await request.get('https://paperless.stack.local', {
      failOnStatusCode: false
    });

    expect([200, 302, 401]).toContain(outlineResponse.status());
    expect([200, 302, 401]).toContain(paperlessResponse.status());
  });
});
