import { test, expect } from '@playwright/test';

test('login to Grafana with direct credentials and take screenshot', async ({ page }) => {
  console.log('Step 1: Navigate to Grafana...');
  await page.goto('https://grafana.stack.local/login', {
    waitUntil: 'networkidle',
    timeout: 30000
  });

  await page.screenshot({
    path: 'test-results/grafana-direct-01-login-page.png',
    fullPage: true
  });

  console.log('Step 2: Enter admin credentials...');
  await page.fill('input[name="user"]', 'admin');
  await page.fill('input[name="password"]', 'changeme_grafana');

  await page.screenshot({
    path: 'test-results/grafana-direct-02-credentials-filled.png',
    fullPage: true
  });

  console.log('Step 3: Click login button...');
  await page.click('button[type="submit"]');

  await page.waitForTimeout(3000);

  await page.screenshot({
    path: 'test-results/grafana-direct-03-after-login.png',
    fullPage: true
  });

  console.log('Step 4: Verify we are logged in...');
  const currentUrl = page.url();
  console.log(`Current URL: ${currentUrl}`);

  const isLoggedIn = !currentUrl.includes('/login');
  console.log(`Logged in: ${isLoggedIn}`);

  // Take final proof screenshot
  await page.screenshot({
    path: 'test-results/grafana-proof.png',
    fullPage: true
  });

  console.log('\n=== Grafana Direct Login Test Complete ===');
  console.log(`Success: ${isLoggedIn}`);
  console.log(`Final URL: ${currentUrl}`);
  console.log('Proof screenshot: test-results/grafana-proof.png');

  expect(isLoggedIn).toBeTruthy();
});
