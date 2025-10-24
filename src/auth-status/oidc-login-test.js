const puppeteer = require('puppeteer-core');

/**
 * Full OIDC login flow test using puppeteer + browserless
 * Tests: Grafana → Dex → LDAP → back to Grafana
 */
class OIDCLoginTest {
  constructor(config = {}) {
    this.browserlessUrl = config.browserlessUrl || process.env.BROWSERLESS_URL || 'http://browserless:3000';
    this.baseDomain = config.baseDomain || process.env.BASE_DOMAIN || 'lab.localhost';
    this.username = config.username || process.env.TEST_USERNAME || 'authtest';
    this.password = config.password || process.env.TEST_PASSWORD || 'TestAuth123!';
    this.timeout = config.timeout || 30000;
  }

  async runTest(serviceName = 'grafana') {
    const startTime = Date.now();
    const results = {
      success: false,
      message: '',
      steps: [],
      duration: 0,
      details: {}
    };

    let browser = null;
    let page = null;

    try {
      console.log(`🔗 Connecting to browserless at ${this.browserlessUrl}`);
      browser = await puppeteer.connect({
        browserWSEndpoint: this.browserlessUrl,
        ignoreHTTPSErrors: true
      });

      page = await browser.newPage();
      page.setDefaultTimeout(this.timeout);

      // Enable console logging from page
      page.on('console', msg => {
        console.log('  [Browser Console]', msg.type(), msg.text());
      });

      // Navigate to service using internal Docker DNS
      const serviceUrl = `http://${serviceName}:3000`;
      console.log(`🌐 Navigating to ${serviceUrl}`);

      // Step 1: Navigate to service
      results.steps.push({ step: 'navigate', status: 'started' });
      await page.goto(serviceUrl, {
        waitUntil: 'networkidle2',
        timeout: this.timeout
      });
      results.steps[results.steps.length - 1].status = 'completed';
      results.steps[results.steps.length - 1].url = page.url();

      console.log(`  ✓ Page loaded: ${page.url()}`);

      // Step 2: Find and click OAuth/OIDC login button
      results.steps.push({ step: 'find_oauth_button', status: 'started' });

      // For Grafana specifically
      if (serviceName === 'grafana') {
        // Wait for OAuth button (link contains "generic_oauth")
        await page.waitForSelector('a[href*="generic_oauth"]', { timeout: this.timeout });

        // Find the OAuth login link
        const oauthButton = await page.$('a[href*="generic_oauth"]');
        if (!oauthButton) {
          throw new Error('OAuth login button not found');
        }

        console.log('  ✓ Found OAuth login button');
        results.steps[results.steps.length - 1].status = 'completed';

        // Step 3: Click OAuth button
        results.steps.push({ step: 'click_oauth_button', status: 'started' });
        await Promise.all([
          page.waitForNavigation({ waitUntil: 'networkidle2', timeout: this.timeout }),
          oauthButton.click()
        ]);
        results.steps[results.steps.length - 1].status = 'completed';
        results.steps[results.steps.length - 1].url = page.url();

        console.log(`  ✓ Redirected to: ${page.url()}`);
      }

      // Step 4: Should now be on Dex login page
      results.steps.push({ step: 'verify_dex_page', status: 'started' });

      const currentUrl = page.url();
      if (!currentUrl.includes('dex') && !currentUrl.includes(':5556')) {
        throw new Error(`Not on Dex login page. Current URL: ${currentUrl}`);
      }

      console.log('  ✓ On Dex login page');
      results.steps[results.steps.length - 1].status = 'completed';

      // Step 5: Fill in login form
      results.steps.push({ step: 'fill_login_form', status: 'started' });

      // Wait for login form
      await page.waitForSelector('input[name="login"]', { timeout: 5000 });
      await page.waitForSelector('input[name="password"]', { timeout: 5000 });

      // Fill credentials
      await page.type('input[name="login"]', this.username);
      await page.type('input[name="password"]', this.password);

      console.log(`  ✓ Filled credentials (username: ${this.username})`);
      results.steps[results.steps.length - 1].status = 'completed';

      // Step 6: Submit login
      results.steps.push({ step: 'submit_login', status: 'started' });

      await Promise.all([
        page.waitForNavigation({ waitUntil: 'networkidle2', timeout: this.timeout }),
        page.click('button[type="submit"]')
      ]);

      console.log(`  ✓ Login submitted, now at: ${page.url()}`);
      results.steps[results.steps.length - 1].status = 'completed';
      results.steps[results.steps.length - 1].url = page.url();

      // Step 7: Handle approval page if present
      const afterLoginUrl = page.url();
      if (afterLoginUrl.includes('approval')) {
        results.steps.push({ step: 'approve_consent', status: 'started' });
        console.log('  → Approval page detected, submitting...');

        await Promise.all([
          page.waitForNavigation({ waitUntil: 'networkidle2', timeout: this.timeout }),
          page.click('button[value="approve"]')
        ]);

        console.log(`  ✓ Consent approved, now at: ${page.url()}`);
        results.steps[results.steps.length - 1].status = 'completed';
        results.steps[results.steps.length - 1].url = page.url();
      }

      // Step 8: Verify back on service
      results.steps.push({ step: 'verify_logged_in', status: 'started' });

      const finalUrl = page.url();
      if (!finalUrl.includes(serviceName)) {
        throw new Error(`Not redirected back to ${serviceName}. Final URL: ${finalUrl}`);
      }

      console.log(`  ✓ Redirected back to ${serviceName}`);

      // For Grafana, verify we're logged in by checking for user menu or main page elements
      if (serviceName === 'grafana') {
        // Wait for main page to load (not login page)
        await page.waitForTimeout(2000); // Give page time to load

        // Check if we're NOT on the login page
        const isOnLoginPage = await page.evaluate(() => {
          return document.body.innerHTML.toLowerCase().includes('sign in');
        });

        if (isOnLoginPage) {
          throw new Error('Still on login page after OAuth flow');
        }

        console.log('  ✓ Confirmed logged in (not on login page)');
      }

      results.steps[results.steps.length - 1].status = 'completed';
      results.steps[results.steps.length - 1].verified = true;

      // Success!
      results.success = true;
      results.message = `OIDC login flow completed successfully for ${serviceName}`;
      results.details.finalUrl = finalUrl;
      results.details.stepsCompleted = results.steps.length;

      console.log(`\n✅ Test passed: ${results.message}`);

    } catch (error) {
      results.success = false;
      results.message = error.message;
      results.details.error = error.message;
      results.details.stack = error.stack;

      // Mark current step as failed
      if (results.steps.length > 0) {
        const currentStep = results.steps[results.steps.length - 1];
        if (currentStep.status === 'started') {
          currentStep.status = 'failed';
          currentStep.error = error.message;
        }
      }

      console.error(`\n❌ Test failed: ${error.message}`);
    } finally {
      if (page) {
        await page.close().catch(() => {});
      }
      if (browser) {
        await browser.disconnect().catch(() => {});
      }
    }

    results.duration = Date.now() - startTime;
    return results;
  }

  /**
   * Generate Prometheus metrics from test results
   */
  generateMetrics(results, serviceName = 'grafana') {
    const labels = `service="${serviceName}"`;
    const successValue = results.success ? 1 : 0;

    return [
      `oidc_login_test_success{${labels}} ${successValue}`,
      `oidc_login_test_duration_ms{${labels}} ${results.duration}`,
      `oidc_login_test_steps_completed{${labels}} ${results.steps.filter(s => s.status === 'completed').length}`,
      `oidc_login_test_steps_total{${labels}} ${results.steps.length}`
    ].join('\n');
  }
}

module.exports = OIDCLoginTest;

// CLI usage
if (require.main === module) {
  const test = new OIDCLoginTest();
  const serviceName = process.argv[2] || 'grafana';

  test.runTest(serviceName)
    .then(results => {
      console.log('\n📊 Test Results:');
      console.log(JSON.stringify(results, null, 2));

      console.log('\n📈 Prometheus Metrics:');
      console.log(test.generateMetrics(results, serviceName));

      process.exit(results.success ? 0 : 1);
    })
    .catch(error => {
      console.error('Fatal error:', error);
      process.exit(1);
    });
}
