/**
 * Global teardown - runs once after all tests
 *
 * Deletes the test user from LDAP to clean up
 */

import * as fs from 'fs';
import * as path from 'path';
import { LDAPClient } from '../utils/ldap-client';

async function globalTeardown() {
  console.log('\n╔═══════════════════════════════════════════════════════════════════════════╗');
  console.log('║  Playwright Global Teardown - LDAP User Cleanup                          ║');
  console.log('╚═══════════════════════════════════════════════════════════════════════════╝\n');

  // Configuration
  const ldapUrl = process.env.LDAP_URL || 'ldap://localhost:10389';
  const ldapAdminDn = process.env.LDAP_ADMIN_DN || 'cn=admin,dc=datamancy,dc=net';
  const ldapAdminPassword = process.env.LDAP_ADMIN_PASSWORD || 'admin';

  // Load test user credentials
  const credsPath = path.join(__dirname, '../.auth/test-user.json');

  if (!fs.existsSync(credsPath)) {
    console.warn('⚠️  No test user credentials found - skipping cleanup');
    return;
  }

  const testUser = JSON.parse(fs.readFileSync(credsPath, 'utf-8'));
  console.log(`🔍 Found test user: ${testUser.username}\n`);

  // Create LDAP client
  const ldapClient = new LDAPClient({
    url: ldapUrl,
    adminDn: ldapAdminDn,
    adminPassword: ldapAdminPassword,
  });

  // Delete user from LDAP
  try {
    await ldapClient.deleteUser(testUser.username);
    console.log('\n✅ LDAP user cleaned up successfully\n');
  } catch (error) {
    console.error('❌ Failed to clean up LDAP user:', error);
    // Don't throw - we want teardown to complete even if cleanup fails
  }

  // Clean up auth files
  try {
    const authDir = path.join(__dirname, '../.auth');
    if (fs.existsSync(authDir)) {
      fs.rmSync(authDir, { recursive: true, force: true });
      console.log('🗑️  Auth files cleaned up\n');
    }
  } catch (error) {
    console.warn('⚠️  Failed to clean up auth files:', error);
  }

  console.log('✅ Global teardown complete!\n');
}

export default globalTeardown;
