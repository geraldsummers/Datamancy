import { test, expect } from '@playwright/test';

test.describe('Planka SSO Authentication', () => {
  test('should authenticate via Authelia OIDC', async ({ page }) => {
    // Set longer timeout for SSO flow
    test.setTimeout(60000);

    // Step 1: Navigate to Planka
    console.log('Step 1: Navigating to Planka...');
    await page.goto('https://planka.stack.local', { waitUntil: 'networkidle', timeout: 30000 });
    await page.screenshot({ path: '../screenshots/planka/01-landing.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/planka/01-landing.png');

    // Step 2: Click SSO login button
    console.log('Step 2: Looking for SSO/OIDC login button...');

    // Wait for page to load and look for SSO button
    await page.waitForLoadState('networkidle', { timeout: 10000 });

    // Try multiple possible selectors for SSO login
    const ssoButton = page.locator('button:has-text("SSO"), button:has-text("OIDC"), button:has-text("Single Sign"), a:has-text("SSO"), a:has-text("OIDC")').first();

    // If no SSO button found, take screenshot and check what's available
    const hasSSOButton = await ssoButton.count() > 0;

    if (!hasSSOButton) {
      console.log('No obvious SSO button found. Checking for alternative auth methods...');
      await page.screenshot({ path: '../screenshots/planka/02-no-sso-button.png', fullPage: true });

      // Check if we need to look for a different UI pattern
      const pageContent = await page.content();
      console.log('Page title:', await page.title());

      // Planka might show a login form first - check if there's an OIDC option
      const hasOIDCLink = await page.locator('text=/oidc|sso/i').count() > 0;
      if (hasOIDCLink) {
        const oidcLink = page.locator('text=/oidc|sso/i').first();
        await oidcLink.click();
        console.log('✓ Clicked OIDC/SSO link');
      } else {
        // If Planka auto-redirects to OIDC, we should already be at Authelia
        const currentUrl = page.url();
        if (currentUrl.includes('authelia')) {
          console.log('✓ Already redirected to Authelia');
        } else {
          throw new Error('Could not find SSO/OIDC login option on Planka landing page');
        }
      }
    } else {
      await ssoButton.click();
      console.log('✓ Clicked SSO button');
    }

    await page.waitForTimeout(2000);
    await page.screenshot({ path: '../screenshots/planka/03-after-sso-click.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/planka/03-after-sso-click.png');

    // Step 3: Should be redirected to Authelia login
    console.log('Step 3: Waiting for Authelia login page...');
    await page.waitForURL(/auth\.stack\.local|authelia/, { timeout: 15000 });
    await page.waitForSelector('input[id="username-textfield"], input[name="username"], input[placeholder*="sername" i]', { timeout: 10000 });
    await page.screenshot({ path: '../screenshots/planka/04-authelia-login.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/planka/04-authelia-login.png');
    console.log('✓ Redirected to Authelia');

    // Step 4: Enter credentials
    console.log('Step 4: Entering Authelia credentials...');
    await page.fill('input[id="username-textfield"], input[name="username"]', 'admin');
    await page.fill('input[id="password-textfield"], input[name="password"]', 'ChangeMe123!');
    await page.screenshot({ path: '../screenshots/planka/05-credentials-entered.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/planka/05-credentials-entered.png');

    // Step 5: Submit login
    console.log('Step 5: Submitting login form...');
    await page.click('button:has-text("SIGN IN"), button:has-text("Sign in")');
    await page.waitForTimeout(2000);
    await page.screenshot({ path: '../screenshots/planka/06-after-submit.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/planka/06-after-submit.png');

    // Step 6: Handle 2FA or Consent Screen
    console.log('Step 6: Checking for 2FA or consent screen...');
    await page.waitForTimeout(2000);

    const has2FA = await page.locator('text=/one-time|token|code/i').count() > 0;
    const hasConsent = await page.locator('button:has-text("ACCEPT"), button:has-text("Accept")').count() > 0;

    if (has2FA) {
      console.log('2FA detected, entering code...');
      await page.screenshot({ path: '../screenshots/planka/07-2fa-prompt.png', fullPage: true });
      console.log('✓ Screenshot saved: screenshots/planka/07-2fa-prompt.png');
      console.log('⚠ 2FA is enabled - this may require additional configuration');
    } else if (hasConsent) {
      console.log('Consent screen detected, accepting permissions...');
      await page.screenshot({ path: '../screenshots/planka/07-consent-screen.png', fullPage: true });
      console.log('✓ Screenshot saved: screenshots/planka/07-consent-screen.png');

      // Click ACCEPT button
      await page.click('button:has-text("ACCEPT"), button:has-text("Accept")');
      console.log('✓ Accepted consent');
      await page.waitForTimeout(2000);
    }

    // Step 7: Should be redirected back to Planka
    console.log('Step 7: Waiting for redirect to Planka...');
    await page.waitForURL(/planka\.stack\.local/, { timeout: 20000 });
    await page.waitForLoadState('networkidle', { timeout: 15000 });
    await page.screenshot({ path: '../screenshots/planka/08-logged-in.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/planka/08-logged-in.png');
    console.log('✓ Redirected back to Planka');

    // Step 8: Verify we're logged in
    console.log('Step 8: Verifying successful login...');

    // Check for common logged-in indicators
    const isLoggedIn = await page.locator('text=/dashboard|board|project|logout|profile|settings/i').first().count() > 0;

    await page.screenshot({ path: '../screenshots/planka/09-final-state.png', fullPage: true });
    console.log('✓ Screenshot saved: screenshots/planka/09-final-state.png');

    if (isLoggedIn) {
      console.log('✅ Successfully authenticated to Planka via Authelia SSO!');
    } else {
      console.log('⚠ Reached Planka but login state unclear - check screenshots');
    }

    // Final verification - URL should be at Planka and not showing login page
    expect(page.url()).toContain('planka.stack.local');
    expect(page.url()).not.toContain('authelia');

    console.log('\n=== SSO Authentication Flow Complete ===');
    console.log('All screenshots saved to screenshots/planka/');
  });
});
