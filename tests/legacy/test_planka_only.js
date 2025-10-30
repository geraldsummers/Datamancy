// Focused Planka OIDC test - runs in test-runner container
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const CREDENTIALS = {
  username: 'admin',
  password: 'DatamancyTest2025!',
  email: 'admin@stack.local',
};

const SCREEN_DIR = '/tests/screenshots/planka';
const log = (...a) => console.log(new Date().toISOString(), ...a);
const errl = (...a) => console.error(new Date().toISOString(), '[ERROR]', ...a);

async function ss(page, name) {
  const out = path.join(SCREEN_DIR, `${name}.png`);
  await page.screenshot({ path: out, fullPage: true });
  log(`📸 ${name}.png → ${out}`);
}

(async () => {
  log('Starting Planka OIDC test...');
  fs.mkdirSync(SCREEN_DIR, { recursive: true });

  const browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  });

  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    viewport: { width: 1920, height: 1080 },
  });

  const page = await context.newPage();

  try {
    // 1. Navigate to Planka
    log('Step 1: Navigate to Planka');
    await page.goto('https://planka.stack.local', { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(2000);
    await ss(page, '01-landing');

    // 2. Look for OIDC/SSO button
    log('Step 2: Looking for OIDC login button');
    const oidcButtonSelectors = [
      'button:has-text("Sign in with")',
      'button:has-text("SSO")',
      'button:has-text("Authelia")',
      'a:has-text("Sign in with")',
      'a:has-text("SSO")',
      '[class*="oidc"]',
      '[class*="sso"]',
    ];

    let oidcClicked = false;
    for (const sel of oidcButtonSelectors) {
      try {
        const btn = page.locator(sel).first();
        if (await btn.count() > 0) {
          log(`Found OIDC button: ${sel}`);
          await btn.click({ timeout: 3000 });
          oidcClicked = true;
          await page.waitForTimeout(2000);
          await ss(page, '02-oidc-clicked');
          break;
        }
      } catch (e) {
        // Try next selector
      }
    }

    if (!oidcClicked) {
      log('No explicit OIDC button found, checking if already redirected to Authelia');
    }

    await page.waitForTimeout(2000);
    await ss(page, '03-after-potential-redirect');

    // 3. Check if we're at Authelia login
    const currentUrl = page.url();
    log(`Current URL: ${currentUrl}`);

    if (currentUrl.includes('auth.stack.local')) {
      log('Step 3: At Authelia login page');
      await ss(page, '04-authelia-login');

      // Fill credentials using correct Authelia selectors
      await page.getByLabel('Username').fill(CREDENTIALS.username);
      await page.getByRole('textbox', { name: 'Password' }).fill(CREDENTIALS.password);
      await ss(page, '05-credentials-filled');

      // Click sign in
      await page.getByRole('button', { name: 'Sign in' }).click();
      await page.waitForTimeout(3000);
      await ss(page, '06-after-submit');

      // Check for consent page
      const consentUrl = page.url();
      if (consentUrl.includes('/consent')) {
        log('Step 4: Consent page detected');
        await ss(page, '07-consent-page');
        await page.getByRole('button', { name: /Accept|Consent/i }).click();
        await page.waitForTimeout(3000);
      }
    }

    // 4. Wait for redirect back to Planka
    log('Step 5: Waiting for Planka dashboard');
    await page.waitForTimeout(5000);
    await ss(page, '08-planka-dashboard');

    // 5. Check for Planka UI markers
    const markers = [
      'text=/Boards/i',
      'text=/Projects/i',
      'text=/Board/i',
      'text=/Create/i',
    ];

    let foundMarker = false;
    for (const marker of markers) {
      try {
        const el = page.locator(marker).first();
        if (await el.count({ timeout: 5000 }) > 0) {
          log(`✅ Found Planka UI marker: ${marker}`);
          foundMarker = true;
          break;
        }
      } catch (e) {
        // Try next marker
      }
    }

    if (foundMarker) {
      log('✅ SUCCESS: Planka OIDC authentication successful!');
      await ss(page, '99-success-logged-in');
    } else {
      errl('❌ FAIL: Could not verify Planka dashboard loaded');
      await ss(page, '99-failed-no-markers');
      process.exit(1);
    }

  } catch (error) {
    errl('Test failed:', error.message);
    await ss(page, '99-error');
    throw error;
  } finally {
    await browser.close();
  }

  log('Planka test complete!');
})();
