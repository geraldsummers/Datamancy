// webui.e2e.js — Browser checks for major UI apps via Caddy + Authelia
// Saves screenshots to SCREEN_DIR. Exits non-zero on failures.

const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer');

const DOMAIN = process.env.DOMAIN || 'project-saturn.com';
const SCREEN_DIR = process.env.SCREEN_DIR || '/screenshots';
const USERNAME = process.env.TEST_USERNAME || process.env.STACK_ADMIN_USER || 'admin';
const PASSWORD = process.env.TEST_PASSWORD || process.env.STACK_ADMIN_PASSWORD || 'DatamancyTest2025!';

const BASES = {
  grafana: `https://grafana.${DOMAIN}`,
  openwebui: `https://open-webui.${DOMAIN}`,
  vaultwarden: `https://vaultwarden.${DOMAIN}`,
  planka: `https://planka.${DOMAIN}`,
  outline: `https://outline.${DOMAIN}`,
  homepage: `https://homepage.${DOMAIN}`,
  portainer: `https://portainer.${DOMAIN}`,
  dockge: `https://dockge.${DOMAIN}`,
  couchdb: `https://couchdb.${DOMAIN}`,
  jupyterhub: `https://jupyterhub.${DOMAIN}`,
  kopia: `https://kopia.${DOMAIN}`,
  litellm: `https://litellm.${DOMAIN}`,
  localai: `https://localai.${DOMAIN}`,
};

function screenshotPath(name) {
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  return path.join(SCREEN_DIR, `${name}-${ts}.png`);
}

async function loginAuthelia(page, targetUrl) {
  await page.goto(targetUrl, { waitUntil: 'networkidle2' });
  const url = page.url();
  if (!/https:\/\/auth\./.test(url)) return; // not redirected => already logged in or bypass

  // Authelia form
  await page.waitForSelector('input[name="username"], input#username', { timeout: 15000 });
  // Use first matching selector for both username and password
  const typeIn = async (sel, value) => {
    const el = await page.$(sel);
    if (el) await el.type(value, { delay: 15 });
  };
  await typeIn('input[name="username"]', USERNAME);
  await typeIn('input#username', USERNAME);
  await typeIn('input[name="password"]', PASSWORD);
  await typeIn('input#password', PASSWORD);

  // Click submit
  const submitSel = 'button[type="submit"], button#btn-sign-in, button[name="action"]';
  await page.click(submitSel);
  await page.waitForNavigation({ waitUntil: 'networkidle2' });
}

async function visitAndVerify(page, name, url, verify) {
  await loginAuthelia(page, url);
  if (page.url() !== url) {
    await page.goto(url, { waitUntil: 'networkidle2' });
  }
  await verify(page);
  await page.screenshot({ path: screenshotPath(name), fullPage: true });
  console.log(`[ok] ${name}`);
}

(async () => {
  await fs.promises.mkdir(SCREEN_DIR, { recursive: true });

  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const page = await browser.newPage();
  page.setDefaultTimeout(30000);

  const checks = [
    { name: 'grafana', url: BASES.grafana, verify: async (p) => { await p.waitForSelector('#app-grafana, a[href="/logout"], [data-testid="serverStats"]'); } },
    { name: 'open-webui', url: BASES.openwebui, verify: async (p) => { await p.waitForSelector('input[placeholder*="Send a message"], [data-testid="chat-input"], #app'); } },
    { name: 'vaultwarden', url: BASES.vaultwarden, verify: async (p) => { await p.waitForSelector('form[action="/identity/accounts/login"], #app'); } },
    { name: 'planka', url: BASES.planka, verify: async (p) => { await p.waitForSelector('a[href="/sign-in"], [data-cy="board-page"], [class*="BoardsPage"]'); } },
    { name: 'outline', url: BASES.outline, verify: async (p) => { await p.waitForSelector('a[href="/dashboard"], [data-test="home"], main'); } },
    { name: 'homepage', url: BASES.homepage, verify: async (p) => { await p.waitForSelector('main .is-dashboard, h1, #__nuxt'); } },
    { name: 'portainer', url: BASES.portainer, verify: async (p) => { await p.waitForSelector('a[href*="/home"], #portainer'); } },
    { name: 'dockge', url: BASES.dockge, verify: async (p) => { await p.waitForSelector('a[href="/login"], [data-theme], body'); } },
    { name: 'couchdb', url: BASES.couchdb, verify: async (p) => { await p.waitForSelector('body'); } },
    { name: 'jupyterhub', url: BASES.jupyterhub, verify: async (p) => { await p.waitForSelector('#login-main, a[href*="/hub/home"], .jp-Launcher-sectionHeader'); } },
    { name: 'kopia', url: BASES.kopia, verify: async (p) => { await p.waitForSelector('a[href="/snapshots"], body'); } },
    { name: 'litellm', url: BASES.litellm, verify: async (p) => { await p.waitForSelector('body'); } },
    { name: 'localai', url: BASES.localai, verify: async (p) => { await p.waitForSelector('body'); } },
  ];

  let failures = 0;
  for (const c of checks) {
    try {
      await visitAndVerify(page, c.name, c.url, c.verify);
    } catch (e) {
      failures++;
      console.error(`[fail] ${c.name}:`, e && e.message ? e.message : String(e));
      try { await page.screenshot({ path: screenshotPath(`${c.name}-error`) }); } catch (_) {}
    }
  }

  await browser.close();
  if (failures > 0) {
    console.error(`${failures} checks failed`);
    process.exit(1);
  }
})().catch(e => { console.error(e); process.exit(1); });
