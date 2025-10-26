import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './specs',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [
    ['html', { outputFolder: 'artifacts/html-report', open: 'never' }],
    ['junit', { outputFile: 'artifacts/junit.xml' }],
    ['list']
  ],
  use: {
    baseURL: process.env.GRAFANA_URL || 'https://grafana.stack.local',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: true, // Custom CA not recognized by Chromium
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Direct browser launch (no Browserless for now—simpler)
        // connectOptions: {
        //   wsEndpoint: `ws://browserless:3000/chromium/playwright?token=${process.env.BROWSERLESS_TOKEN}`,
        // },
      },
    },
  ],
  timeout: 30000,
  expect: {
    timeout: 10000,
  },
});
