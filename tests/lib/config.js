// tests/lib/config.js
// Centralized configuration for all Datamancy tests

module.exports = {
  // Test credentials (from CREDENTIALS.md)
  credentials: {
    username: process.env.TEST_USERNAME || 'admin',
    password: process.env.TEST_PASSWORD || 'DatamancyTest2025!',
    email: process.env.TEST_EMAIL || 'admin@project-saturn.com',
  },

  // Timeouts (all in milliseconds)
  timeouts: {
    default: parseInt(process.env.PW_TIMEOUT || '30000', 10),
    navigation: 45000,
    oidcClick: 15000,
    authRedirect: 20000,
    shortWait: 2000,
    selector: 5000,
    networkIdle: 10000,
  },

  // Browser config
  browser: {
    headless: process.env.HEADFUL !== '1',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  },

  // Screenshot config
  screenshots: {
    rootDir: process.env.SCREEN_DIR || '/tests/screenshots',
    quality: 60, // Low quality (5MB cap per prompt requirement)
    fullPage: true,
  },

  // Logging
  logging: {
    heartbeatMs: parseInt(process.env.HEARTBEAT_MS || '15000', 10),
    logNetwork: process.env.LOG_NETWORK !== '0',
  },

  // Test execution
  execution: {
    pauseBetweenServices: parseInt(process.env.PAUSE_BETWEEN || '1200', 10),
  },

  // Auth domains - PRODUCTION DOMAIN: project-saturn.com
  domains: {
    authelia: 'auth.project-saturn.com',
  },
};
