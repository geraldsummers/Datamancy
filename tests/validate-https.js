#!/usr/bin/env node
// Direct Node.js HTTPS validation - proves CA trust works
const https = require('https');

const tests = [
  { name: 'Homepage', url: 'https://stack.local/' },
  { name: 'Grafana', url: 'https://stack.local/grafana/' },
  { name: 'Traefik Dashboard', url: 'https://stack.local/dashboard/' },
];

let passed = 0;
let failed = 0;

async function testURL(name, url) {
  return new Promise((resolve) => {
    https.get(url, (res) => {
      if (res.statusCode === 200) {
        console.log(`✓ ${name}: HTTPS with trusted CA (${res.statusCode})`);
        passed++;
        resolve(true);
      } else {
        console.log(`✗ ${name}: Got ${res.statusCode}`);
        failed++;
        resolve(false);
      }
    }).on('error', (err) => {
      console.log(`✗ ${name}: ${err.message}`);
      failed++;
      resolve(false);
    });
  });
}

(async () => {
  console.log('==> Phase 1 Browser HTTPS Validation\n');
  
  for (const test of tests) {
    await testURL(test.name, test.url);
  }
  
  console.log(`\n==> Results: ${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
})();
