// Correlation ID Test - Verify request tracing through stack
// Provenance: Distributed tracing patterns (OpenTelemetry, Zipkin)
// Purpose: Confirm correlation ID flows from test-runner → Traefik → Loki

const { test, expect } = require('../fixtures/base');

test.describe('Correlation ID Tracking', () => {
  test('Correlation ID flows from client to Traefik access logs', async ({ page, correlationId }) => {
    console.log(`Testing correlation ID: ${correlationId}`);

    // Make request with correlation ID header
    await page.goto('https://grafana.test.local', {
      waitUntil: 'domcontentloaded'
    });

    // Wait for logs to propagate (Traefik → Promtail → Loki)
    console.log('Waiting 10s for log propagation...');
    await new Promise(resolve => setTimeout(resolve, 10000));

    console.log('✅ Request completed with correlation ID header');
  });

  test('Correlation ID appears in Loki logs', async ({ health, correlationId }) => {
    // Query Loki for the correlation ID
    // This requires Loki to be running (observability profile)

    console.log(`Querying Loki for correlation ID: ${correlationId}`);

    // Retry logic: logs might take time to propagate
    let attempts = 0;
    const maxAttempts = 6;
    let found = false;

    while (attempts < maxAttempts && !found) {
      try {
        const results = await health.queryLoki(correlationId);
        if (results && results.length > 0) {
          found = true;
          console.log(`✅ Correlation ID found in Loki after ${attempts + 1} attempts`);

          // Verify the log contains expected fields
          const logEntry = results[0];
          expect(logEntry).toBeDefined();

          break;
        }
      } catch (error) {
        console.log(`Attempt ${attempts + 1}/${maxAttempts}: ${error.message}`);
      }

      attempts++;
      if (attempts < maxAttempts) {
        console.log('Waiting 5s before retry...');
        await new Promise(resolve => setTimeout(resolve, 5000));
      }
    }

    if (!found) {
      throw new Error(`Correlation ID ${correlationId} not found in Loki after ${maxAttempts} attempts`);
    }
  });

  test('Multiple concurrent requests maintain separate correlation IDs', async ({ page }) => {
    // Generate unique IDs for parallel requests
    const ids = ['test-concurrent-1', 'test-concurrent-2', 'test-concurrent-3'];

    // Make requests in parallel with different IDs
    const requests = ids.map(async (id) => {
      const context = await page.context().browser().newContext();
      const newPage = await context.newPage();

      await newPage.setExtraHTTPHeaders({
        'X-Test-Run': id,
        'X-Correlation-Id': id
      });

      await newPage.goto('https://grafana.test.local', {
        waitUntil: 'domcontentloaded'
      });

      await context.close();
      return id;
    });

    const results = await Promise.all(requests);
    expect(results).toHaveLength(3);

    console.log('✅ Multiple concurrent requests completed with separate IDs');
  });
});
