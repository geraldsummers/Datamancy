import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for Datamancy stack E2E tests
 *
 * Tests SSO flows across all Web UIs using centralized LDAP authentication
 */
export default defineConfig({
  testDir: './tests',

  /* Run only .spec.ts files (Playwright E2E tests, not Jest unit tests) */
  testMatch: '**/*.spec.ts',

  /* Global setup - provisions test user in LDAP */
  globalSetup: require.resolve('./auth/global-setup.ts'),

  /* Global teardown - cleans up test user from LDAP */
  globalTeardown: require.resolve('./auth/global-teardown.ts'),

  /* Maximum time one test can run for */
  timeout: 60 * 1000,

  /* Run tests in files in parallel */
  fullyParallel: true,

  /* Fail the build on CI if you accidentally left test.only */
  forbidOnly: !!process.env.CI,

  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,

  /* Opt out of parallel tests on CI */
  workers: process.env.CI ? 1 : undefined,

  /* Reporter to use */
  reporter: [
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
    ['json', { outputFile: 'test-results/results.json' }],
    ['list'], // Console output
  ],

  /* Shared settings for all projects */
  use: {
    /* Base URL for tests - use full domain even inside Docker
     * Docker Compose provides DNS resolution for *.datamancy.net to Caddy container
     * This ensures TLS certificates are valid and auth cookies work correctly
     * We ignore HTTPS errors for self-signed certificates
     */
    baseURL: process.env.BASE_URL || 'https://datamancy.net',

    /* Ignore HTTPS errors for self-signed certificates in internal testing */
    ignoreHTTPSErrors: true,

    /* Collect trace when retrying the failed test */
    trace: 'on-first-retry',

    /* Screenshot on failure */
    screenshot: 'only-on-failure',

    /* Video on failure */
    video: 'retain-on-failure',

    /* Maximum time for actions like click() */
    actionTimeout: 10 * 1000,

    /* Emulate timezone */
    timezoneId: 'UTC',

    /* Emulate locale */
    locale: 'en-US',
  },

  /* Configure projects for different browsers */
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },

    // Firefox disabled - not installed in container (optimization to reduce build time)
    // See containers.src/test-runner/Dockerfile line 55
    // {
    //   name: 'firefox',
    //   use: { ...devices['Desktop Firefox'] },
    // },

    // {
    //   name: 'webkit',
    //   use: { ...devices['Desktop Safari'] },
    // },

    /* Test against mobile viewports */
    // {
    //   name: 'Mobile Chrome',
    //   use: { ...devices['Pixel 5'] },
    // },
  ],

  /* Folder for test artifacts */
  outputDir: 'test-results/',
});
