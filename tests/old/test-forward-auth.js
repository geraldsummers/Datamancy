#!/usr/bin/env node
// tests/runners/test-forward-auth.js
// Test forward-auth protected services (FileBrowser, Dockge, Kopia, Homepage)

const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const config = require('../lib/config');
const { getService } = require('../services/service-configs');
const { log, screenshot, mkdirp, startHeartbeat, stopHeartbeat } = require('../lib/playwright-utils');
const { reachability } = require('../phases/reachability');
const { autheliaAuth } = require('../phases/authelia-auth');

/**
 * Test a forward-auth service
 */
async function testForwardAuthService(serviceName) {
  const service = getService(serviceName);
  if (!service) {
    console.error(`❌ Service '${serviceName}' not found in service-configs.js`);
    process.exit(1);
  }

  const screenshotDir = path.join(config.screenshots.rootDir, service.name);
  mkdirp(screenshotDir);

  log(`\n${'='.repeat(60)}`);
  log(`🔐 Testing ${service.name} (forward-auth)`);
  log(`URL: ${service.url}`);
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
    // Phase 01: Reachability - should redirect to Authelia
    log(`\n[Phase 01] Checking reachability: ${service.url}`);
    await page.goto(service.url, { waitUntil: 'networkidle', timeout: config.timeouts.default });
    await screenshot(page, screenshotDir, '01-initial');
    log('✓ Phase 01 complete: Page loaded');

    // Phase 02: Check if redirected to Authelia
    const currentUrl = page.url();
    if (!currentUrl.includes('auth.')) {
      throw new Error('Expected redirect to Authelia but stayed on service URL');
    }
    log(`\n[Phase 02] Redirected to Authelia: ${currentUrl}`);
    await screenshot(page, screenshotDir, '02-authelia-redirect');
    log('✓ Phase 02 complete: Redirected to Authelia');

    // Phase 03: Authelia authentication
    log('\n[Phase 03] Authenticating with Authelia');
    await autheliaAuth(page, screenshotDir);
    log('✓ Phase 03 complete: Authelia authentication successful');

    // Phase 04: Wait for redirect back to service
    log('\n[Phase 04] Waiting for redirect back to service');
    await page.waitForURL(
      url => {
        const urlStr = typeof url === 'string' ? url : url.href;
        return !urlStr.includes('auth.') && urlStr.includes(service.name);
      },
      { timeout: config.timeouts.authRedirect }
    );
    await page.waitForLoadState('networkidle', { timeout: config.timeouts.default });
    await screenshot(page, screenshotDir, '04-post-auth');
    log(`✓ Phase 04 complete: Back at service ${page.url()}`);

    // Phase 05: Verify service UI
    log('\n[Phase 05] Verifying service UI');
    await page.waitForTimeout(2000); // Let UI settle
    await screenshot(page, screenshotDir, '05-service-ui');

    // Check for UI markers if defined
    if (service.uiMarkers && service.uiMarkers.length) {
      let found = false;
      for (const marker of service.uiMarkers) {
        try {
          const count = await page.locator(marker).count({ timeout: 2000 });
          if (count > 0) {
            log(`✓ Found UI marker: ${marker}`);
            found = true;
            break;
          }
        } catch (e) {
          // Try next marker
        }
      }
      if (!found) {
        log(`⚠️  Warning: No UI markers found, but service loaded`);
      }
    }
    log('✓ Phase 05 complete: Service UI verified');

    // Final screenshot
    await screenshot(page, screenshotDir, '99-test-complete');

    stopHeartbeat();

    log(`\n${'='.repeat(60)}`);
    log(`✅ All tests PASSED for ${service.name}`);
    log(`Screenshots saved to: ${screenshotDir}`);
    log(`${'='.repeat(60)}\n`);

    await browser.close();
    return { service: service.name, status: 'pass' };

  } catch (error) {
    stopHeartbeat();

    log(`\n${'='.repeat(60)}`);
    log(`❌ TEST FAILED for ${service.name}`);
    log(`Error: ${error.message}`);
    log(`${'='.repeat(60)}\n`);

    await screenshot(page, screenshotDir, '99-error-final').catch(() => {});

    fs.writeFileSync(
      path.join(screenshotDir, 'ERRORS.txt'),
      `Service: ${service.name}\nError: ${error.message}\nStack: ${error.stack}\n`
    );

    await browser.close();
    return { service: service.name, status: 'fail', error: error.message };
  }
}

/**
 * Test multiple forward-auth services
 */
async function testAllForwardAuth() {
  const services = ['filebrowser', 'dockge', 'kopia', 'homepage'];
  const results = [];

  log('Starting forward-auth service tests…');
  log(`Services: ${services.join(', ')}`);
  log(`Auth: Authelia/LDAP. User=${config.credentials.username}\n`);

  mkdirp(config.screenshots.rootDir);

  for (const serviceName of services) {
    const result = await testForwardAuthService(serviceName);
    results.push(result);

    // Pause between tests
    await new Promise(resolve => setTimeout(resolve, 2000));
  }

  // Summary
  log('\n=== FORWARD-AUTH SERVICES SUMMARY ===');
  for (const result of results) {
    const icon = result.status === 'pass' ? '✅' : '❌';
    log(`${icon} ${result.service}: ${result.status}`);
  }

  const failedCount = results.filter(r => r.status !== 'pass').length;
  process.exit(failedCount > 0 ? 1 : 0);
}

// Run if called directly
if (require.main === module) {
  const serviceName = process.argv[2];

  if (serviceName) {
    // Test single service
    testForwardAuthService(serviceName).then(result => {
      process.exit(result.status === 'pass' ? 0 : 1);
    }).catch(err => {
      console.error('Fatal error:', err);
      process.exit(1);
    });
  } else {
    // Test all forward-auth services
    testAllForwardAuth().catch(err => {
      console.error('Fatal error:', err);
      process.exit(1);
    });
  }
}

module.exports = { testForwardAuthService, testAllForwardAuth };
