// TLS Failure Test - Verify test-runner detects certificate trust issues
// Provenance: TLS verification patterns from browser automation
// Purpose: Confirm test-runner fails cleanly when CA cert not trusted

const { test, expect } = require('../fixtures/base');

test.describe('TLS Certificate Validation', () => {
  test('TLS verification failure is detected', async ({ health }) => {
    // This test MUST fail when chaos-tls profile is active (wrong CA)
    // Expected error: certificate verification, self-signed, or CERT_UNTRUSTED

    await expect(async () => {
      await health.checkTLS('grafana.test.local');
    }).rejects.toThrow(/certificate|verify|self.signed|CERT/i);

    console.log('✅ TLS verification failure detected correctly');
  });

  test('Browser navigation fails with certificate error', async ({ page }) => {
    // Playwright should reject untrusted certificate
    // Note: This assumes Playwright is configured to reject bad certs (not ignoreHTTPSErrors)

    await expect(async () => {
      await page.goto('https://grafana.test.local', {
        timeout: 10000,
        waitUntil: 'domcontentloaded'
      });
    }).rejects.toThrow(/SSL|certificate|ERR_CERT/i);

    console.log('✅ Browser rejected untrusted certificate as expected');
  });
});
