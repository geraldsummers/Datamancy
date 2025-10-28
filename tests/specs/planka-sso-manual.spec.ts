import { test, expect } from '@playwright/test';

test.describe('Planka SSO Manual Flow', () => {
  test('manual SSO flow with extended waits', async ({ page }) => {
    test.setTimeout(120000);

    console.log('=== Starting Planka SSO Manual Test ===');

    // Step 1: Navigate to Planka
    console.log('Step 1: Navigating to Planka...');
    await page.goto('https://planka.stack.local', { waitUntil: 'networkidle', timeout: 30000 });
    await page.screenshot({ path: 'screenshots/planka-manual-01-landing.png', fullPage: true });
    console.log('✓ Screenshot: planka-manual-01-landing.png');

    // Step 2: Click SSO button
    console.log('Step 2: Clicking SSO button...');
    const ssoButton = page.locator('button:has-text("SSO"), button:has-text("OIDC")').first();
    await ssoButton.click();
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'screenshots/planka-manual-02-after-sso-click.png', fullPage: true });
    console.log('✓ Screenshot: planka-manual-02-after-sso-click.png');
    console.log('Current URL:', page.url());

    // Step 3: Wait for Authelia
    console.log('Step 3: Waiting for Authelia...');
    await page.waitForURL(/auth\.stack\.local/, { timeout: 20000 });
    await page.waitForTimeout(2000);
    await page.screenshot({ path: 'screenshots/planka-manual-03-authelia.png', fullPage: true });
    console.log('✓ Screenshot: planka-manual-03-authelia.png');
    console.log('Current URL:', page.url());

    // Step 4: Fill credentials
    console.log('Step 4: Filling credentials...');
    await page.fill('input[id="username-textfield"], input[name="username"]', 'alice');
    await page.fill('input[id="password-textfield"], input[name="password"]', 'alice_password');
    await page.screenshot({ path: 'screenshots/planka-manual-04-credentials-filled.png', fullPage: true });
    console.log('✓ Screenshot: planka-manual-04-credentials-filled.png');

    // Step 5: Submit
    console.log('Step 5: Submitting form...');
    await page.click('button:has-text("SIGN IN"), button:has-text("Sign in")');
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'screenshots/planka-manual-05-after-submit.png', fullPage: true });
    console.log('✓ Screenshot: planka-manual-05-after-submit.png');
    console.log('Current URL:', page.url());

    // Step 6: Wait and see what happens
    console.log('Step 6: Waiting for redirect...');
    await page.waitForTimeout(5000);
    await page.screenshot({ path: 'screenshots/planka-manual-06-wait-5s.png', fullPage: true });
    console.log('✓ Screenshot: planka-manual-06-wait-5s.png');
    console.log('Current URL:', page.url());

    // Step 7: Wait more
    console.log('Step 7: Waiting more...');
    await page.waitForTimeout(10000);
    await page.screenshot({ path: 'screenshots/planka-manual-07-wait-15s.png', fullPage: true });
    console.log('✓ Screenshot: planka-manual-07-wait-15s.png');
    console.log('Current URL:', page.url());

    // Step 8: Final check
    console.log('Step 8: Final state check...');
    await page.waitForTimeout(5000);
    await page.screenshot({ path: 'screenshots/planka-manual-08-final.png', fullPage: true });
    console.log('✓ Screenshot: planka-manual-08-final.png');
    console.log('Final URL:', page.url());

    console.log('=== Test Complete ===');
  });
});
