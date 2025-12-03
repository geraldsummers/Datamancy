# LDAP Bootstrap Configuration

## Overview

Datamancy uses OpenLDAP for centralized user directory and authentication. LDAP user passwords must be stored as **SSHA (Salted SHA-1)** hashes, not plaintext.

## Security Model

### Development vs Production

- **Development**: Uses `bootstrap_ldap.ldif` with hardcoded test passwords (in git)
- **Production**: Generate fresh `bootstrap_ldap.ldif` from template with real passwords from `.env`

### Why Template System?

LDAP requires password hashes (SSHA format), not plaintext. The template system:
1. Reads `STACK_ADMIN_PASSWORD` from `.env`
2. Generates unique SSHA hash with random salt
3. Creates production-ready LDIF file
4. Each generation produces different hashes (salted)

## Production Deployment

### Generate LDAP Bootstrap File

```bash
# Generate production bootstrap_ldap.ldif from template
kotlin scripts/security/generate-ldap-bootstrap.main.kts

# Output: bootstrap_ldap.ldif (overwrites development version)
```

### Environment Variables Required

```bash
# .env file must contain:
STACK_ADMIN_USER=admin           # LDAP admin username
STACK_ADMIN_PASSWORD=<strong>    # Will be hashed as SSHA
STACK_ADMIN_EMAIL=admin@domain   # Admin email
DOMAIN=your-domain.com           # Domain for user emails

# Optional (defaults to STACK_ADMIN_PASSWORD):
STACK_USER_PASSWORD=<password>   # Password for 'user' test account
```

### What Gets Generated

The script processes `configs.templates/infrastructure/ldap/bootstrap_ldap.ldif.template` and replaces:

- `{{STACK_ADMIN_USER}}` → Admin username
- `{{STACK_ADMIN_EMAIL}}` → Admin email
- `{{ADMIN_SSHA_PASSWORD}}` → Generated SSHA hash from STACK_ADMIN_PASSWORD
- `{{USER_SSHA_PASSWORD}}` → Generated SSHA hash for test user
- `{{DOMAIN}}` → Your domain
- `{{GENERATION_TIMESTAMP}}` → When file was created

### Verify Generation

```bash
# Check file was generated
ls -lh bootstrap_ldap.ldif

# View generated SSHA hashes (should be unique each time)
grep "userPassword:" bootstrap_ldap.ldif
```

Expected output:
```
userPassword: {SSHA}TW0rMGGGhQBtxyHgbEvYtTa2w56l08P1YG/Rcg==
userPassword: {SSHA}KY88PFLnmX0O2BxBl58AaEi6SNTVoyB9XfIu3A==
```

Note: Hashes are different even for same password (due to random salt).

## LDAP Directory Structure

The bootstrap file creates:

### Organizational Units
- `ou=users,dc=stack,dc=local` - User accounts
- `ou=groups,dc=stack,dc=local` - User groups

### Users
- **admin** (uid=10000) - System administrator
- **user** (uid=10001) - Test regular user (optional)

### Groups
- **admins** - Admin group (contains admin user)
- **users** - All users group
- **openwebui-admin** - Open WebUI administrators
- **planka-admin** - Planka administrators

## Security Best Practices

### ⚠️ Important Security Notes

1. **Never commit production `bootstrap_ldap.ldif`** to git
   - Contains real password hashes derived from `.env`
   - Add to `.gitignore` after first generation

2. **Regenerate for each environment**
   - Dev, staging, prod should have different passwords
   - Run generator script on target server, not locally

3. **Strong passwords required**
   - SSHA hashing doesn't make weak passwords secure
   - Use `openssl rand -base64 32` for STACK_ADMIN_PASSWORD

4. **Test passwords in git**
   - The committed `bootstrap_ldap.ldif` has `DatamancyTest2025!` password
   - This is publicly visible - **do not use in production**

### Recommended Workflow

```bash
# On production server:

# 1. Ensure strong password in .env
grep STACK_ADMIN_PASSWORD .env
# Should be 32+ characters, random

# 2. Generate production LDAP bootstrap
kotlin scripts/security/generate-ldap-bootstrap.main.kts

# 3. Verify unique hashes generated
grep userPassword bootstrap_ldap.ldif

# 4. Add to gitignore (prevents accidental commit)
echo "bootstrap_ldap.ldif" >> .gitignore

# 5. Start LDAP service
docker compose up -d ldap

# 6. Wait for LDAP initialization (10-15 seconds)
docker compose logs -f ldap

# 7. Test authentication
docker exec ldap ldapsearch \
  -x -H ldap://localhost:389 \
  -D "uid=admin,ou=users,dc=stack,dc=local" \
  -w "$STACK_ADMIN_PASSWORD" \
  -b "dc=stack,dc=local"
```

## Manual Password Generation

If you need to generate SSHA hashes manually:

```bash
# Using the provided script
kotlin scripts/security/generate-ssha-password.main.kts "YourPassword123"
# Output: {SSHA}randomHashHere==

# Using slappasswd (if installed)
slappasswd -h {SSHA} -s "YourPassword123"
```

## Troubleshooting

### LDAP Authentication Fails

```bash
# Check if bootstrap was loaded
docker exec ldap ldapsearch -x -LLL -H ldap://localhost:389 \
  -D "cn=admin,dc=stack,dc=local" \
  -w "$STACK_ADMIN_PASSWORD" \
  -b "ou=users,dc=stack,dc=local" uid

# Verify admin user exists
docker exec ldap ldapsearch -x -LLL -H ldap://localhost:389 \
  -D "cn=admin,dc=stack,dc=local" \
  -w "$STACK_ADMIN_PASSWORD" \
  -b "ou=users,dc=stack,dc=local" uid=admin
```

### Bootstrap File Not Loaded

LDAP only loads `bootstrap_ldap.ldif` on **first startup** when data volume is empty.

```bash
# Delete LDAP data and regenerate
docker compose down ldap
rm -rf volumes/ldap_data volumes/ldap_config

# Regenerate bootstrap with production passwords
kotlin scripts/security/generate-ldap-bootstrap.main.kts

# Start fresh
docker compose up -d ldap
```

### Wrong Password in Bootstrap

If you generated with wrong password:

```bash
# Option A: Delete data and regenerate (cleanest)
docker compose down ldap
rm -rf volumes/ldap_data volumes/ldap_config
kotlin scripts/security/generate-ldap-bootstrap.main.kts
docker compose up -d ldap

# Option B: Change password via ldappasswd
docker exec -it ldap ldappasswd \
  -H ldap://localhost:389 \
  -D "cn=admin,dc=stack,dc=local" \
  -W \
  -S "uid=admin,ou=users,dc=stack,dc=local"
```

## Adding New Users

After initial bootstrap, add users via:

1. **LDAP Account Manager** (LAM) - Web UI at `https://lam.your-domain.com`
2. **ldapadd command** - For scripted provisioning
3. **LDAP Sync Service** - Automated user provisioning (if configured)

Example adding user via ldapadd:

```bash
# Create LDIF for new user
cat > newuser.ldif <<EOF
dn: uid=alice,ou=users,dc=stack,dc=local
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: alice
cn: Alice Smith
sn: Smith
mail: alice@your-domain.com
uidNumber: 10002
gidNumber: 10001
homeDirectory: /home/alice
loginShell: /bin/bash
userPassword: {SSHA}GeneratedHashHere
EOF

# Add to LDAP
docker exec -i ldap ldapadd \
  -H ldap://localhost:389 \
  -D "uid=admin,ou=users,dc=stack,dc=local" \
  -w "$STACK_ADMIN_PASSWORD" \
  < newuser.ldif
```

## Files Reference

- `bootstrap_ldap.ldif` - Active bootstrap file (dev version in git, production version generated)
- `configs.templates/infrastructure/ldap/bootstrap_ldap.ldif.template` - Template with placeholders
- `scripts/security/generate-ldap-bootstrap.main.kts` - Generator script
- `scripts/security/generate-ssha-password.main.kts` - Standalone SSHA hash generator

## Related Documentation

- [DEPLOYMENT.md](DEPLOYMENT.md) - Full deployment guide
- [SECURITY.md](SECURITY.md) - Security configuration
- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture
