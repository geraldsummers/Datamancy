#!/usr/bin/env node
// Quick manual service verification
const { chromium } = require('playwright');
const fs = require('fs');

const SERVICE = process.argv[2] || 'grafana';
const SERVICES = {
  grafana: { url: 'https://grafana.stack.local', usage: async (p) => { await p.waitForTimeout(3000); } },
  'open-webui': { url: 'https://open-webui.stack.local', usage: async (p) => { await p.waitForTimeout(3000); } },
  jupyterhub: { url: 'https://jupyterhub.stack.local', usage: async (p) => { await p.waitForTimeout(3000); } },
  outline: { url: 'https://outline.stack.local', usage: async (p) => { await p.waitForTimeout(3000); } },
  vaultwarden: { url: 'https://vaultwarden.stack.local', usage: async (p) => { await p.waitForTimeout(3000); } },
  planka: { url: 'https://planka.stack.local', usage: async (p) => { await p.waitForTimeout(3000); } },
  nextcloud: { url: 'https://nextcloud.stack.local', usage: async (p) => { await p.waitForTimeout(3000); } },
};

(async () => {
  const svc = SERVICES[SERVICE];
  if (!svc) {
    console.error(`Unknown service: ${SERVICE}`);
    process.exit(1);
  }

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  const dir = `./usage_screenshots/${SERVICE}`;
  fs.mkdirSync(dir, { recursive: true });

  console.log(`Testing ${SERVICE}...`);

  await page.goto(svc.url, { timeout: 60000 });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${dir}/1-initial.png`, fullPage: true });

  // Find and click OIDC
  const selectors = [
    'button:has-text("Authelia")', 'a:has-text("Authelia")',
    'button:has-text("SSO")', 'a:has-text("SSO")',
    'button:has-text("Login with SSO")', 'a:has-text("Login with SSO")',
    'a:has-text("Sign in with")'];

  for (const sel of selectors) {
    try {
      if (await page.locator(sel).count() > 0) {
        console.log(`Clicking: ${sel}`);
        await page.locator(sel).first().click({ timeout: 3000 });
        await page.waitForTimeout(2000);
        break;
      }
    } catch {}
  }

  await page.screenshot({ path: `${dir}/2-after-click.png`, fullPage: true });

  // Login if on Authelia
  if (page.url().includes('auth.stack.local')) {
    console.log('On Authelia page');
    await page.getByLabel('Username').fill('admin');
    await page.locator('input[type="password"]').fill('DatamancyTest2025!');
    await page.screenshot({ path: `${dir}/3-creds-filled.png`, fullPage: true });

    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.waitForTimeout(2000);

    // Consent
    try {
      await page.getByRole('button', { name: 'Accept' }).click({ timeout: 5000 });
      await page.waitForTimeout(2000);
    } catch {}

    await page.screenshot({ path: `${dir}/4-after-auth.png`, fullPage: true });
  }

  // Wait for app to load
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(3000);

  // Usage action
  await svc.usage(page);

  await page.screenshot({ path: `${dir}/5-usage.png`, fullPage: true });
  console.log(`✅ Done - screenshots in ${dir}/`);

  await browser.close();
})();
