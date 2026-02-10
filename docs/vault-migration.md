# Vault Multi-User Migration Guide

## Overview

Datamancy's Vault has been upgraded from a single root token system to a multi-user, LDAP-authenticated architecture with per-user secret isolation.

**Key Changes:**
- ✅ LDAP authentication for all users
- ✅ Per-user secret isolation (`secret/users/{username}/*`)
- ✅ Shared service secrets (read-only for users)
- ✅ Admin policy for sysadmin group
- ✅ Manual unseal for security (no keys on disk)
- ✅ Audit logging enabled

---

## For End Users

### 🔐 Login to Vault

**Using CLI:**
```bash
# Set Vault address (add to ~/.bashrc for persistence)
export VAULT_ADDR="http://vault:8200"

# Login with your LDAP credentials
vault login -method=ldap username=your-username
# Enter your LDAP password when prompted
```

**Using API:**
```bash
curl -X POST http://vault:8200/v1/auth/ldap/login/your-username \
  -d '{"password":"your-password"}' | jq -r '.auth.client_token'
```

### 📝 Store Secrets

**Trading API Keys:**
```bash
vault kv put secret/users/your-username/api-keys/binance \
  api_key="your-api-key" \
  api_secret="your-api-secret"
```

**Wallet Private Keys:**
```bash
vault kv put secret/users/your-username/wallets/eth \
  private_key="0x..." \
  address="0x..."
```

**Personal Notes:**
```bash
vault kv put secret/users/your-username/notes/important \
  note="Remember to backup unseal keys"
```

### 🔍 Read Secrets

**Read your secrets:**
```bash
vault kv get secret/users/your-username/api-keys/binance
```

**Read shared service secrets (read-only):**
```bash
vault kv get secret/services/postgres
```

**List your secrets:**
```bash
vault kv list secret/users/your-username
```

### 🗑️ Delete Secrets

```bash
vault kv delete secret/users/your-username/api-keys/old-exchange
```

---

## For Administrators

### 🔓 Unseal Vault After Restart

Vault is **sealed** after every server restart for security. You must manually unseal it.

#### **Option 1: Official Vault Web UI (Recommended)** 🎨

Access the official HashiCorp Vault UI:

**URL:** https://vault.datamancy.local (protected by Authelia - admins only)

**Steps:**
1. Navigate to https://vault.datamancy.local
2. Authelia prompts for login (if not already logged in)
   - Username: `sysadmin` (or any admin user)
   - Password: Your LDAP password
3. You'll see the Vault UI showing "Vault is sealed"
4. Click "Unseal" button
5. Open Vaultwarden → Search "Vault Unseal Keys"
6. Copy Key 1 → Paste into Vault UI → Click "Unseal"
   - Progress: 1/3 keys entered
7. Copy Key 2 → Paste → Click "Unseal"
   - Progress: 2/3 keys entered
8. Copy Key 3 → Paste → Click "Unseal"
   - **Vault is now unsealed!** ✅

**The official Vault UI includes:**
- ✅ Unseal interface with progress
- ✅ Browse/manage all secrets
- ✅ Policy editor
- ✅ Audit log viewer
- ✅ Token management
- ✅ Health monitoring
- ✅ Full Vault administration

#### **Option 2: Command Line** ⌨️

**Steps:**
1. Retrieve unseal keys from Vaultwarden (stored during initialization)
2. Run unseal command 3 times with 3 different keys:

```bash
docker exec -it vault vault operator unseal
# Enter Unseal Key 1
# Press Enter

docker exec vault vault operator unseal
# Enter Unseal Key 2
# Press Enter

docker exec vault vault operator unseal
# Enter Unseal Key 3
# Vault is now unsealed!
```

**Check seal status:**
```bash
docker exec vault vault status
```

### 🔑 Initial Setup (First Time Only)

**If reinitializing Vault (WARNING: Destroys existing data!):**

```bash
# Stop Vault
docker compose down vault vault-init

# Remove old data
docker volume rm datamancy_vault_data

# Start Vault
docker compose up -d vault

# Wait for health check
sleep 30

# Initialize
docker compose run --rm vault-init

# IMPORTANT: Save the unseal keys and root token to Vaultwarden!
# The script will prompt you to confirm you've saved them.
```

### 👥 Manage Service Secrets

**Create shared service secret:**
```bash
# Login as admin
vault login -method=ldap username=sysadmin

# Create service secret
vault kv put secret/services/new-service \
  api_key="service-key" \
  password="service-password"
```

**All users can read service secrets (but not write).**

### 📋 Manage Policies

**View existing policies:**
```bash
vault policy list
```

**Read a policy:**
```bash
vault policy read user-template
```

**Update admin policy:**
```bash
vault policy write admin /path/to/admin.hcl
```

### 🔍 Audit Logs

**View audit logs:**
```bash
docker exec vault cat /vault/logs/audit.log | tail -50
```

**Audit logs track:**
- Every login attempt
- Every secret read/write/delete
- Policy violations
- Token creation/renewal

### 👤 User Management

Users are managed in LDAP, not Vault. Vault authenticates against LDAP.

**To add a new user to Vault:**
1. Create user in LDAP (via your user management scripts)
2. Add user to `cn=users,ou=groups,dc=datamancy,dc=net` group
3. User can now login to Vault with LDAP credentials
4. User automatically gets `user-template` policy

**To grant admin access:**
1. Add user to `cn=admins,ou=groups,dc=datamancy,dc=net` group in LDAP
2. User gets `admin` policy on next login

---

## Architecture Details

### Secret Path Structure

```
secret/
├── users/                        # Per-user secrets
│   ├── alice/                   # User 'alice' has full access
│   │   ├── api-keys/
│   │   │   ├── binance
│   │   │   └── coinbase
│   │   ├── wallets/
│   │   │   ├── eth
│   │   │   └── btc
│   │   └── notes/
│   ├── bob/                     # User 'bob' has full access
│   │   └── ...
│   └── ...
├── services/                     # Shared service secrets
│   ├── postgres                 # All users: read-only
│   ├── litellm                  # Admins: read-write
│   ├── qdrant
│   └── ...
├── agents/                       # Shadow agent credentials
│   ├── alice-agent/             # User 'alice' can read
│   └── bob-agent/
└── teams/                        # Team-shared secrets
    ├── trading/                 # Team members: read-write
    └── devops/
```

### Policy Hierarchy

**user-template policy (regular users):**
- ✅ Full access to `secret/users/{username}/*` (own path)
- ✅ Read-only access to `secret/services/*` (shared services)
- ✅ Read-only access to `secret/agents/{username}-agent/*` (own agent)
- ❌ Cannot access other users' paths
- ❌ Cannot write to service secrets

**admin policy (sysadmin group):**
- ✅ Full access to all paths (`secret/*`)
- ✅ Manage auth methods
- ✅ Manage policies
- ✅ View audit logs

**service policy (AppRole auth):**
- ✅ Read-only access to `secret/services/*`
- ❌ No access to user paths

### LDAP Integration

**LDAP → Vault Mapping:**
```
LDAP Group              → Vault Policy
─────────────────────────────────────────
cn=admins,ou=groups     → admin
cn=users,ou=groups      → user-template
```

**Authentication Flow:**
1. User provides LDAP username + password
2. Vault authenticates against OpenLDAP server
3. Vault queries user's LDAP groups
4. Vault assigns policies based on group membership
5. Vault issues short-lived token (default: 768h = 32 days)

---

## Security Best Practices

### ✅ DO

- **Store unseal keys in Vaultwarden** (password manager)
- **Use strong LDAP passwords** (12+ characters)
- **Rotate API keys regularly** (store in Vault)
- **Use Vault CLI for automation** (avoid hardcoding secrets)
- **Review audit logs** for suspicious activity
- **Backup Vault data** regularly

### ❌ DON'T

- **Don't share unseal keys publicly** (anyone with 3 keys can unseal Vault)
- **Don't commit Vault tokens to Git** (use environment variables)
- **Don't use root token for daily operations** (use LDAP login)
- **Don't store passwords in plaintext** (always use Vault)
- **Don't skip unsealing after restart** (services depend on Vault)

---

## Troubleshooting

### Problem: Vault is sealed after restart

**Solution:** Manually unseal with 3 of 5 keys (see "Unseal Vault" section)

### Problem: "permission denied" when writing secret

**Possible causes:**
- Writing to another user's path → Use your own path: `secret/users/your-username/*`
- Writing to service secrets as regular user → Only admins can write to `secret/services/*`
- Token expired → Login again: `vault login -method=ldap username=your-username`

### Problem: "LDAP login failed"

**Possible causes:**
- Wrong password → Check LDAP password
- User not in LDAP → Create user in LDAP first
- LDAP server unreachable → Check `docker compose logs ldap`
- Vault not configured for LDAP → Run `/vault/config/setup-ldap.sh` as admin

### Problem: Can't read service secrets

**Solution:** Service secrets are only created by admins. Login as sysadmin and create them:
```bash
vault login -method=ldap username=sysadmin
vault kv put secret/services/your-service password="..."
```

### Problem: Lost unseal keys

**Critical situation!** If you lost more than 2 unseal keys, Vault data is **permanently inaccessible**.

**Options:**
1. Check Vaultwarden for backup
2. Check manual backups of `init-output.txt`
3. If lost: Reinitialize Vault (destroys all data)

---

## Migration from Old System

### Old System (Deprecated)

```bash
# Root token shared across all services
export VAULT_TOKEN="s.xxxxxxxxxx"
vault kv put secret/my-secret key="value"
```

**Problems:**
- Single root token = god-mode access for everyone
- No user isolation
- No audit trail of who accessed what
- Token stored in plaintext

### New System (Current)

```bash
# Each user authenticates with LDAP
vault login -method=ldap username=alice
vault kv put secret/users/alice/my-secret key="value"
```

**Benefits:**
- ✅ Each user has isolated namespace
- ✅ Audit logs track individual users
- ✅ No shared credentials
- ✅ LDAP password = single point of authentication

### Migration Steps for Existing Secrets

If you have secrets stored in the old system:

```bash
# 1. Login as admin
vault login -method=ldap username=sysadmin

# 2. Read old secret
OLD_VALUE=$(vault kv get -field=key secret/old-path)

# 3. Write to new user-specific path
vault kv put secret/users/your-username/new-path key="$OLD_VALUE"

# 4. Delete old secret
vault kv delete secret/old-path
```

---

## Testing

Run the comprehensive Vault test suite:

```bash
# Run all vault tests
docker compose run --rm test-runner --suite vault

# Run specific test categories
docker compose run --rm test-runner --suite security  # Includes Vault tests
```

**Test Coverage:**
- ✅ LDAP authentication
- ✅ Per-user secret isolation
- ✅ Policy enforcement
- ✅ Shared service secrets
- ✅ Admin privileges
- ✅ Concurrent multi-user access
- ✅ Token management

---

## Support

**Documentation:**
- Vault Official Docs: https://developer.hashicorp.com/vault/docs
- LDAP Auth Method: https://developer.hashicorp.com/vault/docs/auth/ldap
- KV Secrets Engine: https://developer.hashicorp.com/vault/docs/secrets/kv/kv-v2

**Internal Resources:**
- Policy files: `configs/vault/policies/`
- Init script: `configs/vault/init-vault.sh`
- LDAP setup: `configs/vault/setup-ldap.sh`
- Test suite: `kotlin.src/test-runner/.../VaultTests.kt`

**Questions?**
Contact your Datamancy administrator or check the audit logs for troubleshooting.

---

## Quick Reference

```bash
# Login
vault login -method=ldap username=<username>

# Write secret
vault kv put secret/users/<username>/<path> key="value"

# Read secret
vault kv get secret/users/<username>/<path>

# List secrets
vault kv list secret/users/<username>

# Delete secret
vault kv delete secret/users/<username>/<path>

# Check status
vault status

# Unseal (after restart)
docker exec vault vault operator unseal
```

---

**Last Updated:** 2026-02-10
**Version:** 2.0 (Multi-User LDAP)
