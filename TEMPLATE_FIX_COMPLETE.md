# Template Secrets Fix - COMPLETE ✓

## What Was Fixed

### 1. Updated `scripts/core/configure-environment.kts`
**Added 15 missing secrets:**
- ✅ `AUTHELIA_DB_PASSWORD`, `GRAFANA_DB_PASSWORD`, `VAULTWARDEN_DB_PASSWORD`, `OPENWEBUI_DB_PASSWORD`
- ✅ `SYNAPSE_REGISTRATION_SECRET`, `SYNAPSE_MACAROON_SECRET`, `SYNAPSE_FORM_SECRET`
- ✅ `MAILU_SECRET_KEY`
- ✅ `JELLYFIN_OIDC_SECRET`
- ✅ `HOMEASSISTANT_OAUTH_SECRET`, `DIM_OAUTH_SECRET`
- ✅ 10 `*_OAUTH_SECRET_HASH` placeholders (filled by separate script)

**Backfill support:** All new secrets added to export backfill section for existing deployments

### 2. Created `scripts/security/generate-oidc-hashes.main.kts`
- Reads `.env` file
- Finds all `*_OAUTH_SECRET` values
- Generates pbkdf2-sha512 hashes using authelia/docker
- Writes `*_OAUTH_SECRET_HASH` values back to `.env`
- Fully automated - no manual hash generation needed

### 3. Fixed Template Files (8 files)

#### `configs.templates/applications/authelia/configuration.yml`
- ✅ Replaced hardcoded 4096-bit RSA private key with `{{AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY}}`
- ✅ Replaced 11 hardcoded client_secret hashes with placeholders:
  - `{{GRAFANA_OAUTH_SECRET_HASH}}`
  - `{{PGADMIN_OAUTH_SECRET_HASH}}`
  - `{{DOCKGE_OAUTH_SECRET_HASH}}`
  - `{{OPENWEBUI_OAUTH_SECRET_HASH}}`
  - `{{NEXTCLOUD_OAUTH_SECRET_HASH}}`
  - `{{DIM_OAUTH_SECRET_HASH}}`
  - `{{PLANKA_OAUTH_SECRET_HASH}}`
  - `{{HOMEASSISTANT_OAUTH_SECRET_HASH}}`
  - `{{JUPYTERHUB_OAUTH_SECRET_HASH}}`
  - `{{VAULTWARDEN_OAUTH_SECRET_HASH}}`
  - `{{MASTODON_OAUTH_SECRET_HASH}}`

#### `configs.templates/applications/synapse/homeserver.yaml`
- ✅ `registration_shared_secret: "{{SYNAPSE_REGISTRATION_SECRET}}"`
- ✅ `macaroon_secret_key: "{{SYNAPSE_MACAROON_SECRET}}"`
- ✅ `form_secret: "{{SYNAPSE_FORM_SECRET}}"`

#### `configs.templates/applications/mailu/mailu.env`
- ✅ `SECRET_KEY={{MAILU_SECRET_KEY}}`
- ✅ `SECRET={{MAILU_SECRET_KEY}}`

#### `configs.templates/applications/jellyfin/SSO-Auth.xml`
- ✅ `<OidSecret>{{JELLYFIN_OIDC_SECRET}}</OidSecret>`

#### `configs.templates/databases/postgres/init-db.sh`
- ✅ Changed 7 passwords from `:-changeme_*` to `:?ERROR: not set` (fail-secure)

#### `configs.templates/databases/postgres/ensure-users.sh`
- ✅ Changed 5 passwords from `:-changeme_*` to `:?ERROR: not set` (fail-secure)

---

## How to Use (Clean Setup)

### Step 1: Generate Secrets
```bash
# Generate and encrypt all secrets
kotlin scripts/core/configure-environment.kts init

# Export to .env
kotlin scripts/core/configure-environment.kts export
```

This creates `.env` with 50+ secrets including all the ones we added.

### Step 2: Generate OIDC Hashes
```bash
# Generate pbkdf2-sha512 hashes for Authelia
kotlin scripts/security/generate-oidc-hashes.main.kts
```

This updates `.env` with the 10 hash values.

### Step 3: Process Templates
```bash
# Substitute {{PLACEHOLDERS}} with values from .env
kotlin scripts/core/process-config-templates.main.kts --force
```

This generates clean `configs/` from `configs.templates/` with all your secrets.

### Step 4: Verify
```bash
# Check no hardcoded secrets remain
grep -r "changeme\|BEGIN PRIVATE KEY" configs/
# Should be empty

# Check placeholders were substituted
grep "{{.*}}" configs/applications/authelia/configuration.yml
# Should be empty (all replaced)

# Validate compose file
docker compose --profile bootstrap config
```

### Step 5: Bootstrap!
```bash
docker compose --profile bootstrap up -d
```

---

## What Changed in Your Workflow

### Before (Broken)
```
configs.templates/ → hardcoded secrets
   ↓
process-config-templates.kts
   ↓
configs/ → still hardcoded secrets ❌
```

### After (Fixed)
```
configure-environment.kts init
   ↓
~/.local/share/stack-secrets/stack_secrets.enc (encrypted)
   ↓
configure-environment.kts export
   ↓
.env (plaintext secrets)
   ↓
generate-oidc-hashes.main.kts
   ↓
.env (with OIDC hashes added)
   ↓
configs.templates/ ({{PLACEHOLDERS}})
   ↓
process-config-templates.kts
   ↓
configs/ (clean, unique secrets) ✅
```

---

## Files Modified Summary

| File | Changes | Type |
|------|---------|------|
| `scripts/core/configure-environment.kts` | +15 secrets, +40 backfill lines | Generator |
| `scripts/security/generate-oidc-hashes.main.kts` | NEW FILE (150 lines) | Hash tool |
| `configs.templates/applications/authelia/configuration.yml` | 12 replacements | Template |
| `configs.templates/applications/synapse/homeserver.yaml` | 3 replacements | Template |
| `configs.templates/applications/mailu/mailu.env` | 2 replacements | Template |
| `configs.templates/applications/jellyfin/SSO-Auth.xml` | 1 replacement | Template |
| `configs.templates/databases/postgres/init-db.sh` | 7 fail-secure changes | Template |
| `configs.templates/databases/postgres/ensure-users.sh` | 5 fail-secure changes | Template |

**Total: 8 files modified, 1 new file created**

---

## Security Improvements

1. **No hardcoded secrets in templates** - All use `{{PLACEHOLDERS}}`
2. **Fail-secure database init** - Scripts exit if passwords not set (no defaults)
3. **Automated hash generation** - No manual pbkdf2 generation needed
4. **Encrypted secret storage** - Secrets encrypted at rest in `~/.local/share/stack-secrets/`
5. **Unique per-deployment** - Each `init` generates fresh secrets
6. **Git-safe** - Only templates committed, configs/ gitignored

---

## Troubleshooting

### "ERROR: *_DB_PASSWORD not set"
**Cause**: `.env` not loaded or missing password
**Fix**: Run `kotlin scripts/core/configure-environment.kts export`

### "PENDING" in OIDC hashes
**Cause**: Forgot to run hash generator
**Fix**: `kotlin scripts/security/generate-oidc-hashes.main.kts`

### "Neither docker nor authelia found"
**Cause**: Hash generator needs authelia binary or docker
**Fix**: `docker pull authelia/authelia:latest`

### Authelia fails to start with "invalid key"
**Cause**: OIDC key format issue
**Fix**: Check `.env` has full PEM key (BEGIN/END lines)

---

## Next Steps

1. ✅ Secrets generated
2. ✅ Hashes created
3. ✅ Templates processed
4. ⏭️  Test bootstrap
5. ⏭️  Move to lab server
6. ⏭️  Add resource limits (after profiling)
7. ⏭️  Test disaster recovery

You're now ready for clean deployment! 🚀
