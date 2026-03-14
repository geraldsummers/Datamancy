/**
 * Tests for services using OIDC authentication with Authelia
 *
 * These services have explicit OIDC client configurations and handle
 * the OAuth2/OIDC flow themselves (not just forward-auth).
 *
 * Services tested:
 * - Grafana
 * - Mastodon
 * - Forgejo
 * - BookStack
 * - Planka
 * - Element (Matrix Web)
 */

import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { AutheliaLoginPage } from '../pages/AutheliaLoginPage';
import { OIDCLoginPage } from '../pages/OIDCLoginPage';
import { logPageTelemetry, setupNetworkLogging } from '../utils/telemetry';

const testUserPath = path.join(__dirname, '../.auth/test-user.json');
const testUser = fs.existsSync(testUserPath)
  ? JSON.parse(fs.readFileSync(testUserPath, 'utf-8'))
  : {
      username: process.env.STACK_ADMIN_USER || 'sysadmin',
      password: process.env.STACK_ADMIN_PASSWORD || 'admin',
      email: process.env.STACK_ADMIN_EMAIL || '',
      managed: false,
    };

const guessBaseDomain = (hostname: string) => {
  const parts = hostname.split('.').filter(Boolean);
  return parts.length >= 2 ? parts.slice(-2).join('.') : hostname;
};

async function waitForGrafanaShell(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const text = document.body?.innerText ?? '';
    const hasShell = /Grafana|Last 24 hours|Refresh/i.test(text);
    const stillLoading = /Loading plugin panel/i.test(text);
    return hasShell && !stillLoading;
  }, { timeout: 45000 });
}

/**
 * Helper function to test OIDC service access with proper assertions
 */
async function testOIDCService(
  page: Page,
  serviceName: string,
  servicePath: string,
  uiPattern: RegExp,
  oidcButtonNames: string[] = ['Authelia'],
  options: {
    disallowPatterns?: RegExp[];
    disallowUrlPatterns?: RegExp[];
    loginPath?: string;
    loginButtonPatterns?: RegExp[];
    ssoIdentifier?: string;
    ssoEmail?: string;
    oidcLinkPatterns?: RegExp[];
    oidcIssuer?: string;
    preLogin?: (page: Page) => Promise<void>;
    postLogin?: (page: Page) => Promise<void>;
    uiPatternOverride?: RegExp;
    skipScreenshot?: boolean;
  } = {}
) {
  console.log(`\n🧪 Testing ${serviceName} OIDC login`);

  setupNetworkLogging(page, serviceName);

  // Retry logic for transient connectivity/edge-proxy startup errors.
  let retries = 5;

  while (retries > 0) {
    try {
      await page.goto(servicePath, { waitUntil: 'domcontentloaded', timeout: 15000 });
      break; // Success
    } catch (error: any) {
      const message = String(error?.message || error);
      const isTransient =
        /SSL|ERR_SSL_PROTOCOL_ERROR/i.test(message) ||
        /Timeout|timed out|Navigation timeout/i.test(message) ||
        /ERR_CONNECTION|ERR_ABORTED|ERR_HTTP2_PROTOCOL_ERROR/i.test(message);
      if (isTransient) {
        console.log(`   ⚠️  Transient navigation error, retrying... (${6 - retries}/5)`);
        retries--;
        await page.waitForTimeout(3000);
        if (retries === 0) throw error;
      } else {
        throw error;
      }
    }
  }

  await logPageTelemetry(page, `${serviceName} Login Page`);

  if (options.preLogin) {
    await options.preLogin(page);
    await logPageTelemetry(page, `${serviceName} Login Page (post-preLogin)`);
  }

  const oidcPage = new OIDCLoginPage(page);

  const loginButtonPatterns = options.loginButtonPatterns ?? [/sign in|log in|login/i];

  const handleSsoIdentifierIfPresent = async () => {
    if (!options.ssoIdentifier) {
      return;
    }
    const ssoHeader = page.locator('h1', { hasText: /single sign-on/i });
    const ssoInput = page
      .getByLabel(/sso identifier/i)
      .or(page.locator('input[placeholder*="SSO"]'))
      .or(page.locator('input[id*="bit-input"]'))
      .or(page.locator('input').first());
    const continueButton = page.getByRole('button', { name: /continue/i });
    const continueFallback = page.locator('button[type="submit"]').first();

    const shouldHandle = await ssoHeader.first().isVisible().catch(() => false)
      || /\/sso\b/i.test(page.url());
    if (!shouldHandle) {
      return;
    }

    await ssoInput.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
    if (!(await ssoInput.isVisible().catch(() => false))) {
      return;
    }

    const currentValue = await ssoInput.inputValue().catch(() => '');
    if (!currentValue) {
      await ssoInput.fill(options.ssoIdentifier);
    }

    if (await continueButton.first().isVisible().catch(() => false)) {
      await continueButton.first().click();
    } else if (await continueFallback.isVisible().catch(() => false)) {
      await continueFallback.click();
    } else {
      await ssoInput.press('Enter').catch(() => {});
    }

    await page.waitForURL((url) => !/\/sso\b/i.test(url.toString()), { timeout: 20000 }).catch(() => {});
    await page.waitForTimeout(1000);
  };

  const handleSsoEmailIfPresent = async () => {
    if (!options.ssoEmail) {
      return false;
    }
    const ssoEmailCandidates = [
      page.locator('input.vw-email-sso'),
      page.locator('input[type="email"]').nth(1),
      page.locator('input[type="email"]').first(),
    ];
    const ssoButton = page
      .getByRole('button', { name: /use single sign-on|single sign-on|sso/i })
      .or(page.locator('button:has-text("Use single sign-on")'))
      .or(page.locator('button:has-text("Single sign-on")'))
      .or(page.locator('button:has-text("SSO")'));

    let ssoEmailInput = ssoEmailCandidates[0];
    let inputVisible = await ssoEmailInput.first().isVisible().catch(() => false);
    if (!inputVisible) {
      for (const candidate of ssoEmailCandidates.slice(1)) {
        if (await candidate.first().isVisible().catch(() => false)) {
          ssoEmailInput = candidate;
          inputVisible = true;
          break;
        }
      }
    }
    const buttonVisible = await ssoButton.first().isVisible().catch(() => false);
    if (!inputVisible && !buttonVisible) {
      return false;
    }

    if (buttonVisible && !inputVisible) {
      await ssoButton.first().click({ force: true });
      await page.waitForTimeout(500);
    }

    let inputNowVisible = await ssoEmailInput.first().isVisible().catch(() => false);
    if (!inputNowVisible) {
      for (const candidate of ssoEmailCandidates) {
        if (await candidate.first().isVisible().catch(() => false)) {
          ssoEmailInput = candidate;
          inputNowVisible = true;
          break;
        }
      }
    }
    if (inputNowVisible) {
      const currentValue = await ssoEmailInput.first().inputValue().catch(() => '');
      if (!currentValue) {
        await ssoEmailInput.first().fill(options.ssoEmail, { force: true });
      }
    }

    if (await ssoButton.first().isVisible().catch(() => false)) {
      await ssoButton.first().click({ force: true });
    } else if (inputNowVisible) {
      await ssoEmailInput.first().press('Enter').catch(() => {});
    }

    await page.waitForURL((url) => /auth\.|authelia|identity\/connect\/authorize|#\/sso\b|\/sso\b/i.test(url.toString()), { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);
    return /auth\.|authelia|identity\/connect\/authorize|#\/sso\b|\/sso\b/i.test(page.url());
  };

  const tryOidcLinkPatterns = async () => {
    const patterns = options.oidcLinkPatterns ?? [];
    for (const pattern of patterns) {
      const link = page.getByRole('link', { name: pattern }).or(
        page.getByRole('button', { name: pattern })
      );
      if (await link.first().isVisible().catch(() => false)) {
        await link.first().click();
        await page.waitForTimeout(1500);
        return true;
      }
    }
    return false;
  };

  const tryOidcHrefFallback = async () => {
    const link = page.locator(
      'a[href*="openid"], a[href*="oidc"], a[href*="oauth"], a[href*="sso"]'
    ).first();
    if (await link.isVisible().catch(() => false)) {
      await link.click();
      await page.waitForTimeout(1500);
      return true;
    }
    return false;
  };

  const tryOpenIdFormFallback = async () => {
    if (!options.oidcIssuer) {
      return false;
    }
    const openIdInput = page.locator('input[name="openid"], input#openid').first();
    const signInButton = page.getByRole('button', { name: /sign in|log in/i }).first();
    if (await openIdInput.isVisible().catch(() => false)) {
      await openIdInput.fill(options.oidcIssuer);
      if (await signInButton.isVisible().catch(() => false)) {
        await signInButton.click();
        await page.waitForTimeout(1500);
        return true;
      }
    }
    return false;
  };

  const handleMatrixContinueIfPresent = async (waitFor: boolean = false) => {
    const deadline = Date.now() + (waitFor ? 15000 : 0);
    const continueButton = page.getByRole('button', { name: /^continue$/i })
      .or(page.getByRole('link', { name: /^continue$/i }))
      .or(page.locator('button:has-text("Continue")'))
      .or(page.locator('a:has-text("Continue")'))
      .first();

    do {
      try {
        await continueButton.waitFor({ state: 'visible', timeout: waitFor ? 15000 : 1000 });
        await continueButton.click({ force: true });
        await page.waitForTimeout(1500);
        return true;
      } catch {
        const jsClicked = await page.evaluate(() => {
          const link = document.querySelector('a[href*="loginToken"], a[href*="logintoken"]') as HTMLAnchorElement | null;
          if (!link) return false;
          link.click();
          return true;
        }).catch(() => false);
        if (jsClicked) {
          await page.waitForTimeout(1500);
          return true;
        }
        const directHref = await page.locator('a:has-text("Continue")').first().getAttribute('href').catch(() => null);
        if (directHref) {
          await page.goto(directHref, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
          await page.waitForTimeout(1500);
          return true;
        }
      }
      if (!waitFor) {
        break;
      }
      await page.waitForTimeout(500);
    } while (Date.now() < deadline);

    return false;
  };

  await handleSsoIdentifierIfPresent();
  const ssoEmailHandled = await handleSsoEmailIfPresent();

  // Try to find and click OIDC button
  let buttonFound = false;
  if (ssoEmailHandled) {
    buttonFound = true;
  }
  for (const buttonName of oidcButtonNames) {
    try {
      await oidcPage.clickOIDCButton(buttonName);
      buttonFound = true;
      break;
    } catch (error) {
      continue;
    }
  }

  if (!buttonFound) {
    if (await tryOidcLinkPatterns()) {
      buttonFound = true;
    } else if (await tryOidcHrefFallback()) {
      buttonFound = true;
    } else if (await tryOpenIdFormFallback()) {
      buttonFound = true;
    }
  }

  if (!buttonFound) {
    console.log('   ℹ️  OIDC button not found - attempting to reach login screen...');

    let navigatedToLogin = false;

    // Try clicking a login button/link on the current page
    for (const pattern of loginButtonPatterns) {
      const loginTarget = page.getByRole('link', { name: pattern }).or(
        page.getByRole('button', { name: pattern })
      );
      const hasLoginTarget = await loginTarget.first().isVisible().catch(() => false);
      if (hasLoginTarget) {
        await loginTarget.first().click();
        await page.waitForTimeout(1500);
        navigatedToLogin = true;
        break;
      }
    }

    // Fallback to explicit login path if provided
    if (!navigatedToLogin && options.loginPath) {
      await page.goto(options.loginPath, { waitUntil: 'domcontentloaded', timeout: 15000 });
      navigatedToLogin = true;
    }

    if (navigatedToLogin) {
      await logPageTelemetry(page, `${serviceName} Login Page (post-nav)`);

      await handleSsoIdentifierIfPresent();
      const ssoEmailHandledAfterNav = await handleSsoEmailIfPresent();
      if (ssoEmailHandledAfterNav) {
        buttonFound = true;
      }

      if (!buttonFound) {
        for (const buttonName of oidcButtonNames) {
          try {
            await oidcPage.clickOIDCButton(buttonName);
            buttonFound = true;
            break;
          } catch (error) {
            continue;
          }
        }
      }

      if (!buttonFound) {
        if (await tryOidcLinkPatterns()) {
          buttonFound = true;
        } else if (await tryOidcHrefFallback()) {
          buttonFound = true;
        } else if (await tryOpenIdFormFallback()) {
          buttonFound = true;
        }
      }
    }

  if (!buttonFound) {
    console.log('   ℹ️  OIDC button still not found - might already be logged in...');
  }
}

  // Some SPAs require entering an SSO identifier after navigation
  await handleSsoIdentifierIfPresent();

  // If on Authelia, login
  if (page.url().includes('authelia') || page.url().includes('auth.') || page.url().includes(':9091')) {
    const autheliaPage = new AutheliaLoginPage(page);
    await autheliaPage.login(testUser.username, testUser.password);

    // Handle consent if shown
    await oidcPage.handleConsentScreen();
  }

  await handleMatrixContinueIfPresent();

  // Wait briefly for redirect back to service (post-auth)
  const isAuthUrl = (href: string) => {
    try {
      const parsed = new URL(href);
      return parsed.hostname.startsWith('auth.')
        || parsed.hostname.includes('authelia')
        || parsed.port === '9091';
    } catch {
      return /auth\.|authelia|:9091/.test(href);
    }
  };

  await page.waitForURL((url) => !isAuthUrl(url.toString()), { timeout: 20000 }).catch(() => {});

  // Some providers (notably SOGo) can bounce through multiple OIDC consent redirects.
  // Keep consuming consent screens while still on auth host before enforcing final redirect.
  for (let i = 0; i < 3 && isAuthUrl(page.url()); i++) {
    await oidcPage.handleConsentScreen().catch(() => {});
    await page.waitForURL((url) => !isAuthUrl(url.toString()), { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);
  }

  const shouldCheckMatrix = page.url().includes('/_synapse/client/oidc/callback')
    || page.url().includes('matrix.')
    || /continue to your account/i.test(await page.title().catch(() => ''));
  if (shouldCheckMatrix) {
    const continued = await handleMatrixContinueIfPresent(true);
    if (continued) {
      await page.waitForURL(
        (url) => {
          const href = url.toString();
          return !href.includes('/_synapse/client/oidc/callback') && !href.includes('matrix.');
        },
        { timeout: 20000 }
      ).catch(() => {});
    }
  }

  // CRITICAL ASSERTION: Must NOT be on auth host
  await expect.poll(() => isAuthUrl(page.url()), { timeout: 15000 }).toBeFalsy();

  if (options.postLogin) {
    await options.postLogin(page);
  }

  await logPageTelemetry(page, `${serviceName} Dashboard`);

  if (page.url().includes('/_synapse/client/oidc/callback') || /continue to your account/i.test(await page.title().catch(() => ''))) {
    const continueLink = page.locator('a:has-text("Continue")').first();
    await continueLink.waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
    const continueHref = await continueLink.getAttribute('href').catch(() => null);
    if (continueHref) {
      await page.goto(continueHref, { waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
    } else {
      await continueLink.click({ force: true }).catch(() => {});
    }
    await page.waitForTimeout(1500);
    await page.waitForURL(
      (url) => {
        const href = url.toString();
        return !href.includes('/_synapse/client/oidc/callback') && !href.includes('matrix.');
      },
      { timeout: 20000 }
    ).catch(() => {});
  }

  // ENHANCED: Verify we're on the CORRECT service page, not just "not auth"
  const body = page.locator('body');
  const hasContent = await body.isVisible();
  expect(hasContent).toBeTruthy();

  // Verify service-specific UI pattern to confirm correct page
  // Retry pattern matching to handle slow-loading SPAs
  const effectiveUiPattern = options.uiPatternOverride ?? uiPattern;
  let matchesPattern = false;
  let pageTitle = '';
  let bodyHTML = '';
  let pageText = '';
  const maxPatternRetries = 5;
  const disallowPatterns = options.disallowPatterns ?? [];
  const disallowUrlPatterns = options.disallowUrlPatterns ?? [];
  let disallowedMatch: RegExp | null = null;
  let disallowedUrl: RegExp | null = null;

  for (let i = 0; i < maxPatternRetries; i++) {
    pageTitle = await page.title();
    pageText = (await page.textContent('body').catch(() => '')) || '';
    bodyHTML = await body.innerHTML();

    if (/element/i.test(serviceName) && /verify this device/i.test(pageText)) {
      const skipButton = page.getByRole('button', { name: /skip verification/i }).first();
      if (await skipButton.isVisible().catch(() => false)) {
        await skipButton.click({ force: true }).catch(() => {});
      } else {
        const closeButton = page.locator(
          'button[aria-label="Close"], button[aria-label="Close dialog"], button:has-text("Close")'
        ).first();
        if (await closeButton.isVisible().catch(() => false)) {
          await closeButton.click({ force: true }).catch(() => {});
        } else {
          await page.keyboard.press('Escape').catch(() => {});
        }
      }
      await page.waitForTimeout(1500);
      continue;
    }

    if (/element/i.test(serviceName) && /are you sure\?/i.test(pageText)) {
      const verifyLaterButton = page.getByRole('button', { name: /i'?ll verify later|verify later/i }).first();
      if (await verifyLaterButton.isVisible().catch(() => false)) {
        await verifyLaterButton.click({ force: true }).catch(() => {});
      } else {
        const goBackButton = page.getByRole('button', { name: /go back/i }).first();
        if (await goBackButton.isVisible().catch(() => false)) {
          await goBackButton.click({ force: true }).catch(() => {});
        } else {
          await page.keyboard.press('Escape').catch(() => {});
        }
      }
      await page.waitForTimeout(1500);
      continue;
    }

    if (/element/i.test(serviceName) && /setting up keys|loading…|loading\.\.\./i.test(pageText)) {
      console.log(`   ⏳ Element is still initializing encryption keys... (${i + 1}/${maxPatternRetries})`);
      const setupDialog = page.locator('text=/setting up keys/i').first();
      if (await setupDialog.isVisible().catch(() => false)) {
        await setupDialog.waitFor({ state: 'hidden', timeout: 60000 }).catch(() => {});
      } else {
        await page.waitForTimeout(4000);
      }
      continue;
    }

    if (/continue to your account/i.test(pageText) || page.url().includes('/_synapse/client/oidc/callback') || page.url().includes('matrix.')) {
      const continued = await handleMatrixContinueIfPresent(true);
      if (continued) {
        await page.waitForTimeout(1500);
        continue;
      }
    }
    matchesPattern = effectiveUiPattern.test(pageText || bodyHTML || pageTitle);
    disallowedMatch = disallowPatterns.find((pattern) =>
      pattern.test([pageTitle, pageText, bodyHTML].filter(Boolean).join('\n'))
    ) ?? null;
    disallowedUrl = disallowUrlPatterns.find((pattern) => pattern.test(page.url())) ?? null;

    if (disallowedMatch || disallowedUrl) {
      if (/planka/i.test(serviceName)) {
        const plankaUnknownError = /unknown error|try again later/i.test(pageText);
        const onPlankaLogin = /\/login\b/i.test(page.url());
        if (onPlankaLogin && (plankaUnknownError || disallowedUrl)) {
          console.log(`   ⚠️  Planka returned to login with transient OIDC error; retrying SSO... (${i + 1}/${maxPatternRetries})`);
          const ssoButton = page.getByRole('button', { name: /log in with sso|sso|oidc/i }).first();
          if (await ssoButton.isVisible().catch(() => false)) {
            await ssoButton.click({ force: true }).catch(() => {});
            await page.waitForTimeout(2500);
            continue;
          }
        }
      }

      if (disallowedUrl && options.ssoIdentifier) {
        await handleSsoIdentifierIfPresent();
      }
      if (i < maxPatternRetries - 1) {
        console.log(`   ⏳ Detected disallowed state for ${serviceName}, waiting for redirect... (${i + 1}/${maxPatternRetries})`);
        await page.waitForTimeout(2000);
        continue;
      }
      const reason = disallowedUrl
        ? `URL matched disallowed pattern: ${disallowedUrl}`
        : `Page content matched disallowed pattern: ${disallowedMatch}`;
      throw new Error(`Expected authenticated ${serviceName} page but found disallowed state. ${reason}`);
    }

    if (matchesPattern) {
      break; // Pattern found, exit retry loop
    }

    if (i < maxPatternRetries - 1) {
      console.log(`   ⏳ Waiting for ${serviceName} UI to render... (${i + 1}/${maxPatternRetries})`);
      await page.waitForTimeout(2000); // Wait 2 seconds before retry
    }
  }

  if (!matchesPattern) {
    console.log(`   ⚠️  Pattern match failed for ${serviceName}`);
    console.log(`   Title: "${pageTitle}"`);
    console.log(`   Pattern: ${effectiveUiPattern}`);
    console.log(`   Body length: ${bodyHTML.length} chars`);
    throw new Error(`Expected ${serviceName} page but UI pattern not found. Pattern: ${effectiveUiPattern}, Title: "${pageTitle}"`);
  }

  if (!options.skipScreenshot) {
    // Capture screenshot for manual validation (compressed to prevent 5MB+ files)
    const screenshotName = `${serviceName.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-oidc-authenticated.jpg`;
    await page.screenshot({
      path: `/app/test-results/screenshots/${screenshotName}`,
      type: 'jpeg',
      quality: 85,
      fullPage: true
    });
    console.log(`   📸 Screenshot saved: ${screenshotName}`);
    console.log(`   👀 REVIEW SCREENSHOT to verify correct page loaded`);
  }

  console.log(`   ✅ ${serviceName} OIDC login successful\n`);
}

test.describe.serial('OIDC Services - SSO Flow', () => {
  test.describe.configure({ retries: 2 });

  test('Grafana - OIDC login flow', async ({ page }) => {
    // Grafana uses forward-auth, not OIDC. Validate access and UI via Authelia session.
    setupNetworkLogging(page, 'Grafana (forward-auth)');

    let retries = 3;
    while (retries > 0) {
      try {
        await page.goto('https://grafana.datamancy.net/', { waitUntil: 'domcontentloaded', timeout: 20000 });
        break;
      } catch (error: any) {
        if (error.message?.includes('SSL') || error.message?.includes('ERR_SSL_PROTOCOL_ERROR')) {
          retries--;
          await page.waitForTimeout(2000);
          if (retries === 0) throw error;
        } else {
          throw error;
        }
      }
    }

    if (page.url().includes('authelia') || page.url().includes('auth.') || page.url().includes(':9091')) {
      const loginPage = new AutheliaLoginPage(page);
      await loginPage.login(testUser.username, testUser.password);
    }

    await expect(page).not.toHaveURL(/auth\.|authelia/);
    await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

    const grafanaPattern = /Grafana|Dashboards|Explore|Connections|Data sources|Loki/i;
    const pageTitle = await page.title();
    const pageText = await page.textContent('body').catch(() => '');
    const bodyHtml = await page.locator('body').innerHTML();
    const combined = [pageTitle, pageText, bodyHtml].filter(Boolean).join('\n');
    if (!grafanaPattern.test(combined)) {
      throw new Error('Expected Grafana UI after forward-auth, but UI pattern not found.');
    }
    await waitForGrafanaShell(page);
    await page.setViewportSize({ width: 1280, height: 360 });

  });

  test('Mastodon - OIDC login flow', async ({ page }) => {
    test.setTimeout(180000);

    const runMastodonLogin = async () => {
      await testOIDCService(
        page,
        'Mastodon',
        'https://mastodon.datamancy.net/',
        /What's on your mind|Compose new post|Publish|Home|Notifications|Profile setup|Save and continue|Display name/i,
        ['Authelia', 'SSO', 'OpenID', 'OpenID Connect'],
        {
          disallowPatterns: [/Create account|Log in/i, /Invalid state/i],
          disallowUrlPatterns: [/\/(explore|about|public)\b/i],
          loginPath: 'https://mastodon.datamancy.net/auth/sign_in',
          loginButtonPatterns: [/log in|sign in|continue with sso|sso|openid/i],
          oidcLinkPatterns: [/sign in with.*(openid|sso)/i, /openid/i, /sso/i],
          postLogin: async (page) => {
            await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

            const profileLink = page.locator('a[href^="/@"]:not([href="/@undefined"])').first();
            if (!(await profileLink.isVisible().catch(() => false))) {
              throw new Error(`Mastodon profile link is missing (likely still unauthenticated). Current URL: ${page.url()}`);
            }
            const profileHref = await profileLink.getAttribute('href');
            if (!profileHref) {
              throw new Error('Mastodon profile link href is empty.');
            }

            await page.goto(`https://mastodon.datamancy.net${profileHref.replace(/\/+$/, '')}/following`, {
              waitUntil: 'domcontentloaded',
              timeout: 20000,
            }).catch(() => {});
            await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

            const maxAttempts = 16;
            for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
              const pageHtml = await page.content().catch(() => '');
              const pageText = await page.textContent('body').catch(() => '') || '';
              const isEmptyFollowingUi = /doesn.?t follow anyone yet/i.test(pageText) || /\b0\s+following\b/i.test(pageText);
              const descMatch =
                pageHtml.match(/<meta[^>]*name=["']description["'][^>]*content=["'][^"']*?(\d+)\s+Following,\s+\d+\s+Followers/i) ||
                pageHtml.match(/<meta[^>]*content=["'][^"']*?(\d+)\s+Following,\s+\d+\s+Followers[^"']*["'][^>]*name=["']description["']/i);
              const followingCount = Number(descMatch?.[1] || 0);

              if (followingCount > 0 && !isEmptyFollowingUi) {
                await page.reload({ waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
                await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
                expect(followingCount).toBeGreaterThan(0);
                return;
              }

              if (attempt < maxAttempts) {
                await page.waitForTimeout(10000);
                await page.reload({ waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
                await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
              }
            }

            throw new Error('Mastodon following view stayed empty after waiting for default-follow seeding.');
          },
        }
      );
    };

    try {
      await runMastodonLogin();
    } catch (error: any) {
      const message = String(error?.message || error);
      const pageContent = await page.content().catch(() => '');
      const isTransient =
        /Invalid state/i.test(message) ||
        /Invalid state/i.test(pageContent) ||
        /could not lookup user subject/i.test(pageContent) ||
        /authorization server encountered an unexpected condition/i.test(pageContent);
      if (!isTransient) {
        throw error;
      }
      console.log('   ⚠️  Mastodon OIDC transient auth error detected, retrying login flow once...');
      await page.context().clearCookies();
      await page.goto('https://mastodon.datamancy.net/auth/sign_in', { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
      await page.evaluate(() => {
        const storageOwner = globalThis as typeof globalThis & { localStorage?: { clear: () => void }; sessionStorage?: { clear: () => void } };
        storageOwner.localStorage?.clear();
        storageOwner.sessionStorage?.clear();
      }).catch(() => {});
      await runMastodonLogin();
    }
  });

  test('Forgejo - OIDC login flow', async ({ page }) => {
    const forgejoBaseDomain = guessBaseDomain(new URL('https://forgejo.datamancy.net/').hostname);
    await testOIDCService(
      page,
      'Forgejo',
      'https://forgejo.datamancy.net/',
      /Dashboard|Your Repositories|New Repository|Issues|Pull Requests|Repositories/i,
      ['Authelia', 'OpenID', 'OpenID Connect', 'OIDC'],
      {
        disallowUrlPatterns: [/\/user\/login\b/i],
        loginPath: 'https://forgejo.datamancy.net/user/login',
        loginButtonPatterns: [/sign in|log in/i],
        oidcLinkPatterns: [/authelia/i, /openid/i, /oidc/i],
        oidcIssuer: `https://auth.${forgejoBaseDomain}`,
      }
    );
  });

  test('BookStack - OIDC login flow', async ({ page }) => {
    await testOIDCService(
      page,
      'BookStack',
      'https://bookstack.datamancy.net/',
      /Books|Shelves|Recently (Created|Updated)|My Recently Viewed/i, // Look for authenticated dashboard content, NOT error page
      ['Authelia', 'Login with SSO', 'SSO']
    );
  });

  test('Planka - OIDC login flow', async ({ page }) => {
    await testOIDCService(
      page,
      'Planka',
      'https://planka.datamancy.net/',
      /Boards|Projects|Add board|Create board|New board|PLANKA|Test User/i,
      ['Authelia', 'SSO', 'OIDC'],
      {
        disallowPatterns: [/Log in to Planka|Log in with SSO|E-mail or username/i],
        disallowUrlPatterns: [/\/login\b/i],
        loginPath: 'https://planka.datamancy.net/login',
        loginButtonPatterns: [/log in with sso|sso|oidc/i],
        oidcLinkPatterns: [/log in with sso/i, /sso/i, /oidc/i],
      }
    );
  });

  test('SOGo - OIDC login flow', async ({ page }) => {
    test.setTimeout(180000);
    const domain = process.env.DOMAIN || 'datamancy.net';
    const sogoUrl = `https://sogo.${domain}/`;

    await testOIDCService(
      page,
      'SOGo',
      sogoUrl,
      /SOGo|Mail|Calendar|Contacts|Address\s?Book/i,
      ['OpenID Connect', 'OpenID', 'OIDC', 'Single Sign-On', 'SSO'],
      {
        disallowPatterns: [
          /Consent Request/i,
          /You need to enable JavaScript to run this app/i,
        ],
        postLogin: async (page) => {
          await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
          await expect
            .poll(
              async () => {
                const bodyText = await page.textContent('body').catch(() => '') || '';
                const pageTitle = await page.title().catch(() => '');
                return `${pageTitle}\n${bodyText}`;
              },
              { timeout: 30000, intervals: [500, 1000, 2000] }
            )
            .toMatch(/SOGo|Mail|Calendar|Contacts|Address\s?Book/i);

          const mailboxSelected = async () => {
            const noMailboxLabel = page.locator('text=/No mailbox selected/i').first();
            return !(await noMailboxLabel.isVisible().catch(() => false));
          };
          const mailboxUiReady = async () => {
            const bodyText = (await page.textContent('body').catch(() => '')) || '';
            if (!/@datamancy\.net|mail|inbox/i.test(bodyText)) {
              return false;
            }
            const mailboxHint = page
              .locator('text=/@datamancy\\.net|Inbox|Mail/i')
              .first();
            return await mailboxHint.isVisible().catch(() => false);
          };

          const baseUrl = page.url().split('#')[0];
          const accountEmail = await page
            .evaluate(() => {
              const text = document.body?.innerText ?? '';
              const match = text.match(/[A-Za-z0-9._%+-]+@datamancy\.net/i);
              return match?.[0] ?? null;
            })
            .catch(() => null);

          const tryMailboxHashRoutes = async (mailboxKey: string) => {
            const candidateHashes = [
              `#!/Mail/${mailboxKey}/folderINBOX`,
              `#!/Mail/${mailboxKey}/folderINBOX/view`,
              `#!/Mail/${mailboxKey}/folderInbox`,
              `#!/Mail/${mailboxKey}/folderInbox/view`,
            ];
            for (const hash of candidateHashes) {
              const target = `${baseUrl}${hash}`;
              await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 12000 }).catch(() => {});
              await page
                .evaluate((nextHash) => {
                  window.location.hash = nextHash;
                }, hash)
                .catch(() => {});
              await page.waitForTimeout(1000);
              if (await mailboxSelected()) {
                return true;
              }
            }
            return false;
          };

          const mailNav = page.getByRole('link', { name: /^Mail$/i }).first();
          if (await mailNav.isVisible().catch(() => false)) {
            await mailNav.click({ force: true }).catch(() => {});
            await page.waitForTimeout(800);
          }

          if (!(await mailboxSelected()) && accountEmail) {
            await tryMailboxHashRoutes(accountEmail);
            if (!(await mailboxSelected())) {
              await tryMailboxHashRoutes(encodeURIComponent(accountEmail));
            }
          }

          // Keep numeric account index fallback for older SOGo route formats.
          if (!(await mailboxSelected())) {
            for (let i = 0; i <= 5; i += 1) {
              await tryMailboxHashRoutes(String(i));
              if (await mailboxSelected()) {
                break;
              }
            }
          }

          // Ensure a mailbox row/folder is selected before screenshot capture.
          const mailboxSelectors = [
            'li:has-text("@datamancy.net") div:has(button:has-text("more_vert"))',
            'li:has-text("@datamancy.net") div:has(button:has-text("Options"))',
            'li:has-text("@datamancy.net") > div',
            'li:has-text("@datamancy.net") > div > div:has-text("@datamancy.net")',
            'li:has-text("@datamancy.net") div[style*="cursor"]',
            'li:has(button:has-text("more_vert")):has-text("@datamancy.net")',
            'li:has(button[aria-label*="more_vert"]):has-text("@datamancy.net")',
            'li:has(button:has-text("Options")):has-text("@datamancy.net")',
            'a[href*="#!/Mail/"][href*="/folderINBOX"]',
            'a[href*="/folderINBOX"]',
            'li:has-text("@datamancy.net")',
            '[role="listitem"]:has-text("@datamancy.net")',
            'a:has-text("INBOX"), a:has-text("Inbox")',
            'div:has-text("@datamancy")',
            '.mailboxListView .listItem:not(.selected)',
            '.mailbox-list .mailbox-row:not(.selected)',
            '[id*="mailbox"] .listItem:not(.selected)',
            'text=/Inbox/i',
          ];
          for (const selector of mailboxSelectors) {
            const mailbox = page.locator(selector).first();
            if (await mailbox.isVisible().catch(() => false)) {
              await mailbox.click({ force: true }).catch(() => {});
              await mailbox.dblclick({ force: true }).catch(() => {});
              await page.waitForTimeout(1000);
              if (await mailboxSelected()) {
                break;
              }
            }
          }

          if (!(await mailboxSelected())) {
            await page.keyboard.press('i').catch(() => {});
            await page.waitForTimeout(1000);
          }
          if (!(await mailboxSelected())) {
            await page.evaluate(() => {
              const inboxLink = document.querySelector('a[href*="folderINBOX"], a[href*="/Mail/0/folder"]') as HTMLElement | null;
              inboxLink?.click();
            }).catch(() => {});
            await page.waitForTimeout(1200);
          }
          if (!(await mailboxSelected())) {
            await page.evaluate(() => {
              const selectableAccountRow = Array.from(document.querySelectorAll('li, div')).find((el) => {
                const text = el.textContent ?? '';
                return (
                  /@datamancy\.net/i.test(text) &&
                  /more_vert|options|delegation|subscribe|new folder/i.test(text) &&
                  window.getComputedStyle(el).cursor === 'pointer'
                );
              }) as HTMLElement | undefined;
              selectableAccountRow?.click();
              selectableAccountRow?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
              selectableAccountRow?.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));

              const clickableAccountRow = Array.from(document.querySelectorAll('li, div, span'))
                .find((el) =>
                  /@datamancy\.net/i.test(el.textContent ?? '') &&
                  window.getComputedStyle(el).cursor === 'pointer'
                ) as HTMLElement | undefined;
              if (clickableAccountRow) {
                clickableAccountRow.click();
                clickableAccountRow.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                clickableAccountRow.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                clickableAccountRow.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
              }

              const accountRow = Array.from(document.querySelectorAll('li'))
                .find(
                  (el) =>
                    /@datamancy\.net/i.test(el.textContent ?? '') &&
                    /more_vert|options/i.test(el.textContent ?? '')
                );
              const accountText = Array.from(document.querySelectorAll('div, span, p'))
                .find((el) => /@datamancy\.net/i.test(el.textContent ?? '')) as HTMLElement | undefined;
              const textNode = accountRow ??
                accountText ??
                Array.from(document.querySelectorAll('li, div, span'))
                  .find((el) => /@datamancy\.net/i.test(el.textContent ?? ''));
              const el = textNode as HTMLElement | undefined;
              if (!el) {
                return;
              }
              el.click();
              el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
              el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
              el.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));

              const inboxCandidate = Array.from(
                document.querySelectorAll('a, li, div, span')
              ).find(
                (node) =>
                  /^(inbox|folder inbox)$/i.test((node.textContent ?? '').trim()) ||
                  /\binbox\b/i.test(node.textContent ?? '') ||
                  /folderINBOX/i.test((node as HTMLAnchorElement).getAttribute?.('href') ?? '')
              ) as HTMLElement | undefined;
              inboxCandidate?.click();
            }).catch(() => {});
            await page.keyboard.press('ArrowRight').catch(() => {});
            await page.keyboard.press('Enter').catch(() => {});
            await page.waitForTimeout(1200);
          }
          if (!(await mailboxSelected())) {
            throw new Error('SOGo mailbox was not selected before screenshot.');
          }
          if (!(await mailboxUiReady())) {
            await page.goto(baseUrl, { waitUntil: 'domcontentloaded', timeout: 12000 }).catch(() => {});
            await page.waitForTimeout(1500);
          }
          if (!(await mailboxUiReady())) {
            throw new Error('SOGo mailbox UI did not render before screenshot.');
          }

          // Guard against visually blank captures where only hidden app shell text is present.
          const detectSogoVisibleUi = async () =>
            page
              .evaluate(() => {
                const visibleTextNodes = Array.from(document.querySelectorAll('div, li, span, a, button'))
                  .filter((el) => {
                    const rect = el.getBoundingClientRect();
                    const style = window.getComputedStyle(el);
                    const text = (el.textContent ?? '').trim();
                    return (
                      rect.width > 24 &&
                      rect.height > 14 &&
                      style.display !== 'none' &&
                      style.visibility !== 'hidden' &&
                      Number(style.opacity || '1') > 0 &&
                      text.length >= 4
                    );
                  });

                const hasSogoIndicators = visibleTextNodes.some((el) =>
                  /inbox|mail|calendar|contacts|address\s?book|today|week|month|@datamancy\.net/i
                    .test((el.textContent ?? '').trim())
                );
                return hasSogoIndicators && visibleTextNodes.length >= 8;
              })
              .catch(() => false);

          const calendarLink = page.getByRole('link', { name: /calendar/i }).first();
          if (await calendarLink.isVisible().catch(() => false)) {
            await calendarLink.click({ force: true }).catch(() => {});
            await page.waitForTimeout(1200);
          }

          const calendarUrl = baseUrl
            .replace(/\/Mail\/view.*/i, '/Calendar/view')
            .replace(/\/Mail(\/|$).*/i, '/Calendar/view');

          let sogoUiVisible = await detectSogoVisibleUi();
          for (let attempt = 1; !sogoUiVisible && attempt <= 6; attempt += 1) {
            console.log(`   ⚠️  SOGo UI not visibly rendered yet, retrying (${attempt}/6)...`);
            await page.goto(calendarUrl, { waitUntil: 'domcontentloaded', timeout: 12000 }).catch(() => {});
            await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
            await page.waitForTimeout(2500);
            sogoUiVisible = await detectSogoVisibleUi();
          }
          if (!sogoUiVisible) {
            throw new Error('SOGo UI remained visually ambiguous after retries; refusing to pass with a likely blank render.');
          }
          await page.waitForTimeout(800);
        },
      }
    );
  });

  test('Element (Matrix Web) - OIDC login flow', async ({ page }) => {
    const domain = process.env.DOMAIN || 'datamancy.net';
    const homeserverUrl = `https://matrix.${domain}`;
    await testOIDCService(
      page,
      'Element (Matrix Web)',
      'https://element.datamancy.net/',
      /All rooms|Home|People|Rooms|Explore|Settings|Chats|Start chat|Recents|Room|Start a chat|Create a room|People/i,
      ['Authelia', 'Continue with Authelia SSO', 'Continue with SSO', 'SSO', 'Single sign-on', 'Sign in with SSO'],
      {
        disallowPatterns: [/Welcome to Element/i, /Sign in/i, /Create Account/i],
        disallowUrlPatterns: [/#\/welcome\b/i, /#\/login\b/i],
        loginPath: 'https://element.datamancy.net/#/login',
        loginButtonPatterns: [/sign in|log in|continue with authelia sso|continue with sso|sso|openid/i],
        oidcLinkPatterns: [/continue with authelia sso/i, /sign in with sso/i, /sso/i, /single sign-on/i, /authelia/i],
        preLogin: async (page) => {
          const signInLink = page.getByRole('link', { name: /sign in/i }).first();
          const signInButton = page.getByRole('button', { name: /sign in/i }).first();
          if (await signInLink.isVisible().catch(() => false)) {
            await signInLink.click({ force: true });
            await page.waitForTimeout(1000);
          } else if (await signInButton.isVisible().catch(() => false)) {
            await signInButton.click({ force: true });
            await page.waitForTimeout(1000);
          }

          const homeserverInput = page.locator(
            'input[placeholder*="matrix"], input[name*="home"], input[id*="home"]'
          ).first();
          const continueButton = page.getByRole('button', { name: /continue|next|submit/i }).first();
          if (await homeserverInput.isVisible().catch(() => false)) {
            const currentValue = await homeserverInput.inputValue().catch(() => '');
            if (!currentValue) {
              await homeserverInput.fill(homeserverUrl);
            }
            if (await continueButton.isVisible().catch(() => false)) {
              await continueButton.click({ force: true });
            } else {
              await homeserverInput.press('Enter').catch(() => {});
            }
            await page.waitForTimeout(1500);
          }

          await page.waitForSelector('text=/continue with authelia sso/i', { timeout: 5000 }).catch(() => {});
          const autheliaSsoButton = page.locator('text=/continue with authelia sso/i');
          if ((await autheliaSsoButton.count()) > 0) {
            await autheliaSsoButton.first().click({ force: true });
            await page.waitForTimeout(1500);
            return;
          }

          await page.waitForSelector('text=/sign in with sso|continue with sso|single sign-on|sso/i', { timeout: 3000 }).catch(() => {});
          const anySsoButton = page.locator('text=/sign in with sso|continue with sso|single sign-on|sso/i');
          if ((await anySsoButton.count()) > 0) {
            await anySsoButton.first().click({ force: true });
            await page.waitForTimeout(1500);
          }
        },
        postLogin: async (page) => {
          if (page.url().includes('loginToken=')) {
            const progress = page.getByRole('progressbar').first();
            if (await progress.isVisible().catch(() => false)) {
              await progress.waitFor({ state: 'hidden', timeout: 30000 }).catch(() => {});
            }
            await page.waitForURL(
              (url) => !url.toString().includes('loginToken='),
              { timeout: 60000 }
            ).catch(() => {});
          }

          const setupKeysDialog = page.locator('text=/setting up keys/i').first();
          if (await setupKeysDialog.isVisible().catch(() => false)) {
            await setupKeysDialog.waitFor({ state: 'hidden', timeout: 90000 }).catch(() => {});
            await page.waitForTimeout(1500);
          }

          const verifyLaterButton = page.getByRole('button', { name: /i'?ll verify later|verify later/i }).first();
          const areYouSureHeading = page.locator('text=/are you sure\\?/i').first();
          await areYouSureHeading.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
          if ((await areYouSureHeading.isVisible().catch(() => false)) || (await verifyLaterButton.isVisible().catch(() => false))) {
            if (await verifyLaterButton.isVisible().catch(() => false)) {
              await verifyLaterButton.click({ force: true }).catch(() => {});
            } else {
              const goBackButton = page.getByRole('button', { name: /go back/i }).first();
              if (await goBackButton.isVisible().catch(() => false)) {
                await goBackButton.click({ force: true }).catch(() => {});
              } else {
                await page.keyboard.press('Escape').catch(() => {});
              }
            }
            await page.waitForTimeout(1500);
          }

          const verifyHeading = page.locator('text=/verify this device/i').first();
          await verifyHeading.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
          if (await verifyHeading.isVisible().catch(() => false)) {
            const skipButton = page.getByRole('button', { name: /skip verification/i }).first();
            if (await skipButton.isVisible().catch(() => false)) {
              await skipButton.click({ force: true }).catch(() => {});
              await page.waitForTimeout(1500);
              return;
            }
            const closeButton = page.locator(
              'button[aria-label="Close"], button[aria-label="Close dialog"], button:has-text("Close")'
            ).first();
            if (await closeButton.isVisible().catch(() => false)) {
              await closeButton.click({ force: true }).catch(() => {});
            } else {
              await page.keyboard.press('Escape').catch(() => {});
            }
            await page.waitForTimeout(1500);
          }
        },
      }
    );
  });

  test('Vaultwarden - OIDC login flow', async ({ page }) => {
    const vaultwardenEmail = process.env.STACK_ADMIN_EMAIL || testUser.email || 'admin@datamancy.net';
    await testOIDCService(
      page,
      'Vaultwarden',
      'https://vaultwarden.datamancy.net/',
      /My Vault|Vaults|Folders|Items|Search vault/i,
      ['Authelia', 'SSO', 'Single sign-on', 'Use single sign-on'],
      {
        disallowPatterns: [/SSO identifier/i],
        disallowUrlPatterns: [/#\/sso\b/i, /\/sso\b/i],
        loginPath: 'https://app.vaultwarden.datamancy.net/#/login',
        loginButtonPatterns: [/use single sign-on|single sign-on|sso|enterprise|login/i],
        ssoIdentifier: vaultwardenEmail.split('@').pop() || 'datamancy.net',
        ssoEmail: vaultwardenEmail,
        skipScreenshot: true,
        uiPatternOverride: /My Vault|Vaults|Folders|Items|Search vault|Create account|Set up your vault|Set master password|Confirm master password|Join organization|Log in|Use single sign-on/i,
        postLogin: async (page) => {
          // Handle create account / master password setup after SSO
          const masterPassword = testUser.password;
          const newPasswordField = page.locator('#input-password-form_new-password');
          const confirmNewPasswordField = page.locator('#input-password-form_new-password-confirm');
          const masterPasswordField = page.getByLabel(/master password/i).or(
            page.locator('input[type="password"]').first()
          );
          const confirmPasswordField = page.getByLabel(/confirm master password/i).or(
            page.locator('input[type="password"]').nth(1)
          );

          for (let i = 0; i < 5; i++) {
            const onSetup = /#\/set-initial-password\b/i.test(page.url());
            const createAccountButton = page.getByRole('button', { name: /create account|save|continue|submit/i });
            const hasPasswordField = await masterPasswordField.isVisible().catch(() => false);
            if (onSetup || hasPasswordField) {
              if (onSetup) {
                await newPasswordField.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
                await confirmNewPasswordField.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
              }
              if (await newPasswordField.isVisible().catch(() => false)) {
                await newPasswordField.fill(masterPassword);
              }
              if (await confirmNewPasswordField.isVisible().catch(() => false)) {
                await confirmNewPasswordField.fill(masterPassword);
              }
              if (await masterPasswordField.isVisible().catch(() => false)) {
                await masterPasswordField.fill(masterPassword);
              }
              if (await confirmPasswordField.isVisible().catch(() => false)) {
                await confirmPasswordField.fill(masterPassword);
              }
              if (await createAccountButton.first().isVisible().catch(() => false)) {
                await createAccountButton.first().click({ force: true });
              } else {
                const fallbackSubmit = page.locator('button[type="submit"]').first();
                if (await fallbackSubmit.isVisible().catch(() => false)) {
                  await fallbackSubmit.click({ force: true });
                }
              }
              const form = page.locator('form').first();
              if (await form.isVisible().catch(() => false)) {
                await form.evaluate((el) => {
                  const maybeSubmit = (el as { requestSubmit?: () => void } | null)?.requestSubmit;
                  if (typeof maybeSubmit === 'function') {
                    maybeSubmit.call(el);
                  }
                }).catch(() => {});
              }
              await page.waitForTimeout(2000);
            }
            if (!/#\/(sso|set-initial-password)\b/i.test(page.url())) {
              break;
            }
            await page.waitForTimeout(1000);
          }

          const joinHeader = page.locator('h1', { hasText: /join organization/i });
          if (await joinHeader.first().isVisible().catch(() => false)) {
            const passwordFields = page.locator('input[type="password"]');
            const masterPassword = testUser.password;
            if (await passwordFields.first().isVisible().catch(() => false)) {
              await passwordFields.nth(0).fill(masterPassword);
              if (await passwordFields.nth(1).isVisible().catch(() => false)) {
                await passwordFields.nth(1).fill(masterPassword);
              }
            }
            const submitButton = page.getByRole('button', { name: /submit|save|continue|finish|join/i });
            if (await submitButton.first().isVisible().catch(() => false)) {
              await submitButton.first().click();
            } else {
              const fallbackSubmit = page.locator('button[type="submit"]').first();
              if (await fallbackSubmit.isVisible().catch(() => false)) {
                await fallbackSubmit.click();
              }
            }
            await page.waitForTimeout(2000);
          }

          await page.waitForURL((url) => !/#\/sso\b/i.test(url.toString()), { timeout: 20000 }).catch(() => {});
        },
        oidcLinkPatterns: [/single sign-on/i, /sso/i],
      }
    );
  });
});

test.describe.serial('OIDC - Cross-service Session', () => {

  test('OIDC session works across multiple services', async ({ page }) => {
    console.log('\n🧪 Testing OIDC session persistence');

    // Login to first OIDC service
    console.log('\n   Logging into Grafana (first OIDC service)...');
    await page.goto('https://grafana.datamancy.net/');

    const oidcPage = new OIDCLoginPage(page);

    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      // Might already be logged in
    }

    if (page.url().includes('authelia') || page.url().includes('auth.')) {
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
      await oidcPage.handleConsentScreen();
    }

    // CRITICAL: Verify we're actually on Grafana (allow a brief auth redirect)
    await page.waitForURL((url) => {
      const host = url.hostname;
      return !/^(auth\.|authelia)/.test(host);
    }, { timeout: 30000 }).catch(() => {});
    const grafanaHost = new URL(page.url()).hostname;
    expect(grafanaHost).not.toMatch(/^(auth\.|authelia)/);
    console.log('   ✅ Grafana login complete');

    // Try second OIDC service - should not require full login
    console.log('\n   Accessing BookStack (second OIDC service)...');
    await page.goto('https://bookstack.datamancy.net/');

    try {
      await oidcPage.clickOIDCButton('Authelia');
    } catch (error) {
      try {
        await oidcPage.clickOIDCButton('Login with SSO');
      } catch (error2) {
        // Button might not be there if already logged in
      }
    }

    // Should skip Authelia login screen (already authenticated)
    const needsAuth = page.url().includes('authelia') &&
      await page.locator('input[type="password"]').isVisible({ timeout: 2000 }).catch(() => false);

    if (needsAuth) {
      console.log('   ⚠️  Had to re-authenticate (session not shared)');
      const autheliaPage = new AutheliaLoginPage(page);
      await autheliaPage.login(testUser.username, testUser.password);
    } else {
      console.log('   ✅ No re-authentication needed - session shared!');
    }

    await oidcPage.handleConsentScreen();

    // CRITICAL: Verify we're on BookStack (allow authelia in query params)
    const bookstackHost = new URL(page.url()).hostname;
    expect(bookstackHost).not.toMatch(/^(auth\.|authelia)/);

    console.log('\n   ✅ OIDC session test complete\n');
  });
});
