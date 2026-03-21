/**
 * Global teardown - runs once after all tests
 *
 * Deletes the test user from LDAP to clean up
 */

import * as fs from 'fs';
import * as path from 'path';
import { LDAPClient } from '../utils/ldap-client';

function resolveCredsPath(): string | null {
  const authDirCandidates = [
    process.env.PLAYWRIGHT_AUTH_DIR,
    '/app/playwright-tests/.auth',
    path.resolve(process.cwd(), '.auth'),
    path.resolve(__dirname, '../.auth'),
  ].filter((candidate): candidate is string => Boolean(candidate && candidate.trim().length > 0));

  for (const authDir of authDirCandidates) {
    const credsPath = path.join(authDir, 'test-user.json');
    if (fs.existsSync(credsPath)) {
      return credsPath;
    }
  }

  return null;
}

async function globalTeardown() {
  console.log('\n╔═══════════════════════════════════════════════════════════════════════════╗');
  console.log('║  Playwright Global Teardown - LDAP User Cleanup                          ║');
  console.log('╚═══════════════════════════════════════════════════════════════════════════╝\n');

  // Configuration
  const ldapUrl = process.env.LDAP_URL || 'ldap://localhost:10389';
  const ldapAdminDn = process.env.LDAP_ADMIN_DN || 'cn=admin,dc=datamancy,dc=net';
  const ldapAdminPassword = process.env.LDAP_ADMIN_PASSWORD || 'admin';
  const stackAdminUser = process.env.STACK_ADMIN_USER;
  const preservedUsers = [stackAdminUser].filter((username): username is string => Boolean(username && username.trim()));
  const ldapClient = new LDAPClient({
    url: ldapUrl,
    adminDn: ldapAdminDn,
    adminPassword: ldapAdminPassword,
  });

  // Load test user credentials
  const credsPath = resolveCredsPath();

  if (!credsPath) {
    console.warn('⚠️  No test user credentials found - skipping direct user cleanup');
  } else {
    const testUser = JSON.parse(fs.readFileSync(credsPath, 'utf-8'));
    console.log(`🔍 Found test user: ${testUser.username}\n`);

    const managedUser = testUser.managed !== false;
    if (!managedUser || (stackAdminUser && testUser.username === stackAdminUser)) {
      console.log('⚠️  Skipping LDAP cleanup for non-managed or stack admin user\n');
    } else {
      try {
        await ldapClient.deleteUser(testUser.username);
        console.log('\n✅ LDAP user cleaned up successfully\n');
      } catch (error) {
        console.error('❌ Failed to clean up LDAP user:', error);
        // Don't throw - we want teardown to complete even if cleanup fails
      }
    }
  }

  try {
    const removedStaleUsers = await ldapClient.cleanupManagedTestUsers(preservedUsers);
    if (removedStaleUsers.length > 0) {
      console.log(`🧹 Removed stale managed LDAP users: ${removedStaleUsers.join(', ')}\n`);
    }
  } catch (error) {
    console.error('❌ Failed to remove stale LDAP users:', error);
  }

  // Clean up auth files
  try {
    const authDir = credsPath ? path.dirname(credsPath) : null;
    if (authDir && fs.existsSync(authDir)) {
      fs.rmSync(authDir, { recursive: true, force: true });
      console.log('🗑️  Auth files cleaned up\n');
    }
  } catch (error) {
    console.warn('⚠️  Failed to clean up auth files:', error);
  }

  console.log('✅ Global teardown complete!\n');

  // Remind users to review screenshots
  console.log('╔═══════════════════════════════════════════════════════════════════════════╗');
  console.log('║  📸 SCREENSHOT REVIEW REQUIRED                                            ║');
  console.log('╚═══════════════════════════════════════════════════════════════════════════╝\n');
  console.log('⚠️  IMPORTANT: Test pass/fail status is NOT sufficient for validation!\n');
  console.log('📋 Next Steps:');
  console.log('   1. Review ALL screenshots in: /app/test-results/screenshots/');
  console.log('   2. Check failure screenshots in test-results/**/test-failed-*.png');
  console.log('   3. Verify each page shows the CORRECT service (not error pages)');
  console.log('   4. Compare screenshots against expected UI patterns\n');
  console.log('💡 Why? Pattern matching can give false positives/negatives.');
  console.log('   Only visual validation confirms services are truly working.\n');
  console.log('🔍 Copy screenshots from container:');
  console.log('   docker cp $(docker compose ps -q test-playwright-e2e):/app/test-results ~/test-results-$(date +%Y%m%d)\n');
  console.log('📊 Generate HTML report (if available):');
  console.log('   npm run screenshot-report\n');
  console.log('═══════════════════════════════════════════════════════════════════════════\n');
}

export default globalTeardown;
