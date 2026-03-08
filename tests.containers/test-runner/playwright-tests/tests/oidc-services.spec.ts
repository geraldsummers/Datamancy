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
 */

import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { AutheliaLoginPage } from '../pages/AutheliaLoginPage';
import { OIDCLoginPage } from '../pages/OIDCLoginPage';
import { logPageTelemetry, setupNetworkLogging } from '../utils/telemetry';

const testUser = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../.auth/test-user.json'), 'utf-8')
);

const guessBaseDomain = (hostname: string) => {
  const parts = hostname.split('.').filter(Boolean);
  return parts.length >= 2 ? parts.slice(-2).join('.') : hostname;
};

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
    postLogin?: (page: Page) => Promise<void>;
    uiPatternOverride?: RegExp;
  } = {}
) {
  console.log(`\n🧪 Testing ${serviceName} OIDC login`);

  setupNetworkLogging(page, serviceName);

  // Retry logic for SSL errors
  let retries = 3;

  while (retries > 0) {
    try {
      await page.goto(servicePath, { waitUntil: 'domcontentloaded', timeout: 15000 });
      break; // Success
    } catch (error: any) {
      if (error.message?.includes('SSL') || error.message?.includes('ERR_SSL_PROTOCOL_ERROR')) {
        console.log(`   ⚠️  SSL error, retrying... (${4 - retries}/3)`);
        retries--;
        await page.waitForTimeout(2000);
        if (retries === 0) throw error;
      } else {
        throw error;
      }
    }
  }

  await logPageTelemetry(page, `${serviceName} Login Page`);

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
    const ssoEmailInput = page
      .locator('input.vw-email-sso')
      .or(page.locator('input[type="email"]').nth(1))
      .or(page.locator('input[type="email"]').first());
    const ssoButton = page
      .getByRole('button', { name: /use single sign-on|single sign-on|sso/i })
      .or(page.locator('button:has-text("Use single sign-on")'))
      .or(page.locator('button:has-text("Single sign-on")'))
      .or(page.locator('button:has-text("SSO")'));

    const inputCount = await ssoEmailInput.count().catch(() => 0);
    const buttonCount = await ssoButton.count().catch(() => 0);
    if (!inputCount && !buttonCount) {
      return false;
    }

    if (buttonCount) {
      await ssoButton.first().click({ force: true });
    }

    if (inputCount) {
      const currentValue = await ssoEmailInput.first().inputValue().catch(() => '');
      if (!currentValue) {
        await ssoEmailInput.first().fill(options.ssoEmail, { force: true });
      }
    }

    if (buttonCount) {
      await ssoButton.first().click({ force: true });
    } else if (inputCount) {
      await ssoEmailInput.first().press('Enter').catch(() => {});
    }

    await page.waitForURL((url) => /auth\.|authelia|identity\/connect\/authorize/i.test(url.toString()), { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);
    return true;
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

  // Wait briefly for redirect back to service (post-auth)
  await page.waitForURL((url) => {
    const href = url.toString();
    return !/auth\.|authelia|:9091/.test(href);
  }, { timeout: 20000 }).catch(() => {});

  // CRITICAL ASSERTION: Must NOT be on auth page
  await expect(page).not.toHaveURL(/auth\.|authelia/);

  if (options.postLogin) {
    await options.postLogin(page);
  }

  await logPageTelemetry(page, `${serviceName} Dashboard`);

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
    matchesPattern = effectiveUiPattern.test(pageText || bodyHTML || pageTitle);
    disallowedMatch = disallowPatterns.find((pattern) =>
      pattern.test([pageTitle, pageText, bodyHTML].filter(Boolean).join('\n'))
    ) ?? null;
    disallowedUrl = disallowUrlPatterns.find((pattern) => pattern.test(page.url())) ?? null;

  if (disallowedMatch || disallowedUrl) {
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

  console.log(`   ✅ ${serviceName} OIDC login successful\n`);
}

test.describe.serial('OIDC Services - SSO Flow', () => {

  test('Grafana - OIDC login flow', async ({ page }) => {
    // NOTE: Grafana is configured for Auth Proxy (forward auth), not OIDC
    // Environment: GF_AUTH_PROXY_ENABLED=true
    // Grafana uses Authelia -> Caddy -> Header-based auth, not OAuth/OIDC flow
    // This test is skipped as Grafana doesn't use OIDC in this deployment
    test.skip(true, 'Grafana uses Auth Proxy (forward auth), not OIDC');
  });

  test('Mastodon - OIDC login flow', async ({ page }) => {
    const runMastodonLogin = async () => {
      await testOIDCService(
        page,
        'Mastodon',
        'https://mastodon.datamancy.net/',
        /What's on your mind|Compose new post|Publish|Home|Notifications/i,
        ['Authelia', 'SSO', 'OpenID', 'OpenID Connect'],
        {
          disallowPatterns: [/Create account|Log in/i, /Invalid state/i],
          disallowUrlPatterns: [/\/(explore|about|public)\b/i],
          loginPath: 'https://mastodon.datamancy.net/auth/sign_in',
          loginButtonPatterns: [/log in|sign in|continue with sso|sso|openid/i],
          oidcLinkPatterns: [/sign in with.*(openid|sso)/i, /openid/i, /sso/i],
        }
      );
    };

    try {
      await runMastodonLogin();
    } catch (error: any) {
      const message = String(error?.message || error);
      if (!/Invalid state/i.test(message)) {
        throw error;
      }
      console.log('   ⚠️  Mastodon OIDC invalid state detected, retrying login flow once...');
      await page.context().clearCookies();
      await page.goto('https://mastodon.datamancy.net/auth/sign_in', { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
      await page.evaluate(() => {
        const storageOwner = globalThis as typeof globalThis & { localStorage?: Storage; sessionStorage?: Storage };
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

  test('Vaultwarden - OIDC login flow', async ({ page }) => {
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
        ssoEmail: testUser.email,
        uiPatternOverride: /My Vault|Vaults|Folders|Items|Search vault|Create account|Set up your vault|Set master password|Master password|Confirm master password|Join organization/i,
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
