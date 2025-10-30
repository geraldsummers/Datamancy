#!/usr/bin/env node
// Manual Open-WebUI test - capture actual URL after button click
const { chromium } = require('playwright');
const fs = require('fs');

const URL = 'https://open-webui.project-saturn.com';

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  const dir = '/screenshots/open-webui';
  fs.mkdirSync(dir, { recursive: true });

  console.log('Testing Open-WebUI OAuth button...\n');

  // Navigate
  await page.goto(URL, { timeout: 30000 });
  await page.waitForLoadState('domcontentloaded');
  console.log('1. Initial URL:', page.url());

  // Scroll down fully
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${dir}/01-scrolled.png` });
  console.log('2. After scroll URL:', page.url());

  // Check what's actually on the page
  const buttonText = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('*')].find(
      el => el.textContent?.includes('Continue with Authelia')
    );
    if (btn) {
      return {
        found: true,
        tag: btn.tagName,
        text: btn.textContent,
        href: btn.href || null,
        onclick: btn.onclick ? btn.onclick.toString() : null,
        parent: btn.parentElement?.tagName
      };
    }
    return { found: false };
  });

  console.log('3. Button details:', JSON.stringify(buttonText, null, 2));

  // Try to find the actual OAuth redirect URL
  const oauthUrl = await page.evaluate(() => {
    // Check for OAuth init in window or scripts
    const links = [...document.querySelectorAll('a[href*="oauth"], a[href*="oidc"], a[href*="auth"]')];
    return links.map(l => ({ href: l.href, text: l.textContent?.trim() }));
  });

  console.log('4. OAuth-related links:', JSON.stringify(oauthUrl, null, 2));

  // Listen for navigation
  page.on('framenavigated', frame => {
    if (frame === page.mainFrame()) {
      console.log('   [NAVIGATION]', frame.url());
    }
  });

  // Try clicking with forced action
  console.log('\n5. Attempting to click button...');
  try {
    await page.evaluate(() => {
      const btn = [...document.querySelectorAll('*')].find(
        el => el.textContent?.includes('Continue with Authelia')
      );
      if (btn) {
        console.log('Found button, clicking...');
        const clickable = btn.closest('button, a') || btn;
        clickable.click();
        return true;
      }
      throw new Error('Button not found');
    });
    console.log('   Click executed');
  } catch (e) {
    console.error('   Click failed:', e.message);
  }

  // Wait and see what happens
  await page.waitForTimeout(5000);
  await page.screenshot({ path: `${dir}/02-after-click.png` });
  console.log('6. After click URL:', page.url());

  // Check browser console for errors
  page.on('console', msg => console.log('   [CONSOLE]', msg.text()));
  page.on('pageerror', err => console.error('   [PAGE ERROR]', err.message));

  await page.waitForTimeout(5000);
  console.log('7. Final URL:', page.url());

  await browser.close();
})();
