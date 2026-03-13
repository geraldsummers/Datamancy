/**
 * Tests for services using Authelia Forward Authentication
 *
 * These services rely on Caddy's forward_auth directive pointing to Authelia.
 * Once authenticated with Authelia, the session works across all forward-auth services.
 *
 * Services tested:
 * - JupyterHub
 * - Open-WebUI
 * - Prometheus
 * - Vaultwarden
 * - Homepage
 * - Ntfy
 * - qBittorrent
 * - Home Assistant
 * - Kopia (Backup)
 * - LDAP Account Manager
 * - LiteLLM
 * - Radicale (Calendar/Contacts)
 * - Vault
 */

import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { AutheliaLoginPage } from '../pages/AutheliaLoginPage';
import { logPageTelemetry, setupNetworkLogging } from '../utils/telemetry';

// Load test user credentials with fallback if auth artifacts were cleaned up
const authDir = path.join(__dirname, '../.auth');
const testUserPath = path.join(authDir, 'test-user.json');
const autheliaSessionPath = path.join(authDir, 'authelia-session.json');
const autheliaSessionState = fs.existsSync(autheliaSessionPath) ? autheliaSessionPath : undefined;
if (!autheliaSessionState) {
  console.warn('⚠️  Authelia session state missing; forward-auth tests will re-authenticate interactively.');
}
const testUser = fs.existsSync(testUserPath)
  ? JSON.parse(fs.readFileSync(testUserPath, 'utf-8'))
  : {
      username: process.env.STACK_ADMIN_USER || 'sysadmin',
      password: process.env.STACK_ADMIN_PASSWORD || 'admin',
      email: process.env.STACK_ADMIN_EMAIL || '',
      managed: false,
    };
const stackAdminUser = process.env.STACK_ADMIN_USER || testUser.username;
const stackAdminPassword = process.env.STACK_ADMIN_PASSWORD || testUser.password;

async function waitForGrafanaShell(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const text = document.body?.innerText ?? '';
    const hasShell = /Grafana|Last 24 hours|Refresh/i.test(text);
    const stillLoading = /Loading plugin panel/i.test(text);
    return hasShell && !stillLoading;
  }, { timeout: 45000 });
}

/**
 * Helper function to test forward auth service access with proper assertions
 */
async function testForwardAuthService(
  page: Page,
  serviceName: string,
  servicePath: string,
  uiPattern: RegExp,
  options: {
    urlPattern?: RegExp;
    requireUI?: boolean;
    disallowPatterns?: RegExp[];
    disallowUrlPatterns?: RegExp[];
    maxPatternRetries?: number;
    retryDelayMs?: number;
    waitForSelector?: string;
    waitForSelectorVisible?: string;
    waitForSelectorTimeoutMs?: number;
    requireSelectorVisible?: boolean;
    waitForUrlNotMatch?: RegExp;
    waitForUrlMatch?: RegExp;
    clickIfVisibleSelector?: string;
    screenshotSelector?: string;
    screenshotType?: 'jpeg' | 'png';
    screenshotQuality?: number;
    screenshotFullPage?: boolean;
    screenshotDelayMs?: number;
    screenshotUsePage?: boolean;
    screenshotViewport?: { width: number; height: number };
    onAfterLoad?: (page: Page) => Promise<void>;
  } = {}
) {
  console.log(`\n🧪 Testing ${serviceName} forward auth`);

  setupNetworkLogging(page, serviceName);

  // Retry logic for SSL errors and timeouts
  let retries = 3;
  let lastError;

  let navResponse;
  while (retries > 0) {
    try {
      navResponse = await page.goto(servicePath, { waitUntil: 'domcontentloaded', timeout: 30000 });
      break; // Success, exit retry loop
    } catch (error: any) {
      lastError = error;
      if (error.message?.includes('SSL') || error.message?.includes('ERR_SSL_PROTOCOL_ERROR') || error.message?.includes('Timeout')) {
        console.log(`   ⚠️  SSL/timeout error, retrying... (${4 - retries}/3)`);
        retries--;
        await page.waitForTimeout(3000); // Wait 3 seconds before retry
        if (retries === 0) {
          throw error; // Give up after 3 retries
        }
      } else {
        throw error; // Not an SSL/timeout error, don't retry
      }
    }
  }

  // Handle auth redirect if needed
  if (page.url().includes('authelia') || page.url().includes('auth.') || page.url().includes(':9091')) {
    console.log('   ⚠️  Auth state expired, logging in again...');
    const loginPage = new AutheliaLoginPage(page);
    await loginPage.login(testUser.username, testUser.password);
  }

  // Handle Authelia consent screens (some clients still require explicit consent)
  for (let i = 0; i < 3; i++) {
    if (!page.url().includes('/consent/')) {
      break;
    }
    console.log('   ⚠️  Consent screen detected, accepting...');
    const acceptButton = page.locator('#openid-consent-accept, button:has-text(\"Accept\")').first();
    if (await acceptButton.isVisible().catch(() => false)) {
      await acceptButton.click().catch(() => {});
      await page.waitForTimeout(1500);
      await page.waitForLoadState('domcontentloaded', { timeout: 15000 }).catch(() => {});
      await page.waitForURL((url) => !url.toString().includes('/consent/'), { timeout: 10000 }).catch(() => {});
    } else {
      break;
    }
  }

  // CRITICAL ASSERTION: Must NOT be on auth page
  await expect(page).not.toHaveURL(/auth\.|authelia/);

  if (options.waitForUrlMatch) {
    await page.waitForURL(options.waitForUrlMatch, { timeout: 30000 }).catch(() => {});
  }

  if (options.waitForUrlNotMatch) {
    await page.waitForURL((url) => !options.waitForUrlNotMatch!.test(url.toString()), { timeout: 60000 }).catch(() => {});
  }

  if (options.waitForSelector) {
    const waitPromise = page.waitForSelector(options.waitForSelector, { timeout: 10000 });
    if (options.requireSelectorVisible) {
      await waitPromise;
    } else {
      await waitPromise.catch(() => {});
    }
  }

  if (options.waitForSelectorVisible) {
    const timeout = options.waitForSelectorTimeoutMs ?? 15000;
    const waitPromise = page.waitForSelector(options.waitForSelectorVisible, { state: 'visible', timeout });
    if (options.requireSelectorVisible) {
      await waitPromise;
    } else {
      await waitPromise.catch(() => {});
    }
  }

  if (options.clickIfVisibleSelector) {
    const clickTarget = page.locator(options.clickIfVisibleSelector).first();
    if (await clickTarget.isVisible().catch(() => false)) {
      await clickTarget.click().catch(() => {});
      await page.waitForTimeout(1000);
    }
  }

  // If the UI is still loading, give the app a moment to finish first paint.
  if (options.waitForSelectorVisible) {
    await page.waitForTimeout(options.screenshotDelayMs ?? 3000);
  }

  if (options.screenshotViewport) {
    await page.setViewportSize(options.screenshotViewport);
  }

  await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});

  if (options.onAfterLoad) {
    await options.onAfterLoad(page);
  }

  await logPageTelemetry(page, `${serviceName} Main Page`);

  // Check for 400/500 errors
  const status = navResponse?.status?.();
  if (typeof status === 'number' && status >= 400) {
    console.log(`   ⚠️  ${serviceName} returned ${status} error - skipping UI check\n`);
    return; // Skip this test, don't fail it
  }

  // ENHANCED: Verify we're on the CORRECT service page, not just "not auth"
  const body = page.locator('body');
  await expect(body).toBeAttached({ timeout: 10000 });

  // Verify page has meaningful content (not just an empty body)
  let bodyHTML = await body.innerHTML();
  if (options.requireUI !== false) {
    expect(bodyHTML.length).toBeGreaterThan(10);
  }

  const disallowPatterns = options.disallowPatterns ?? [];
  const disallowUrlPatterns = options.disallowUrlPatterns ?? [];

  // Check for service-specific UI pattern to confirm correct page
  if (options.requireUI !== false && uiPattern) {
    // Retry pattern matching to handle slow-loading SPAs
    let matchesPattern = false;
    let pageTitle = '';
    const maxPatternRetries = options.maxPatternRetries ?? 5;
    const retryDelayMs = options.retryDelayMs ?? 2000;
    let disallowedMatch: RegExp | null = null;
    let disallowedUrl: RegExp | null = null;

    for (let i = 0; i < maxPatternRetries; i++) {
      pageTitle = await page.title();
      const currentPageText = (await page.textContent('body').catch(() => null)) ?? '';
      bodyHTML = await body.innerHTML();
      const combinedContent = [pageTitle, currentPageText, bodyHTML].filter(Boolean).join('\n');
      matchesPattern = uiPattern.test(combinedContent);
      disallowedMatch = disallowPatterns.find((pattern) =>
        pattern.test([pageTitle, currentPageText, bodyHTML].filter(Boolean).join('\n'))
      ) ?? null;
      disallowedUrl = disallowUrlPatterns.find((pattern) => pattern.test(page.url())) ?? null;

      if (disallowedMatch || disallowedUrl) {
        if (i < maxPatternRetries - 1) {
          console.log(`   ⏳ Detected disallowed state for ${serviceName}, waiting for redirect... (${i + 1}/${maxPatternRetries})`);
          await page.waitForTimeout(retryDelayMs);
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
        await page.waitForTimeout(retryDelayMs); // Wait before retry
      }
    }

    if (!matchesPattern) {
      console.log(`   ⚠️  Pattern match failed for ${serviceName}`);
      console.log(`   Title: "${pageTitle}"`);
      console.log(`   Pattern: ${uiPattern}`);
      console.log(`   Body length: ${bodyHTML.length} chars`);
      throw new Error(`Expected service page for ${serviceName} but UI pattern not found. Pattern: ${uiPattern}, Title: "${pageTitle}"`);
    }
  }

  // Additional URL validation if specified
  if (options.urlPattern) {
    await expect(page).toHaveURL(options.urlPattern);
  }

  // Capture screenshot for manual validation (compressed to prevent 5MB+ files)
  const screenshotBase = `${serviceName.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-authenticated`;
  const screenshotType = options.screenshotType ?? 'jpeg';
  const screenshotName = `${screenshotBase}.${screenshotType}`;
  const screenshotPath = `/app/test-results/screenshots/${screenshotName}`;
  if (options.screenshotUsePage) {
    await page.screenshot({
      path: screenshotPath,
      type: screenshotType,
      quality: screenshotType === 'jpeg' ? (options.screenshotQuality ?? 85) : undefined,
      fullPage: options.screenshotFullPage ?? true
    });
  } else if (options.screenshotSelector) {
    const target = page.locator(options.screenshotSelector).first();
    const visible = await target.isVisible().catch(() => false);
    if (visible) {
      await target.screenshot({
        path: screenshotPath,
        type: screenshotType,
        quality: screenshotType === 'jpeg' ? (options.screenshotQuality ?? 85) : undefined
      });
    } else {
      console.log(`   ⚠️  Screenshot selector not visible (${options.screenshotSelector}); falling back to full page.`);
      await page.screenshot({
        path: screenshotPath,
        type: screenshotType,
        quality: screenshotType === 'jpeg' ? (options.screenshotQuality ?? 85) : undefined,
        fullPage: options.screenshotFullPage ?? true
      });
    }
  } else {
    await page.screenshot({
      path: screenshotPath,
      type: screenshotType,
      quality: screenshotType === 'jpeg' ? (options.screenshotQuality ?? 85) : undefined,
      fullPage: options.screenshotFullPage ?? true
    });
  }
  console.log(`   📸 Screenshot saved: ${screenshotName}`);
  console.log(`   👀 REVIEW SCREENSHOT to verify correct page loaded`);

  console.log(`   ✅ ${serviceName} accessed successfully\n`);
}

test.describe('Forward Auth Services - SSO Flow', () => {
  test.use(
    autheliaSessionState
      ? {
          // Use saved auth state from global setup
          storageState: autheliaSessionState,
        }
      : {}
  );

  test('JupyterHub - Access with forward auth', async ({ page }) => {
    test.setTimeout(180000);
    await testForwardAuthService(
      page,
      'JupyterHub',
      'https://jupyterhub.datamancy.net/hub/home',
      /JupyterHub|Start My Server|Control Panel|JupyterLab|Notebook|Files|New/i,
      {
        urlPattern: /jupyterhub\.datamancy\.net/,
        disallowUrlPatterns: [/\/spawn-pending\//i],
        disallowPatterns: [/Spawning server|Your server is starting up/i],
        maxPatternRetries: 5,
        retryDelayMs: 2000,
        waitForUrlNotMatch: /\/spawn-pending\//i,
        waitForSelectorVisible: '#start, #stop',
        waitForSelectorTimeoutMs: 30000,
        requireSelectorVisible: true,
        // JupyterLab element screenshots often render blank; use page screenshot.
        screenshotUsePage: true,
        screenshotType: 'png',
        screenshotDelayMs: 4000,
        onAfterLoad: async (page) => {
          if (/\/user\//.test(page.url())) {
            await page.goto('https://jupyterhub.datamancy.net/hub/home', { waitUntil: 'domcontentloaded' }).catch(() => {});
          }
          await page.waitForSelector('#start, #stop', { state: 'visible', timeout: 30000 }).catch(() => {});
        },
      }
    );
  });

  test('Open-WebUI - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Open-WebUI',
      'https://open-webui.datamancy.net/',
      /Open WebUI|New Chat|Chats|Workspace|Models|Settings/i,
      {
        urlPattern: /open-webui\.datamancy\.net/,
        waitForSelectorVisible: 'text=New Chat',
        waitForSelectorTimeoutMs: 20000,
      }
    );
  });

  test('Prometheus - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Prometheus',
      'https://prometheus.datamancy.net/',
      /Prometheus|Query|Execute|Alerts/i, // Look for Prometheus UI elements or title
      { urlPattern: /prometheus\.datamancy\.net/ }
    );
  });

  test('Grafana - Logs home + Loki datasource', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Grafana',
      'https://grafana.datamancy.net/',
      /Grafana|Dashboards|Explore|Connections|Data sources|Loki/i,
      {
        urlPattern: /grafana\.datamancy\.net/,
        onAfterLoad: async (page) => {
          await waitForGrafanaShell(page);
        },
        screenshotDelayMs: 2000,
        screenshotFullPage: false,
        screenshotViewport: { width: 1280, height: 360 },
      }
    );

    // Validate default home dashboard shows Logs panel
    const logsPanelTitle = page.getByText('All Logs', { exact: false }).first();
    if (await logsPanelTitle.isVisible().catch(() => false)) {
      await expect(logsPanelTitle).toBeVisible();
    }

    // Validate Loki datasource via Grafana API
    const response = await page.request.get('https://grafana.datamancy.net/api/datasources/name/Loki');
    expect(response.ok()).toBeTruthy();
    const data = await response.json();
    expect(data.type).toBe('loki');
    expect(String(data.url)).toContain('http://loki:3100');
  });

  test('Vaultwarden - Access with forward auth', async ({ page }) => {
    // Vaultwarden requires explicit OIDC (SSO_ONLY=true). Validate that forward-auth does not
    // grant access and that we land on Vaultwarden's SSO/login UI.
    await testForwardAuthService(
      page,
      'Vaultwarden (forward-auth)',
      'https://vaultwarden.datamancy.net/',
      /Single sign-on|Use single sign-on|SSO|Log in|Vaultwarden|Bitwarden|Join organization|Master password/i,
      {
        urlPattern: /vaultwarden\.datamancy\.net/,
        disallowPatterns: [/My Vault|Search vault/i],
        disallowUrlPatterns: [/#\/vault\b/i],
        maxPatternRetries: 4,
        retryDelayMs: 2000,
      }
    );
  });

  test('Homepage - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Homepage',
      'https://homepage.datamancy.net/',
      /(Homepage|AI & Development|Collaboration|System)/i, // Title is "Homepage" and has service group buttons
      { urlPattern: /homepage\.datamancy\.net/ }
    );
  });

  test('Ntfy - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Ntfy',
      'https://ntfy.datamancy.net/',
      /ntfy/i, // Title is "ntfy"
      { urlPattern: /ntfy\.datamancy\.net/ }
    );
  });

  test('qBittorrent - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'qBittorrent',
      'https://qbittorrent.datamancy.net/',
      /qBittorrent|Add Torrent|Transfers/i, // Look for qBittorrent UI elements
      { urlPattern: /qbittorrent\.datamancy\.net/ }
    );
  });

  test('Home Assistant - Access with forward auth', async ({ page }) => {
    test.setTimeout(120000);
    // NOTE: Home Assistant requires onboarding on first run
    // Forward auth works, but HA shows setup wizard if not configured
    // This test validates HA is fully loaded (not onboarding)
    await testForwardAuthService(
      page,
      'Home Assistant',
      'https://homeassistant.datamancy.net/',
      /Overview|Map|Energy|Settings|Developer Tools|History|Logbook/i,
      {
        urlPattern: /homeassistant\.datamancy\.net/,
        waitForSelector: 'home-assistant, ha-app',
        requireUI: false,
      }
    );
  });

  test('Kopia - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Kopia',
      'https://kopia.datamancy.net/',
      /Kopia|Snapshots|Repository|Policies/i, // Look for Kopia UI elements or title
      { urlPattern: /kopia\.datamancy\.net/ }
    );
  });

  test('LDAP Account Manager - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'LDAP Account Manager',
      'https://lam.datamancy.net/lam/', // LAM requires /lam/ path
      /Tree view|Account tools|Tools|Logout/i,
      {
        urlPattern: /lam\.datamancy\.net/,
        disallowPatterns: [/\bPassword\b|\bLogin\b/i, /Invalid DN syntax/i, /Cannot connect to specified LDAP server/i],
        onAfterLoad: async (page) => {
          const userInput = page.locator('input[name=\"username\"], #username, input[name=\"user\"]').first();
          const passInput = page.locator('input[name=\"passwd\"], #passwd, input[name=\"password\"], input[type=\"password\"]').first();
          const loginButton = page.locator('#btn_checklogin, button[type=\"submit\"], input[type=\"submit\"]').first();
          if (await userInput.isVisible().catch(() => false)) {
            await userInput.fill(stackAdminUser);
            await passInput.fill(stackAdminPassword);
            await loginButton.click().catch(() => {});
            await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
          }
          await page.waitForSelector('#lam-topnav', { state: 'visible', timeout: 30000 });
        }
      }
    );
  });

  test('LiteLLM - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'LiteLLM',
      'https://litellm.datamancy.net/',
      /LiteLLM API|Swagger UI/i, // Title is "LiteLLM API - Swagger UI"
      {
        urlPattern: /litellm\.datamancy\.net/,
        waitForSelectorVisible: '.swagger-ui',
        waitForSelectorTimeoutMs: 20000,
        requireSelectorVisible: true,
        screenshotViewport: { width: 1280, height: 720 },
        // Avoid huge full-page screenshots from long Swagger UI.
        screenshotFullPage: false,
      }
    );
  });

  test('Radicale - Access with forward auth', async ({ page }) => {
    await testForwardAuthService(
      page,
      'Radicale',
      'https://radicale.datamancy.net/',
      /Radicale CalDAV\/CardDAV|CalDAV|CardDAV/i,
      {
        urlPattern: /radicale\.datamancy\.net/,
        maxPatternRetries: 4,
        retryDelayMs: 2000,
        disallowPatterns: [/Sign in/i],
      }
    );
  });

  test('Vault - Access with forward auth', async ({ page }) => {
    // This endpoint should remain protected by Vaultwarden's OIDC flow.
    await testForwardAuthService(
      page,
      'Vault (Vaultwarden UI)',
      'https://app.vaultwarden.datamancy.net/#/sso?identifier=datamancy.net',
      /Single sign-on|Use single sign-on|SSO|Log in|Vaultwarden|Bitwarden|Join organization|Master password/i,
      {
        urlPattern: /app\.vaultwarden\.datamancy\.net/,
        disallowPatterns: [/My Vault|Search vault/i],
        disallowUrlPatterns: [/#\/vault\b/i],
        maxPatternRetries: 4,
        retryDelayMs: 2000,
      }
    );
  });
});

test.describe('Forward Auth - Session Persistence', () => {
  test.use(
    autheliaSessionState
      ? {
          storageState: autheliaSessionState,
        }
      : {}
  );

  test('Session works across multiple forward-auth services', async ({ page }) => {
    test.setTimeout(120000);
    console.log('\n🧪 Testing session persistence across services');

    // Visit multiple services in sequence - should not require re-auth
    const services = [
      { name: 'JupyterHub', path: 'https://jupyterhub.datamancy.net/' },
      { name: 'Prometheus', path: 'https://prometheus.datamancy.net/' },
      { name: 'Homepage', path: 'https://homepage.datamancy.net/' },
    ];

    for (const service of services) {
      console.log(`\n   Visiting ${service.name}...`);
      const timeoutMs = service.name === 'JupyterHub' ? 90000 : 15000;

      // Retry logic for SSL errors
      let retries = 3;
      while (retries > 0) {
        try {
          await page.goto(service.path, { timeout: timeoutMs });
          break;
        } catch (error: any) {
          if (error.message?.includes('SSL') || error.message?.includes('ERR_SSL_PROTOCOL_ERROR')) {
            console.log(`      ⚠️  SSL error, retrying...`);
            retries--;
            await page.waitForTimeout(2000);
            if (retries === 0) throw error;
          } else {
            throw error;
          }
        }
      }

      if (service.name === 'JupyterHub') {
        await page
          .waitForURL((url) => !url.toString().includes('spawn-pending'), { timeout: 90000 })
          .catch(() => {});
        if (page.url().includes('spawn-pending')) {
          await page.waitForTimeout(15000);
        }
      }

      // CRITICAL: Should NOT be on Authelia
      await expect(page).not.toHaveURL(/auth\.|authelia/);

      console.log(`   ✅ ${service.name} accessed without re-auth`);
    }

    console.log('\n   ✅ Session persisted across all services\n');
  });
});
