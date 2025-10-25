// Phase 3 LDAP Test - Identity & Access
// Provenance: Playwright test patterns
// Tests: LDAP directory health, user authentication (via container checks)
// Note: LDAP has no web UI - verification is via container health and CLI tools

const { test, expect } = require('@playwright/test');
const { exec } = require('child_process');
const util = require('util');
const execPromise = util.promisify(exec);

test.describe('Phase 3 - OpenLDAP', () => {
  test('LDAP container is healthy', async () => {
    const { stdout } = await execPromise('docker ps --filter name=openldap --format "{{.Status}}"');

    expect(stdout).toContain('healthy');
    console.log('✓ LDAP container is healthy');
  });

  test('LDAP directory base DN is accessible', async () => {
    const { stdout } = await execPromise(
      'docker exec openldap ldapsearch -x -H ldap://localhost -b "dc=datamancy,dc=local" -s base -LLL dn'
    );

    expect(stdout).toContain('dn: dc=datamancy,dc=local');
    console.log('✓ LDAP directory base DN is accessible');
  });

  test('LDAP test users exist', async () => {
    const { stdout } = await execPromise(
      'docker exec openldap ldapsearch -x -H ldap://localhost -b "ou=people,dc=datamancy,dc=local" -D "cn=admin,dc=datamancy,dc=local" -w "admin_password" "(uid=*)" uid -LLL'
    );

    expect(stdout).toContain('uid: admin');
    expect(stdout).toContain('uid: testuser');
    console.log('✓ LDAP test users exist (admin, testuser)');
  });

  test('LDAP user authentication works', async () => {
    // Test that testuser can authenticate
    const { stdout } = await execPromise(
      'docker exec openldap ldapwhoami -x -H ldap://localhost -D "uid=testuser,ou=people,dc=datamancy,dc=local" -w "password"'
    );

    expect(stdout).toContain('uid=testuser');
    console.log('✓ LDAP user can authenticate (testuser/password)');
  });

  test('LDAP groups are configured', async () => {
    const { stdout } = await execPromise(
      'docker exec openldap ldapsearch -x -H ldap://localhost -b "ou=groups,dc=datamancy,dc=local" -D "cn=admin,dc=datamancy,dc=local" -w "admin_password" "(cn=*)" cn -LLL'
    );

    expect(stdout).toContain('cn: admins');
    expect(stdout).toContain('cn: users');
    console.log('✓ LDAP groups are configured (admins, users)');
  });
});
