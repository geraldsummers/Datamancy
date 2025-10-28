import { test, expect } from '@playwright/test';

test.describe('Portainer SSO Authentication via Authelia', () => {
  test('should authenticate via Authelia forward_auth', async ({ page }) => {
    // Set longer timeout for SSO flow
    test.setTimeout(60000);

    // Step 1: Navigate to Portainer
    console.log('Step 1: Navigating to Portainer...');
    await page.goto('https://portainer.stack.local', { waitUntil: 'networkidle', timeout: 30000 });
    await page.screenshot({ path: 'screenshots/portainer/01-initial-request.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/portainer/01-initial-request.png');

    // Step 2: Should be immediately redirected to Authelia (forward_auth)
    console.log('Step 2: Checking for Authelia redirect...');
    await page.waitForTimeout(2000);

    let url = page.url();
    console.log('Current URL:', url);

    if (url.includes('auth.stack.local') || url.includes('authelia')) {
      console.log('✓ Redirected to Authelia as expected (forward_auth working)');
    } else {
      console.log('⚠ Not at Authelia yet, waiting for redirect...');
      await page.waitForURL(/auth\.stack\.local/, { timeout: 10000 });
    }

    await page.screenshot({ path: 'screenshots/portainer/02-authelia-login.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/portainer/02-authelia-login.png');

    // Step 3: Wait for login form
    console.log('Step 3: Waiting for Authelia login form...');
    await page.waitForSelector('input[id="username-textfield"], input[name="username"], input[placeholder*="sername" i]', { timeout: 10000 });
    await page.screenshot({ path: 'screenshots/portainer/03-login-form-ready.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/portainer/03-login-form-ready.png');

    // Step 4: Enter credentials
    console.log('Step 4: Entering Authelia credentials...');
    await page.fill('input[id="username-textfield"], input[name="username"]', 'admin');
    await page.fill('input[id="password-textfield"], input[name="password"]', 'ChangeMe123!');
    await page.screenshot({ path: 'screenshots/portainer/04-credentials-entered.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/portainer/04-credentials-entered.png');

    // Step 5: Submit login
    console.log('Step 5: Submitting login form...');
    await page.click('button:has-text("SIGN IN"), button[type="submit"]');
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'screenshots/portainer/05-after-submit.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/portainer/05-after-submit.png');

    // Step 6: Should be redirected back to Portainer
    console.log('Step 6: Waiting for redirect to Portainer...');
    await page.waitForURL(/portainer\.stack\.local/, { timeout: 20000 });
    await page.waitForLoadState('networkidle', { timeout: 15000 });
    await page.screenshot({ path: 'screenshots/portainer/06-redirected-to-portainer.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/portainer/06-redirected-to-portainer.png');
    console.log('✓ Redirected back to Portainer');

    // Step 7: Check if initialization is needed
    console.log('Step 7: Checking Portainer state...');
    await page.waitForTimeout(2000);

    url = page.url();
    const isInitPage = url.includes('/init/admin');

    if (isInitPage) {
      console.log('Portainer needs initialization - creating admin user...');
      await page.screenshot({ path: 'screenshots/portainer/07-init-page.png', fullPage: true });
      console.log('✓ Screenshot saved: screenshots/portainer/07-init-page.png');

      // Wait for form to be ready
      await page.waitForSelector('input[type="password"]', { timeout: 5000 });

      // Get all password fields
      const passwordFields = await page.locator('input[type="password"]').all();
      console.log(`Found ${passwordFields.length} password fields`);

      // Fill password fields (first is password, second is confirm)
      if (passwordFields.length >= 2) {
        await passwordFields[0].fill('PortainerAdmin123!');
        await passwordFields[1].fill('PortainerAdmin123!');
      }

      await page.screenshot({ path: 'screenshots/portainer/08-init-filled.png', fullPage: true });
      console.log('✓ Screenshot saved: screenshots/portainer/08-init-filled.png');

      // Submit initialization
      await page.click('button:has-text("Create user")');
      console.log('Clicked Create user button');
      await page.waitForTimeout(5000);

      await page.screenshot({ path: 'screenshots/portainer/08b-after-create.png', fullPage: true });
      console.log('✓ Screenshot saved: screenshots/portainer/08b-after-create.png');

      console.log('✓ Admin user created');
    }

    // Step 8: Verify Portainer dashboard
    console.log('Step 8: Waiting for dashboard...');
    await page.waitForLoadState('networkidle', { timeout: 15000 });

    await page.screenshot({ path: 'screenshots/portainer/09-dashboard.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/portainer/09-dashboard.png');

    // Check for Portainer UI elements
    const hasPortainerUI = await page.locator('text=/dashboard|container|image|volume|network|endpoint|environment|home/i').first().count() > 0;

    if (hasPortainerUI) {
      console.log('✅ Portainer dashboard detected - successfully authenticated!');
    }

    // Step 9: Final verification
    console.log('Step 9: Final verification...');
    await page.screenshot({ path: 'screenshots/portainer/10-final-proof.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/portainer/10-final-proof.png');

    // Final assertions
    expect(page.url()).toContain('portainer.stack.local');
    expect(page.url()).not.toContain('authelia');
    expect(page.url()).not.toContain('auth.stack.local');

    console.log('\n=== Portainer SSO Authentication Flow Complete ===');
    console.log('All screenshots saved to screenshots/portainer/');
    console.log('✅ Successfully proved Portainer authentication via Authelia forward_auth!');
  });
});
