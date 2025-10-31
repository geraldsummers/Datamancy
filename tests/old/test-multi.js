#!/usr/bin/env node
// tests/runners/test-multi.js
// Run multiple services sequentially (replaces test_web_ui.js)

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
const { getService, getServices, allServices } = require('../services/service-configs');
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
  // Parse arguments
  const args = process.argv.slice(2);
  let services;

  if (args.length === 0) {
    // No args = run all services
    services = allServices;
  } else if (args[0] === '--oidc-only') {
    // Only OIDC services
    services = getServices({ usesOAuth: true });
  } else if (args[0] === '--non-oidc-only') {
    // Only non-OIDC services
    services = getServices({ usesOAuth: false });
  } else {
    // Specific services by name
    services = args.map(name => getService(name)).filter(s => s !== null);
    if (services.length === 0) {
      console.error('No valid services specified');
      process.exit(1);
    }
  }

  log('Starting multi-service tests…');
  log(`Services: ${services.map(s => s.name).join(', ')}`);
  log(`Auth: Authelia/LDAP. User=${config.credentials.username}, headless=${config.browser.headless}`);

  mkdirp(config.screenshots.rootDir);
  startHeartbeat();

  const browser = await launchBrowser();

  const results = [];
  for (const service of services) {
    const result = await testService(browser, service);
    results.push(result);

    // Pause between services to be nice to IdP
    await new Promise(resolve =>
      setTimeout(resolve, config.execution.pauseBetweenServices)
    );
  }

  await browser.close();
  stopHeartbeat();

  // Summary
  log('\n=== SUMMARY ===');
  for (const result of results) {
    const icon = result.status === 'pass' ? '✅' : '❌';
    log(`${icon} ${result.service}: ${result.status} (${result.phases.length} phases)`);
  }

  // Save results
  const resultsPath = path.join(config.screenshots.rootDir, 'test-results.json');
  fs.writeFileSync(resultsPath, JSON.stringify(results, null, 2));
  log(`Results → ${resultsPath}`);

  // Exit with failure if any service failed
  const failedCount = results.filter(r => r.status !== 'pass').length;
  if (failedCount > 0) {
    error(`${failedCount} service(s) failed.`);
    process.exit(1);
  }

  process.exit(0);
})();
