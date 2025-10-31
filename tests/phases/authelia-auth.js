// tests/phases/authelia-auth.js
// Phase 03: Authelia authentication (form fill, submit, consent)

const { screenshot, clickFirst, fail } = require('../lib/playwright-utils');
const { authelia } = require('../lib/selectors');
const config = require('../lib/config');

/**
 * Phase 03: Authelia Authentication
 * Fill credentials, submit, handle consent
 *
 * @param {Page} page - Playwright page object
 * @param {string} screenshotDir - Directory for screenshots
 * @throws {TestError} if authentication fails
 */
async function autheliaAuth(page, screenshotDir) {
  await screenshot(page, screenshotDir, '03-authelia-auth', 'login-form');

  // Fill username (using accessible selector from AUTHELIA_SELECTORS.md)
  try {
    await page.getByLabel(authelia.username).fill(
      config.credentials.username,
      { timeout: config.timeouts.selector }
    );
  } catch (e) {
    await fail(
      screenshotDir,
      '03-authelia-auth',
      `Failed to locate username field: ${e.message}`,
      page
    );
  }

  // Fill password (MUST use getByRole to avoid ambiguity with visibility toggle)
  try {
    await page.getByRole(
      authelia.passwordRole.role,
      { name: authelia.passwordRole.name }
    ).fill(
      config.credentials.password,
      { timeout: config.timeouts.selector }
    );
  } catch (e) {
    await fail(
      screenshotDir,
      '03-authelia-auth',
      `Failed to locate password field: ${e.message}`,
      page
    );
  }

  await screenshot(page, screenshotDir, '03-authelia-auth', 'credentials-filled');

  // Click sign in button
  const signInClicked = await clickFirst(
    page,
    authelia.signInButton,
    config.timeouts.selector
  );

  if (!signInClicked) {
    await fail(
      screenshotDir,
      '03-authelia-auth',
      'Could not find Sign in button',
      page
    );
  }

  // Wait for navigation after sign in (either to consent page or back to service)
  await page.waitForLoadState('networkidle', {
    timeout: config.timeouts.authRedirect
  }).catch(() => {
    // networkidle timeout is OK
  });

  // Additional wait for page to stabilize
  await page.waitForTimeout(config.timeouts.shortWait);

  // Handle consent/authorization page (first-time per client)
  const consentClicked = await clickFirst(
    page,
    authelia.consentButtons,
    config.timeouts.selector
  );

  if (consentClicked) {
    await screenshot(page, screenshotDir, '03-authelia-auth', 'consent-clicked');
    // Wait for redirect after consent
    await new Promise(resolve => setTimeout(resolve, config.timeouts.shortWait));
  } else {
    await screenshot(page, screenshotDir, '03-authelia-auth', 'no-consent');
  }
}

module.exports = { autheliaAuth };
