# Secrets Management & Build Process

## Overview

The Datamancy build system generates and handles secrets in two distinct ways:

1. **Runtime Secrets** - Generated once, stored in `.env`, used at container runtime via docker compose variable substitution
2. **Build-time Hashes** - Generated during build, embedded directly into config files

## Critical Rules

### ⚠️ NEVER regenerate secrets on rebuild unless doing a fresh install

**Problem**: If you regenerate secrets in `.env`, they won't match what's stored in databases/applications that were initialized with the old secrets.

**Solution**:
- On **first build**: Generate all secrets fresh
- On **rebuilds**: Reuse existing `.env` file from deployment or keep a backup
- Only generate new secrets when doing a complete teardown/reinstall

### Build-time vs Runtime Variables

#### Runtime Variables (in RUNTIME_VARS set)
These variables use `${VAR}` syntax and are substituted by docker compose at runtime from `.env`:

```kotlin
val RUNTIME_VARS = setOf(
    "LDAP_ADMIN_PASSWORD",
    "POSTGRES_ROOT_PASSWORD",
    "GRAFANA_OAUTH_SECRET",  // Plain OAuth secret
    // ... etc
)
```

**Template syntax**: `{{DOMAIN}}` → becomes → `${DOMAIN}` → docker compose substitutes from `.env`

#### Build-time Hash Variables (NOT in RUNTIME_VARS)
These are OAuth secret hashes that Authelia requires in password digest format:

```kotlin
// Generated during build with generateAutheliaHash()
val oauthHashes = mapOf(
    "GRAFANA_OAUTH_SECRET_HASH" to "$argon2id$v=19$m=65536...",
    "PLANKA_OAUTH_SECRET_HASH" to "$argon2id$v=19$m=65536...",
    // ... etc
)
```

**Template syntax**: `{{GRAFANA_OAUTH_SECRET_HASH}}` → becomes → `$argon2id$v=19$m=65536...` (literal hash in config)

**Why?** Authelia's OIDC client configs require password digests (bcrypt/argon2), not plain secrets.

## How It Works

### Build Process Flow

1. **Load existing .env** (if rebuilding) or generate fresh secrets (if first build)
2. **Generate OAuth hashes** from plain secrets using `docker run authelia/authelia:latest authelia crypto hash generate argon2`
3. **Process config templates**:
   - Replace `{{DOMAIN}}`, `{{STACK_ADMIN_EMAIL}}` etc with literal values
   - Replace `{{*_OAUTH_SECRET_HASH}}` with generated argon2 hashes
   - Convert `{{VAR}}` to `${VAR}` for variables in RUNTIME_VARS
4. **Generate .env** with plain secrets (not hashes)
5. **Deploy**: Configs have embedded hashes, `.env` has plain secrets

### Secret Types

| Secret Type | Stored In | Format | Example Use |
|-------------|-----------|--------|-------------|
| DB Passwords | `.env` | Hex string | `POSTGRES_ROOT_PASSWORD=a1b2c3...` |
| OAuth Secrets | `.env` | Hex string | `GRAFANA_OAUTH_SECRET=d4e5f6...` |
| OAuth Hashes | Config files | Argon2 digest | `client_secret: '$argon2id$v=19$...'` |
| LDAP Password Hashes | Config files | SSHA | `userPassword: {SSHA}abc123...` |
| API Keys | `.env` | Hex string | `QDRANT_API_KEY=g7h8i9...` |

## Common Mistakes

### ❌ WRONG: Adding hash variables to RUNTIME_VARS

```kotlin
val RUNTIME_VARS = setOf(
    "GRAFANA_OAUTH_SECRET",
    "GRAFANA_OAUTH_SECRET_HASH",  // ❌ WRONG! This should be build-time
)
```

**What happens**:
- Template has `{{GRAFANA_OAUTH_SECRET_HASH}}`
- Gets converted to `${GRAFANA_OAUTH_SECRET_HASH}`
- `.env` has plain hex string, not argon2 hash
- Authelia rejects it: "invalid format: digest doesn't have minimum number of parts"

### ✅ CORRECT: Hash variables are build-time only

```kotlin
// In main():
val oauthHashes = mutableMapOf<String, String>()
oauthSecretNames.forEach { name ->
    val hash = generateAutheliaHash(generateSecret())
    oauthHashes["${name}_OAUTH_SECRET_HASH"] = hash
}

// In processConfigs():
oauthHashes.forEach { (varName, hashValue) ->
    content = content.replace("{{$varName}}", hashValue)  // Direct substitution
}
```

### ❌ WRONG: Regenerating .env on every build

```bash
# DON'T DO THIS on a running system!
./build-datamancy.main.kts
# ^ This generates NEW random secrets

rsync dist/.env latium:/deployment/  # ❌ Overwrites working secrets!
```

**What happens**:
- New `POSTGRES_ROOT_PASSWORD` doesn't match postgres database
- New `AUTHELIA_DB_PASSWORD` doesn't match user created in postgres
- All services fail authentication

### ✅ CORRECT: Preserve .env on rebuild

```bash
# Before rebuild, backup the working .env
scp latium:/deployment/.env .env.backup

# Build (this will generate configs but we'll replace .env)
./build-datamancy.main.kts

# Restore working .env
cp .env.backup dist/.env

# Deploy with correct secrets
rsync dist/ latium:/deployment/
```

## Verification

After deployment, verify Authelia can parse configs:

```bash
ssh latium "docker compose logs authelia | grep -E '(error|fatal)'"
```

Should see NO errors about:
- `could not decode '${GRAFANA_OAUTH_SECRET_HASH}' to a *schema.PasswordDigest`
- `invalid format: the digest doesn't have the minimum number of parts`

May see warnings about:
- Plaintext mastodon secret (different issue, acceptable)
- Deprecated config keys (acceptable, Authelia still works)

## Rebuild Checklist

When rebuilding the stack:

- [ ] Backup current `.env` from server
- [ ] Run build script (will generate fresh configs with new hashes)
- [ ] **Before deploying**: Copy old `.env` back to `dist/.env`
- [ ] Verify `.env` has same secret values as before
- [ ] Deploy with rsync
- [ ] Check Authelia logs for config parsing errors
- [ ] Verify services can authenticate

## Fresh Install vs Rebuild

### Fresh Install (no existing secrets)
```bash
./build-datamancy.main.kts          # Generate everything fresh
rsync dist/ latium:/deployment/
ssh latium "cd /deployment && docker compose up -d"
```

### Rebuild (preserve secrets)
```bash
scp latium:/deployment/.env .env.backup
./build-datamancy.main.kts          # Updates configs only
cp .env.backup dist/.env            # Restore secrets
rsync dist/ latium:/deployment/
ssh latium "cd /deployment && docker compose up -d"
```

## Future Improvements

Consider:
1. **Make .env location configurable** - Allow loading existing .env during build
2. **Separate hash generation** - Only regenerate OAuth hashes when needed
3. **Secret rotation script** - Safe procedure for updating specific secrets
4. **Validation tool** - Check if .env matches what's in databases

## Related Files

- `build-datamancy.main.kts:100-135` - RUNTIME_VARS definition
- `build-datamancy.main.kts:343-355` - generateAutheliaHash() function
- `build-datamancy.main.kts:558-571` - OAuth hash generation loop
- `build-datamancy.main.kts:314-317` - Hash substitution in processConfigs()
- `configs.templates/applications/authelia/configuration.yml` - Uses `{{*_OAUTH_SECRET_HASH}}`
