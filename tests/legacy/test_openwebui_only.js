// Quick Open WebUI SSO test
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const CREDENTIALS = {
  username: 'admin',
  password: 'DatamancyTest2025!',
};

const SCREEN_DIR = './screenshots/open-webui';

async function main() {
  fs.mkdirSync(SCREEN_DIR, { recursive: true });

  console.log('Starting Open WebUI SSO test...');

  const browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });

  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    viewport: { width: 1280, height: 1024 }
  });

  const page = await context.newPage();

  try {
    // Step 1: Visit Open WebUI
    console.log('1. Navigating to Open WebUI...');
    await page.goto('https://open-webui.stack.local', { waitUntil: 'networkidle', timeout: 30000 });
    await page.screenshot({ path: path.join(SCREEN_DIR, '01-landing.png'), fullPage: true });
    console.log('✓ Landed on Open WebUI');

    // Step 2: Click OAuth login button
    console.log('2. Looking for OAuth/SSO button...');
    const ssoSelectors = [
      'button:has-text("Authelia")',
      'button:has-text("Sign in with")',
      'a:has-text("Authelia")',
      'a:has-text("Sign in with")',
      '.oauth-button',
      '[data-testid="oauth-button"]'
    ];

    // Click and wait for navigation
    console.log('  Clicking OAuth button...');
    await Promise.all([
      page.waitForNavigation({ timeout: 30000 }),
      page.evaluate(() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const oauthButton = buttons.find(b => b.textContent.includes('Authelia') || b.textContent.includes('Continue with'));
        if (oauthButton) {
          oauthButton.click();
        }
      })
    ]);

    console.log(`  ✓ Navigated to: ${page.url()}`);
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(SCREEN_DIR, '02-after-oauth-click.png'), fullPage: true });

    // Step 3: Should be on Authelia login page
    console.log('3. Waiting for Authelia login form...');
    await page.waitForSelector('input[name="username"], input#username-textfield', { timeout: 15000 });
    await page.screenshot({ path: path.join(SCREEN_DIR, '03-authelia-login.png'), fullPage: true });
    console.log('✓ Authelia login page loaded');

    // Step 4: Fill credentials
    console.log('4. Filling credentials...');
    const usernameField = page.locator('input[name="username"], input#username-textfield').first();
    await usernameField.fill(CREDENTIALS.username);

    const passwordField = page.locator('input[name="password"], input#password-textfield').first();
    await passwordField.fill(CREDENTIALS.password);

    await page.screenshot({ path: path.join(SCREEN_DIR, '04-credentials-filled.png'), fullPage: true });

    // Step 5: Submit
    console.log('5. Submitting login form...');
    await page.click('button[type="submit"], input[type="submit"]');
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(SCREEN_DIR, '05-after-submit.png'), fullPage: true });

    // Step 6: Handle consent if present
    console.log('6. Checking for consent page...');
    try {
      const consentButton = page.locator('button:has-text("Accept"), button:has-text("Consent"), input[value="Accept"]').first();
      if (await consentButton.count({ timeout: 5000 })) {
        console.log('  Consent page detected, accepting...');
        await page.screenshot({ path: path.join(SCREEN_DIR, '06-consent-page.png'), fullPage: true });
        await consentButton.click();
        await page.waitForTimeout(2000);
        await page.screenshot({ path: path.join(SCREEN_DIR, '07-after-consent.png'), fullPage: true });
      } else {
        console.log('  No consent page (already accepted or not required)');
      }
    } catch (e) {
      console.log('  No consent page needed');
    }

    // Step 7: Wait for redirect to Open WebUI
    console.log('7. Waiting for redirect back to Open WebUI...');
    await page.waitForURL(/open-webui\.stack\.local/, { timeout: 20000 });
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(SCREEN_DIR, '08-back-to-openwebui.png'), fullPage: true });

    // Step 8: Verify logged in
    console.log('8. Verifying logged in state...');
    await page.waitForTimeout(2000);

    // Look for Open WebUI UI elements
    const loggedInMarkers = [
      'text=/New Chat/i',
      'text=/Models/i',
      'text=/Settings/i',
      'button:has-text("New Chat")',
      '[data-testid="new-chat"]'
    ];

    let foundMarker = false;
    for (const marker of loggedInMarkers) {
      try {
        if (await page.locator(marker).count({ timeout: 5000 })) {
          console.log(`✓ Found UI marker: ${marker}`);
          foundMarker = true;
          break;
        }
      } catch (e) {
        // Try next
      }
    }

    await page.screenshot({ path: path.join(SCREEN_DIR, '99-final-state.png'), fullPage: true });

    if (foundMarker) {
      console.log('\n✅ SUCCESS: Logged into Open WebUI via Authelia + LDAP!');
    } else {
      console.log('\n⚠️  Redirected back but UI markers not found. Check screenshots.');
    }

  } catch (error) {
    console.error('\n❌ ERROR:', error.message);
    await page.screenshot({ path: path.join(SCREEN_DIR, '99-error.png'), fullPage: true });
    throw error;
  } finally {
    await browser.close();
  }
}

main().catch(err => {
  console.error('Test failed:', err);
  process.exit(1);
});
