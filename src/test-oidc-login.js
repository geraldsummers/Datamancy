const puppeteer = require('puppeteer-core');

async function testOIDCLogin() {
  const browser = await puppeteer.connect({
    browserWSEndpoint: 'ws://browserless:3000'
  });

  try {
    const page = await browser.newPage();

    // Step 1: Navigate to Grafana
    console.log('Step 1: Navigating to Grafana...');
    await page.goto('https://grafana.lab.localhost', {
      waitUntil: 'networkidle0',
      timeout: 30000
    });

    // Step 2: Click on OAuth login button
    console.log('Step 2: Looking for OAuth login button...');
    await page.waitForSelector('a[href*="login/generic_oauth"], button:has-text("Sign in with Dex")', { timeout: 10000 });
    await page.click('a[href*="login/generic_oauth"]');

    // Step 3: Wait for Dex login page
    console.log('Step 3: Waiting for Dex login page...');
    await page.waitForNavigation({ waitUntil: 'networkidle0', timeout: 10000 });

    // Verify we're on Dex
    const url = page.url();
    console.log('Current URL:', url);
    if (!url.includes('dex')) {
      throw new Error('Not redirected to Dex! URL: ' + url);
    }

    // Step 4: Fill in credentials
    console.log('Step 4: Filling in credentials...');
    await page.waitForSelector('input[name="login"]', { timeout: 5000 });
    await page.type('input[name="login"]', 'authtest');
    await page.type('input[name="password"]', 'TestAuth123!');

    // Step 5: Submit login form
    console.log('Step 5: Submitting login form...');
    await Promise.all([
      page.click('button[type="submit"]'),
      page.waitForNavigation({ waitUntil: 'networkidle0', timeout: 15000 })
    ]);

    // Step 6: Verify redirect back to Grafana
    console.log('Step 6: Verifying redirect back to Grafana...');
    const finalUrl = page.url();
    console.log('Final URL:', finalUrl);

    if (!finalUrl.includes('grafana.lab.localhost')) {
      throw new Error('Not redirected back to Grafana! URL: ' + finalUrl);
    }

    // Step 7: Wait for Grafana to load and check we're logged in
    console.log('Step 7: Checking if logged into Grafana...');
    await page.waitForTimeout(3000); // Give Grafana time to initialize

    // Check for logged-in indicators
    const content = await page.content();
    const isLoggedIn = !content.includes('Sign in') &&
                       (content.includes('Home') ||
                        content.includes('Dashboard') ||
                        content.includes('profile-dropdown') ||
                        finalUrl.includes('/?'));

    if (!isLoggedIn) {
      console.log('Page content snippet:', content.substring(0, 500));
      throw new Error('Not successfully logged into Grafana!');
    }

    console.log('✅ SUCCESS: OIDC login flow completed successfully!');
    console.log('   - Redirected to Dex ✓');
    console.log('   - Submitted credentials ✓');
    console.log('   - Redirected back to Grafana ✓');
    console.log('   - Logged into Grafana ✓');

    return true;

  } catch (error) {
    console.error('❌ FAILED:', error.message);
    throw error;
  } finally {
    await browser.close();
  }
}

testOIDCLogin()
  .then(() => {
    console.log('\nOIDC test completed successfully!');
    process.exit(0);
  })
  .catch((error) => {
    console.error('\nOIDC test failed:', error);
    process.exit(1);
  });
