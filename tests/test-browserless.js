// Quick Browserless test - navigate to Grafana and capture screenshot
// Uses Browserless HTTP API

const http = require('http');

const browserlessHost = process.env.BROWSERLESS_HOST || 'browserless:3000';
const token = process.env.BROWSERLESS_TOKEN || 'browserless-token-2024';
const targetUrl = process.env.TARGET_URL || 'https://stack.local/grafana/';

const payload = JSON.stringify({
  url: targetUrl,
  gotoOptions: {
    waitUntil: 'networkidle2',
    timeout: 30000
  }
});

const options = {
  hostname: browserlessHost.split(':')[0],
  port: browserlessHost.split(':')[1] || 3000,
  path: `/screenshot?token=${token}&--ignore-certificate-errors`,
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Content-Length': payload.length
  }
};

console.log('🔍 Testing Browserless -> Grafana');
console.log('Target:', targetUrl);
console.log('');

const req = http.request(options, (res) => {
  console.log('Status:', res.statusCode);

  if (res.statusCode === 200) {
    let data = [];
    res.on('data', (chunk) => {
      data.push(chunk);
    });
    res.on('end', () => {
      const buffer = Buffer.concat(data);
      console.log('✓ Screenshot captured:', buffer.length, 'bytes');
      console.log('✓ Successfully loaded Grafana via Browserless over HTTPS');
      console.log('');
      console.log('Phase 1 browser emulation test: PASSED');
      process.exit(0);
    });
  } else {
    console.error('✗ Failed with status:', res.statusCode);
    let errorData = '';
    res.on('data', (chunk) => {
      errorData += chunk.toString();
    });
    res.on('end', () => {
      console.error('Error:', errorData);
      process.exit(1);
    });
  }
});

req.on('error', (e) => {
  console.error('✗ Error:', e.message);
  process.exit(1);
});

req.write(payload);
req.end();
