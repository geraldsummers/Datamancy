// Phase 2 Loki Test - Observability Core
// Provenance: Playwright test patterns
// Tests: Loki API, log ingestion, query capabilities

const { test, expect } = require('@playwright/test');

test.describe('Phase 2 - Loki', () => {
  // Note: Loki's /ready and /metrics endpoints are internal-only and not routed via Traefik
  // These are tested via container healthchecks instead

  test('Loki label API is accessible', async ({ page }) => {
    // Query for labels (may be empty initially)
    const response = await page.goto('/loki/api/v1/labels');

    expect(response.status()).toBe(200);

    // Verify JSON response
    const content = await page.textContent('body');
    const json = JSON.parse(content);

    expect(json).toHaveProperty('status');
    expect(json.status).toBe('success');

    console.log('✓ Loki label API accessible');
  });

  test('Loki query API responds correctly', async ({ page }) => {
    // Try a simple query (may return empty results)
    const query = encodeURIComponent('{job="docker"}');
    const response = await page.goto(`/loki/api/v1/query_range?query=${query}&limit=10`);

    expect(response.status()).toBe(200);

    // Verify JSON response structure
    const content = await page.textContent('body');
    const json = JSON.parse(content);

    expect(json).toHaveProperty('status');
    expect(json.status).toBe('success');
    expect(json).toHaveProperty('data');

    console.log('✓ Loki query API accessible and responding');
  });

  test('Loki can be queried via LogQL', async ({ page }) => {
    // Test a more complex LogQL query
    const query = encodeURIComponent('{job=~".+"}');
    const start = Date.now() - 3600000; // 1 hour ago
    const end = Date.now();

    const response = await page.goto(
      `/loki/api/v1/query_range?query=${query}&start=${start}000000&end=${end}000000&limit=100`
    );

    expect(response.status()).toBe(200);

    // Parse response
    const content = await page.textContent('body');
    const json = JSON.parse(content);

    expect(json.status).toBe('success');
    expect(json.data).toHaveProperty('resultType');

    // Note: May have no data initially if Promtail hasn't shipped logs yet
    console.log('✓ Loki LogQL query interface working');
  });
});
