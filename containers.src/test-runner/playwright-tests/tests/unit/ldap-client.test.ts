/**
 * Unit tests for LDAP client utilities
 */

import { LDAPClient } from '../../utils/ldap-client';

describe('LDAPClient', () => {
  describe('generatePassword', () => {
    test('generates password of correct length', () => {
      const password = LDAPClient.generatePassword(16);
      expect(password).toHaveLength(16);
    });

    test('generates password with only valid characters', () => {
      const password = LDAPClient.generatePassword(32);
      const validChars = /^[A-Za-z0-9!@#$%^&*]+$/;
      expect(password).toMatch(validChars);
    });

    test('generates different passwords each time', () => {
      const password1 = LDAPClient.generatePassword();
      const password2 = LDAPClient.generatePassword();
      expect(password1).not.toBe(password2);
    });
  });

  describe('generateUsername', () => {
    test('generates username with correct prefix', () => {
      const username = LDAPClient.generateUsername('test');
      expect(username).toMatch(/^test-\d+-\d+$/);
    });

    test('generates unique usernames', () => {
      const username1 = LDAPClient.generateUsername('test');
      const username2 = LDAPClient.generateUsername('test');
      expect(username1).not.toBe(username2);
    });

    test('uses default prefix when not specified', () => {
      const username = LDAPClient.generateUsername();
      expect(username).toMatch(/^test-\d+-\d+$/);
    });
  });
});
