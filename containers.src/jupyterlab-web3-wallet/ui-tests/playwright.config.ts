import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for JupyterLab Web3 Wallet Extension tests
 */
export default defineConfig({
  testDir: './ui-tests',

  /* Maximum time one test can run */
  timeout: 60 * 1000,

  /* Run tests in files in parallel */
  fullyParallel: true,

  /* Fail the build on CI if you accidentally left test.only in the source code */
  forbidOnly: !!process.env.CI,

  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,

  /* Reporter to use */
  reporter: [
    ['html', { outputFolder: 'ui-tests/playwright-report' }],
    ['junit', { outputFile: 'ui-tests/test-results/junit.xml' }],
  ],

  /* Shared settings for all the projects below */
  use: {
    /* Base URL for tests */
    baseURL: process.env.JUPYTER_URL || 'http://localhost:8000',

    /* Collect trace when retrying the failed test */
    trace: 'on-first-retry',

    /* Screenshot on failure */
    screenshot: 'only-on-failure',

    /* Video on failure */
    video: 'retain-on-failure',
  },

  /* Configure projects for major browsers */
  projects: [
    {
      name: 'chromium-with-metamask',
      use: {
        ...devices['Desktop Chrome'],
        // Load MetaMask extension (requires extension path)
        // This would need the MetaMask .crx or unpacked extension
      },
    },

    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
  ],

  /* Run local dev server before starting tests (optional) */
  // webServer: {
  //   command: 'jupyter lab --no-browser',
  //   url: 'http://localhost:8888',
  //   reuseExistingServer: !process.env.CI,
  //   timeout: 120 * 1000,
  // },
});
