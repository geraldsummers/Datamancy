// Provenance: Playwright configuration v1.45
// Purpose: Configure browser-based UI testing for Datamancy stack
// Architecture: Single hostname (stack.local), path-based routing

import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './specs',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1, // Sequential execution to ensure clean state
  reporter: [
    ['junit', { outputFile: '/results/junit.xml' }],
    ['html', { outputFolder: '/results/html', open: 'never' }],
    ['json', { outputFile: '/results/results.json' }],
    ['list']
  ],
  use: {
    baseURL: process.env.BASE_URL || 'https://stack.local',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: true, // HTTPS still active, just ignore self-signed cert warnings
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        launchOptions: {
          args: [
            '--disable-dev-shm-usage',
            '--no-sandbox',
            // Point Chromium directly to our CA cert file
            '--ignore-certificate-errors-spki-list=' + require('fs').readFileSync('/usr/local/share/ca-certificates/datamancy-ca.crt', 'utf8').match(/-----BEGIN CERTIFICATE-----\n(.*?)\n-----END CERTIFICATE-----/s)?.[1]?.replace(/\n/g, '') || '',
          ],
        },
      },
    },
  ],
  outputDir: '/results/artifacts',
});
