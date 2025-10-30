// tests/webui.e2e.js
// Phase-driven, fail-fast WebUI smoke across your stack via Caddy.
// Loud, structured logs to STDOUT so `docker logs -f test-runner` stays informative.

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

// =====================
// Config
// =====================
const CREDENTIALS = {
  username: process.env.TEST_USERNAME || 'admin',
  password: process.env.TEST_PASSWORD || 'DatamancyTest2025!',
  email:    process.env.TEST_EMAIL    || 'admin@stack.local',
};

const DEFAULT_TIMEOUT = parseInt(process.env.PW_TIMEOUT || '30000', 10);
const HEADLESS        = !(process.env.HEADFUL === '1');
const HEARTBEAT_MS    = parseInt(process.env.HEARTBEAT_MS || '15000', 10);
const LOG_NETWORK     = process.env.LOG_NETWORK !== '0'; // set LOG_NETWORK=0 to quiet network logs
const SCREEN_DIR_ROOT = process.env.SCREEN_DIR || '/tests/screenshots';
// Kept for compatibility but no longer used in parallel mode; harmless if set.
const PAUSE_BETWEEN   = parseInt(process.env.PAUSE_BETWEEN || '0', 10);
const CONCURRENCY     = parseInt(process.env.CONCURRENCY || '4', 10);

// Services routed via Caddy (only include hosts you actually route!)
const SERVICES = [
  // OIDC via Authelia/LDAP
  { name: 'grafana',      url: 'https://grafana.stack.local',      usesOAuth: true,  uiMarkers: ['text=/Dashboards/i','text=/Explore/i'] },
  { name: 'open-webui',   url: 'https://open-webui.stack.local',   usesOAuth: true,  uiMarkers: ['text=/New Chat/i','text=/Models/i'] },
  { name: 'jupyterhub',   url: 'https://jupyterhub.stack.local',   usesOAuth: true,  uiMarkers: ['text=/jupyterhub/i','text=/Start My Server/i','text=/Spawning server/i','text=/Your server is starting/i'] },
  { name: 'outline',      url: 'https://outline.stack.local',      usesOAuth: true,  uiMarkers: ['text=/Documents/i','text=/Collections/i'] },
  { name: 'planka',       url: 'https://planka.stack.local',       usesOAuth: true,  uiMarkers: ['text=/Boards/i','text=/Projects/i'] },
  { name: 'vaultwarden',  url: 'https://vaultwarden.stack.local',  usesOAuth: true,  uiMarkers: ['text=/Vaultwarden/i','text=/Vault/i'] },

  // OIDC wired in compose for Nextcloud; others are reachability-only unless you route them behind Authelia.
  { name: 'nextcloud',    url: 'https://nextcloud.stack.local',    usesOAuth: true,  optionalWizards: true, uiMarkers: ['text=/Files/i','text=/Photos/i'] },
  { name: 'filebrowser',  url: 'https://filebrowser.stack.local',  usesOAuth: false, uiMarkers: ['text=/File Browser/i','text=/Sign in/i'] },
  { name: 'homeassistant',url: 'https://homeassistant.stack.local',usesOAuth: false, uiMarkers: ['text=/Home Assistant/i','text=/Overview/i'] },
  { name: 'kopia',        url: 'https://kopia.stack.local',        usesOAuth: false, uiMarkers: ['text=/Kopia/i','text=/Repository/i','text=/Snapshots/i'] },
];

// =====================
// Logging helpers
// =====================
const START_TS = Date.now();
const log  = (...a) => console.log(new Date().toISOString(), ...a);
const warn = (...a) => console.warn(new Date().toISOString(), '[WARN]', ...a);
const errl = (...a) => console.error(new Date().toISOString(), '[ERROR]', ...a);
function sinceStart() { return `${((Date.now()-START_TS)/1000).toFixed(1)}s`; }

async function phaseWrap(name, fn) {
  const t0 = Date.now();
  log(`▶️  PHASE START: ${name}`);
  try {
    const r = await fn();
    log(`✅ PHASE OK: ${name} (+${((Date.now()-t0)/1000).toFixed(2)}s, t=${sinceStart()})`);
    return r;
  } catch (e) {
    errl(`❌ PHASE FAIL: ${name} (+${((Date.now()-t0)/1000).toFixed(2)}s, t=${sinceStart()}) :: ${e.message}`);
    throw e;
  }
}

// Heartbeat so long runs show signs of life
const hb = setInterval(() => log(`💓 heartbeat t=${sinceStart()}`), HEARTBEAT_MS);
process.on('exit', () => clearInterval(hb));
process.on('SIGINT', () => { clearInterval(hb); process.exit(130); });
process.on('uncaughtException', (e) => { errl('uncaughtException', e.stack||e); process.exit(1); });
process.on('unhandledRejection', (e) => { errl('unhandledRejection', e.stack||e); process.exit(1); });

// =====================
 // Utils
// =====================
function mkdirp(p) { fs.mkdirSync(p, { recursive: true }); }
function esc(s) { return s.replace(/[-/\\^$*+?.()|[\]{}]/g, '\\$&'); }
function hostRe(service) { const h = new URL(service.url).hostname; return new RegExp(esc(h), 'i'); }

async function ss(page, dir, phase, suffix = '') {
  const name = `${phase}${suffix ? '-' + suffix : ''}`;
  const out = path.join(dir, `${name}.png`);
  await page.screenshot({ path: out, fullPage: true });
  log(`📸 ${name}.png → ${out}`);
}

async function fail(dir, phase, msg, page) {
  try { await ss(page, dir, `${phase}`, 'ERROR'); } catch {}
  const e = `[${phase}] ${msg}`;
  errl(e);
  throw new Error(e);
}

async function clickFirst(page, selectors, timeout = 2500) {
  for (const sel of selectors) {
    const loc = page.locator(sel).first();
    try {
      await loc.waitFor({ state: 'visible', timeout });
      // trial click first to avoid detachment surprises
      await loc.click({ timeout, trial: true });
      await loc.click({ timeout });
      return sel;
    } catch {}
  }
  return null;
}

// =====================
// OIDC selectors
// =====================
const OIDC_BUTTONS_GENERIC = [
  'button:has-text("Authelia")',
  'a:has-text("Authelia")',
  'text=/single sign on/i',
  'text=/sign in with/i',
  'text=/continue with/i',
  'text=/oidc/i',
  'text=/open id/i',
  'text=/oauth/i',
  'text=/sso/i',
  'button:has-text("SSO")',
];

const OIDC_BUTTONS_BY_SERVICE = {
  grafana: [
    'button:has-text("Sign in with Authelia")',
    'button:has-text("Sign in with OAuth")',
  ],
  'open-webui': [
    'button:has-text("Continue with Authelia")',
    'button:has-text("Login with OIDC")',
  ],
  jupyterhub: [
    'button:has-text("Sign in with")',
    'a:has-text("Sign in with")',
  ],
  outline: [
    'button:has-text("Continue with Authelia")',
    'button:has-text("Continue with OpenID")',
  ],
  planka: [
    'button:has-text("Sign in with OpenID")',
    'button:has-text("Sign in with Authelia")',
  ],
  vaultwarden: [
    'button:has-text("Single Sign-On")',
    'button:has-text("Enterprise Single Sign-On")',
  ],
  nextcloud: [
    'button:has-text("Log in with OpenID")',
    'button:has-text("Log in with Authelia")',
  ],
};

async function oidcClickIfPresent(page, dir, phase, serviceName) {
  const candidates = [
    ...(OIDC_BUTTONS_BY_SERVICE[serviceName] || []),
    ...OIDC_BUTTONS_GENERIC,
  ];
  const clicked = await clickFirst(page, candidates, 2500);
  if (clicked) {
    log(`[${serviceName}] OIDC click: ${clicked}`);
    await ss(page, dir, phase, 'oidc-clicked');
    return true;
  }
  await ss(page, dir, phase, 'no-oidc');
  return false;
}

// =====================
// Phases
// =====================
async function reachability(page, dir, service) {
  const resp = await page.goto(service.url, { timeout: DEFAULT_TIMEOUT, waitUntil: 'domcontentloaded' });
  await ss(page, dir, '01-reachability');
  if (!resp || !resp.ok()) {
    await fail(dir, '01-reachability', `HTTP not OK (${resp ? resp.status() : 'no response'})`, page);
  }
}

async function autheliaActivation(page, dir, service) {
  if (!service.usesOAuth) {
    await ss(page, dir, '02-authelia-activation', 'skipped-non-oidc');
    return;
  }
  if (!/auth\.stack\.local/i.test(page.url())) {
    await ss(page, dir, '02-authelia-activation', 'landing');
    await oidcClickIfPresent(page, dir, '02-authelia-activation', service.name);
    await page.waitForURL(/auth\.stack\.local/i, { timeout: DEFAULT_TIMEOUT });
  }
  await ss(page, dir, '02-authelia-activation', 'on-auth');
  if (!/auth\.stack\.local/i.test(page.url())) {
    await fail(dir, '02-authelia-activation', 'Did not reach Authelia (no redirect and no SSO control)', page);
  }
}

async function autheliaAuthentication(page, dir, service) {
  await ss(page, dir, '03-authelia-authentication', 'login-form');
  try {
    // Accessible selectors (Authelia UI usually exposes these properly)
    await page.getByLabel('Username').fill(CREDENTIALS.username, { timeout: 5000 });
    // Try common patterns for password field
    try {
      await page.getByLabel('Password').fill(CREDENTIALS.password, { timeout: 3000 });
    } catch {
      await page.locator('input[type="password"]').first().fill(CREDENTIALS.password, { timeout: 3000 });
    }
  } catch (e) {
    await fail(dir, '03-authelia-authentication', `Failed to locate username/password fields: ${e.message}`, page);
  }
  await ss(page, dir, '03-authelia-authentication', 'credentials-filled');

  const clicked = await clickFirst(page, [
    'button:has-text("Sign in")',
    'button[type="submit"]',
    'text=/sign in/i',
  ], 5000);
  if (!clicked) {
    await fail(dir, '03-authelia-authentication', 'Could not find Sign in button', page);
  }

  // After submit we either remain on IdP for consent or bounce back to the app host.
  const appHost = hostRe(service);
  await Promise.race([
    page.waitForURL(/auth\.stack\.local/i, { timeout: DEFAULT_TIMEOUT }),
    page.waitForURL(appHost, { timeout: DEFAULT_TIMEOUT }),
  ]).catch(() => {});

  // Consent/authorize (first time per client)
  if (/auth\.stack\.local/i.test(page.url())) {
    const consent = await clickFirst(page, [
      'button:has-text("ACCEPT")',      // Authelia uses all-caps ACCEPT
      'button:has-text("Authorize")',
      'button:has-text("Allow")',
      'button:has-text("Approve")',
      'button:has-text("Confirm")',
      'text=/accept/i',
      'text=/continue/i',
    ], 5000);
    if (consent) await ss(page, dir, '03-authelia-authentication', 'consent-clicked');
    // Expect to leave IdP for the app host
    await page.waitForURL(appHost, { timeout: DEFAULT_TIMEOUT }).catch(() => {});
  } else {
    await ss(page, dir, '03-authelia-authentication', 'no-consent');
  }
}

async function redirection(page, dir, service) {
  const appHost = hostRe(service);
  await page.waitForURL(appHost, { timeout: DEFAULT_TIMEOUT }).catch(() => {});
  await ss(page, dir, '04-redirection');
  if (!appHost.test(page.url())) {
    await fail(dir, '04-redirection', `Did not return to ${service.name} after auth`, page);
  }
}

async function login(page, dir, service) {
  if (!service.usesOAuth) {
    await ss(page, dir, '05-login', 'non-oidc-login-page');
    try {
      await page.locator('input[type="password"], text=/sign in|log in/i').first()
        .waitFor({ state: 'visible', timeout: DEFAULT_TIMEOUT });
    } catch {
      await fail(dir, '05-login', 'Non-OIDC service did not show a recognizable login form', page);
    }
    return;
  }

  // Some apps show a "Sign in with Authelia/SSO" page AFTER returning from IdP
  const clicked = await oidcClickIfPresent(page, dir, '05-login', service.name);
  if (clicked) {
    const appHost = hostRe(service);
    await Promise.race([
      page.waitForURL(/auth\.stack\.local/i, { timeout: DEFAULT_TIMEOUT }),
      page.waitForURL(appHost, { timeout: DEFAULT_TIMEOUT }),
    ]).catch(() => {});
    if (/auth\.stack\.local/i.test(page.url())) {
      await ss(page, dir, '05-login', 'returned-to-auth');
      await clickFirst(page, [
        'button:has-text("ACCEPT")',
        'button:has-text("Authorize")',
        'button:has-text("Allow")',
        'button:has-text("Approve")',
        'button:has-text("Confirm")',
        'text=/accept/i',
        'text=/continue/i',
      ], 2000);
      await page.waitForURL(appHost, { timeout: DEFAULT_TIMEOUT }).catch(() => {});
    }
    await ss(page, dir, '05-login', 'post-oidc-click');
  } else {
    await ss(page, dir, '05-login', 'no-oidc');
  }
}

async function homepageDisplay(page, dir, service) {
  // Best-effort: clear first-run wizards
  if (service.optionalWizards || service.usesOAuth) {
    await clickFirst(page, [
      'button:has-text("Skip")',
      'button:has-text("Next")',
      'button:has-text("Finish")',
      'button:has-text("Done")',
      'button:has-text("Continue")',
      'text=/skip getting started/i',
    ], 1200);
  }
  await ss(page, dir, '06-homepage-display');

  // Mild UI assertions for OIDC apps
  if (service.uiMarkers && service.uiMarkers.length && service.usesOAuth) {
    let ok = false;
    for (const sel of service.uiMarkers) {
      try {
        await page.locator(sel).first().waitFor({ state: 'visible', timeout: 2000 });
        ok = true; break;
      } catch {}
    }
    if (!ok) {
      await fail(dir, '06-homepage-display', `Expected UI markers not found for ${service.name}`, page);
    }
  }
}

// =====================
// Test harness
// =====================
async function testService(browser, service) {
  log(`\n================= ${service.name.toUpperCase()} =================`);
  log(`[${service.name}] Target: ${service.url} | OIDC=${service.usesOAuth ? 'yes' : 'no'}`);

  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();

  // Wire verbose browser/Network logs
  context.on('page', p => log(`[${service.name}] new page: ${p.url()}`));
  page.on('console', msg => {
    const t = msg.type().toUpperCase();
    log(`[${service.name}] [BROWSER ${t}]`, msg.text());
  });
  page.on('pageerror', e => errl(`[${service.name}] [BROWSER PAGEERROR]`, e.message));
  if (LOG_NETWORK) {
    page.on('request', req => log(`[${service.name}] → ${req.method()} ${req.url()}`));
    page.on('response', res => log(
      `[${service.name}] ← ${res.status()} ${res.request().method()} ${res.url()}`
    ));
    page.on('requestfailed', req => errl(
      `[${service.name}] ✖ requestfailed ${req.method()} ${req.url()} :: ${req.failure()?.errorText}`
    ));
  } else {
    page.on('requestfailed', req => errl(
      `[${service.name}] ✖ requestfailed ${req.method()} ${req.url()} :: ${req.failure()?.errorText}`
    ));
  }

  const screenshotDir = path.join(SCREEN_DIR_ROOT, service.name);
  mkdirp(screenshotDir);

  const phases = [];
  let status = 'fail';
  const errors = [];

  try {
    await phaseWrap('01-reachability',           () => reachability(page, screenshotDir, service)); phases.push('reachability');

    if (service.usesOAuth) {
      await phaseWrap('02-authelia-activation',  () => autheliaActivation(page, screenshotDir, service)); phases.push('authelia-activation');
      await phaseWrap('03-authelia-authentication', () => autheliaAuthentication(page, screenshotDir, service));   phases.push('authelia-authentication');
      await phaseWrap('04-redirection',          () => redirection(page, screenshotDir, service));        phases.push('redirection');
    } else {
      await ss(page, screenshotDir, '02-authelia-activation', 'skipped'); phases.push('authelia-activation');
      await ss(page, screenshotDir, '03-authelia-authentication', 'skipped'); phases.push('authelia-authentication');
      await ss(page, screenshotDir, '04-redirection', 'skipped'); phases.push('redirection');
    }

    await phaseWrap('05-login',                  () => login(page, screenshotDir, service));             phases.push('login');
    await phaseWrap('06-homepage-display',       () => homepageDisplay(page, screenshotDir, service));   phases.push('homepage-display');

    await ss(page, screenshotDir, '99-final');
    status = 'pass';
    log(`✅ [${service.name}] PASS`);
  } catch (err) {
    errors.push(err.message);
    errl(`❌ [${service.name}] FAIL — ${err.message}`);
    const errorLog = `Service: ${service.name}\nError: ${err.message}\nPhases completed: ${phases.join(', ')}`;
    fs.writeFileSync(path.join(screenshotDir, 'ERRORS.txt'), errorLog);
  } finally {
    // Emit NDJSON result (easy to parse in CI)
    const result = { ts: new Date().toISOString(), kind: 'service_result', service: service.name, status, phases, errors };
    console.log(JSON.stringify(result));
    // Also persist per-service result to avoid any aggregator race in other tooling
    fs.writeFileSync(path.join(screenshotDir, 'result.json'), JSON.stringify(result, null, 2));
    log(`[${service.name}] DONE status=${status} phases=${phases.join('>')}`);
    await context.close();
  }

  return { service: service.name, status, phases, errors };
}

async function runPool(browser, services, concurrency) {
  const results = [];
  const queue = services.slice(); // shallow copy
  async function worker(id) {
    while (queue.length) {
      const svc = queue.shift();
      if (!svc) return;
      log(`🧵 worker-${id} → ${svc.name}`);
      const r = await testService(browser, svc);
      results.push(r);
      if (PAUSE_BETWEEN > 0) {
        await new Promise(res => setTimeout(res, PAUSE_BETWEEN));
      }
    }
  }
  const workers = Array.from({ length: Math.max(1, concurrency) }, (_, i) => worker(i + 1));
  await Promise.all(workers);
  return results;
}

// =====================
// Main
// =====================
(async () => {
  log('Starting WebUI phase tests…');
  log(`Auth: Authelia/LDAP. User=${CREDENTIALS.username}, headless=${HEADLESS}, timeout=${DEFAULT_TIMEOUT}ms, concurrency=${CONCURRENCY}`);
  mkdirp(SCREEN_DIR_ROOT);

  const browser = await chromium.launch({
    headless: HEADLESS,
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  });

  const results = await runPool(browser, SERVICES, CONCURRENCY);

  await browser.close();

  log('\n=== SUMMARY ===');
  for (const r of results) {
    const icon = r.status === 'pass' ? '✅' : '❌';
    log(`${icon} ${r.service}: ${r.status} (${r.phases.length} phases)`);
  }

  fs.writeFileSync(path.join(SCREEN_DIR_ROOT, 'test-results.json'), JSON.stringify(results, null, 2));
  log(`Results → ${path.join(SCREEN_DIR_ROOT, 'test-results.json')}`);

  // Non-zero exit on any failure to fail CI fast
  const failed = results.filter(r => r.status !== 'pass').length;
  if (failed > 0) {
    errl(`${failed} service(s) failed.`);
    process.exit(1);
  }
  process.exit(0);
})();
