// tests/lib/playwright-utils.js
// Playwright utilities: logging, screenshots, browser setup

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const config = require('./config');

// =====================
// Logging
// =====================
const START_TS = Date.now();
const log = (...args) => console.log(new Date().toISOString(), ...args);
const warn = (...args) => console.warn(new Date().toISOString(), '[WARN]', ...args);
const error = (...args) => console.error(new Date().toISOString(), '[ERROR]', ...args);

function sinceStart() {
  return `${((Date.now() - START_TS) / 1000).toFixed(1)}s`;
}

async function phaseWrap(name, fn) {
  const t0 = Date.now();
  log(`▶️  PHASE START: ${name}`);
  try {
    const result = await fn();
    log(`✅ PHASE OK: ${name} (+${((Date.now() - t0) / 1000).toFixed(2)}s, t=${sinceStart()})`);
    return result;
  } catch (e) {
    error(`❌ PHASE FAIL: ${name} (+${((Date.now() - t0) / 1000).toFixed(2)}s, t=${sinceStart()}) :: ${e.message}`);
    throw e;
  }
}

// Heartbeat for long-running tests
let heartbeatInterval = null;
function startHeartbeat() {
  if (!heartbeatInterval) {
    heartbeatInterval = setInterval(() => {
      log(`💓 heartbeat t=${sinceStart()}`);
    }, config.logging.heartbeatMs);
  }
}

function stopHeartbeat() {
  if (heartbeatInterval) {
    clearInterval(heartbeatInterval);
    heartbeatInterval = null;
  }
}

// =====================
// File System
// =====================
function mkdirp(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function esc(str) {
  return str.replace(/[-/\\^$*+?.()|[\]{}]/g, '\\$&');
}

// =====================
// Screenshots
// =====================
async function screenshot(page, dir, phase, suffix = '') {
  const name = `${phase}${suffix ? '-' + suffix : ''}`;
  const out = path.join(dir, `${name}.jpg`);
  await page.screenshot({
    path: out,
    fullPage: config.screenshots.fullPage,
    type: 'jpeg',
    quality: config.screenshots.quality,
  });
  log(`📸 ${name}.jpg → ${out}`);
  return out;
}

async function screenshotOnError(page, dir, phase) {
  try {
    await screenshot(page, dir, phase, 'ERROR');
  } catch (e) {
    warn(`Failed to capture error screenshot: ${e.message}`);
  }
}

// =====================
// Browser Setup
// =====================
async function launchBrowser() {
  return await chromium.launch({
    headless: config.browser.headless,
    args: config.browser.args,
  });
}

async function createContext(browser) {
  return await browser.newContext({
    ignoreHTTPSErrors: true,
  });
}

function attachPageLogging(page, serviceName) {
  // Console logs from browser
  page.on('console', msg => {
    const type = msg.type().toUpperCase();
    log(`[${serviceName}] [BROWSER ${type}]`, msg.text());
  });

  // Page errors
  page.on('pageerror', e => {
    error(`[${serviceName}] [BROWSER PAGEERROR]`, e.message);
  });

  // Network logging (optional)
  if (config.logging.logNetwork) {
    page.on('request', req => {
      log(`[${serviceName}] → ${req.method()} ${req.url()}`);
    });
    page.on('response', res => {
      log(`[${serviceName}] ← ${res.status()} ${res.request().method()} ${res.url()}`);
    });
  }

  // Always log failed requests
  page.on('requestfailed', req => {
    error(`[${serviceName}] ✖ requestfailed ${req.method()} ${req.url()} :: ${req.failure()?.errorText}`);
  });
}

// =====================
// Selector Helpers
// =====================
async function clickFirst(page, selectors, timeout = 2500) {
  for (const selector of selectors) {
    const locator = page.locator(selector).first();
    try {
      // Wait for element to be visible
      await locator.waitFor({ state: 'visible', timeout });
      // Scroll into view if needed
      await locator.scrollIntoViewIfNeeded({ timeout });
      // Try normal click first
      try {
        await locator.click({ timeout: timeout / 2 });
      } catch (clickErr) {
        // Fallback to force click for stubborn elements
        await locator.click({ force: true, timeout });
      }
      return selector;
    } catch (e) {
      // Try next selector
    }
  }
  return null;
}

async function waitForAnySelector(page, selectors, timeout = 5000) {
  for (const selector of selectors) {
    try {
      const locator = page.locator(selector).first();
      await locator.waitFor({ timeout, state: 'visible' });
      return selector;
    } catch (e) {
      // Try next selector
    }
  }
  return null;
}

// =====================
// Error Handling
// =====================
class TestError extends Error {
  constructor(phase, message) {
    super(`[${phase}] ${message}`);
    this.phase = phase;
    this.name = 'TestError';
  }
}

async function fail(dir, phase, message, page) {
  await screenshotOnError(page, dir, phase);
  throw new TestError(phase, message);
}

// =====================
// Process Handlers
// =====================
function setupProcessHandlers() {
  process.on('exit', () => stopHeartbeat());
  process.on('SIGINT', () => {
    stopHeartbeat();
    process.exit(130);
  });
  process.on('uncaughtException', (e) => {
    error('uncaughtException', e.stack || e);
    process.exit(1);
  });
  process.on('unhandledRejection', (e) => {
    error('unhandledRejection', e.stack || e);
    process.exit(1);
  });
}

module.exports = {
  // Logging
  log,
  warn,
  error,
  sinceStart,
  phaseWrap,
  startHeartbeat,
  stopHeartbeat,

  // File system
  mkdirp,
  esc,

  // Screenshots
  screenshot,
  screenshotOnError,

  // Browser
  launchBrowser,
  createContext,
  attachPageLogging,

  // Selectors
  clickFirst,
  waitForAnySelector,

  // Error handling
  TestError,
  fail,

  // Process
  setupProcessHandlers,
};
