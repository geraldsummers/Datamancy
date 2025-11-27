// screenshot-loop.js — Continuous puppeteer loop capturing screenshots and analyzing them with LocalAI
// Takes screenshots of bootstrap services and sends them to LocalAI vision model for analysis

const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer');
const https = require('https');
const http = require('http');

const DOMAIN = process.env.DOMAIN || 'project-saturn.com';
const SCREEN_DIR = process.env.SCREEN_DIR || '/screenshots';
const USERNAME = process.env.TEST_USERNAME || 'admin';
const PASSWORD = process.env.TEST_PASSWORD || 'DatamancyTest2025!';
const LOCALAI_URL = process.env.LOCALAI_URL || 'http://localai:8080';
const LITELLM_URL = process.env.LITELLM_URL || 'http://litellm:4000';
const LITELLM_API_KEY = process.env.LITELLM_API_KEY || '';
const LOOP_INTERVAL_MS = parseInt(process.env.LOOP_INTERVAL_MS || '300000'); // 5 minutes default

const SERVICES = [
  { name: 'open-webui', url: `https://open-webui.${DOMAIN}`, selector: 'body' },
  { name: 'localai', url: `https://localai.${DOMAIN}`, selector: 'body' },
  { name: 'litellm', url: `https://litellm.${DOMAIN}`, selector: 'body' },
  { name: 'kfuncdb', url: `https://kfuncdb.${DOMAIN}/healthz`, selector: 'body' },
  { name: 'lam', url: `https://lam.${DOMAIN}`, selector: 'body' },
  { name: 'portainer', url: `https://portainer.${DOMAIN}`, selector: 'body' },
  { name: 'auth', url: `https://auth.${DOMAIN}`, selector: 'body' },
];

function screenshotPath(name) {
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  return path.join(SCREEN_DIR, `${name}-${ts}.png`);
}

function logPath(name) {
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  return path.join(SCREEN_DIR, `${name}-analysis-${ts}.json`);
}

async function loginAuthelia(page, targetUrl) {
  try {
    await page.goto(targetUrl, { waitUntil: 'networkidle2', timeout: 30000 });
    const url = page.url();

    // Check if redirected to auth
    if (!/auth\./.test(url)) {
      console.log(`  [auth] No auth redirect for ${targetUrl}, already authenticated or bypassed`);
      return;
    }

    console.log(`  [auth] Logging in via Authelia for ${targetUrl}`);
    await page.waitForSelector('input[name="username"], input#username', { timeout: 15000 });

    // Type credentials
    const usernameInput = await page.$('input[name="username"]') || await page.$('input#username');
    const passwordInput = await page.$('input[name="password"]') || await page.$('input#password');

    if (usernameInput) await usernameInput.type(USERNAME, { delay: 15 });
    if (passwordInput) await passwordInput.type(PASSWORD, { delay: 15 });

    // Submit
    const submitBtn = await page.$('button[type="submit"]') || await page.$('button#btn-sign-in');
    if (submitBtn) {
      await submitBtn.click();
      await page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 30000 });
      console.log(`  [auth] Login successful`);
    }
  } catch (err) {
    console.warn(`  [auth] Warning during auth: ${err.message}`);
  }
}

async function analyzeScreenshot(imagePath, serviceName) {
  return new Promise((resolve, reject) => {
    // Read image and convert to base64
    const imageBuffer = fs.readFileSync(imagePath);
    const base64Image = imageBuffer.toString('base64');

    // Prepare vision API request for LocalAI
    const payload = JSON.stringify({
      model: 'vision',
      messages: [
        {
          role: 'user',
          content: [
            {
              type: 'text',
              text: `Analyze this screenshot of the ${serviceName} service. Describe what you see, identify any errors, issues, or anomalies. Is the service functioning correctly? What elements are visible on the page?`
            },
            {
              type: 'image_url',
              image_url: {
                url: `data:image/png;base64,${base64Image}`
              }
            }
          ]
        }
      ],
      max_tokens: 500
    });

    const url = new URL(`${LOCALAI_URL}/v1/chat/completions`);
    const client = url.protocol === 'https:' ? https : http;

    const options = {
      hostname: url.hostname,
      port: url.port || (url.protocol === 'https:' ? 443 : 80),
      path: url.pathname,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload)
      }
    };

    const req = client.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        try {
          const result = JSON.parse(data);
          if (result.choices && result.choices[0] && result.choices[0].message) {
            resolve(result.choices[0].message.content);
          } else {
            resolve(`Analysis completed but no content returned: ${data.substring(0, 200)}`);
          }
        } catch (err) {
          reject(new Error(`Failed to parse LocalAI response: ${err.message}`));
        }
      });
    });

    req.on('error', (err) => {
      reject(new Error(`LocalAI request failed: ${err.message}`));
    });

    req.write(payload);
    req.end();
  });
}

async function captureAndAnalyze(page, service) {
  const timestamp = new Date().toISOString();
  console.log(`\n[${timestamp}] Processing: ${service.name}`);

  try {
    // Navigate to service (with auth if needed)
    await loginAuthelia(page, service.url);

    // Wait for page to load
    await page.waitForSelector(service.selector, { timeout: 15000 });
    await page.waitForTimeout(2000); // Let page settle

    // Take screenshot
    const screenshotFile = screenshotPath(service.name);
    await page.screenshot({ path: screenshotFile, fullPage: false });
    console.log(`  [screenshot] Saved to ${screenshotFile}`);

    // Analyze with LocalAI vision
    console.log(`  [ai] Analyzing screenshot with LocalAI vision model...`);
    const analysis = await analyzeScreenshot(screenshotFile, service.name);
    console.log(`  [ai] Analysis: ${analysis.substring(0, 200)}...`);

    // Save analysis results
    const analysisLog = {
      timestamp,
      service: service.name,
      url: service.url,
      screenshot: screenshotFile,
      analysis: analysis,
      status: 'success'
    };

    const logFile = logPath(service.name);
    fs.writeFileSync(logFile, JSON.stringify(analysisLog, null, 2));
    console.log(`  [log] Analysis saved to ${logFile}`);

    return { success: true, service: service.name, analysis };

  } catch (err) {
    console.error(`  [error] Failed to process ${service.name}: ${err.message}`);

    // Try to capture error screenshot
    try {
      const errorScreenshot = screenshotPath(`${service.name}-error`);
      await page.screenshot({ path: errorScreenshot });
      console.log(`  [error-screenshot] Saved to ${errorScreenshot}`);
    } catch (_) {}

    // Save error log
    const errorLog = {
      timestamp,
      service: service.name,
      url: service.url,
      error: err.message,
      status: 'error'
    };
    fs.writeFileSync(logPath(`${service.name}-error`), JSON.stringify(errorLog, null, 2));

    return { success: false, service: service.name, error: err.message };
  }
}

async function runLoop() {
  await fs.promises.mkdir(SCREEN_DIR, { recursive: true });

  console.log('='.repeat(80));
  console.log('Screenshot Analysis Loop Started');
  console.log('='.repeat(80));
  console.log(`Domain: ${DOMAIN}`);
  console.log(`LocalAI URL: ${LOCALAI_URL}`);
  console.log(`Screenshot Directory: ${SCREEN_DIR}`);
  console.log(`Loop Interval: ${LOOP_INTERVAL_MS / 1000}s`);
  console.log(`Services to monitor: ${SERVICES.map(s => s.name).join(', ')}`);
  console.log('='.repeat(80));

  const browser = await puppeteer.launch({
    headless: 'new',
    args: [
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--disable-software-rasterizer'
    ]
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  page.setDefaultTimeout(30000);

  // Main loop
  let iteration = 1;
  while (true) {
    console.log(`\n${'#'.repeat(80)}`);
    console.log(`ITERATION ${iteration} - ${new Date().toISOString()}`);
    console.log('#'.repeat(80));

    const results = [];
    for (const service of SERVICES) {
      const result = await captureAndAnalyze(page, service);
      results.push(result);

      // Small delay between services
      await new Promise(resolve => setTimeout(resolve, 2000));
    }

    // Summary
    const successful = results.filter(r => r.success).length;
    const failed = results.filter(r => !r.success).length;
    console.log(`\n${'='.repeat(80)}`);
    console.log(`Iteration ${iteration} Complete: ${successful} successful, ${failed} failed`);
    console.log(`Next run in ${LOOP_INTERVAL_MS / 1000}s...`);
    console.log('='.repeat(80));

    iteration++;

    // Wait before next iteration
    await new Promise(resolve => setTimeout(resolve, LOOP_INTERVAL_MS));
  }
}

// Start the loop
runLoop().catch(err => {
  console.error('Fatal error in screenshot loop:', err);
  process.exit(1);
});
