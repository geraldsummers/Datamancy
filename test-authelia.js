const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu'
    ],
    executablePath: '/usr/bin/chromium-browser'
  });

  const screenshotDir = '/screenshots/authelia';
  if (!fs.existsSync(screenshotDir)) {
    fs.mkdirSync(screenshotDir, { recursive: true });
  }

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 800 });

    console.log('[Authelia] Navigating to login page...');
    await page.goto('https://auth.project-saturn.com', {
      waitUntil: 'networkidle2',
      timeout: 30000
    });

    await page.screenshot({
      path: path.join(screenshotDir, '01-login-page.png'),
      quality: 50
    });
    console.log('[Authelia] Screenshot: login page captured');

    // Fill login form
    await page.waitForSelector('input[name="username"]', { timeout: 10000 });
    await page.type('input[name="username"]', 'admin');
    await page.type('input[name="password"]', 'DatamancyTest2025!');

    await page.screenshot({
      path: path.join(screenshotDir, '02-credentials-entered.png'),
      quality: 50
    });
    console.log('[Authelia] Screenshot: credentials entered');

    // Submit login
    await page.click('button[type="submit"]');
    await page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 15000 });

    await page.screenshot({
      path: path.join(screenshotDir, '03-after-login.png'),
      quality: 50
    });
    console.log('[Authelia] Screenshot: after login');

    console.log('[Authelia] ✅ Test completed successfully');
  } catch (error) {
    console.error('[Authelia] ❌ Test failed:', error.message);
    process.exit(1);
  } finally {
    await browser.close();
  }
})();
