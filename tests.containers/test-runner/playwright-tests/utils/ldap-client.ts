/**
 * LDAP client for test user provisioning
 *
 * Interfaces with OpenLDAP to create/delete ephemeral test users
 */

import * as ldap from 'ldapjs';

const PLAYWRIGHT_MANAGED_DESCRIPTION = 'datamancy-playwright-managed';
const LEGACY_PLAYWRIGHT_USERNAME_PREFIX = 'pl';

export interface TestUser {
  username: string;
  password: string;
  email: string;
  groups: string[];
}

export interface LDAPConfig {
  url: string;
  adminDn: string;
  adminPassword: string;
  baseDn?: string;
}

export class LDAPClient {
  private url: string;
  private adminDn: string;
  private adminPassword: string;
  private baseDn: string;
  private usersDn: string;
  private groupsDn: string;

  constructor(config: LDAPConfig) {
    this.url = config.url;
    this.adminDn = config.adminDn;
    this.adminPassword = config.adminPassword;
    this.baseDn = config.baseDn || 'dc=datamancy,dc=net';
    this.usersDn = `ou=users,${this.baseDn}`;
    this.groupsDn = `ou=groups,${this.baseDn}`;
  }

  /**
   * Create a test user in LDAP
   */
  async createUser(user: TestUser): Promise<void> {
    const client = ldap.createClient({ url: this.url });

    return new Promise((resolve, reject) => {
      client.bind(this.adminDn, this.adminPassword, (err) => {
        if (err) {
          console.error('❌ LDAP bind failed:', err);
          return reject(err);
        }

        const userDn = `uid=${user.username},${this.usersDn}`;

        const entry = {
          objectClass: ['inetOrgPerson', 'organizationalPerson', 'person', 'top'],
          uid: user.username,
          cn: user.username,
          sn: 'TestUser',
          mail: user.email,
          userPassword: user.password,
          displayName: `Test User ${user.username}`,
          description: PLAYWRIGHT_MANAGED_DESCRIPTION,
        };

        console.log(`🔧 Creating LDAP user: ${userDn}`);

        client.add(userDn, entry, (err) => {
          if (err) {
            console.error('❌ Failed to create LDAP user:', err);
            client.unbind();
            return reject(err);
          }

          console.log(`✓ User created: ${user.username}`);

          // Add user to groups
          this.addUserToGroups(client, user.username, user.groups)
            .then(() => {
              client.unbind();
              resolve();
            })
            .catch((err) => {
              client.unbind();
              reject(err);
            });
        });
      });
    });
  }

  /**
   * Delete a test user from LDAP
   */
  async deleteUser(username: string): Promise<void> {
    const client = ldap.createClient({ url: this.url });

    return new Promise((resolve, reject) => {
      client.bind(this.adminDn, this.adminPassword, (err) => {
        if (err) {
          console.error('❌ LDAP bind failed during delete:', err);
          return reject(err);
        }

        const userDn = `uid=${username},${this.usersDn}`;

        console.log(`🗑️  Deleting LDAP user: ${userDn}`);

        // Remove from groups first
        this.removeUserFromGroups(client, username)
          .then(() => {
            client.del(userDn, (err) => {
              if (err && !err.message.includes('No Such Object')) {
                console.error('❌ Failed to delete LDAP user:', err);
                client.unbind();
                return reject(err);
              }

              console.log(`✓ User deleted: ${username}`);
              client.unbind();
              resolve();
            });
          })
          .catch((err) => {
            console.warn('⚠️  Failed to remove user from groups:', err);
            // Continue with deletion anyway
            client.del(userDn, (delErr) => {
              client.unbind();
              if (delErr && !delErr.message.includes('No Such Object')) {
                return reject(delErr);
              }
              resolve();
            });
          });
      });
    });
  }

  /**
   * Remove any leftover Playwright-managed users from prior interrupted runs.
   * This keeps the LDAP directory homogeneous across repeated test passes.
   */
  async cleanupManagedTestUsers(excludedUsernames: string[] = []): Promise<string[]> {
    const excluded = new Set(
      excludedUsernames
        .map((username) => username.trim().toLowerCase())
        .filter((username) => username.length > 0)
    );

    const managedUsernames = await this.findManagedTestUsernames();
    const deletedUsernames: string[] = [];

    for (const username of managedUsernames) {
      if (excluded.has(username.toLowerCase())) {
        continue;
      }
      await this.deleteUser(username);
      deletedUsernames.push(username);
    }

    return deletedUsernames;
  }

  /**
   * Add user to LDAP groups
   */
  private async addUserToGroups(client: ldap.Client, username: string, groups: string[]): Promise<void> {
    const userDn = `uid=${username},${this.usersDn}`;

    const promises = groups.map((groupName) => {
      return new Promise<void>((resolve) => {
        const groupDn = `cn=${groupName},${this.groupsDn}`;

        const change = new ldap.Change({
          operation: 'add',
          modification: {
            type: 'member',
            values: [userDn],
          },
        });

        console.log(`  Adding ${username} to group: ${groupName}`);

        client.modify(groupDn, change, (err) => {
          if (err) {
            console.warn(`  ⚠️  Could not add to group ${groupName}:`, err.message);
            // Don't fail - group might not exist or user already in it
            resolve();
          } else {
            console.log(`  ✓ Added to group: ${groupName}`);
            resolve();
          }
        });
      });
    });

    await Promise.all(promises);
  }

  /**
   * Remove user from all LDAP groups
   */
  private async removeUserFromGroups(client: ldap.Client, username: string): Promise<void> {
    const userDn = `uid=${username},${this.usersDn}`;

    return new Promise((resolve, reject) => {
      // Search for all groups containing this user
      const opts = {
        filter: `(member=${userDn})`,
        scope: 'sub' as const,
        attributes: ['cn'],
      };

      client.search(this.groupsDn, opts, (err, res) => {
        if (err) {
          return reject(err);
        }

        const groups: string[] = [];

        res.on('searchEntry', (entry) => {
          groups.push(entry.dn.toString());
        });

        res.on('error', (err) => {
          console.error('Search error:', err);
          reject(err);
        });

        res.on('end', async () => {
          // Remove user from each group
          const promises = groups.map((groupDn) => {
            return new Promise<void>((resolve) => {
              const change = new ldap.Change({
                operation: 'delete',
                modification: {
                  type: 'member',
                  values: [userDn],
                },
              });

              client.modify(groupDn, change, (err) => {
                if (err) {
                  console.warn(`  ⚠️  Could not remove from group ${groupDn}:`, err.message);
                }
                resolve(); // Don't fail on individual group removal
              });
            });
          });

          await Promise.all(promises);
          resolve();
        });
      });
    });
  }

  /**
   * Verify a user's credentials by attempting a bind.
   * Returns true if bind succeeds, false otherwise.
   */
  async verifyUserCredentials(username: string, password: string): Promise<boolean> {
    const client = ldap.createClient({ url: this.url });
    const userDn = `uid=${username},${this.usersDn}`;

    return new Promise((resolve) => {
      client.bind(userDn, password, (err) => {
        if (err) {
          console.warn(`  ⚠️  LDAP credential check failed for ${username}:`, err.message);
          client.unbind();
          resolve(false);
          return;
        }

        client.unbind();
        resolve(true);
      });
    });
  }

  private async findManagedTestUsernames(): Promise<string[]> {
    const client = ldap.createClient({ url: this.url });
    const managedUserFilter =
      `(|(description=${PLAYWRIGHT_MANAGED_DESCRIPTION})` +
      `(&(uid=${LEGACY_PLAYWRIGHT_USERNAME_PREFIX}*)(sn=TestUser)(displayName=Test User *)))`;

    return new Promise((resolve, reject) => {
      client.bind(this.adminDn, this.adminPassword, (bindErr) => {
        if (bindErr) {
          console.error('❌ LDAP bind failed during managed user search:', bindErr);
          return reject(bindErr);
        }

        const opts = {
          filter: managedUserFilter,
          scope: 'sub' as const,
          attributes: ['uid'],
        };

        client.search(this.usersDn, opts, (searchErr, res) => {
          if (searchErr) {
            client.unbind();
            return reject(searchErr);
          }

          const usernames = new Set<string>();

          res.on('searchEntry', (entry) => {
            const entryDn = (entry as { dn?: { toString?: () => string } }).dn?.toString?.() ?? '';
            const usernameFromDn = entryDn.match(/^uid=([^,]+)/i)?.[1]?.trim() ?? '';
            const rawUid = (entry as { object?: { uid?: string | string[] } }).object?.uid;
            const usernameFromObject = Array.isArray(rawUid)
              ? String(rawUid[0] ?? '').trim()
              : String(rawUid ?? '').trim();
            const username = usernameFromDn || usernameFromObject;
            if (username) {
              usernames.add(username);
            }
          });

          res.on('error', (entryErr) => {
            client.unbind();
            reject(entryErr);
          });

          res.on('end', () => {
            client.unbind();
            resolve([...usernames].sort());
          });
        });
      });
    });
  }

  /**
   * Generate a secure random password
   */
  static generatePassword(length: number = 16): string {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*';
    let password = '';
    for (let i = 0; i < length; i++) {
      password += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return password;
  }

  /**
   * Generate unique username with timestamp
   */
  static generateUsername(prefix: string = 'test'): string {
    const compactPrefix = prefix.replace(/[^a-z0-9]/gi, '').slice(0, 2).toLowerCase() || 'u';
    const ts = Date.now().toString(36);
    const rand = Math.floor(Math.random() * 36 ** 3).toString(36).padStart(3, '0');
    let username = `${compactPrefix}${ts}${rand}`.toLowerCase();
    // Ensure length <= 16 and only alnum for strict consumers (e.g., Planka)
    username = username.replace(/[^a-z0-9]/g, '').slice(0, 16);
    return username;
  }
}
