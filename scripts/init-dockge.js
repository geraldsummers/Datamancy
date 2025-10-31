#!/usr/bin/env node
// Initialize Dockge admin account via API
const http = require('http');

const DOCKGE_HOST = process.env.DOCKGE_HOST || 'dockge';
const DOCKGE_PORT = process.env.DOCKGE_PORT || 5001;
const ADMIN_USERNAME = process.env.DOCKGE_ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.DOCKGE_ADMIN_PASSWORD || 'DatamancyAdmin2025!';
const MAX_RETRIES = 30;
const RETRY_DELAY = 2000;

console.log('=== Dockge Initialization ===');

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function makeRequest(path, method = 'GET', data = null) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: DOCKGE_HOST,
      port: DOCKGE_PORT,
      path: path,
      method: method,
      headers: {
        'Content-Type': 'application/json',
      },
    };

    if (data) {
      const body = JSON.stringify(data);
      options.headers['Content-Length'] = Buffer.byteLength(body);
    }

    const req = http.request(options, (res) => {
      let body = '';
      res.on('data', (chunk) => body += chunk);
      res.on('end', () => {
        try {
          const parsed = body ? JSON.parse(body) : {};
          resolve({ status: res.statusCode, data: parsed });
        } catch (e) {
          resolve({ status: res.statusCode, data: body });
        }
      });
    });

    req.on('error', reject);

    if (data) {
      req.write(JSON.stringify(data));
    }

    req.end();
  });
}

async function waitForDockge() {
  for (let i = 0; i < MAX_RETRIES; i++) {
    try {
      console.log(`Waiting for Dockge to be ready (attempt ${i + 1}/${MAX_RETRIES})...`);
      const response = await makeRequest('/');
      if (response.status === 200 || response.status === 302) {
        console.log('✅ Dockge is ready');
        return true;
      }
    } catch (err) {
      // Connection refused, Dockge not ready yet
    }
    await sleep(RETRY_DELAY);
  }
  throw new Error('Dockge failed to start within timeout');
}

async function checkSetupStatus() {
  try {
    const response = await makeRequest('/setup');
    return response.data;
  } catch (err) {
    console.log('Error checking setup status:', err.message);
    return null;
  }
}

async function createAdminAccount() {
  try {
    const setupData = {
      username: ADMIN_USERNAME,
      password: ADMIN_PASSWORD,
    };

    console.log(`Creating admin account: ${ADMIN_USERNAME}`);
    const response = await makeRequest('/setup', 'POST', setupData);

    if (response.status === 200) {
      console.log('✅ Dockge admin account created successfully');
      return true;
    } else {
      console.log('⚠️  Setup response:', response.status, response.data);
      return false;
    }
  } catch (err) {
    console.log('Error creating admin account:', err.message);
    return false;
  }
}

async function main() {
  try {
    // Wait for Dockge to be ready
    await waitForDockge();

    // Check if setup is needed
    const setupStatus = await checkSetupStatus();

    if (setupStatus && setupStatus.needSetup === true) {
      console.log('Dockge needs setup, creating admin account...');
      await createAdminAccount();
    } else if (setupStatus && setupStatus.needSetup === false) {
      console.log('✅ Dockge already initialized');
    } else {
      console.log('⚠️  Could not determine setup status, trying to create account anyway...');
      await createAdminAccount();
    }

    console.log('=== Dockge initialization complete ===');
    process.exit(0);
  } catch (err) {
    console.error('❌ Dockge initialization failed:', err.message);
    process.exit(1);
  }
}

main();
