#!/usr/bin/env node
// tests/runners/test-homeassistant.js
// Dedicated Home Assistant test runner

const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const config = require('../lib/config');
const { getService } = require('../services/service-configs');
const { log, screenshot, mkdirp, startHeartbeat, stopHeartbeat } = require('../lib/playwright-utils');

// Test phases
const { reachability } = require('../phases/reachability');
const { redirection } = require('../phases/redirection');
const { autheliaAuth } = require('../phases/authelia-auth');
const { loginVerification, homepageDisplay } = require('../phases/verification');
const { homeAssistantInteraction } = require('../phases/homeassistant');

/**
 * Main test execution
 */
async function runHomeAssistantTest() {
  const service = getService('homeassistant');
  if (!service) {
    console.error('❌ Home Assistant service not found in service-configs.js');
    process.exit(1);
  }

  const screenshotDir = path.join(config.screenshots.rootDir, service.name);

  // Ensure screenshot directory exists
  mkdirp(screenshotDir);

  log(`\n${'='.repeat(60)}`);
  log(`🏠 Testing Home Assistant`);
  log(`URL: ${service.url}`);
  log(`Auth Type: ${service.authType || 'forward-auth'}`);
  log(`${'='.repeat(60)}\n`);

  const browser = await chromium.launch({
    headless: config.browser.headless,
    args: config.browser.args,
  });

  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    locale: 'en-US',
  });

  const page = await context.newPage();
  startHeartbeat();

  try {
    // Phase 01: Reachability
    log(`\n[Phase 01] Checking reachability: ${service.url}`);
    await reachability(page, screenshotDir, service);
    log('✓ Phase 01 complete: Service reachable');

    // Phase 02: Redirection (should redirect to Authelia)
    log('\n[Phase 02] Checking auth redirection');
    await redirection(page, screenshotDir, service);
    log('✓ Phase 02 complete: Redirected to Authelia');

    // Phase 03-04: Authelia authentication
    log('\n[Phase 03-04] Authenticating with Authelia');
    await autheliaAuth(page, screenshotDir, service);
    log('✓ Phase 03-04 complete: Authelia authentication successful');

    // Wait for redirect back to Home Assistant
    await page.waitForURL(
      new RegExp(service.name),
      { timeout: config.timeouts.authRedirect }
    ).catch(() => log('Warning: Did not detect redirect back to service'));

    // Phase 05: Skip login verification for forward-auth (auto-login via trusted networks)
    log('\n[Phase 05] Skipping login verification (forward-auth auto-login)');
    await screenshot(page, screenshotDir, '05-post-auth');
    log('✓ Phase 05 complete: Forward-auth successful');

    // Phase 06: Homepage display
    log('\n[Phase 06] Verifying homepage display');
    await homepageDisplay(page, screenshotDir, service);
    log('✓ Phase 06 complete: Homepage displayed');

    // Phase 07-13: Home Assistant specific interaction
    log('\n[Phase 07-13] Home Assistant interaction');
    await homeAssistantInteraction(page, screenshotDir, service);
    log('✓ Phase 07-13 complete: Home Assistant interaction successful');

    // Final screenshot
    await screenshot(page, screenshotDir, '99-test-complete');

    stopHeartbeat();

    log(`\n${'='.repeat(60)}`);
    log('✅ All tests PASSED for Home Assistant');
    log(`Screenshots saved to: ${screenshotDir}`);
    log(`${'='.repeat(60)}\n`);

    await browser.close();
    process.exit(0);

  } catch (error) {
    stopHeartbeat();

    log(`\n${'='.repeat(60)}`);
    log('❌ TEST FAILED');
    log(`Error: ${error.message}`);
    log(`${'='.repeat(60)}\n`);

    await screenshot(page, screenshotDir, '99-error-final').catch(() => {});
    await browser.close();
    process.exit(1);
  }
}

// Run if called directly
if (require.main === module) {
  runHomeAssistantTest().catch(err => {
    console.error('Fatal error:', err);
    process.exit(1);
  });
}

module.exports = { runHomeAssistantTest };
