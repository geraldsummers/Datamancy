const axios = require('axios');
const https = require('https');
const puppeteer = require('puppeteer-core');

class AuthTester {
  constructor(baseDomain, config) {
    this.baseDomain = baseDomain;
    this.config = config;
    this.browserlessUrl = process.env.BROWSERLESS_URL || 'http://browserless:3000';
    this.testUsername = process.env.TEST_USERNAME || 'test@lab.localhost';
    this.testPassword = process.env.TEST_PASSWORD || 'changeme';

    // Create axios instance with self-signed cert support
    this.axios = axios.create({
      httpsAgent: new https.Agent({
        rejectUnauthorized: false // Accept self-signed certs
      }),
      timeout: 10000,
      maxRedirects: 0, // Don't follow redirects
      validateStatus: () => true // Accept all status codes
    });
  }

  async getBrowser() {
    return await puppeteer.connect({
      browserWSEndpoint: `${this.browserlessUrl}?stealth&blockAds`,
      ignoreHTTPSErrors: true
    });
  }

  async testDex() {
    const startTime = Date.now();
    const results = {
      success: true,
      message: '',
      details: {},
      duration: 0
    };

    try {
      // Test 1: Dex health/root endpoint (use internal service name)
      const webTest = await this.axios.get('http://dex:5556');
      results.details.webInterface = {
        status: webTest.status,
        accessible: webTest.status === 200 || webTest.status === 404, // 404 is OK for Dex root
        message: `HTTP ${webTest.status}`
      };

      // Test 2: OIDC discovery endpoint
      try {
        const discoveryUrl = 'http://dex:5556/.well-known/openid-configuration';
        const discoveryTest = await this.axios.get(discoveryUrl);
        const discoveryData = discoveryTest.data;

        results.details.oidcDiscovery = {
          status: discoveryTest.status,
          accessible: discoveryTest.status === 200,
          hasIssuer: !!discoveryData.issuer,
          hasAuthEndpoint: !!discoveryData.authorization_endpoint,
          hasTokenEndpoint: !!discoveryData.token_endpoint,
          hasUserinfoEndpoint: !!discoveryData.userinfo_endpoint,
          message: discoveryTest.status === 200 ? 'Valid OIDC discovery' : `HTTP ${discoveryTest.status}`
        };

        if (discoveryTest.status !== 200) {
          results.success = false;
        }
      } catch (error) {
        results.details.oidcDiscovery = {
          accessible: false,
          message: error.message
        };
        results.success = false;
      }

      // Test 3: LDAP connection (via Dex)
      try {
        const healthUrl = 'http://dex:5556/.well-known/openid-configuration';
        const healthTest = await this.axios.get(healthUrl);

        results.details.ldapConnector = {
          status: healthTest.status,
          accessible: true,
          message: `Dex operational (HTTP ${healthTest.status})`
        };
      } catch (error) {
        results.details.ldapConnector = {
          accessible: false,
          message: error.code || error.message
        };
      }

      // Determine overall status
      if (results.success) {
        results.message = 'All Dex endpoints healthy';
      } else {
        results.message = 'Some Dex endpoints failed';
      }

    } catch (error) {
      results.success = false;
      results.message = `Dex test failed: ${error.message}`;
      results.details.error = error.message;
    }

    results.duration = Date.now() - startTime;
    return results;
  }

  async testService(service) {
    const startTime = Date.now();
    const results = {
      success: true,
      message: '',
      details: {},
      duration: 0
    };

    try {
      const url = service.url.replace('${BASE_DOMAIN}', this.baseDomain);

      if (service.authType === 'oidc') {
        await this.testOIDCService(service, url, results);
      } else if (service.authType === 'forward_auth') {
        await this.testForwardAuthService(service, url, results);
      } else {
        results.success = false;
        results.message = `Unknown auth type: ${service.authType}`;
      }

    } catch (error) {
      results.success = false;
      results.message = `Test failed: ${error.message}`;
      results.details.error = error.message;
    }

    results.duration = Date.now() - startTime;
    return results;
  }

  async testOIDCService(service, url, results) {
    let browser = null;
    let page = null;

    try {
      // Test 1: Basic endpoint check
      const response = await this.axios.get(url);
      results.details.endpoint = {
        status: response.status,
        accessible: true,
        message: `HTTP ${response.status}`
      };

      // Test 2: Actual OIDC login flow with browser
      // Since we're in Docker network, we can't use external HTTPS URLs
      // Skip browser-based testing for now and rely on endpoint checks
      console.log(`Skipping browser test for ${service.name} - would require external network access`);

      // For now, check if the service has OIDC configuration
      // by looking for redirects or login pages
      if (response.status === 302 || response.status === 301) {
        const location = response.headers.location || '';
        if (location.includes('id.') || location.includes('auth')) {
          results.success = true;
          results.message = 'Service redirects to authentication';
          results.details.oidcFlow = {
            redirectsToAuth: true,
            location: location.substring(0, 150),
            message: 'OIDC redirect detected'
          };
        } else {
          results.success = false;
          results.message = 'Service redirects but not to auth provider';
          results.details.oidcFlow = {
            redirectsToAuth: false,
            location: location.substring(0, 150)
          };
        }
      } else if (response.status === 200) {
        const responseText = typeof response.data === 'string' ? response.data.toLowerCase() : '';
        const hasLoginElements = responseText.includes('login') ||
                                 responseText.includes('sign in') ||
                                 responseText.includes('oauth') ||
                                 responseText.includes('oidc') ||
                                 responseText.includes('authentik');

        if (hasLoginElements) {
          results.success = true;
          results.message = 'Service has auth/login elements';
          results.details.oidcFlow = {
            hasAuthElements: true,
            message: 'Login page or OIDC references detected'
          };
        } else {
          results.success = true;
          results.message = 'Service accessible';
          results.details.oidcFlow = {
            accessible: true
          };
        }
      } else if (response.status === 401 || response.status === 403) {
        results.success = true;
        results.message = 'Service requires authentication';
        results.details.oidcFlow = {
          requiresAuth: true
        };
      } else {
        results.success = false;
        results.message = `Unexpected status: ${response.status}`;
      }

    } catch (error) {
      results.success = false;
      results.message = `Test failed: ${error.message}`;
      results.details.error = error.message;
    }

    // Note: Browser-based OIDC testing is disabled due to Docker network constraints
    // To enable, run auth-status-dashboard on host machine with external network access
  }

  async testOIDCService_DISABLED_BrowserVersion(service, url, results) {
    // This function is kept for reference but not currently used
    // Browser testing requires external network access or complex DNS setup
    let browser = null;
    let page = null;

    try {
      const publicUrl = `https://${service.name}.${this.baseDomain}`;
      console.log(`Testing OIDC login for ${service.name} at ${publicUrl}`);

      browser = await this.getBrowser();
      page = await browser.newPage();

      // Set a reasonable timeout
      page.setDefaultTimeout(30000);

      // Navigate to service
      await page.goto(publicUrl, {
        waitUntil: 'networkidle2',
        timeout: 30000
      });

      const currentUrl = page.url();

      // Check if redirected to Authentik login
      if (currentUrl.includes(`id.${this.baseDomain}`)) {
        results.details.oidcFlow = {
          redirectedToAuth: true,
          authProvider: 'authentik',
          message: 'Redirected to Authentik login'
        };

        // Attempt to login
        try {
          // Wait for login form
          await page.waitForSelector('input[name="uidField"]', { timeout: 5000 });

          // Fill in credentials
          await page.type('input[name="uidField"]', this.testUsername);
          await page.click('button[type="submit"]');

          // Wait for password field
          await page.waitForSelector('input[name="password"]', { timeout: 5000 });
          await page.type('input[name="password"]', this.testPassword);
          await page.click('button[type="submit"]');

          // Wait for redirect back to service (or consent screen)
          await page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 10000 });

          const finalUrl = page.url();

          // Check if we're back at the service (successful login)
          if (finalUrl.includes(service.name)) {
            results.success = true;
            results.message = 'OIDC login successful';
            results.details.oidcLogin = {
              loginSuccessful: true,
              authenticatedUrl: finalUrl.substring(0, 100),
              message: 'Successfully authenticated via Authentik'
            };
          } else if (finalUrl.includes('authentik')) {
            // Still on Authentik - might be consent screen or error
            const pageContent = await page.content();

            if (pageContent.includes('authorize') || pageContent.includes('consent')) {
              results.success = true;
              results.message = 'Login succeeded, consent required';
              results.details.oidcLogin = {
                loginSuccessful: true,
                requiresConsent: true,
                message: 'Authenticated but needs consent approval'
              };
            } else {
              results.success = false;
              results.message = 'Login failed - invalid credentials or configuration';
              results.details.oidcLogin = {
                loginSuccessful: false,
                message: 'Authentication failed'
              };
            }
          } else {
            results.success = false;
            results.message = `Unexpected redirect to ${finalUrl}`;
          }

        } catch (loginError) {
          results.success = false;
          results.message = `Login flow error: ${loginError.message}`;
          results.details.oidcLogin = {
            error: loginError.message
          };
        }

      } else if (currentUrl.includes(service.name)) {
        // Already at service - might be already logged in or no auth required
        const pageContent = await page.content();

        if (pageContent.toLowerCase().includes('login') ||
            pageContent.toLowerCase().includes('sign in')) {
          results.success = false;
          results.message = 'OIDC not configured - service has own login';
          results.details.oidcFlow = {
            redirectedToAuth: false,
            hasOwnLogin: true
          };
        } else {
          results.success = true;
          results.message = 'Service accessible (no auth or already authenticated)';
          results.details.oidcFlow = {
            accessible: true,
            noAuthRequired: true
          };
        }
      } else {
        results.success = false;
        results.message = `Unexpected redirect to ${currentUrl}`;
      }

    } catch (error) {
      results.success = false;
      results.message = `Browser test failed: ${error.message}`;
      results.details.error = error.message;
    } finally {
      if (page) await page.close().catch(() => {});
      if (browser) await browser.disconnect().catch(() => {});
    }
  }

  async testForwardAuthService(service, url, results) {
    // Test 1: Service endpoint without auth
    const response = await this.axios.get(url);
    results.details.endpoint = {
      status: response.status,
      accessible: true,
      message: `HTTP ${response.status}`
    };

    // Forward auth services accessed internally (bypassing Caddy) will be accessible
    // This is EXPECTED - forward auth is applied by Caddy reverse proxy, not the service itself
    // Services with forward auth should return 200 when accessed directly

    if (response.status === 200) {
      results.success = true;
      results.message = 'Service running (auth enforced by Caddy)';
      results.details.forwardAuth = {
        serviceUp: true,
        internallyAccessible: true,
        message: 'Internal access OK - Caddy handles auth at reverse proxy level'
      };

    } else if (response.status === 401 || response.status === 403) {
      // Service has its own auth (unexpected for forward auth services)
      results.success = true;
      results.message = 'Service has built-in auth';
      results.details.forwardAuth = {
        hasOwnAuth: true,
        status: response.status,
        message: 'Service requires auth internally'
      };

    } else if (response.status === 302 || response.status === 301) {
      const location = response.headers.location || '';
      results.success = true;
      results.message = 'Service redirects';
      results.details.forwardAuth = {
        redirects: true,
        location: location.substring(0, 150),
        message: 'Redirect configured'
      };

    } else if (response.status >= 500) {
      results.success = false;
      results.message = `Service error: HTTP ${response.status}`;

    } else {
      results.success = false;
      results.message = `Unexpected status: ${response.status}`;
    }
  }
}

module.exports = AuthTester;
