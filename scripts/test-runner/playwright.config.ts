// Playwright Configuration - Test Runner for Agent-First Stack
// Provenance: Playwright official docs + Browserless integration patterns
// Sources:
//   - https://playwright.dev/docs/test-configuration
//   - https://www.browserless.io/docs/docker-quickstart
//
// Purpose: Configure Playwright to connect via Browserless WebSocket,
// trust self-contained CA, inject correlation IDs, and capture failure artifacts

import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './specs',
  fullyParallel: true,  // Run tests in parallel for speed
  forbidOnly: !!process.env.CI,
  retries: 0,  // No retries - failures must be deterministic
  workers: 4,  // Run 4 tests concurrently
  timeout: 60000,  // 60s per test

  reporter: [
    ['list'],
    ['junit', { outputFile: '/results/junit.xml' }],
    ['html', { outputFolder: '/results/html', open: 'never' }],
  ],

  use: {
    baseURL: process.env.TEST_BASE_URL || 'https://grafana.test.local',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',

    // TODO: Configure Chrome to trust CA cert properly
    // For now, ignore HTTPS errors to validate end-to-end flow
    ignoreHTTPSErrors: true,

    // TODO: Connect to Browserless via WebSocket (currently using local Chrome)
    // Browserless integration requires additional configuration
    // connectOptions: {
    //   wsEndpoint: process.env.BROWSERLESS_URL || 'ws://browserless.svc.local:3000',
    // },
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Correlation ID header injected via fixture, not here
        // (allows per-test unique IDs)
      },
    },
  ],

  outputDir: '/results/artifacts',
});
