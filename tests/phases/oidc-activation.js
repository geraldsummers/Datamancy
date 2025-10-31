// tests/phases/oidc-activation.js
// Phase 02: OIDC button detection and Authelia redirect

const { screenshot, clickFirst, fail, log } = require('../lib/playwright-utils');
const { getOidcSelectors } = require('../lib/selectors');
const config = require('../lib/config');

/**
 * Phase 02: Authelia Activation
 * Find and click OIDC/SSO button, wait for Authelia redirect
 *
 * @param {Page} page - Playwright page object
 * @param {string} screenshotDir - Directory for screenshots
 * @param {Object} service - Service config object
 * @throws {TestError} if Authelia redirect fails
 */
async function oidcActivation(page, screenshotDir, service) {
  // Skip for non-OAuth services
  if (!service.usesOAuth) {
    await screenshot(page, screenshotDir, '02-oidc-activation', 'skipped-non-oidc');
    return;
  }

  // Check if already on Authelia (some services auto-redirect)
  if (page.url().includes(config.domains.authelia)) {
    await screenshot(page, screenshotDir, '02-oidc-activation', 'auto-redirected');
    return;
  }

  // Landing page - need to click OIDC button
  await screenshot(page, screenshotDir, '02-oidc-activation', 'landing');

  // Wait for page to stabilize (SPAs need time to render)
  await page.waitForLoadState('networkidle', {
    timeout: config.timeouts.networkIdle
  }).catch(() => {});

  const selectors = getOidcSelectors(service.name);
  const clicked = await clickFirst(page, selectors, config.timeouts.oidcClick);

  if (clicked) {
    log(`[${service.name}] OIDC button clicked: ${clicked}`);
    await screenshot(page, screenshotDir, '02-oidc-activation', 'oidc-clicked');

    // Wait for OIDC redirect to prepare
    await new Promise(resolve => setTimeout(resolve, 1000));

    // Wait for Authelia redirect
    await page.waitForURL(
      new RegExp(config.domains.authelia),
      { timeout: config.timeouts.navigation }
    ).catch(() => {
      // Timeout is OK if URL didn't change - check manually below
    });

    // Fallback: If still not on Authelia, SPA button may need direct navigation
    if (!page.url().includes(config.domains.authelia)) {
      log(`[${service.name}] Button click didn't redirect, trying direct OAuth URL`);
      const currentDomain = new URL(page.url()).hostname.split('.')[0];
      const oauthUrl = `https://${currentDomain}.${config.domains.base}/oauth/oidc/login`;
      await page.goto(oauthUrl, { timeout: config.timeouts.navigation }).catch(() => {});
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  } else {
    await screenshot(page, screenshotDir, '02-oidc-activation', 'no-oidc-button');
  }

  // Final check: must be on Authelia now
  await screenshot(page, screenshotDir, '02-oidc-activation', 'final');
  if (!page.url().includes(config.domains.authelia)) {
    await fail(
      screenshotDir,
      '02-oidc-activation',
      'Did not reach Authelia (no redirect and no SSO button found)',
      page
    );
  }
}

module.exports = { oidcActivation };
