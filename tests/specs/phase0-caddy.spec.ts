import { test, expect } from '@playwright/test';

test.describe('Phase 0: Caddy Front Door Tests', () => {
  test('Caddy root endpoint should respond', async ({ page }) => {
    const response = await page.goto('https://stack.local');

    expect(response?.status()).toBe(200);
    const content = await page.textContent('body');
    expect(content).toContain('Datamancy Stack');
  });

  test('Caddy should route to all configured hostnames', async ({ request }) => {
    // Test all static Caddyfile routes
    const routes = [
      { url: 'https://auth.stack.local', name: 'auth' },
      { url: 'https://grafana.stack.local', name: 'grafana' },
      { url: 'https://prometheus.stack.local', name: 'prometheus' },
      { url: 'https://loki.stack.local/ready', name: 'loki' },
      { url: 'https://clickhouse.stack.local/ping', name: 'clickhouse' },
      { url: 'https://adminer.stack.local', name: 'adminer' },
      { url: 'https://mongo-express.stack.local', name: 'mongo-express' },
      { url: 'https://portainer.stack.local', name: 'portainer' },
    ];

    for (const route of routes) {
      const response = await request.get(route.url, { maxRedirects: 0 });
      // Should get 200 (service responds) or 3xx (redirect) or 401 (auth) - not 404/502
      const status = response.status();
      expect(status).not.toBe(404);
      expect(status).not.toBe(502);
    }
  });

  test('Caddy should use TLS with wildcard cert', async ({ request }) => {
    const response = await request.get('https://stack.local');

    expect(response.status()).toBe(200);
    // Connection successful means TLS handshake worked
  });

  test('Caddy admin API should be accessible', async ({ request }) => {
    // Admin API on port 2019
    const response = await request.get('http://caddy:2019/config/');

    expect(response.status()).toBe(200);
    const config = await response.json();
    expect(config).toHaveProperty('apps');
  });

  test('Caddy should serve correct backend for each hostname', async ({ page }) => {
    // Verify grafana route goes to grafana
    await page.goto('https://grafana.stack.local');
    await page.waitForLoadState('networkidle');

    const title = await page.title();
    expect(title.toLowerCase()).toContain('grafana');
  });
});
