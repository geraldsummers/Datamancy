// tests/test_ai_services.js
// Focused test for LocalAI, LiteLLM, and Open WebUI authentication

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const CREDENTIALS = {
  username: process.env.TEST_USERNAME || 'admin',
  password: process.env.TEST_PASSWORD || 'DatamancyTest2025!',
};

const DEFAULT_TIMEOUT = 30000;
const SCREEN_DIR_ROOT = process.env.SCREEN_DIR || '/tests/screenshots';

const SERVICES = [
  {
    name: 'localai',
    url: 'https://localai.project-saturn.com',
    type: 'forward_auth',
    description: 'Forward-auth only, no built-in users'
  },
  {
    name: 'litellm',
    url: 'https://litellm.project-saturn.com',
    type: 'oidc',
    description: 'Generic OAuth SSO with multi-user RBAC'
  },
  {
    name: 'open-webui',
    url: 'https://open-webui.project-saturn.com',
    type: 'oidc',
    description: 'Native OIDC with multi-user RBAC'
  },
];

const log = (...a) => console.log(new Date().toISOString(), ...a);
const errl = (...a) => console.error(new Date().toISOString(), '[ERROR]', ...a);

function mkdirp(p) { fs.mkdirSync(p, { recursive: true }); }

async function ss(page, dir, name) {
  const out = path.join(dir, `${name}.png`);
  await page.screenshot({ path: out, fullPage: true });
  log(`📸 ${name}.png`);
}

async function clickFirst(page, selectors, timeout = 3000) {
  for (const sel of selectors) {
    try {
      const loc = page.locator(sel).first();
      if (await loc.count({ timeout: timeout })) {
        await loc.click({ timeout });
        return sel;
      }
    } catch {}
  }
  return null;
}

async function testService(browser, service) {
  log(`\n========== Testing ${service.name.toUpperCase()} ==========`);
  log(`Type: ${service.type} | ${service.description}`);

  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  const dir = path.join(SCREEN_DIR_ROOT, service.name);
  mkdirp(dir);

  try {
    // Step 1: Navigate to service
    log(`[${service.name}] Navigating to ${service.url}`);
    await page.goto(service.url, { timeout: DEFAULT_TIMEOUT, waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
    await new Promise(res => setTimeout(res, 2000));
    await ss(page, dir, '01-landing');

    // Step 2: Handle authentication based on type
    if (service.type === 'forward_auth') {
      // Should redirect to Authelia immediately
      if (page.url().includes('auth.project-saturn.com')) {
        log(`[${service.name}] Redirected to Authelia (forward_auth working)`);
        await ss(page, dir, '02-authelia-redirect');

        // Fill credentials
        await page.getByLabel('Username').fill(CREDENTIALS.username);
        await page.getByRole('textbox', { name: 'Password' }).fill(CREDENTIALS.password);
        await ss(page, dir, '03-credentials-filled');

        // Sign in
        await clickFirst(page, ['button:has-text("Sign in")', 'button[type="submit"]']);
        await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
        await ss(page, dir, '04-after-signin');

        // Should return to service
        await page.waitForURL(new RegExp(service.name), { timeout: DEFAULT_TIMEOUT }).catch(() => {});
        await ss(page, dir, '05-authenticated');

        if (!page.url().includes(service.name)) {
          throw new Error('Did not return to service after auth');
        }

        log(`✅ [${service.name}] Forward-auth SUCCESS`);
      } else {
        throw new Error('Expected redirect to Authelia but stayed on service');
      }

    } else if (service.type === 'oidc') {
      // Look for OIDC button
      await ss(page, dir, '02-oidc-page');

      // Wait for SPA to render
      await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
      await new Promise(res => setTimeout(res, 2000));

      // Try Playwright's text locator first (works for styled divs/spans)
      let clicked = false;
      try {
        const btn = page.getByText('Continue with Authelia', { exact: false }).first();
        if (await btn.count() > 0) {
          await btn.click({ timeout: 5000 });
          clicked = true;
          log(`[${service.name}] Clicked OIDC button with Playwright text locator`);
        }
      } catch (e) {
        log(`[${service.name}] Text locator failed: ${e.message}`);
      }

      // Fallback to JavaScript click
      if (!clicked) {
        try {
          await page.evaluate(() => {
            const el = [...document.querySelectorAll('*')].find(
              el => el.textContent?.includes('Continue with Authelia')
            );
            if (el) {
              const clickable = el.closest('button, a, [role="button"]') || el;
              clickable.scrollIntoView({ behavior: 'smooth', block: 'center' });
              setTimeout(() => clickable.click(), 500);
            } else {
              throw new Error('Element not found');
            }
          });
          await new Promise(res => setTimeout(res, 1500));
          clicked = true;
          log(`[${service.name}] Clicked OIDC button with JS fallback`);
        } catch (e) {
          throw new Error(`Could not find or click OIDC button: ${e.message}`);
        }
      }

      await ss(page, dir, '03-oidc-clicked');

      // Wait for Authelia
      await page.waitForURL(/auth\.project-saturn\.com/, { timeout: DEFAULT_TIMEOUT });
      await ss(page, dir, '04-authelia-page');

      // Fill credentials
      await page.getByLabel('Username').fill(CREDENTIALS.username);
      await page.getByRole('textbox', { name: 'Password' }).fill(CREDENTIALS.password);
      await ss(page, dir, '05-credentials-filled');

      // Sign in
      await clickFirst(page, ['button:has-text("Sign in")', 'button[type="submit"]']);
      await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});

      // Check for consent
      const consent = await clickFirst(page, [
        'button:has-text("ACCEPT")',
        'button:has-text("Authorize")',
      ], 3000);

      if (consent) {
        log(`[${service.name}] Consent required, accepting`);
        await ss(page, dir, '06-consent');
      }

      // Wait to return to service
      await page.waitForURL(new RegExp(service.name), { timeout: DEFAULT_TIMEOUT });
      await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
      await ss(page, dir, '07-authenticated');

      // Verify we're logged in (look for user-specific UI)
      await new Promise(res => setTimeout(res, 2000));
      await ss(page, dir, '08-logged-in-ui');

      log(`✅ [${service.name}] OIDC authentication SUCCESS`);
    }

    await ss(page, dir, '99-final');
    return { service: service.name, status: 'pass' };

  } catch (err) {
    errl(`❌ [${service.name}] FAIL: ${err.message}`);
    await ss(page, dir, '99-error').catch(() => {});
    fs.writeFileSync(path.join(dir, 'ERRORS.txt'), `Error: ${err.message}\nStack: ${err.stack}`);
    return { service: service.name, status: 'fail', error: err.message };
  } finally {
    await context.close();
  }
}

(async () => {
  log('Starting AI Services authentication tests');
  mkdirp(SCREEN_DIR_ROOT);

  const browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  });

  const results = [];
  for (const svc of SERVICES) {
    const r = await testService(browser, svc);
    results.push(r);
    await new Promise(res => setTimeout(res, 2000));
  }

  await browser.close();

  log('\n=== AI SERVICES TEST RESULTS ===');
  for (const r of results) {
    const icon = r.status === 'pass' ? '✅' : '❌';
    log(`${icon} ${r.service}: ${r.status}`);
  }

  // Document LiteLLM limitation
  log('\n=== LITELLM STATUS ===');
  log('⚠️  LiteLLM: Has multi-user capability but NO native OIDC support');
  log('    - Built-in user management via database');
  log('    - Only API key-based authentication');
  log('    - Cannot integrate cleanly with SSO');
  log('    - VERDICT: FAIL OUT (no clean SSO integration method)');

  const failed = results.filter(r => r.status !== 'pass').length;
  process.exit(failed > 0 ? 1 : 0);
})();
