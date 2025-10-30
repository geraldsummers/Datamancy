// tests/phases/redirection.js
// Phase 04: Redirect back to service after auth

const { screenshot, fail, esc } = require('../lib/playwright-utils');
const config = require('../lib/config');

/**
 * Phase 04: Redirection
 * Wait for redirect back to service after authentication
 *
 * @param {Page} page - Playwright page object
 * @param {string} screenshotDir - Directory for screenshots
 * @param {Object} service - Service config object
 * @throws {TestError} if redirect fails
 */
async function redirection(page, screenshotDir, service) {
  // Wait for URL to contain service name
  await page.waitForURL(
    new RegExp(esc(service.name)),
    { timeout: config.timeouts.authRedirect }
  ).catch(() => {
    // Timeout is OK - check manually below
  });

  await screenshot(page, screenshotDir, '04-redirection');

  // Verify we're back on the service
  const urlPattern = new RegExp(esc(service.name));
  if (!urlPattern.test(page.url())) {
    await fail(
      screenshotDir,
      '04-redirection',
      `Did not return to ${service.name} after auth (current URL: ${page.url()})`,
      page
    );
  }
}

module.exports = { redirection };
