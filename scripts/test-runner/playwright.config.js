// Playwright Configuration - Agent Smoke Tests
// Provenance: Based on Playwright official docs + Browserless CDP integration
// Sources:
//   - https://playwright.dev/docs/test-configuration
//   - https://www.browserless.io/docs/playwright
//
// Purpose: Configure Playwright to connect to Browserless via WebSocket
// and run smoke tests with readiness gates (no sleeps)

const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './specs',

  // Timeouts
  timeout: 90000, // 90s per test
  expect: {
    timeout: 30000 // 30s for assertions
  },

  // Retries
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1, // Sequential execution for smoke tests

  // Reporter
  reporter: [
    ['list'],
    ['json', { outputFile: '/results/test-results.json' }],
    ['html', { outputFolder: '/results/html', open: 'never' }]
  ],

  // Global test settings
  use: {
    // Connect to Browserless via WebSocket
    connectOptions: {
      wsEndpoint: process.env.BROWSERLESS_URL || 'ws://localhost:3000'
    },

    // Browser context options
    baseURL: process.env.TEST_BASE_URL || 'https://grafana.test.local',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',

    // TLS - trust our self-signed CA
    ignoreHTTPSErrors: false, // Enforce TLS validation

    // Viewport
    viewport: { width: 1280, height: 720 },

    // Timeouts
    actionTimeout: 30000,
    navigationTimeout: 60000
  },

  // Projects - Test against Chromium via Browserless
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Browserless runs Chrome, so we use CDP
        channel: undefined
      }
    }
  ],

  // Output
  outputDir: '/results/playwright-artifacts'
});
