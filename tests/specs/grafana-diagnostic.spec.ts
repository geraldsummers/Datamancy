import { test, expect } from '@playwright/test';

test('diagnose grafana access and SSO flow', async ({ page }) => {
  // Step 1: Check if we can reach Grafana through Caddy
  console.log('Step 1: Accessing Grafana via Caddy proxy...');

  const response = await page.goto('https://grafana.stack.local', {
    waitUntil: 'networkidle',
    timeout: 30000
  });

  console.log(`Initial response status: ${response?.status()}`);
  console.log(`Current URL after redirect: ${page.url()}`);

  await page.screenshot({
    path: 'test-results/grafana-01-initial-load.png',
    fullPage: true
  });

  // Step 2: Check what we see
  const pageTitle = await page.title();
  console.log(`Page title: ${pageTitle}`);

  const bodyText = await page.locator('body').textContent();
  console.log(`Body text preview: ${bodyText?.substring(0, 200)}`);

  // Step 3: Look for specific elements
  const hasLoginForm = await page.locator('form').count() > 0;
  const hasOAuthButton = await page.locator('text=/oauth|authelia/i').count() > 0;
  const hasErrorMessage = await page.locator('text=/error|failed|denied/i').count() > 0;

  console.log(`Has login form: ${hasLoginForm}`);
  console.log(`Has OAuth/Authelia button: ${hasOAuthButton}`);
  console.log(`Has error message: ${hasErrorMessage}`);

  // Step 4: Try to find and click OAuth login if available
  if (hasOAuthButton) {
    console.log('Step 4: OAuth button found, attempting click...');
    const oauthButton = page.locator('a, button').filter({ hasText: /authelia|oauth|sign in with/i }).first();
    await oauthButton.click({ timeout: 5000 }).catch(e => {
      console.log(`Failed to click OAuth button: ${e.message}`);
    });

    await page.waitForLoadState('networkidle').catch(() => {});
    console.log(`URL after OAuth click: ${page.url()}`);

    await page.screenshot({
      path: 'test-results/grafana-02-after-oauth-click.png',
      fullPage: true
    });
  }

  // Step 5: Take final diagnostic screenshot
  await page.screenshot({
    path: 'test-results/grafana-03-final-state.png',
    fullPage: true
  });

  console.log('\n=== Diagnostic Summary ===');
  console.log(`Final URL: ${page.url()}`);
  console.log(`Final Title: ${await page.title()}`);
  console.log('Screenshots saved to test-results/');
});
