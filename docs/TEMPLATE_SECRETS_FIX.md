# Template Secrets Fix - Pre-Deployment

## Problem

The `configs.templates/` directory contains **33 hardcoded secrets** that get copied into `configs/` when you regenerate. These include:

1. **OIDC private key** (4096-bit RSA) - commits this = anyone can forge auth tokens
2. **11 OIDC client secret hashes** - for Grafana, Planka, Vaultwarden, etc.
3. **Mailu encryption keys**
4. **Synapse Matrix secrets**
5. **Database password defaults** that say "changeme"

Even though `configs/` is gitignored, **the templates themselves** have these hardcoded values.

## Solution

Run the automated fix script to replace all hardcoded values with `{{PLACEHOLDERS}}`:

```bash
# Preview changes (safe, no modifications)
kotlin scripts/security/fix-template-secrets.main.kts --dry-run

# Apply fixes (backs up to configs.templates.backup-{timestamp}/)
kotlin scripts/security/fix-template-secrets.main.kts
```

## What the Script Does

### Automatic Fixes (27 replacements)
1. ✅ Replaces OIDC private key with `key_file: /secrets/authelia-oidc-key.pem`
2. ✅ Replaces 11 OIDC client_secret hashes with `{{CLIENT_NAME_OAUTH_SECRET_HASH}}`
3. ✅ Replaces Mailu secrets with `{{MAILU_SECRET_KEY}}`
4. ✅ Replaces Synapse secrets with `{{SYNAPSE_*_SECRET}}`
5. ✅ Replaces Jellyfin secret with `{{JELLYFIN_OIDC_SECRET}}`
6. ✅ Changes DB password defaults from `:-changeme_*` to `:?ERROR: must be set` (fail-secure)

### Manual Steps Required

After running the script:

#### 1. Generate OIDC Private Key
```bash
mkdir -p volumes/secrets
openssl genpkey -algorithm RSA \
  -out volumes/secrets/authelia-oidc-key.pem \
  -pkeyopt rsa_keygen_bits:4096
chmod 600 volumes/secrets/authelia-oidc-key.pem
```

#### 2. Add Mount Point to docker-compose.yml
Edit `docker-compose.yml` authelia service:
```yaml
authelia:
  volumes:
    # ... existing volumes ...
    - ${VOLUMES_ROOT}/secrets/authelia-oidc-key.pem:/secrets/authelia-oidc-key.pem:ro
```

#### 3. Generate Missing Secrets in .env

The script created placeholders. You need values in `.env`:

```bash
# Mailu
MAILU_SECRET_KEY=$(openssl rand -base64 16)

# Synapse
SYNAPSE_REGISTRATION_SECRET=$(openssl rand -base64 32)
SYNAPSE_MACAROON_SECRET=$(openssl rand -base64 32)
SYNAPSE_FORM_SECRET=$(openssl rand -base64 32)

# Jellyfin
JELLYFIN_OIDC_SECRET=$(openssl rand -base64 32)

# OIDC Client Secrets (generate plaintext, will hash for Authelia)
GRAFANA_OAUTH_SECRET=$(openssl rand -base64 32)
PGADMIN_OAUTH_SECRET=$(openssl rand -base64 32)
DOCKGE_OAUTH_SECRET=$(openssl rand -base64 32)
OPENWEBUI_OAUTH_SECRET=$(openssl rand -base64 32)
NEXTCLOUD_OAUTH_SECRET=$(openssl rand -base64 32)
DIM_OAUTH_SECRET=$(openssl rand -base64 32)
PLANKA_OAUTH_SECRET=$(openssl rand -base64 32)
HOMEASSISTANT_OAUTH_SECRET=$(openssl rand -base64 32)
JUPYTERHUB_OAUTH_SECRET=$(openssl rand -base64 32)
VAULTWARDEN_OAUTH_SECRET=$(openssl rand -base64 32)
MASTODON_OAUTH_SECRET=$(openssl rand -base64 32)

# Now generate pbkdf2-sha512 hashes for Authelia
# (You need authelia binary or docker)
docker run --rm -it authelia/authelia:latest \
  authelia crypto hash generate pbkdf2 --password "$GRAFANA_OAUTH_SECRET"
# Copy the hash output and add to .env as GRAFANA_OAUTH_SECRET_HASH
# Repeat for each client...
```

**OR** use this helper script to generate and hash all at once:

```bash
#!/bin/bash
# generate-oidc-secrets.sh

clients="GRAFANA PGADMIN DOCKGE OPENWEBUI NEXTCLOUD DIM PLANKA HOMEASSISTANT JUPYTERHUB VAULTWARDEN MASTODON"

for client in $clients; do
  secret=$(openssl rand -base64 32)
  hash=$(docker run --rm authelia/authelia:latest \
    authelia crypto hash generate pbkdf2 --password "$secret" 2>/dev/null | tail -1)

  echo "# $client"
  echo "${client}_OAUTH_SECRET=$secret"
  echo "${client}_OAUTH_SECRET_HASH=$hash"
  echo ""
done
```

#### 4. Regenerate Configs
```bash
kotlin scripts/core/process-config-templates.main.kts --force
```

#### 5. Verify No Secrets Remain
```bash
# Check for any remaining hardcoded secrets
grep -r "changeme\|BEGIN PRIVATE KEY" configs.templates/
# Should return nothing (or only comments)

# Verify placeholders are present
grep -r "{{.*SECRET" configs.templates/ | head -10
# Should show template variables
```

#### 6. Test Bootstrap
```bash
# Validate compose file
docker compose --profile bootstrap config > /dev/null
echo "Config valid: $?"

# Test startup (don't worry about healthchecks yet)
docker compose --profile bootstrap up -d
docker compose ps
docker compose logs --tail=50
```

## Files Modified by Script

- `configs.templates/applications/authelia/configuration.yml` (OIDC key + 11 client secrets)
- `configs.templates/applications/mailu/mailu.env` (2 secrets)
- `configs.templates/applications/synapse/homeserver.yaml` (3 secrets)
- `configs.templates/applications/jellyfin/SSO-Auth.xml` (1 secret)
- `configs.templates/applications/kopia/init-kopia.sh` (1 default removed)
- `configs.templates/databases/postgres/init-db.sh` (7 defaults removed)

## Verification Checklist

Before deploying:

- [ ] Ran `fix-template-secrets.main.kts` successfully
- [ ] Generated new OIDC private key in `volumes/secrets/`
- [ ] Updated docker-compose.yml with OIDC key volume mount
- [ ] Generated all missing secrets in `.env`
- [ ] Generated pbkdf2 hashes for all OIDC client secrets
- [ ] Regenerated `configs/` from templates
- [ ] Verified no "changeme" or "BEGIN PRIVATE KEY" in templates
- [ ] Tested `docker compose config` (validates syntax)
- [ ] Tested bootstrap startup
- [ ] Created backup of working .env (encrypted with sops/age)

## Rollback

If something breaks:

```bash
# Restore templates from backup
timestamp="<your-backup-timestamp>"
rm -rf configs.templates
cp -r configs.templates.backup-$timestamp configs.templates

# Regenerate configs with old (but working) setup
kotlin scripts/core/process-config-templates.main.kts --force
```

## Why This Matters

**Before Fix:**
- Secrets committed to git = anyone with repo access can compromise your stack
- Even if you delete the files, they're in git history forever
- Regenerating configs gives you the **same compromised secrets**

**After Fix:**
- Templates have `{{PLACEHOLDERS}}` only
- Real secrets are in `.env` (encrypted with sops/age, never committed)
- Each deployment can have unique secrets
- Easy to rotate: change `.env`, regenerate, restart

## Additional Security: Bootstrap LDAP

The LDAP bootstrap file (`bootstrap_ldap.ldif`) **also** has hardcoded password hashes. This is a separate issue from template secrets.

Fix by generating a new hash:
```bash
# Generate random password
NEW_PASS=$(openssl rand -base64 24)
echo "New LDAP admin password: $NEW_PASS" # Save this securely!

# Generate SSHA hash
NEW_HASH=$(slappasswd -s "$NEW_PASS" -h {SSHA})

# Update bootstrap_ldap.ldif
sed -i "s/userPassword: {SSHA}.*$/userPassword: $NEW_HASH/" bootstrap_ldap.ldif

# Store plaintext in .env for stack admin user
echo "LDAP_ADMIN_PASSWORD=$NEW_PASS" >> .env
```

Or better: template the LDAP file too and generate password on first boot.
