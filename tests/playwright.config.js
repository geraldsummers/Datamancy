// Playwright Configuration for Datamancy Stack
// Provenance: https://playwright.dev/docs/test-configuration

const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './specs',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  timeout: 60000,
  expect: {
    timeout: 30000
  },

  reporter: [
    ['html', { outputFolder: '/results/html-report' }],
    ['junit', { outputFile: '/results/junit.xml' }],
    ['list']
  ],

  use: {
    baseURL: 'https://stack.local',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: false, // Phase 1: Verify proper TLS with trusted CA
  },

  projects: [
    {
      name: 'firefox',
      use: {
        ...devices['Desktop Firefox'],
      },
    },
  ],

  webServer: undefined,
});
