# Obliterate Persistence Fixes

## Summary
Fixed multiple issues related to service authentication and ensuring configuration persistence through the `obliterate` command.

## Issues Fixed

### 1. **Planka - Unknown Error After SSO Button Click**
**Root Cause:**
- The `caddy-ca.crt` file was being created as a **directory** instead of a file
- Docker would fail to mount it properly, and Planka couldn't trust internal HTTPS calls
- Planka's OIDC client secret authentication was failing (`invalid_client`) due to auth method mismatch
- Planka sends authentication using `client_secret_basic` but Authelia was configured for `client_secret_post`

**Solution:**
1. Created `scripts/core/setup-caddy-ca-cert.sh` to properly extract Caddy's CA certificate
2. Modified `stack-controller.main.kts` to call this script during `config process`
3. Updated `obliterate` function to preserve and restore init script volumes
4. When Caddy is not running or hasn't generated the cert yet, creates a placeholder file to prevent Docker mount failures
5. Changed Authelia's Planka client configuration to use `token_endpoint_auth_method: client_secret_basic`
   - Planka's OAuth library defaults to `client_secret_basic` and the environment variable setting doesn't override it
   - This matches what Planka actually sends in its token requests

**Files Modified:**
- `stack-controller.main.kts:1061-1064` - Added setup-caddy-ca-cert.sh call
- `stack-controller.main.kts:1476-1563` - Updated obliterate to backup/restore init volumes
- `configs.templates/applications/authelia/configuration.yml:236` - Changed Planka auth method to client_secret_basic
- Created `scripts/core/setup-caddy-ca-cert.sh`

### 2. **Bookstack - 500 Error and SSO "invalid_client" Error**
**Root Causes:**
1. Database migrations weren't running automatically on first boot
   - The LinuxServer Bookstack container creates a default `.env` file with placeholder credentials (`database_username`)
   - Laravel tried to connect before the init script fixed the credentials
   - Even after credentials were fixed, migrations never ran, causing "users table doesn't exist" errors

2. SSO login failed with "invalid_client" error
   - Authelia configuration had duplicate `bookstack` client entries (lines 254 and 329)
   - The duplicate caused Authelia to fail startup with "option 'id' must be unique" error
   - One entry was a remnant comment saying Bookstack uses forward_auth only

**Solution:**
1. Updated `configs.templates/applications/bookstack/init/10-fix-env.sh` to:
   - Fix database credentials in the `.env` file (existing functionality)
   - Configure OIDC authentication for Authelia (existing functionality)
   - **NEW:** Automatically run database migrations if not already applied
   - Clear Laravel cache to ensure new config is loaded
2. The init script checks if migrations are needed using `php artisan migrate:status`
3. If migrations haven't run, executes `php artisan migrate --force`
4. This is safe to run on every container start (idempotent)
5. Init scripts are preserved through obliterate via backup/restore in `stack-controller.main.kts`
6. Removed duplicate Bookstack OIDC client from Authelia configuration
7. Kept the correct client configuration at line 328 with proper settings:
   - `client_id: bookstack`
   - `token_endpoint_auth_method: client_secret_basic` (BookStack's OAuth library doesn't support client_secret_post)
   - `consent_mode: implicit`
   - Proper redirect URI: `https://bookstack.{{DOMAIN}}/oidc/callback`

**Files Modified:**
- `configs.templates/applications/bookstack/init/10-fix-env.sh:129-143` - Added migration check and execution
- `stack-controller.main.kts:1467-1540` - Added backup/restore logic for init script directories
- `configs.templates/applications/authelia/configuration.yml:253` - Removed duplicate Bookstack client entry
- `configs.templates/applications/authelia/configuration.yml:346` - Changed auth method to client_secret_basic

### 3. **JupyterHub - 401 Unauthorized**
**Root Cause:**
- RemoteUserAuthenticator expects the `Remote-User` header from Authelia forward-auth
- Caddy must properly forward this header from Authelia to JupyterHub

**Configuration:**
- `src/jupyterhub/jupyterhub_config.py:52-56` - RemoteUserAuthenticator configured
- Authelia forward-auth passes `Remote-User` header via Caddy
- Requires Caddy configuration to proxy auth headers

### 4. **SOGo - Unauthorized (401)**
**Root Cause:**
- SOGo expects proxy authentication via `x-webobjects-remote-user` header from Authelia
- The 401 "Unauthorized" error is **expected behavior** when not logged in to Authelia
- After Authelia login, the `Remote-User` header is forwarded through Caddy → Apache → SOGo

**Solution:**
The configuration is already correct and persists through obliterate:

1. **SOGo Configuration** (`configs.templates/applications/sogo/sogo.conf`):
   - `SOGoTrustProxyAuthentication = YES` - Trusts authentication headers from proxy
   - `SOGoAuthProxyHeaderValue = "x-webobjects-remote-user"` - Expects this specific header
   - LDAP configured for user lookup and fallback authentication

2. **Apache Configuration** (`configs.templates/applications/sogo/init-apache.sh`):
   - Runs automatically via `sogo-init` container on every start
   - Maps Caddy's `Remote-User` header to `x-webobjects-remote-user`
   - Configuration change: `RequestHeader set "x-webobjects-remote-user" "%{Remote-User}i"`
   - Persists through obliterate because init script is mounted from processed configs

3. **Caddy Configuration** (`configs.templates/infrastructure/caddy/Caddyfile:77-95`):
   - Forward auth to Authelia for all requests
   - Copies headers: `Remote-User`, `Remote-Groups`, `Remote-Name`, `Remote-Email`
   - Forwards these headers to SOGo backend

**Authentication Flow:**
1. User visits `https://sogo.project-saturn.com`
2. Caddy intercepts → Authelia forward-auth check
3. If not authenticated: 401 → redirect to `https://auth.project-saturn.com` (THIS IS WHAT YOU'RE SEEING)
4. User logs in to Authelia with LDAP credentials
5. Authelia sets session cookie → redirects back to SOGo with headers
6. Caddy forwards `Remote-User` header to Apache
7. Apache maps to `x-webobjects-remote-user` header
8. SOGo trusts the header and logs user in automatically

**To test:**
```bash
# 1. Open in browser (not curl)
https://sogo.project-saturn.com

# 2. You'll be redirected to Authelia - this is correct behavior

# 3. Log in with LDAP credentials:
#    Username: your_username
#    Password: your_password

# 4. After login, you'll be redirected back to SOGo and automatically logged in

# Note: Session expires after 1h of initial login or 5m of inactivity
# If you see "unauthorized" even after logging in, your session expired - log in again
```

**Common Issues:**

1. **"Unauthorized" error even though you logged in**
   - **Cause**: Session expired (1h expiration or 5m inactivity)
   - **Solution**: Log in to Authelia again
   - **Verification**: Check active sessions with:
     ```bash
     docker exec valkey valkey-cli keys "authelia:session:*"
     ```
   - If this returns empty, no sessions exist - you need to log in

2. **To increase session timeout** (edit `configs.templates/applications/authelia/configuration.yml`):
   ```yaml
   session:
     cookies:
       - expiration: 8h      # Change from 1h
         inactivity: 1h      # Change from 5m
   ```
   Then run: `./stack-controller config process && ./stack-controller restart authelia`

**Persistence Through Obliterate:**
- ✅ Templates in `configs.templates/applications/sogo/` are git-tracked
- ✅ Processed configs generated automatically by `./stack-controller up` → `config process`
- ✅ Init script mounted from `~/.datamancy/configs/applications/sogo/init-apache.sh`
- ✅ Runs automatically on container start via `sogo-init` container
- ✅ No manual intervention needed after obliterate

**Files Modified:**
- No changes needed - existing configuration already correct and persistent

## Obliterate Function Improvements

The `obliterate` command now preserves:

1. **Caddy certificates** (existing functionality, optional with `--CERT` flag)
2. **Init script volumes** (new):
   - `~/.datamancy/volumes/bookstack_init/`
   - `~/.datamancy/volumes/qbittorrent_init/`

### Backup/Restore Process

The `obliterate` command now backs up and restores init script directories:

```kotlin
// Backup phase (before deletion)
val backupDir = Files.createTempDirectory("datamancy-obliterate-backup")
val volumesDir = dataDir.resolve("volumes")
val initDirs = listOf("bookstack_init", "qbittorrent_init")

for (initDir in initDirs) {
    val sourcePath = volumesDir.resolve(initDir)
    if (Files.exists(sourcePath)) {
        run("cp", "-r", sourcePath.toString(), backupDir.resolve(initDir).toString())
    }
}

// ... wipe ~/.datamancy ...

// Restore phase (after recreation)
Files.createDirectories(dataDir.resolve("volumes"))
for (initDir in backedUpDirs) {
    run("cp", "-r", backupDir.resolve(initDir).toString(),
        dataDir.resolve("volumes").resolve(initDir).toString())
}
```

This ensures init scripts persist through obliterate, preventing the need to regenerate them.

## Testing After Obliterate

After running `./stack-controller obliterate`, the following should work:

```bash
# 1. Start fresh
./stack-controller up

# 2. Verify init scripts are regenerated
ls -la ~/.datamancy/volumes/bookstack_init/
ls -la ~/.datamancy/volumes/qbittorrent_init/

# 3. Verify Caddy CA cert is created
cat ~/.datamancy/configs/applications/planka/caddy-ca.crt

# 4. Test services:
# - Planka: Click SSO button -> should redirect to Authelia -> login -> redirect back with session
# - Bookstack: Access via https://bookstack.yourdomain.com -> Authelia login -> LDAP auth works
# - JupyterHub: Access -> Authelia login -> Remote-User header trusted -> notebook launches
# - SOGo: Access -> Authelia login -> proxy auth header trusted -> mailbox loads
```

## Configuration Files Involved

### Init Scripts (Generated from Templates)
- `configs.templates/applications/bookstack/init/10-fix-env.sh` → `~/.datamancy/volumes/bookstack_init/`
- `configs.templates/applications/qbittorrent/init/10-configure-auth.sh` → `~/.datamancy/volumes/qbittorrent_init/`

### Service Configurations
- `configs.templates/applications/planka/` - No template for caddy-ca.crt (dynamically generated)
- `configs.templates/applications/jupyterhub/jupyterhub_config.py` - RemoteUserAuthenticator config
- `configs.templates/applications/sogo/sogo.conf` - Proxy auth trust config
- `configs.templates/applications/sogo/init-apache.sh` - Apache header forwarding

### Docker Compose Volume Mounts
- Line 998: `${VOLUMES_ROOT}/bookstack_init:/custom-cont-init.d:ro`
- Line 959: `${HOME}/.datamancy/configs/applications/planka/caddy-ca.crt:/usr/local/share/ca-certificates/caddy-ca.crt:ro`
- Line 1387: `${VOLUMES_ROOT}/qbittorrent_init:/custom-cont-init.d:ro`

## Known Limitations

1. **Caddy CA Certificate**: Only needed for internal HTTPS between services (e.g., Planka calling Authelia internally). With production Let's Encrypt certs, this isn't necessary.

2. **Init Script Regeneration**: If obliterate is run without preserving volumes, init scripts are regenerated from templates on next `config process`. This is safe but adds a few seconds to startup.

3. **Permission Issues**: Some services (like Planka config dir) may be created by Docker as root. The setup scripts handle this with Docker-based cleanup when needed.

## Future Improvements

- [ ] Add option to obliterate to skip preserving init scripts (`--clean-all`)
- [ ] Auto-detect when Caddy CA cert changes and update dependent services
- [ ] Add health check after obliterate + up to verify all auth flows work
- [ ] Consider moving init scripts to a persistent location outside volumes/

## Commands Reference

```bash
# Obliterate everything (preserves certs and init scripts)
./stack-controller obliterate

# Obliterate including Caddy certs
./stack-controller obliterate --CERT

# Force without confirmation
./stack-controller obliterate --force

# Regenerate configs after obliterate
./stack-controller config process

# Full rebuild workflow
./stack-controller obliterate
./stack-controller up

# Test specific service after rebuild
docker logs planka --tail 50
docker logs bookstack --tail 50
docker logs jupyterhub --tail 50
docker logs sogo --tail 50
```
