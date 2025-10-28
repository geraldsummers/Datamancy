import { test, expect } from '@playwright/test';

test('login to Grafana via Authelia SSO and verify access', async ({ page }) => {
  console.log('Step 1: Navigate to Grafana...');
  await page.goto('https://grafana.stack.local', {
    waitUntil: 'networkidle',
    timeout: 30000
  });

  console.log('Step 2: Click "Sign in with Authelia" button...');
  const autheliaButton = page.locator('button, a').filter({ hasText: /sign in with authelia/i });
  await autheliaButton.click();

  await page.waitForLoadState('networkidle');
  console.log(`Redirected to: ${page.url()}`);

  await page.screenshot({
    path: 'test-results/grafana-login-01-authelia-page.png',
    fullPage: true
  });

  console.log('Step 3: Enter credentials (admin / ChangeMe123!)...');

  // Wait for Authelia login form
  await page.waitForSelector('input[name="username"], input#username-textfield', { timeout: 10000 });

  // Fill in username
  const usernameField = page.locator('input[name="username"], input#username-textfield').first();
  await usernameField.fill('admin');

  // Fill in password
  const passwordField = page.locator('input[name="password"], input[type="password"]').first();
  await passwordField.fill('ChangeMe123!');

  await page.screenshot({
    path: 'test-results/grafana-login-02-credentials-filled.png',
    fullPage: true
  });

  console.log('Step 4: Submit login form...');
  const signInButton = page.locator('button[type="submit"], button').filter({ hasText: /sign in|login/i }).first();
  await signInButton.click();

  // Wait for redirect and check for consent page
  await page.waitForTimeout(2000);
  console.log(`After login submission: ${page.url()}`);

  // Step 4.5: Handle OAuth consent page if present
  try {
    // Wait for either consent page or grafana redirect
    await page.waitForURL('**/consent/**', { timeout: 5000 }).catch(() => {});

    if (page.url().includes('/consent')) {
      console.log('Step 4.5: OAuth consent page detected, clicking ACCEPT...');

      await page.screenshot({
        path: 'test-results/grafana-login-03-consent-page.png',
        fullPage: true
      });

      const acceptButton = page.locator('button').filter({ hasText: /accept/i }).first();
      await acceptButton.click();

      console.log('Waiting for redirect to Grafana after consent...');
      await page.waitForURL('**/grafana.stack.local/**', { timeout: 30000 });
      console.log(`After consent: ${page.url()}`);
    }
  } catch (e) {
    console.log(`Consent handling: ${e.message}`);
  }

  await page.screenshot({
    path: 'test-results/grafana-login-03-after-auth.png',
    fullPage: true
  });

  console.log('Step 5: Verify we are logged into Grafana...');

  // Wait a bit for Grafana to fully load
  await page.waitForTimeout(3000);

  // Check if we're on Grafana (not login page)
  const currentUrl = page.url();
  console.log(`Final URL: ${currentUrl}`);

  const isOnGrafana = currentUrl.includes('grafana.stack.local') && !currentUrl.includes('/login');
  console.log(`On Grafana (not login page): ${isOnGrafana}`);

  // Look for Grafana UI elements
  const hasGrafanaNav = await page.locator('nav, [data-testid="sidemenu"], .sidemenu').count() > 0;
  console.log(`Has Grafana navigation: ${hasGrafanaNav}`);

  // Take final screenshot showing logged-in state
  await page.screenshot({
    path: 'test-results/grafana-login-04-logged-in.png',
    fullPage: true
  });

  console.log('\n=== Login Test Complete ===');
  console.log(`Success: ${isOnGrafana}`);
  console.log(`Final URL: ${currentUrl}`);
  console.log('Screenshot: test-results/grafana-login-04-logged-in.png');

  expect(isOnGrafana).toBeTruthy();
});
