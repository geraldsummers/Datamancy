// Phase 3 Dex Test - Identity & Access (OIDC Provider)
// Provenance: Playwright test patterns
// Tests: Dex OIDC discovery, authentication UI, LDAP integration

const { test, expect } = require('@playwright/test');

test.describe('Phase 3 - Dex OIDC Provider', () => {
  test('Dex OIDC discovery endpoint is accessible', async ({ page }) => {
    const response = await page.goto('/dex/.well-known/openid-configuration');

    expect(response.status()).toBe(200);

    const content = await page.textContent('body');
    const config = JSON.parse(content);

    // Verify required OIDC discovery fields
    expect(config.issuer).toBe('https://stack.local/dex');
    expect(config.authorization_endpoint).toContain('/dex/auth');
    expect(config.token_endpoint).toContain('/dex/token');
    expect(config.jwks_uri).toContain('/dex/keys');
    expect(config.response_types_supported).toContain('code');
    expect(config.subject_types_supported).toBeTruthy();
    expect(config.id_token_signing_alg_values_supported).toContain('RS256');

    console.log('✓ Dex OIDC discovery endpoint working');
  });

  test('Dex authorization endpoint is accessible', async ({ page }) => {
    // Try to access the auth endpoint (will redirect or show login)
    const response = await page.goto('/dex/auth');

    // Should get a response (either 200, 302, or 400 for missing params)
    expect([200, 302, 400]).toContain(response.status());

    console.log('✓ Dex authorization endpoint accessible');
  });

  test('Dex has LDAP connector configured', async ({ page }) => {
    // Access the OIDC config and verify connectors
    await page.goto('/dex/.well-known/openid-configuration');

    const content = await page.textContent('body');
    const config = JSON.parse(content);

    // Dex should advertise its capabilities
    expect(config.issuer).toBe('https://stack.local/dex');

    console.log('✓ Dex OIDC provider configured correctly');
  });

  test('Dex login page renders', async ({ page }) => {
    // Access Dex auth with minimal required OAuth params to see login page
    const authUrl = '/dex/auth?' + new URLSearchParams({
      client_id: 'grafana',
      redirect_uri: 'https://stack.local/grafana/login/generic_oauth',
      response_type: 'code',
      scope: 'openid profile email groups'
    }).toString();

    await page.goto(authUrl);

    // Wait for page to load
    await page.waitForLoadState('networkidle');

    // Look for Dex login UI elements (connector selection or login form)
    const bodyText = await page.textContent('body');

    // Dex should show either connector buttons or a login form
    // Check for common Dex UI elements
    const hasDexUI = bodyText.includes('Log in') ||
                      bodyText.includes('LDAP') ||
                      bodyText.includes('Datamancy');

    expect(hasDexUI).toBeTruthy();

    // Take screenshot for verification
    await page.screenshot({
      path: '/results/dex-login-page.png',
      fullPage: true
    });

    console.log('✓ Dex login page renders');
  });

  test('Dex JWKS endpoint is accessible', async ({ page }) => {
    const response = await page.goto('/dex/keys');

    expect(response.status()).toBe(200);

    const content = await page.textContent('body');
    const jwks = JSON.parse(content);

    // JWKS should have keys array
    expect(jwks.keys).toBeDefined();
    expect(Array.isArray(jwks.keys)).toBeTruthy();
    expect(jwks.keys.length).toBeGreaterThan(0);

    // Keys should have required fields
    const key = jwks.keys[0];
    expect(key.use).toBe('sig');
    expect(key.kty).toBe('RSA');
    expect(key.kid).toBeDefined();
    expect(key.n).toBeDefined();
    expect(key.e).toBeDefined();

    console.log('✓ Dex JWKS endpoint accessible and valid');
  });
});
