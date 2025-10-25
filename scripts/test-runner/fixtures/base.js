// Base Test Fixture - Reusable health checks and utilities
// Provenance: Playwright test fixtures + retry patterns from production systems
// Sources:
//   - https://playwright.dev/docs/test-fixtures
//   - https://playwright.dev/docs/test-timeouts
//
// Purpose: Provide reusable readiness gates, DNS checks, TLS verification,
// and correlation ID injection for all test specs

const { test: base, expect } = require('@playwright/test');
const dns = require('dns').promises;
const { exec } = require('child_process');
const { promisify } = require('util');

const execAsync = promisify(exec);

/**
 * Extended test fixture with health check utilities
 */
const test = base.extend({
  /**
   * Correlation ID for tracing requests through the stack
   */
  correlationId: async ({}, use) => {
    const testRunId = process.env.TEST_RUN_ID || `test-${Date.now()}`;
    await use(testRunId);
  },

  /**
   * Page with correlation ID header injected
   */
  page: async ({ page, correlationId }, use) => {
    await page.setExtraHTTPHeaders({
      'X-Test-Run': correlationId,
      'X-Correlation-Id': correlationId
    });
    await use(page);
  },

  /**
   * Health check utilities
   */
  health: async ({}, use) => {
    const utils = {
      /**
       * Wait for DNS resolution with retries
       */
      async checkDNS(hostname, options = {}) {
        const { timeout = 30000, interval = 2000 } = options;
        const startTime = Date.now();

        while (Date.now() - startTime < timeout) {
          try {
            const addresses = await dns.resolve4(hostname);
            console.log(`✓ DNS resolved: ${hostname} → ${addresses[0]}`);
            return addresses[0];
          } catch (error) {
            if (Date.now() - startTime + interval >= timeout) {
              throw new Error(`DNS resolution failed for ${hostname}: ${error.message}`);
            }
            await new Promise(resolve => setTimeout(resolve, interval));
          }
        }
      },

      /**
       * Wait for HTTP 200 with retries
       */
      async checkHTTP(page, url, options = {}) {
        const { timeout = 60000, interval = 5000, expectStatus = 200 } = options;
        const startTime = Date.now();

        while (Date.now() - startTime < timeout) {
          try {
            const response = await page.goto(url, {
              waitUntil: 'domcontentloaded',
              timeout: 10000
            });

            if (response && response.status() === expectStatus) {
              console.log(`✓ HTTP check passed: ${url} → ${response.status()}`);
              return response;
            }

            console.log(`⚠ HTTP check retry: ${url} → ${response?.status() || 'no response'}`);
          } catch (error) {
            console.log(`⚠ HTTP check error: ${url} → ${error.message}`);
          }

          if (Date.now() - startTime + interval >= timeout) {
            throw new Error(`HTTP readiness check failed for ${url} after ${timeout}ms`);
          }

          await new Promise(resolve => setTimeout(resolve, interval));
        }
      },

      /**
       * Wait for DOM selector with screenshot on failure
       */
      async checkSelector(page, selector, options = {}) {
        const { timeout = 30000, screenshot = true } = options;

        try {
          await page.waitForSelector(selector, { timeout, state: 'visible' });
          console.log(`✓ Selector found: ${selector}`);
          return true;
        } catch (error) {
          if (screenshot) {
            const screenshotPath = `/results/failure-${Date.now()}.png`;
            await page.screenshot({ path: screenshotPath, fullPage: true });
            console.log(`✗ Selector not found: ${selector} (screenshot: ${screenshotPath})`);
          }
          throw new Error(`Selector "${selector}" not found within ${timeout}ms: ${error.message}`);
        }
      },

      /**
       * Verify TLS certificate trust
       */
      async checkTLS(hostname, port = 443) {
        try {
          const { stdout } = await execAsync(
            `echo | openssl s_client -connect ${hostname}:${port} -CAfile /usr/local/share/ca-certificates/test-ca.crt -verify_return_error 2>&1`
          );

          if (stdout.includes('Verify return code: 0')) {
            console.log(`✓ TLS verified: ${hostname}:${port}`);
            return true;
          }

          throw new Error(`TLS verification failed for ${hostname}:${port}`);
        } catch (error) {
          console.log(`✗ TLS check failed: ${error.message}`);
          throw error;
        }
      },

      /**
       * Query Loki for correlation ID
       */
      async queryLoki(correlationId, lokiUrl = 'http://loki.svc.local:3100') {
        const query = encodeURIComponent(`{job="traefik"} |= "${correlationId}"`);
        const url = `${lokiUrl}/loki/api/v1/query?query=${query}`;

        try {
          const { stdout } = await execAsync(`wget -qO- "${url}"`);
          const result = JSON.parse(stdout);

          if (result.data?.result?.length > 0) {
            console.log(`✓ Correlation ID found in Loki: ${correlationId}`);
            return result.data.result;
          }

          throw new Error(`Correlation ID not found in Loki: ${correlationId}`);
        } catch (error) {
          console.log(`✗ Loki query failed: ${error.message}`);
          throw error;
        }
      }
    };

    await use(utils);
  }
});

module.exports = { test, expect };
