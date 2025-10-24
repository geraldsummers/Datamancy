const puppeteer = require('puppeteer-core');

async function testOIDCFlow() {
  console.log('=== Starting OIDC Login Flow Test ===\n');

  const browser = await puppeteer.connect({
    browserWSEndpoint: 'ws://localhost:3000'
  });

  const page = await browser.newPage();

  try {
    // Set viewport
    await page.setViewport({ width: 1280, height: 800 });

    // Step 1: Navigate to Grafana
    console.log('Step 1: Navigating to Grafana...');
    await page.goto('https://grafana.lab.localhost', {
      waitUntil: 'networkidle2',
      timeout: 30000
    });
    console.log('  ✓ Loaded:', page.url());

    // Step 2: Find and click OAuth login button
    console.log('\nStep 2: Looking for OAuth login button...');
    await page.waitForSelector('a[href*="login/generic_oauth"]', { timeout: 10000 });
    console.log('  ✓ Found OAuth login button');

    // Click and wait for navigation
    console.log('\nStep 3: Clicking OAuth login and waiting for Dex...');
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 15000 }),
      page.click('a[href*="login/generic_oauth"]')
    ]);

    const dexUrl = page.url();
    console.log('  Current URL:', dexUrl);

    if (!dexUrl.includes('dex')) {
      throw new Error('Not redirected to Dex! URL: ' + dexUrl);
    }
    console.log('  ✓ Successfully redirected to Dex');

    // Step 4: Fill in login form
    console.log('\nStep 4: Filling in credentials...');
    await page.waitForSelector('input[name="login"]', { timeout: 5000 });
    await page.type('input[name="login"]', 'authtest');
    await page.type('input[name="password"]', 'TestAuth123!');
    console.log('  ✓ Credentials entered');

    // Step 5: Submit and wait for redirect
    console.log('\nStep 5: Submitting login form...');
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 20000 }),
      page.click('button[type="submit"]')
    ]);

    const finalUrl = page.url();
    console.log('  Final URL:', finalUrl);

    // Step 6: Verify we're back on Grafana
    if (!finalUrl.includes('grafana.lab.localhost')) {
      throw new Error('Not redirected back to Grafana! URL: ' + finalUrl);
    }
    console.log('  ✓ Redirected back to Grafana');

    // Step 7: Verify we're on the main page (not login)
    console.log('\nStep 6: Verifying logged in state...');
    await page.waitForTimeout(3000); // Wait for page to fully load

    const pageContent = await page.content();
    const pageTitle = await page.title();

    console.log('  Page title:', pageTitle);

    // Check if we're logged in (not on login page)
    const isOnLoginPage = pageContent.includes('Sign in') &&
                          pageContent.includes('password') &&
                          !pageContent.includes('Sign out');

    if (isOnLoginPage) {
      console.log('\n❌ FAILED: Still on login page');
      console.log('Page content preview:', pageContent.substring(0, 500));
      throw new Error('Login failed - still on login page');
    }

    // Take a screenshot
    const screenshot = await page.screenshot({
      encoding: 'base64',
      fullPage: false
    });
    console.log('  Screenshot captured (length:', screenshot.length, 'bytes)');

    console.log('  ✓ Successfully logged into Grafana!');

    // Final success message
    console.log('\n╔════════════════════════════════════════╗');
    console.log('║  ✅ OIDC LOGIN TEST PASSED!           ║');
    console.log('╚════════════════════════════════════════╝');
    console.log('\nVerified:');
    console.log('  • Grafana redirected to Dex ✓');
    console.log('  • Dex login page loaded ✓');
    console.log('  • Credentials submitted ✓');
    console.log('  • Redirected back to Grafana ✓');
    console.log('  • Successfully logged in ✓');

    await browser.close();
    return true;

  } catch (error) {
    console.error('\n❌ TEST FAILED:', error.message);

    // Take error screenshot
    try {
      const errorScreenshot = await page.screenshot({
        encoding: 'base64',
        fullPage: false
      });
      console.log('Error screenshot captured (length:', errorScreenshot.length, 'bytes)');
    } catch (e) {
      console.log('Could not capture error screenshot');
    }

    await browser.close();
    throw error;
  }
}

// Run the test
testOIDCFlow()
  .then(() => {
    console.log('\n✅ Test completed successfully');
    process.exit(0);
  })
  .catch((error) => {
    console.error('\n❌ Test failed:', error.message);
    process.exit(1);
  });
