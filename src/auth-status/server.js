const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const { register, Counter, Gauge, Histogram } = require('prom-client');
const AuthTester = require('./auth-tests');
const OIDCLoginTest = require('./oidc-login-test');
const config = require('./config.json');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = process.env.PORT || 3000;
const BASE_DOMAIN = process.env.BASE_DOMAIN || 'lab.localhost';
const TEST_INTERVAL = parseInt(process.env.TEST_INTERVAL || '30000'); // 30 seconds

// Prometheus metrics
const authTestSuccess = new Gauge({
  name: 'auth_test_success',
  help: 'Authentication test success status (1=success, 0=failure)',
  labelNames: ['service', 'auth_type']
});

const authTestDuration = new Histogram({
  name: 'auth_test_duration_seconds',
  help: 'Duration of authentication tests in seconds',
  labelNames: ['service', 'auth_type'],
  buckets: [0.1, 0.5, 1, 2, 5, 10]
});

const authTestTotal = new Counter({
  name: 'auth_test_total',
  help: 'Total number of authentication tests run',
  labelNames: ['service', 'auth_type', 'status']
});

const oidcLoginTestSuccess = new Gauge({
  name: 'oidc_login_test_success',
  help: 'OIDC full login flow test success (1=success, 0=failure)',
  labelNames: ['service']
});

const oidcLoginTestDuration = new Histogram({
  name: 'oidc_login_test_duration_seconds',
  help: 'Duration of OIDC login flow tests in seconds',
  labelNames: ['service'],
  buckets: [1, 5, 10, 15, 30, 60]
});

const oidcLoginTestSteps = new Gauge({
  name: 'oidc_login_test_steps_completed',
  help: 'Number of steps completed in OIDC login flow',
  labelNames: ['service']
});

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.static('public'));

// Initialize auth tester
const authTester = new AuthTester(BASE_DOMAIN, config);
const oidcLoginTester = new OIDCLoginTest({ baseDomain: BASE_DOMAIN });

// Store for test results
let testResults = {
  lastUpdate: null,
  dex: { status: 'unknown', message: '', timestamp: null },
  services: {},
  oidcLogin: { status: 'unknown', message: '', timestamp: null }
};

// WebSocket connections
const clients = new Set();

wss.on('connection', (ws) => {
  console.log('WebSocket client connected');
  clients.add(ws);

  // Send current status immediately
  ws.send(JSON.stringify({ type: 'status', data: testResults }));

  ws.on('close', () => {
    console.log('WebSocket client disconnected');
    clients.delete(ws);
  });

  ws.on('error', (error) => {
    console.error('WebSocket error:', error);
    clients.delete(ws);
  });
});

// Broadcast to all WebSocket clients
function broadcast(data) {
  const message = JSON.stringify(data);
  clients.forEach(client => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(message);
    }
  });
}

// Run authentication tests
async function runTests() {
  console.log('Running authentication tests...');
  const startTime = Date.now();

  try {
    // Test Dex
    const dexResult = await authTester.testDex();
    testResults.dex = {
      status: dexResult.success ? 'healthy' : 'unhealthy',
      message: dexResult.message,
      details: dexResult.details,
      timestamp: new Date().toISOString(),
      duration: dexResult.duration
    };

    authTestSuccess.set({ service: 'dex', auth_type: 'provider' }, dexResult.success ? 1 : 0);
    authTestDuration.observe({ service: 'dex', auth_type: 'provider' }, dexResult.duration / 1000);
    authTestTotal.inc({ service: 'dex', auth_type: 'provider', status: dexResult.success ? 'success' : 'failure' });

    // Test each service
    for (const service of config.services) {
      const result = await authTester.testService(service);

      testResults.services[service.name] = {
        status: result.success ? 'healthy' : 'unhealthy',
        authType: service.authType,
        message: result.message,
        details: result.details,
        timestamp: new Date().toISOString(),
        duration: result.duration,
        url: service.url
      };

      authTestSuccess.set({ service: service.name, auth_type: service.authType }, result.success ? 1 : 0);
      authTestDuration.observe({ service: service.name, auth_type: service.authType }, result.duration / 1000);
      authTestTotal.inc({ service: service.name, auth_type: service.authType, status: result.success ? 'success' : 'failure' });
    }

    // Run OIDC full login flow test (Grafana)
    try {
      console.log('Running OIDC full login flow test...');
      const oidcResult = await oidcLoginTester.runTest('grafana');

      testResults.oidcLogin = {
        status: oidcResult.success ? 'healthy' : 'unhealthy',
        message: oidcResult.message,
        steps: oidcResult.steps,
        details: oidcResult.details,
        timestamp: new Date().toISOString(),
        duration: oidcResult.duration
      };

      oidcLoginTestSuccess.set({ service: 'grafana' }, oidcResult.success ? 1 : 0);
      oidcLoginTestDuration.observe({ service: 'grafana' }, oidcResult.duration / 1000);
      oidcLoginTestSteps.set({ service: 'grafana' }, oidcResult.steps.filter(s => s.status === 'completed').length);

      console.log(`OIDC login test: ${oidcResult.success ? '✓' : '✗'} ${oidcResult.message}`);
    } catch (oidcError) {
      console.error('OIDC login test failed:', oidcError);
      testResults.oidcLogin = {
        status: 'unhealthy',
        message: `Test error: ${oidcError.message}`,
        timestamp: new Date().toISOString()
      };
      oidcLoginTestSuccess.set({ service: 'grafana' }, 0);
    }

    testResults.lastUpdate = new Date().toISOString();

    // Broadcast results
    broadcast({ type: 'status', data: testResults });

    const duration = Date.now() - startTime;
    console.log(`Tests completed in ${duration}ms`);

  } catch (error) {
    console.error('Error running tests:', error);
    broadcast({ type: 'error', message: error.message });
  }
}

// API Routes

// Health check
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime() });
});

// Get current status
app.get('/api/status', (req, res) => {
  res.json(testResults);
});

// Get specific service status
app.get('/api/status/:service', (req, res) => {
  const serviceName = req.params.service;

  if (serviceName === 'dex') {
    res.json(testResults.dex);
  } else if (testResults.services[serviceName]) {
    res.json(testResults.services[serviceName]);
  } else {
    res.status(404).json({ error: 'Service not found' });
  }
});

// Trigger immediate test run
app.post('/api/test', async (req, res) => {
  res.json({ message: 'Test run initiated' });
  setImmediate(runTests);
});

// Test specific service
app.post('/api/test/:service', async (req, res) => {
  const serviceName = req.params.service;

  if (serviceName === 'dex') {
    const result = await authTester.testDex();
    testResults.dex = {
      status: result.success ? 'healthy' : 'unhealthy',
      message: result.message,
      details: result.details,
      timestamp: new Date().toISOString(),
      duration: result.duration
    };
    broadcast({ type: 'update', service: 'dex', data: testResults.dex });
    res.json(testResults.dex);
  } else {
    const service = config.services.find(s => s.name === serviceName);
    if (!service) {
      return res.status(404).json({ error: 'Service not found' });
    }

    const result = await authTester.testService(service);
    testResults.services[serviceName] = {
      status: result.success ? 'healthy' : 'unhealthy',
      authType: service.authType,
      message: result.message,
      details: result.details,
      timestamp: new Date().toISOString(),
      duration: result.duration,
      url: service.url
    };
    broadcast({ type: 'update', service: serviceName, data: testResults.services[serviceName] });
    res.json(testResults.services[serviceName]);
  }
});

// Trigger OIDC full login flow test
app.post('/api/test/oidc/:service', async (req, res) => {
  const serviceName = req.params.service;

  try {
    const oidcResult = await oidcLoginTester.runTest(serviceName);

    testResults.oidcLogin = {
      status: oidcResult.success ? 'healthy' : 'unhealthy',
      message: oidcResult.message,
      steps: oidcResult.steps,
      details: oidcResult.details,
      timestamp: new Date().toISOString(),
      duration: oidcResult.duration
    };

    oidcLoginTestSuccess.set({ service: serviceName }, oidcResult.success ? 1 : 0);
    oidcLoginTestDuration.observe({ service: serviceName }, oidcResult.duration / 1000);
    oidcLoginTestSteps.set({ service: serviceName }, oidcResult.steps.filter(s => s.status === 'completed').length);

    broadcast({ type: 'update', service: 'oidc_login', data: testResults.oidcLogin });
    res.json(testResults.oidcLogin);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Prometheus metrics endpoint
app.get('/metrics', async (req, res) => {
  res.set('Content-Type', register.contentType);
  res.end(await register.metrics());
});

// Start server
server.listen(PORT, () => {
  console.log(`Auth Status Dashboard running on port ${PORT}`);
  console.log(`Base domain: ${BASE_DOMAIN}`);
  console.log(`Test interval: ${TEST_INTERVAL}ms`);

  // Run initial tests
  runTests();

  // Schedule periodic tests
  setInterval(runTests, TEST_INTERVAL);
});

// Graceful shutdown
process.on('SIGTERM', () => {
  console.log('SIGTERM received, shutting down gracefully');
  server.close(() => {
    console.log('Server closed');
    process.exit(0);
  });
});
