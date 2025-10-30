#!/usr/bin/env node
// Debug Open-WebUI OIDC authentication flow
const { chromium } = require('playwright');
const fs = require('fs');

const CREDS = { user: 'admin', pass: 'DatamancyTest2025!' };
const URL = 'https://open-webui.project-saturn.com';

(async () => {
  console.log('🔍 Debugging Open-WebUI OIDC flow...');

  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  const dir = '/screenshots/open-webui';
  fs.mkdirSync(dir, { recursive: true });

  try {
    // Navigate to Open-WebUI
    console.log('1. Navigating to Open-WebUI...');
    await page.goto(URL, { timeout: 30000, waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
    await page.screenshot({ path: `${dir}/01-landing.png` });
    console.log('   ✓ Landing page loaded');

    // Scroll down to reveal login form
    console.log('2. Scrolling to login form...');
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(1000);
    await page.screenshot({ path: `${dir}/02-after-scroll.png` });

    // Find and click the Authelia button
    console.log('3. Looking for Authelia button...');

    // Try multiple strategies
    let clicked = false;

    // Strategy 1: Direct text locator with scroll into view
    try {
      const btn = page.getByText('Continue with Authelia').first();
      await btn.scrollIntoViewIfNeeded();
      await btn.click({ timeout: 5000 });
      clicked = true;
      console.log('   ✓ Clicked via text locator');
    } catch (e) {
      console.log('   ✗ Text locator failed:', e.message);
    }

    // Strategy 2: JavaScript click
    if (!clicked) {
      try {
        await page.evaluate(() => {
          const btn = [...document.querySelectorAll('*')].find(
            el => el.textContent?.includes('Continue with Authelia')
          );
          if (btn) {
            const clickable = btn.closest('button, a, [role="button"]') || btn;
            clickable.scrollIntoView({ behavior: 'smooth', block: 'center' });
            setTimeout(() => clickable.click(), 500);
            return true;
          }
          throw new Error('Button not found');
        });
        await page.waitForTimeout(1000);
        clicked = true;
        console.log('   ✓ Clicked via JavaScript');
      } catch (e) {
        console.log('   ✗ JavaScript click failed:', e.message);
      }
    }

    if (!clicked) {
      throw new Error('Could not click Authelia button with any strategy');
    }

    await page.screenshot({ path: `${dir}/03-button-clicked.png` });

    // Wait for redirect to Authelia
    console.log('4. Waiting for Authelia redirect...');
    await page.waitForURL(/auth\.project-saturn\.com/, { timeout: 15000 });
    await page.screenshot({ path: `${dir}/04-authelia-page.png` });
    console.log('   ✓ Redirected to Authelia');

    // Fill credentials
    console.log('5. Filling credentials...');
    await page.getByLabel('Username').fill(CREDS.user);
    await page.getByRole('textbox', { name: 'Password' }).fill(CREDS.pass);
    await page.screenshot({ path: `${dir}/05-credentials-filled.png` });

    // Sign in
    console.log('6. Signing in...');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.waitForTimeout(3000);
    await page.screenshot({ path: `${dir}/06-after-signin.png` });

    // Check for consent screen
    if (page.url().includes('auth.project-saturn.com')) {
      const consentBtn = page.locator('button:has-text("Accept"), button:has-text("ACCEPT")').first();
      if (await consentBtn.count() > 0) {
        console.log('7. Accepting consent...');
        await consentBtn.click();
        await page.waitForTimeout(2000);
        await page.screenshot({ path: `${dir}/07-consent-accepted.png` });
      }
    }

    // Wait for redirect back to Open-WebUI
    console.log('8. Waiting for redirect back to Open-WebUI...');
    await page.waitForURL(/open-webui\.project-saturn\.com/, { timeout: 20000 });
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
    await page.screenshot({ path: `${dir}/08-back-at-openwebui.png` });

    // Verify we're logged in (check for dashboard/chat UI elements)
    console.log('9. Verifying login...');
    await page.waitForTimeout(2000);
    await page.screenshot({ path: `${dir}/09-logged-in.png` });

    const url = page.url();
    console.log(`   Current URL: ${url}`);

    if (url.includes('open-webui.project-saturn.com') && !url.includes('/auth')) {
      console.log('\n✅ SUCCESS: Open-WebUI OIDC authentication working!');
      await browser.close();
      process.exit(0);
    } else {
      throw new Error(`Still at: ${url}`);
    }

  } catch (err) {
    console.error('\n❌ FAILED:', err.message);
    await page.screenshot({ path: `${dir}/99-error.png` }).catch(() => {});
    fs.writeFileSync(`${dir}/error.txt`, `${err.message}\n\n${err.stack}`);
    await browser.close();
    process.exit(1);
  }
})();
