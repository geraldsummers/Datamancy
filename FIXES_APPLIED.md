# Fixes Applied - OBLITERATE Persistent

This document lists all fixes applied to resolve JupyterHub, Planka, and other OAuth authentication issues. All changes are integrated into templates and scripts to survive OBLITERATE.

## Date: 2025-12-05

### Issue 1: JupyterHub and Planka OAuth Authentication Failures

**Problem**: Both services returned 500 errors or "Unknown error" messages during OAuth login.

**Root Cause**: OAuth client secret hashes were set to "PENDING" in Authelia configuration instead of proper pbkdf2-sha512 hashes.

**Solution**:
1. Created `/scripts/security/generate-oidc-hashes.main.kts` - Automated script that:
   - Reads OAuth secrets from `.env.runtime`
   - Uses Authelia's crypto tool (via Docker exec) to generate pbkdf2-sha512 hashes
   - Updates `.env.runtime` with generated hashes
   - Provides clear instructions for next steps

2. Added `hash-oidc` command to `stack-controller.main.kts`:
   ```bash
   ./stack-controller.main.kts hash-oidc
   ```
   - Invokes the hash generation script
   - Integrated into stack workflow

3. Updated `stack-controller.main.kts` `config generate` function:
   - Added reminder message about running `hash-oidc` after starting Authelia
   - Documents the workflow for users

**Files Modified**:
- `/scripts/security/generate-oidc-hashes.main.kts` (NEW)
- `/stack-controller.main.kts` (cmdHashOidc function added, config generate updated)

### Issue 2: Docker Compose Environment Variable Parsing

**Problem**: After generating hashes, docker-compose failed with errors like:
```
The "pbkdf2" variable is not set. Defaulting to a blank string.
```

**Root Cause**: The `$` characters in pbkdf2-sha512 hashes (e.g., `$pbkdf2-sha512$...`) were being interpreted as environment variable references by docker-compose when reading the Authelia configuration file.

**Solution**: Updated Authelia configuration template to use single quotes for `client_secret` values:
```yaml
# Before (double quotes):
client_secret: "{{JUPYTERHUB_OAUTH_SECRET_HASH}}"

# After (single quotes):
client_secret: '{{JUPYTERHUB_OAUTH_SECRET_HASH}}'
```

Single quotes in YAML prevent variable interpolation, treating `$` literally.

**Files Modified**:
- `/configs.templates/applications/authelia/configuration.yml` (all `client_secret` fields now use single quotes)

### Issue 3: Test Script Not Saving HTML/DOM State

**Problem**: Screenshots showed services on login screens, but no way to inspect the actual DOM to understand why.

**Solution**: Enhanced test script to save HTML dumps alongside screenshots:
- Added HTML content saving in `saveScreenshot()` function
- HTML files saved with same naming convention as screenshots (e.g., `Planka_logged_in.html`)
- Allows post-test DOM inspection without re-running tests

**Files Modified**:
- `/scripts/testing/TestAllUIServices.main.kts` (saveScreenshot function enhanced)

## Post-OBLITERATE Workflow

After running OBLITERATE, follow this workflow to ensure OAuth authentication works:

```bash
# 1. Generate environment configuration (hashes will be PENDING)
./stack-controller.main.kts config generate

# 2. Start the stack (including Authelia)
./stack-controller.main.kts up

# 3. Generate OAuth client secret hashes (requires Authelia running)
./stack-controller.main.kts hash-oidc

# 4. Apply updated hashes to Authelia configuration
./stack-controller.main.kts config process

# 5. Restart Authelia to load new configuration
./stack-controller.main.kts restart authelia
```

## Services Fixed

- ✅ **JupyterHub** - OAuth authentication now works (shows `/hub/spawn` after login)
- ✅ **Planka** - OAuth authentication now works
- ✅ **Grafana** - OAuth authentication verified working
- ✅ **Open WebUI** - OAuth authentication verified working
- ✅ **Vaultwarden** - OAuth secret hash generated (needs SSO button click in test script)

## Services Still Needing Attention

- ⚠️ **Bookstack** - Uses LDAP authentication (not OAuth), redirect to `/login` requires second login
- ⚠️ **Vaultwarden** - Hash generated, but test script doesn't detect SSO button properly yet
- ⚠️ **Mailu Admin/Roundcube** - Redirect loop issues (separate from OAuth)

## Technical Details

### OAuth Secret Hash Generation

The pbkdf2-sha512 hashing algorithm used by Authelia:
```bash
docker exec authelia authelia crypto hash generate pbkdf2 --password "secret_here"
```

Produces hashes like:
```
$pbkdf2-sha512$310000$base64salt$base64hash
```

### YAML Quoting Requirements

When embedding pbkdf2 hashes in YAML files that are parsed by docker-compose:
- **DO**: Use single quotes: `client_secret: '$pbkdf2-sha512$...'`
- **DON'T**: Use double quotes: `client_secret: "$pbkdf2-sha512$..."` (breaks!)
- **WHY**: Single quotes prevent `$` from being interpreted as variable references

## Verification

To verify all fixes are working:
```bash
# Run UI test script
cd scripts/testing
kotlin TestAllUIServices.main.kts

# Check results
cat screenshots/ui_test_report_kotlin.json

# Inspect HTML dumps if needed
ls screenshots/*.html
```

## Summary

All fixes are now part of the codebase templates and scripts. After OBLITERATE:
1. The hash generation script exists and is callable via stack-controller
2. The Authelia template uses proper quoting to prevent docker-compose parsing issues
3. The test script saves HTML dumps for debugging
4. The workflow is documented in stack-controller help messages

**No manual fixes required after OBLITERATE** - just follow the documented workflow.
