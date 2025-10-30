// Manual service verification with screenshots
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const CREDENTIALS = {
  username: 'admin',
  password: 'DatamancyTest2025!',
};

async function loginAndCapture(serviceName, url, usageAction) {
  console.log(`\n=== Testing ${serviceName} at ${url} ===`);

  const browser = await chromium.launch({
    headless: false,  // Show browser for visibility
    slowMo: 500
  });

  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();

  const dir = `./manual_screenshots/${serviceName}`;
  fs.mkdirSync(dir, { recursive: true });

  try {
    // Navigate to service
    await page.goto(url, { timeout: 30000 });
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
    await page.screenshot({ path: `${dir}/01-landing.png`, fullPage: true });

    // Look for OIDC/SSO button
    const oidcSelectors = [
      'button:has-text("Authelia")',
      'a:has-text("Authelia")',
      'button:has-text("Sign in with")',
      'button:has-text("Continue with")',
      'button:has-text("SSO")',
      'text=/single sign/i',
    ];

    let clicked = false;
    for (const sel of oidcSelectors) {
      try {
        const elem = page.locator(sel).first();
        if (await elem.count() > 0) {
          console.log(`Found OIDC button: ${sel}`);
          await elem.click({ timeout: 5000 });
          clicked = true;
          break;
        }
      } catch {}
    }

    if (clicked) {
      await page.waitForURL(/auth\.stack\.local/, { timeout: 10000 }).catch(() => {});
    }

    // If on Authelia login page
    if (page.url().includes('auth.stack.local')) {
      console.log('On Authelia login page');
      await page.screenshot({ path: `${dir}/02-authelia-login.png`, fullPage: true });

      // Fill credentials
      await page.getByLabel('Username').fill(CREDENTIALS.username);
      await page.getByRole('textbox', { name: 'Password' }).fill(CREDENTIALS.password);
      await page.screenshot({ path: `${dir}/03-credentials-filled.png`, fullPage: true });

      // Click sign in
      await page.getByRole('button', { name: 'Sign in' }).click();
      await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});

      // Handle consent if present
      try {
        await page.getByRole('button', { name: 'ACCEPT' }).click({ timeout: 5000 });
      } catch {}

      // Wait for redirect back
      await page.waitForTimeout(3000);
      await page.screenshot({ path: `${dir}/04-after-auth.png`, fullPage: true });
    }

    // Execute usage action if provided
    if (usageAction) {
      console.log('Executing usage action...');
      await usageAction(page);
    }

    // Final screenshot
    await page.screenshot({ path: `${dir}/99-final-usage.png`, fullPage: true });
    console.log(`✅ ${serviceName} verified - screenshots in ${dir}/`);

  } catch (err) {
    console.error(`❌ ${serviceName} failed: ${err.message}`);
    await page.screenshot({ path: `${dir}/ERROR.png`, fullPage: true });
  } finally {
    await browser.close();
  }
}

(async () => {
  // Test Grafana
  await loginAndCapture('grafana', 'https://grafana.stack.local', async (page) => {
    await page.waitForTimeout(2000);
    // Try to navigate to dashboards
    try {
      await page.click('text=/dashboards/i', { timeout: 5000 });
      await page.waitForTimeout(1000);
    } catch {}
  });

  console.log('\n✅ Manual verification complete!');
})();
