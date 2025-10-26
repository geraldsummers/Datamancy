// Stack HTTP Validation Test
// Validates all services are accessible via HTTP/API (without full browser navigation)

const { test, expect } = require('@playwright/test');

test.describe('Stack HTTP Validation', () => {

  test('All Phase 1-5 services respond correctly', async ({ request }) => {
    const endpoints = [
      { phase: 'Phase 1', name: 'Homepage', url: 'https://stack.local/', expectedStatus: [200] },
      { phase: 'Phase 1', name: 'Grafana', url: 'https://stack.local/grafana/', expectedStatus: [200, 302] },
      { phase: 'Phase 2', name: 'Prometheus', url: 'https://stack.local/prometheus/', expectedStatus: [200, 302] },
      { phase: 'Phase 2', name: 'Prometheus Health', url: 'https://stack.local/prometheus/-/healthy', expectedStatus: [200] },
      { phase: 'Phase 2', name: 'Alertmanager', url: 'https://stack.local/alertmanager/', expectedStatus: [200, 302] },
      { phase: 'Phase 2', name: 'Alertmanager Health', url: 'https://stack.local/alertmanager/-/healthy', expectedStatus: [200] },
      { phase: 'Phase 2', name: 'Loki Ready', url: 'https://stack.local/loki/ready', expectedStatus: [200, 404] },
      { phase: 'Phase 3', name: 'Authelia', url: 'https://stack.local/authelia/', expectedStatus: [200] },
      { phase: 'Phase 3', name: 'Authelia Health', url: 'https://stack.local/authelia/api/health', expectedStatus: [200] },
      { phase: 'Phase 3', name: 'Mailpit', url: 'https://stack.local/mailpit/', expectedStatus: [200, 302] },
      { phase: 'Phase 5', name: 'LocalAI', url: 'https://stack.local/localai/', expectedStatus: [200, 404] },
      { phase: 'Phase 5', name: 'LibreChat', url: 'https://stack.local/librechat/', expectedStatus: [200] }
    ];

    console.log('\n═══════════════════════════════════════════════════════════════');
    console.log('           Stack HTTP Validation Results');
    console.log('═══════════════════════════════════════════════════════════════\n');

    let passCount = 0;
    let failCount = 0;

    for (const endpoint of endpoints) {
      try {
        const response = await request.get(endpoint.url, {
          ignoreHTTPSErrors: true,
          maxRedirects: 0,
          timeout: 10000
        });

        const expected = endpoint.expectedStatus;
        const passed = expected.includes(response.status());

        if (passed) {
          console.log(`✓ [${endpoint.phase}] ${endpoint.name.padEnd(25)} HTTP ${response.status()}`);
          passCount++;
        } else {
          console.log(`✗ [${endpoint.phase}] ${endpoint.name.padEnd(25)} HTTP ${response.status()} (expected ${expected.join(' or ')})`);
          failCount++;
        }

        expect(expected).toContain(response.status());
      } catch (error) {
        console.log(`✗ [${endpoint.phase}] ${endpoint.name.padEnd(25)} ERROR: ${error.message}`);
        failCount++;
        throw error;
      }
    }

    console.log('\n───────────────────────────────────────────────────────────────');
    console.log(`  Total: ${passCount + failCount} | Passed: ${passCount} | Failed: ${failCount}`);
    console.log('═══════════════════════════════════════════════════════════════\n');
  });

  test('Grafana API health check', async ({ request }) => {
    const response = await request.get('https://stack.local/grafana/api/health', {
      ignoreHTTPSErrors: true
    });

    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.database).toBe('ok');

    console.log('✓ Grafana API: database healthy');
  });

  test('Prometheus metrics endpoint', async ({ request }) => {
    const response = await request.get('https://stack.local/prometheus/metrics', {
      ignoreHTTPSErrors: true
    });

    expect(response.status()).toBe(200);
    const body = await response.text();
    expect(body).toContain('prometheus_');

    console.log('✓ Prometheus: metrics endpoint functional');
  });

  test('Authelia health endpoint', async ({ request }) => {
    const response = await request.get('https://stack.local/authelia/api/health', {
      ignoreHTTPSErrors: true
    });

    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.status).toBe('OK');

    console.log('✓ Authelia: health check passed');
  });
});
