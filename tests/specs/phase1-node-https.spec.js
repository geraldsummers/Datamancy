// Phase 1 HTTPS Validation - Using Node.js native HTTPS
// Validates: TLS chain of trust, single front door, path routing
// Uses NODE_EXTRA_CA_CERTS which is already configured

const { test, expect } = require('@playwright/test');
const https = require('https');
const { promisify } = require('util');

async function httpsGet(url) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve({ status: res.statusCode, data, headers: res.headers }));
    }).on('error', reject);
  });
}

test.describe('Phase 1 - HTTPS Validation with Trusted CA', () => {
  test('Homepage loads over HTTPS with valid certificate', async () => {
    const result = await httpsGet('https://stack.local/');
    expect(result.status).toBe(200);
    expect(result.data).toContain('Datamancy'); // Homepage content
    console.log('✓ Homepage loaded over trusted HTTPS');
  });

  test('Grafana loads over HTTPS with valid certificate', async () => {
    const result = await httpsGet('https://stack.local/grafana/');
    expect(result.status).toBe(200);
    expect(result.data.length).toBeGreaterThan(0);
    console.log('✓ Grafana loaded over trusted HTTPS');
  });

  test('Traefik dashboard loads over HTTPS with valid certificate', async () => {
    const result = await httpsGet('https://stack.local/dashboard/');
    expect(result.status).toBe(200);
    expect(result.data.length).toBeGreaterThan(0);
    console.log('✓ Traefik dashboard loaded over trusted HTTPS');
  });

  test('TLS connection uses valid certificate chain', async () => {
    const result = await httpsGet('https://stack.local/');
    expect(result.status).toBe(200);
    // If we got here without errors, the cert chain is valid (NODE_EXTRA_CA_CERTS worked)
    console.log('✓ Certificate chain validated by Node.js');
  });
});
