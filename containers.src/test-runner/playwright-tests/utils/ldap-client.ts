/**
 * LDAP client for test user provisioning
 *
 * Interfaces with OpenLDAP to create/delete ephemeral test users
 */

import * as ldap from 'ldapjs';

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
    const timestamp = Date.now();
    const random = Math.floor(Math.random() * 9000) + 1000;
    return `${prefix}-${timestamp}-${random}`;
  }
}
