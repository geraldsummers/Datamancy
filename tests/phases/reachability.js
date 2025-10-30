// tests/phases/reachability.js
// Phase 01: HTTP reachability and initial page load

const { screenshot, fail } = require('../lib/playwright-utils');
const config = require('../lib/config');

/**
 * Phase 01: Reachability
 * Navigate to service URL and verify HTTP response
 *
 * @param {Page} page - Playwright page object
 * @param {string} screenshotDir - Directory for screenshots
 * @param {Object} service - Service config object
 * @throws {TestError} if service is unreachable or returns non-200
 */
async function reachability(page, screenshotDir, service) {
  const response = await page.goto(service.url, {
    timeout: config.timeouts.default,
    waitUntil: 'domcontentloaded',
  });

  await page.waitForLoadState('domcontentloaded', {
    timeout: config.timeouts.default,
  });

  // Wait for SPAs to render - give JS time to execute
  await page.waitForLoadState('networkidle', {
    timeout: config.timeouts.networkIdle,
  }).catch(() => {
    // networkidle can timeout for SPAs with long-polling, that's OK
  });

  // Extra time for rendering (especially for React/Vue apps)
  await new Promise(resolve => setTimeout(resolve, config.timeouts.shortWait));

  await screenshot(page, screenshotDir, '01-reachability');

  if (!response || !response.ok()) {
    const status = response ? response.status() : 'no response';
    await fail(screenshotDir, '01-reachability', `HTTP not OK (${status})`, page);
  }
}

module.exports = { reachability };
