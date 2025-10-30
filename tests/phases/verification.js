// tests/phases/verification.js
// Phase 05-06: Post-auth login verification and homepage display

const { screenshot, clickFirst, fail, log } = require('../lib/playwright-utils');
const { getOidcSelectors, wizardSkip } = require('../lib/selectors');
const config = require('../lib/config');

/**
 * Phase 05: Login Verification
 * Handle post-auth OIDC clicks (some services need this)
 *
 * @param {Page} page - Playwright page object
 * @param {string} screenshotDir - Directory for screenshots
 * @param {Object} service - Service config object
 * @throws {TestError} if login verification fails
 */
async function loginVerification(page, screenshotDir, service) {
  if (!service.usesOAuth) {
    await screenshot(page, screenshotDir, '05-login', 'non-oidc-login-page');
    const hasLogin = await page.locator('input[type="password"], text=/sign in|log in/i')
      .count()
      .catch(() => 0);

    if (!hasLogin) {
      await fail(
        screenshotDir,
        '05-login',
        'Non-OIDC service did not show a recognizable login form',
        page
      );
    }
    return;
  }

  // Some apps show "Sign in with Authelia/SSO" AFTER returning from IdP
  const selectors = getOidcSelectors(service.name);
  const clicked = await clickFirst(page, selectors, 2500);

  if (clicked) {
    log(`[${service.name}] Post-auth OIDC click: ${clicked}`);
    await screenshot(page, screenshotDir, '05-login', 'post-oidc-clicked');

    await page.waitForLoadState('domcontentloaded', {
      timeout: config.timeouts.default
    }).catch(() => {});

    // Check if we're back on Authelia (consent page)
    if (page.url().includes(config.domains.authelia)) {
      await screenshot(page, screenshotDir, '05-login', 'returned-to-auth');

      // Click consent again
      const { authelia } = require('../lib/selectors');
      await clickFirst(page, authelia.consentButtons, 2000);

      // Wait for redirect back to service
      const { esc } = require('../lib/playwright-utils');
      await page.waitForURL(
        new RegExp(esc(service.name)),
        { timeout: config.timeouts.default }
      ).catch(() => {});
    }

    await screenshot(page, screenshotDir, '05-login', 'final');
  } else {
    await screenshot(page, screenshotDir, '05-login', 'no-oidc');
  }
}

/**
 * Phase 06: Homepage Display
 * Verify we're logged in by checking for UI markers
 *
 * @param {Page} page - Playwright page object
 * @param {string} screenshotDir - Directory for screenshots
 * @param {Object} service - Service config object
 * @throws {TestError} if homepage verification fails
 */
async function homepageDisplay(page, screenshotDir, service) {
  // Skip first-run wizards (best-effort)
  if (service.optionalWizards || service.usesOAuth) {
    await clickFirst(page, wizardSkip, 1200);
  }

  await screenshot(page, screenshotDir, '06-homepage-display');

  // Verify UI markers (for OIDC services)
  if (service.uiMarkers && service.uiMarkers.length && service.usesOAuth) {
    let markerFound = false;

    for (const selector of service.uiMarkers) {
      try {
        const count = await page.locator(selector).count({ timeout: 1200 });
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
      await fail(
        screenshotDir,
        '06-homepage-display',
        `Expected UI markers not found for ${service.name}`,
        page
      );
    }
  }
}

module.exports = {
  loginVerification,
  homepageDisplay,
};
