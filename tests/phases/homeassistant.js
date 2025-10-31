// tests/phases/homeassistant.js
// Home Assistant specific testing phase

const { screenshot, clickFirst, fail, log } = require('../lib/playwright-utils');
const config = require('../lib/config');

// Helper: sleep
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

/**
 * Home Assistant Interaction Phase
 * Tests Home Assistant after forward-auth authentication
 *
 * @param {Page} page - Playwright page object
 * @param {string} screenshotDir - Directory for screenshots
 * @param {Object} service - Service config object
 * @throws {TestError} if Home Assistant interaction fails
 */
async function homeAssistantInteraction(page, screenshotDir, service) {
  log('[homeassistant] Starting Home Assistant interaction phase');

  // Wait for page to load fully
  await page.waitForLoadState('networkidle', {
    timeout: config.timeouts.networkIdle
  }).catch(() => log('[homeassistant] Network not idle, continuing'));

  await screenshot(page, screenshotDir, '07-ha-initial');

  // Check if we're on the Home Assistant UI or onboarding wizard
  const haLoaded = await page.locator('home-assistant').or(page.locator('ha-onboarding')).or(page.locator('text=/Welcome/i'))
    .count({ timeout: 5000 })
    .catch(() => 0);

  if (!haLoaded) {
    await fail(
      screenshotDir,
      '07-ha-initial',
      'Home Assistant UI did not load properly',
      page
    );
  }

  // Check if we're on onboarding page
  const isOnboarding = await page.locator('ha-onboarding').or(page.locator('text=/Create my smart home/i')).count().catch(() => 0);

  if (isOnboarding > 0) {
    log('[homeassistant] On onboarding wizard - skipping interaction (fresh install)');
    await screenshot(page, screenshotDir, '08-ha-onboarding');
    log('[homeassistant] Home Assistant onboarding page loaded successfully');
    return; // Exit early for onboarding
  }

  log('[homeassistant] Home Assistant main UI loaded');

  // Wait for sidebar to be visible
  await sleep(2000);
  await screenshot(page, screenshotDir, '08-ha-loaded');

  // Try to navigate to different sections to verify functionality

  // 1. Check Overview/Dashboard
  const overviewLink = page.locator('a[href="/lovelace/0"], text=/Overview/i, [aria-label*="Overview"]').first();
  const hasOverview = await overviewLink.count().catch(() => 0);

  if (hasOverview > 0) {
    log('[homeassistant] Clicking Overview');
    await overviewLink.click({ timeout: 3000 }).catch(() =>
      log('[homeassistant] Could not click Overview')
    );
    await sleep(1500);
    await screenshot(page, screenshotDir, '09-ha-overview');
  }

  // 2. Try to access Settings or Developer Tools
  const settingsMenuSelectors = [
    'a[href="/config/dashboard"]',
    'text=/Settings/i',
    'mwc-list-item:has-text("Settings")',
    '[aria-label*="Settings"]'
  ];

  const settingsClicked = await clickFirst(page, settingsMenuSelectors, 2000);
  if (settingsClicked) {
    log(`[homeassistant] Opened settings via: ${settingsClicked}`);
    await sleep(1500);
    await screenshot(page, screenshotDir, '10-ha-settings');
  }

  // 3. Try to access Developer Tools
  const devToolsSelectors = [
    'a[href="/developer-tools"]',
    'text=/Developer Tools/i',
    'mwc-list-item:has-text("Developer")'
  ];

  const devToolsClicked = await clickFirst(page, devToolsSelectors, 2000);
  if (devToolsClicked) {
    log(`[homeassistant] Opened Developer Tools via: ${devToolsClicked}`);
    await sleep(1500);
    await screenshot(page, screenshotDir, '11-ha-devtools');

    // Try to click States tab in developer tools
    const statesTab = page.locator('paper-tab:has-text("States"), mwc-tab:has-text("States")').first();
    const hasStates = await statesTab.count().catch(() => 0);

    if (hasStates > 0) {
      await statesTab.click({ timeout: 2000 }).catch(() => {});
      await sleep(1000);
      await screenshot(page, screenshotDir, '12-ha-states');
    }
  }

  // 4. Return to main dashboard
  const homeButton = page.locator('a[href="/lovelace/0"], mwc-list-item:has-text("Overview")').first();
  const hasHome = await homeButton.count().catch(() => 0);

  if (hasHome > 0) {
    await homeButton.click({ timeout: 2000 }).catch(() => {});
    await sleep(1000);
    await screenshot(page, screenshotDir, '13-ha-final');
  } else {
    await screenshot(page, screenshotDir, '13-ha-final');
  }

  log('[homeassistant] Home Assistant interaction phase complete');
}

module.exports = {
  homeAssistantInteraction,
};
