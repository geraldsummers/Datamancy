// DNS Failure Test - Verify test-runner detects DNS failures
// Provenance: Chaos engineering pattern for network failure injection
// Purpose: Confirm test-runner fails cleanly when DNS resolution broken

const { test, expect } = require('../fixtures/base');

test.describe('DNS Failure Detection', () => {
  test('DNS resolution failure is detected and reported', async ({ health }) => {
    // This test MUST fail when chaos-dns profile is active
    // Expected error: ENOTFOUND, getaddrinfo, or DNS timeout

    await expect(async () => {
      await health.checkDNS('grafana.test.local', { timeout: 10000 });
    }).rejects.toThrow(/ENOTFOUND|getaddrinfo|DNS/i);

    console.log('✅ DNS failure detected correctly');
  });

  test('Page navigation fails with DNS error', async ({ page }) => {
    // Navigation should fail at DNS resolution stage
    await expect(async () => {
      await page.goto('https://grafana.test.local', {
        timeout: 10000,
        waitUntil: 'domcontentloaded'
      });
    }).rejects.toThrow(/net::ERR_NAME_NOT_RESOLVED|ENOTFOUND/i);

    console.log('✅ Page navigation failed with DNS error as expected');
  });
});
