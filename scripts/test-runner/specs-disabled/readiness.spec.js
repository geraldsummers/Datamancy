// Readiness Gate Test - Verify test-runner detects timeout failures
// Provenance: Kubernetes readiness probe patterns
// Purpose: Confirm test-runner fails cleanly when services never become ready

const { test, expect } = require('../fixtures/base');

test.describe('Readiness Gate Validation', () => {
  test('HTTP readiness timeout is detected', async ({ page, health }) => {
    // This test MUST fail when chaos-timeout profile is active
    // Service either never responds or takes >60s to become ready

    await expect(async () => {
      await health.checkHTTP(page, 'https://grafana.test.local', {
        timeout: 30000, // 30s timeout for chaos scenario
        interval: 3000
      });
    }).rejects.toThrow(/timeout|readiness|health check failed/i);

    console.log('✅ Readiness timeout detected correctly');
  });

  test('DOM selector timeout is detected with screenshot', async ({ page }) => {
    // Even if page loads, critical element might be missing
    // This should capture a screenshot on failure

    // First, try to load the page (might timeout or succeed)
    try {
      await page.goto('https://grafana.test.local', {
        timeout: 20000,
        waitUntil: 'domcontentloaded'
      });
    } catch (error) {
      // Page load timeout is acceptable for this test
      console.log('Page load timed out as expected');
      throw error;
    }

    // If page loaded, selector should still fail (missing element)
    await expect(async () => {
      await page.waitForSelector('input[name="user"]', {
        timeout: 10000,
        state: 'visible'
      });
    }).rejects.toThrow(/Timeout|timeout/i);

    console.log('✅ Selector timeout detected correctly');
  });
});
