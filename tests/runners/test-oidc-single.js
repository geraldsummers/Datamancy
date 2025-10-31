#!/usr/bin/env node
// tests/runners/test-single.js
// Run a single service test (replaces verify_one_service.js)

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
const { getService } = require('../services/service-configs');
const { reachability } = require('../phases/reachability');
const { oidcActivation } = require('../phases/oidc-activation');
const { autheliaAuth } = require('../phases/authelia-auth');
const { redirection } = require('../phases/redirection');
const { loginVerification, homepageDisplay } = require('../phases/verification');

setupProcessHandlers();

/**
 * Test a single service through all phases
 */
async function testService(browser, service) {
  log(`\n================= ${service.name.toUpperCase()} =================`);
  log(`[${service.name}] Target: ${service.url} | OIDC=${service.usesOAuth ? 'yes' : 'no'}`);

  const context = await createContext(browser);
  const page = await context.newPage();
  attachPageLogging(page, service.name);

  const screenshotDir = path.join(config.screenshots.rootDir, service.name);
  mkdirp(screenshotDir);

  const phases = [];
  let status = 'fail';
  const errors = [];

  try {
    // Phase 01: Reachability
    await phaseWrap('01-reachability', () =>
      reachability(page, screenshotDir, service)
    );
    phases.push('reachability');

    if (service.usesOAuth) {
      // Phase 02: OIDC Activation
      await phaseWrap('02-oidc-activation', () =>
        oidcActivation(page, screenshotDir, service)
      );
      phases.push('oidc-activation');

      // Phase 03: Authelia Authentication
      await phaseWrap('03-authelia-auth', () =>
        autheliaAuth(page, screenshotDir)
      );
      phases.push('authelia-auth');

      // Phase 04: Redirection
      await phaseWrap('04-redirection', () =>
        redirection(page, screenshotDir, service)
      );
      phases.push('redirection');
    } else {
      // Skip OAuth phases for non-OIDC services
      await screenshot(page, screenshotDir, '02-oidc-activation', 'skipped');
      phases.push('oidc-activation');
      await screenshot(page, screenshotDir, '03-authelia-auth', 'skipped');
      phases.push('authelia-auth');
      await screenshot(page, screenshotDir, '04-redirection', 'skipped');
      phases.push('redirection');
    }

    // Phase 05: Login Verification
    await phaseWrap('05-login', () =>
      loginVerification(page, screenshotDir, service)
    );
    phases.push('login');

    // Phase 06: Homepage Display
    await phaseWrap('06-homepage-display', () =>
      homepageDisplay(page, screenshotDir, service)
    );
    phases.push('homepage-display');

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
    console.error('Usage: node test-single.js <service-name>');
    console.error('Example: node test-single.js grafana');
    process.exit(1);
  }

  const service = getService(serviceName);
  if (!service) {
    console.error(`Unknown service: ${serviceName}`);
    process.exit(1);
  }

  log('Starting single service test…');
  log(`Service: ${service.name}`);
  log(`Auth: Authelia/LDAP. User=${config.credentials.username}, headless=${config.browser.headless}`);

  mkdirp(config.screenshots.rootDir);
  startHeartbeat();

  const browser = await launchBrowser();
  const result = await testService(browser, service);
  await browser.close();

  stopHeartbeat();

  log('\n=== RESULT ===');
  const icon = result.status === 'pass' ? '✅' : '❌';
  log(`${icon} ${result.service}: ${result.status} (${result.phases.length} phases)`);

  process.exit(result.status === 'pass' ? 0 : 1);
})();
