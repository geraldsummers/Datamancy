const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const BASE_URL = 'https://homepage.project-saturn.com';
const AUTH_URL = 'https://auth.project-saturn.com';
const SCREENSHOT_DIR = './screenshots/homepage';
const CREDENTIALS = {
  username: 'admin',
  password: 'DatamancyTest2025!'
};

async function ensureDir(dir) {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
}

async function saveScreenshot(page, name) {
  const filepath = path.join(SCREENSHOT_DIR, name);
  await page.screenshot({ path: filepath, fullPage: true });
  console.log(`Screenshot saved: ${filepath}`);
}

async function testHomepage() {
  console.log('Starting Homepage SSO test...');
  ensureDir(SCREENSHOT_DIR);

  const browser = await chromium.launch({
    headless: true,
    args: ['--ignore-certificate-errors', '--no-sandbox', '--disable-setuid-sandbox']
  });

  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    viewport: { width: 1920, height: 1080 }
  });

  const page = await context.newPage();

  try {
    // Step 1: Navigate to Homepage
    console.log('Step 1: Navigating to Homepage...');
    await page.goto(BASE_URL, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(2000);
    await saveScreenshot(page, '01-landing.png');

    // Step 2: Should be redirected to Authelia
    console.log('Step 2: Checking for Authelia login redirect...');
    const currentUrl = page.url();
    console.log('Current URL:', currentUrl);

    if (currentUrl.includes('auth.project-saturn.com')) {
      console.log('Redirected to Authelia login page');
      await saveScreenshot(page, '02-authelia-login-form.png');

      // Step 3: Fill in credentials
      console.log('Step 3: Filling in credentials...');
      await page.getByLabel('Username').fill(CREDENTIALS.username);
      await page.getByRole('textbox', { name: 'Password' }).fill(CREDENTIALS.password);
      await saveScreenshot(page, '03-authelia-credentials-filled.png');

      // Step 4: Submit login
      console.log('Step 4: Submitting login...');
      await page.getByRole('button', { name: 'Sign in' }).click();
      await page.waitForTimeout(3000);
      await saveScreenshot(page, '04-after-login-submit.png');

      // Step 5: Check if we're redirected back to Homepage
      console.log('Step 5: Checking redirection...');
      await page.waitForURL((url) => url.toString().includes('homepage.project-saturn.com'), { timeout: 15000 });
      await page.waitForTimeout(2000);
      await saveScreenshot(page, '05-homepage-authenticated.png');

      console.log('✅ Successfully authenticated and accessed Homepage!');
      console.log('Final URL:', page.url());
    } else if (currentUrl.includes('homepage.project-saturn.com')) {
      console.log('Already at Homepage - checking if authenticated or bypassed...');
      await saveScreenshot(page, '99-direct-access.png');
    } else {
      console.log('Unexpected URL:', currentUrl);
      await saveScreenshot(page, '99-unexpected.png');
    }

  } catch (error) {
    console.error('Test failed:', error.message);
    await saveScreenshot(page, '99-error.png');
    throw error;
  } finally {
    await browser.close();
  }
}

testHomepage().catch(err => {
  console.error('Fatal error:', err);
  process.exit(1);
});
