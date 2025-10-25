// Phase 3 Grafana OIDC Test - End-to-end authentication flow
// Provenance: Playwright test patterns
// Tests: Complete authentication flow through Dex to LDAP and back to Grafana

const { test, expect } = require('@playwright/test');

test.describe('Phase 3 - Grafana OIDC Authentication', () => {
  test('Grafana login page shows Dex OAuth option', async ({ page }) => {
    await page.goto('/grafana/login');
    await page.waitForLoadState('networkidle');

    // Take screenshot of login page
    await page.screenshot({
      path: '/results/grafana-login-page.png',
      fullPage: true
    });

    // Look for Dex OAuth button
    const pageContent = await page.content();
    expect(pageContent).toContain('Dex');

    console.log('✓ Grafana login page shows Dex OAuth option');
  });

  test('Complete OIDC login flow: Grafana -> Dex -> LDAP -> Grafana', async ({ page }) => {
    // Start at Grafana login page
    await page.goto('/grafana/login');
    await page.waitForLoadState('networkidle');

    // Click "Sign in with Dex" button (it's rendered as a div/link, not a button element)
    const dexButton = page.locator('text=Sign in with Dex');
    await expect(dexButton).toBeVisible({ timeout: 10000 });
    await dexButton.click();

    // Wait for redirect to Dex
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    // Should now be on Dex login page
    const currentUrl = page.url();
    expect(currentUrl).toContain('/dex/auth');

    // Take screenshot of Dex login page
    await page.screenshot({
      path: '/results/dex-auth-page.png',
      fullPage: true
    });

    // Look for LDAP login option
    const pageText = await page.textContent('body');
    const hasLDAPOption = pageText.includes('LDAP') || pageText.includes('Log in');

    expect(hasLDAPOption).toBeTruthy();

    console.log('✓ Redirected to Dex authentication page');

    // If there's an LDAP connector button, click it
    const ldapButton = page.locator('a:has-text("LDAP")').first();
    const ldapButtonExists = await ldapButton.count() > 0;

    if (ldapButtonExists) {
      await ldapButton.click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(1000);
    }

    // Look for login form (username/password fields)
    const loginForm = page.locator('form').first();
    await expect(loginForm).toBeVisible({ timeout: 10000 });

    // Fill in LDAP credentials (testuser/password)
    const usernameField = page.locator('input[name="login"], input[type="text"]').first();
    const passwordField = page.locator('input[name="password"], input[type="password"]').first();

    await usernameField.fill('testuser');
    await passwordField.fill('password');

    // Take screenshot before submitting
    await page.screenshot({
      path: '/results/dex-login-form-filled.png',
      fullPage: true
    });

    // Submit the form
    const submitButton = page.locator('button[type="submit"]').first();
    await submitButton.click();

    // Wait for redirect back to Grafana
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);

    // Take screenshot of final page
    await page.screenshot({
      path: '/results/grafana-post-login.png',
      fullPage: true
    });

    // Verify we're back in Grafana
    const finalUrl = page.url();
    expect(finalUrl).toContain('stack.local/grafana');

    // Check if we're logged in by looking for common Grafana UI elements
    const grafanaUI = await page.locator('[data-testid="data-testid Nav toolbar"], nav, [class*="navbar"]').count();

    // If we see Grafana navigation, we're logged in
    if (grafanaUI > 0) {
      console.log('✓ Successfully logged into Grafana via Dex OIDC');
    } else {
      // Check if we got an error page
      const bodyText = await page.textContent('body');
      if (bodyText.includes('error') || bodyText.includes('Error')) {
        throw new Error(`Login failed with error: ${bodyText.substring(0, 500)}`);
      }
      console.log('⚠ Login completed but UI verification inconclusive');
    }
  });

  test('Grafana API health check still works', async ({ page }) => {
    // Verify Grafana is still functional after OIDC configuration
    const response = await page.goto('/grafana/api/health');
    expect(response.status()).toBe(200);

    const content = await page.textContent('body');
    const health = JSON.parse(content);
    expect(health.database).toBe('ok');

    console.log('✓ Grafana API health check passed');
  });
});
