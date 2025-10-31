#!/usr/bin/env node
// tests/runners/test-forwardauth-single.js
// Run a single forward-auth service test with Authelia login

const path = require('path');
const fs = require('fs');
const {
  log,
  error,
  phaseWrap,
  mkdirp,
  launchBrowser,
  createContext,
  attachPageLogging,
  screenshot,
  setupProcessHandlers,
  startHeartbeat,
  stopHeartbeat,
} = require('../lib/playwright-utils');
const config = require('../lib/config');
const { getForwardAuthService } = require('../services/service-configs');
const { reachability } = require('../phases/reachability');
const { autheliaAuth } = require('../phases/authelia-auth');

setupProcessHandlers();

/**
 * Test a single forward-auth service
 * Forward-auth services are protected by Authelia at the reverse proxy level
 */
async function testForwardAuthService(browser, service) {
  log(`\n================= ${service.name.toUpperCase()} (FORWARD-AUTH) =================`);
  log(`[${service.name}] Target: ${service.url} | Auth=forward-auth via Authelia`);

  const context = await createContext(browser);
  const page = await context.newPage();
  attachPageLogging(page, service.name);

  const screenshotDir = path.join(config.screenshots.rootDir, service.name);
  mkdirp(screenshotDir);

  const phases = [];
  let status = 'fail';
  const errors = [];

  try {
    // Phase 01: Reachability - Will redirect to Authelia login
    await phaseWrap('01-reachability', () =>
      reachability(page, screenshotDir, service)
    );
    phases.push('reachability');
    await screenshot(page, screenshotDir, '01-after-reachability');

    // Phase 02: Authelia Authentication
    log(`[${service.name}] Detected Authelia login screen, authenticating...`);
    await phaseWrap('02-authelia-login', () =>
      autheliaAuth(page, screenshotDir)
    );
    phases.push('authelia-login');

    // Wait for redirect back to service
    await page.waitForLoadState('domcontentloaded', {
      timeout: config.timeouts.default
    }).catch(() => {});

    await screenshot(page, screenshotDir, '03-post-auth');

    // Phase 03: Verify service UI markers
    await phaseWrap('03-ui-verification', async () => {
      if (service.uiMarkers && service.uiMarkers.length) {
        let markerFound = false;

        for (const selector of service.uiMarkers) {
          try {
            const count = await page.locator(selector).count({ timeout: 3000 });
            if (count > 0) {
              markerFound = true;
              log(`[${service.name}] Found UI marker: ${selector}`);
              break;
            }
          } catch (e) {
            // Try next marker
          }
        }

        if (!markerFound) {
          throw new Error(`Expected UI markers not found for ${service.name}`);
        }
      }

      await screenshot(page, screenshotDir, '04-ui-verified');
    });
    phases.push('ui-verification');

    // Final screenshot
    await screenshot(page, screenshotDir, '99-final');
    status = 'pass';
    log(`✅ [${service.name}] PASS`);

  } catch (err) {
    errors.push(err.message);
    error(`❌ [${service.name}] FAIL — ${err.message}`);

    const errorLog = `Service: ${service.name}\nError: ${err.message}\nPhases completed: ${phases.join(', ')}`;
    fs.writeFileSync(path.join(screenshotDir, 'ERRORS.txt'), errorLog);
  } finally {
    // Emit NDJSON result
    const result = {
      ts: new Date().toISOString(),
      kind: 'service_result',
      service: service.name,
      status,
      phases,
      errors,
    };
    console.log(JSON.stringify(result));
    log(`[${service.name}] DONE status=${status} phases=${phases.join('>')}`);

    await context.close();
  }

  return { service: service.name, status, phases, errors };
}

// =====================
// Main
// =====================
(async () => {
  const serviceName = process.argv[2];

  if (!serviceName) {
    console.error('Usage: node test-forwardauth-single.js <service-name>');
    console.error('Example: node test-forwardauth-single.js homepage');
    process.exit(1);
  }

  const service = getForwardAuthService(serviceName);
  if (!service) {
    console.error(`Unknown forward-auth service: ${serviceName}`);
    console.error('Available: filebrowser, homeassistant, kopia, dockge, homepage');
    process.exit(1);
  }

  log('Starting forward-auth service test…');
  log(`Service: ${service.name}`);
  log(`Auth: Authelia forward-auth. User=${config.credentials.username}, headless=${config.browser.headless}`);

  mkdirp(config.screenshots.rootDir);
  startHeartbeat();

  const browser = await launchBrowser();
  const result = await testForwardAuthService(browser, service);
  await browser.close();

  stopHeartbeat();

  log('\n=== RESULT ===');
  const icon = result.status === 'pass' ? '✅' : '❌';
  log(`${icon} ${result.service}: ${result.status} (${result.phases.length} phases)`);

  process.exit(result.status === 'pass' ? 0 : 1);
})();
