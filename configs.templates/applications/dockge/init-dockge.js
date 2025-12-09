// Auto-create Dockge admin account using stack-wide credentials
const http = require('http');

const host = process.env.DOCKGE_HOST || 'dockge';
const port = process.env.DOCKGE_PORT || '5001';
const adminUser = process.env.DOCKGE_ADMIN_USER || 'admin';
const adminPass = process.env.DOCKGE_ADMIN_PASSWORD || 'admin';

console.log(`[init-dockge] Checking Dockge at ${host}:${port}...`);

// Function to make HTTP request
function httpRequest(options, data) {
  return new Promise((resolve, reject) => {
    const req = http.request(options, (res) => {
      let body = '';
      res.on('data', (chunk) => body += chunk);
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode, body: body ? JSON.parse(body) : null });
        } catch (e) {
          resolve({ status: res.statusCode, body: body });
        }
      });
    });
    req.on('error', reject);
    if (data) req.write(JSON.stringify(data));
    req.end();
  });
}

async function setupDockge() {
  try {
    // Check if setup is needed
    console.log('[init-dockge] Checking if setup is required...');
    const checkRes = await httpRequest({
      hostname: host,
      port: port,
      path: '/api/need-setup',
      method: 'GET',
      headers: { 'Content-Type': 'application/json' }
    });

    if (checkRes.body && checkRes.body.needSetup === true) {
      console.log('[init-dockge] Setup required - creating admin account...');
      console.log(`[init-dockge] Username: ${adminUser}`);

      const setupRes = await httpRequest({
        hostname: host,
        port: port,
        path: '/api/setup',
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      }, {
        username: adminUser,
        password: adminPass
      });

      if (setupRes.status === 200) {
        console.log('[init-dockge] ✓ Admin account created successfully!');
      } else {
        console.log(`[init-dockge] Setup response: ${setupRes.status}`);
        console.log(`[init-dockge] Body: ${JSON.stringify(setupRes.body)}`);
      }
    } else {
      console.log('[init-dockge] Setup not needed - admin account already exists');
    }

    process.exit(0);
  } catch (err) {
    console.error('[init-dockge] Error:', err.message);
    process.exit(1);
  }
}

setupDockge();
