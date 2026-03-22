/**
 * Unit tests for LDAP client utilities
 *
 * These are Jest-based unit tests for utility functions.
 * For E2E browser tests, see ../forward-auth-services.spec.ts
 */

import { LDAPClient } from '../../utils/ldap-client';

describe('LDAPClient', () => {
  describe('generatePassword', () => {
    it('generates password of correct length', () => {
      const password = LDAPClient.generatePassword(16);
      expect(password).toHaveLength(16);
    });

    it('generates password with only valid characters', () => {
      const password = LDAPClient.generatePassword(32);
      const validChars = /^[A-Za-z0-9!@#$%^&*]+$/;
      expect(password).toMatch(validChars);
    });

    it('generates different passwords each time', () => {
      const password1 = LDAPClient.generatePassword();
      const password2 = LDAPClient.generatePassword();
      expect(password1).not.toBe(password2);
    });
  });

  describe('generateUsername', () => {
    it('generates username with correct prefix', () => {
      const username = LDAPClient.generateUsername('test');
      expect(username).toMatch(/^te[a-z0-9]+$/);
      expect(username.length).toBeLessThanOrEqual(16);
    });

    it('generates unique usernames', () => {
      const username1 = LDAPClient.generateUsername('test');
      const username2 = LDAPClient.generateUsername('test');
      expect(username1).not.toBe(username2);
    });

    it('uses default prefix when not specified', () => {
      const username = LDAPClient.generateUsername();
      expect(username).toMatch(/^te[a-z0-9]+$/);
      expect(username.length).toBeLessThanOrEqual(16);
    });

    it('keeps the legacy playwright prefix stable for managed-user cleanup', () => {
      const username = LDAPClient.generateUsername('playwright');
      expect(username).toMatch(/^pl[a-z0-9]+$/);
      expect(username.length).toBeLessThanOrEqual(16);
    });
  });

  describe('managed-user cleanup safety', () => {
    it('extracts the embedded timestamp from managed usernames', () => {
      const createdAt = Date.UTC(2026, 2, 22, 9, 55, 0);
      const username = `pl${createdAt.toString(36)}abc`;

      expect(LDAPClient.extractGeneratedUsernameTimestamp(username)).toBe(createdAt);
    });

    it('preserves fresh managed usernames during cleanup', () => {
      const createdAt = Date.UTC(2026, 2, 22, 9, 55, 0);
      const username = `pl${createdAt.toString(36)}abc`;
      const oneHourLater = createdAt + 60 * 60 * 1000;

      expect(LDAPClient.isManagedUserStale(username, 6 * 60 * 60 * 1000, oneHourLater)).toBe(false);
    });

    it('reaps stale managed usernames once they age out', () => {
      const createdAt = Date.UTC(2026, 2, 22, 9, 55, 0);
      const username = `pl${createdAt.toString(36)}abc`;
      const sevenHoursLater = createdAt + 7 * 60 * 60 * 1000;

      expect(LDAPClient.isManagedUserStale(username, 6 * 60 * 60 * 1000, sevenHoursLater)).toBe(true);
    });

    it('treats legacy usernames without embedded timestamps as stale', () => {
      expect(LDAPClient.isManagedUserStale('pllegacyuser', 6 * 60 * 60 * 1000, Date.now())).toBe(true);
    });
  });
});
