#!/usr/bin/env node
// Simple proof-of-concept: Authenticate to LocalAI, LiteLLM, Open-WebUI via Authelia
const { chromium } = require('playwright');
const fs = require('fs');

const CREDS = { user: 'admin', pass: 'DatamancyTest2025!' };

async function proveAuth(url, name, type) {
  console.log(`\n=== Testing ${name} (${type}) ===`);
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  const dir = `/screenshots/${name}`;
  fs.mkdirSync(dir, { recursive: true });

  try {
    await page.goto(url, { timeout: 30000 });
    await page.waitForLoadState('domcontentloaded');
    await page.screenshot({ path: `${dir}/01-initial.png` });

    if (type === 'forward-auth') {
      // Should be at Authelia already
      await page.waitForURL(/auth\.project-saturn\.com/, { timeout: 10000 });
      await page.screenshot({ path: `${dir}/02-authelia.png` });

      await page.getByLabel('Username').fill(CREDS.user);
      await page.getByRole('textbox', { name: 'Password' }).fill(CREDS.pass);
      await page.screenshot({ path: `${dir}/03-filled.png` });

      await page.getByRole('button', { name: 'Sign in' }).click();
      await page.waitForURL(new RegExp(name.replace('-', '[-.]')), { timeout: 15000 });
      await page.screenshot({ path: `${dir}/04-authenticated.png` });
      console.log(`✅ ${name}: Forward-auth SUCCESS`);

    } else if (type === 'oidc') {
      // Look for SSO button
      await page.waitForTimeout(2000);
      await page.screenshot({ path: `${dir}/02-login-page.png` });

      const btn = await page.locator('text=/continue with authelia/i').first();
      if (await btn.count() === 0) {
        throw new Error('No Authelia OIDC button found');
      }

      await btn.click();
      await page.screenshot({ path: `${dir}/03-clicked.png` });

      await page.waitForURL(/auth\.project-saturn\.com/, { timeout: 15000 });
      await page.screenshot({ path: `${dir}/04-authelia.png` });

      await page.getByLabel('Username').fill(CREDS.user);
      await page.getByRole('textbox', { name: 'Password' }).fill(CREDS.pass);
      await page.screenshot({ path: `${dir}/05-filled.png` });

      await page.getByRole('button', { name: 'Sign in' }).click();
      await page.waitForTimeout(3000);
      await page.screenshot({ path: `${dir}/06-after-signin.png` });

      // Check for consent
      if (page.url().includes('auth.project-saturn.com') && await page.locator('text=/accept/i').count() > 0) {
        await page.locator('text=/accept/i').first().click();
        await page.waitForTimeout(2000);
      }

      await page.waitForURL(new RegExp(name.replace('-', '[-.]')), { timeout: 20000 });
      await page.screenshot({ path: `${dir}/07-authenticated.png` });
      console.log(`✅ ${name}: OIDC SUCCESS`);
    }

    await browser.close();
    return true;

  } catch (err) {
    console.error(`❌ ${name}: ${err.message}`);
    await page.screenshot({ path: `${dir}/99-error.png` }).catch(() => {});
    fs.writeFileSync(`${dir}/error.txt`, err.stack);
    await browser.close();
    return false;
  }
}

(async () => {
  const results = {
    localai: await proveAuth('https://localai.project-saturn.com', 'localai', 'forward-auth'),
    litellm: await proveAuth('https://litellm.project-saturn.com', 'litellm', 'oidc'),
    openwebui: await proveAuth('https://open-webui.project-saturn.com', 'open-webui', 'oidc'),
  };

  console.log('\n=== FINAL RESULTS ===');
  console.log(`LocalAI (forward-auth): ${results.localai ? '✅ PASS' : '❌ FAIL'}`);
  console.log(`LiteLLM (OIDC): ${results.litellm ? '✅ PASS' : '❌ FAIL'}`);
  console.log(`Open-WebUI (OIDC): ${results.openwebui ? '✅ PASS' : '❌ FAIL'}`);

  process.exit(Object.values(results).every(r => r) ? 0 : 1);
})();
