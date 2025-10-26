// Playwright configuration for Caddy Security testing
// @ts-check
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './specs',
  testMatch: '**/caddy-security-*.spec.js',
  fullyParallel: false, // Run tests sequentially to avoid session conflicts
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 1,
  workers: 1, // Single worker to avoid cookie/session conflicts
  reporter: [
    ['list'],
    ['junit', { outputFile: '../data/tests/caddy-security-results.xml' }],
    ['html', { outputFolder: '../data/tests/caddy-security-html-report' }]
  ],

  use: {
    baseURL: process.env.BASE_URL || 'https://stack.local',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: true, // For self-signed certs
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1280, height: 720 },
      },
    },
  ],

  outputDir: '../data/tests/test-results',
});
