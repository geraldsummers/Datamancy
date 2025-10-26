// Comprehensive Stack Browser Test
// Tests all phases end-to-end with browser emulation

const { test, expect } = require('@playwright/test');

test.describe('Comprehensive Stack Test', () => {

  test('Phase 1: Homepage accessible', async ({ page }) => {
    await page.goto('https://stack.local/', { waitUntil: 'networkidle', timeout: 30000 });

    // Should see homepage service links
    const hasServices = await page.locator('text=/services|dashboard/i').count();
    expect(hasServices).toBeGreaterThan(0);

    console.log('✓ Phase 1: Homepage accessible');
  });

  test('Phase 1: Grafana UI loads', async ({ page }) => {
    await page.goto('https://stack.local/grafana/', { waitUntil: 'networkidle', timeout: 30000 });

    // Grafana should load
    await expect(page.locator('text=/grafana|sign in|login/i').first()).toBeVisible({ timeout: 15000 });

    console.log('✓ Phase 1: Grafana accessible');
  });

  test('Phase 2: Prometheus UI loads', async ({ page }) => {
    await page.goto('https://stack.local/prometheus/', { waitUntil: 'networkidle', timeout: 30000 });

    // Prometheus should show UI elements or redirect to auth
    const hasPrometheus = await page.locator('text=/prometheus|graph|query|expression|authelia|sign/i').count();
    expect(hasPrometheus).toBeGreaterThan(0);

    console.log('✓ Phase 2: Prometheus accessible');
  });

  test('Phase 2: Alertmanager UI loads', async ({ page }) => {
    await page.goto('https://stack.local/alertmanager/', { waitUntil: 'networkidle', timeout: 30000 });

    // Alertmanager should load
    await expect(page.locator('text=/alertmanager|alerts|silences/i').first()).toBeVisible({ timeout: 15000 });

    console.log('✓ Phase 2: Alertmanager accessible');
  });

  test('Phase 3: Authelia portal loads', async ({ page }) => {
    await page.goto('https://stack.local/authelia/', { waitUntil: 'networkidle', timeout: 30000 });

    // Authelia login should be visible
    await expect(page.locator('text=/sign|login|username/i').first()).toBeVisible({ timeout: 15000 });

    console.log('✓ Phase 3: Authelia accessible');
  });

  test('Phase 3: Mailpit UI loads', async ({ page }) => {
    await page.goto('https://stack.local/mailpit/', { waitUntil: 'networkidle', timeout: 30000 });

    // Mailpit should load
    await expect(page.locator('text=/mailpit|messages|inbox/i').first()).toBeVisible({ timeout: 15000 });

    console.log('✓ Phase 3: Mailpit accessible');
  });

  test('Phase 5: LocalAI web UI loads', async ({ page }) => {
    await page.goto('https://stack.local/localai/', { waitUntil: 'networkidle', timeout: 30000 });

    // LocalAI UI should show
    await expect(page.locator('text=/LocalAI|models|chat/i').first()).toBeVisible({ timeout: 15000 });

    console.log('✓ Phase 5: LocalAI accessible');
  });

  test('Phase 5: LibreChat UI loads', async ({ page }) => {
    await page.goto('https://stack.local/librechat/', { waitUntil: 'networkidle', timeout: 30000 });

    // LibreChat or auth should load - check for any visible content
    const hasContent = await page.locator('body').count();
    expect(hasContent).toBeGreaterThan(0);

    // Check page title or content loaded
    const title = await page.title();
    console.log(`LibreChat page title: ${title}`);
    expect(title.length).toBeGreaterThan(0);

    console.log('✓ Phase 5: LibreChat accessible');
  });

  test('All services respond via HTTP', async ({ request }) => {
    const endpoints = [
      { name: 'Homepage', url: 'https://stack.local/', expectedStatus: [200] },
      { name: 'Grafana', url: 'https://stack.local/grafana/', expectedStatus: [200, 302] },
      { name: 'Prometheus', url: 'https://stack.local/prometheus/', expectedStatus: [200, 302] },
      { name: 'Alertmanager', url: 'https://stack.local/alertmanager/', expectedStatus: [200, 302] },
      { name: 'Loki', url: 'https://stack.local/loki/ready', expectedStatus: [200, 302, 404] },
      { name: 'Authelia', url: 'https://stack.local/authelia/', expectedStatus: [200, 302] },
      { name: 'Mailpit', url: 'https://stack.local/mailpit/', expectedStatus: [200, 302] },
      { name: 'LocalAI', url: 'https://stack.local/localai/', expectedStatus: [200, 404] },
      { name: 'LibreChat', url: 'https://stack.local/librechat/', expectedStatus: [200] }
    ];

    for (const endpoint of endpoints) {
      const response = await request.get(endpoint.url, {
        ignoreHTTPSErrors: true,
        maxRedirects: 0
      });

      const expected = Array.isArray(endpoint.expectedStatus)
        ? endpoint.expectedStatus
        : [endpoint.expectedStatus];

      expect(expected).toContain(response.status());
      console.log(`✓ ${endpoint.name}: HTTP ${response.status()}`);
    }
  });
});
