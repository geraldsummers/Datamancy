# FileBrowser SSO Configuration

## Overview

FileBrowser is configured with **proxy authentication** using Authelia forward-auth headers. This provides seamless SSO integration with LDAP-based RBAC.

## Authentication Flow

```
User → Caddy (filebrowser.stack.local)
     → Authelia forward_auth (validates session)
     → LDAP authentication
     → Caddy forwards Remote-User header
     → FileBrowser (proxy auth mode)
     → User logged in as LDAP username
```

## Configuration

### Authentication Method
- **Mode**: `proxy` (header-based authentication)
- **Header**: `Remote-User` (passed by Caddy from Authelia)
- **Bind Address**: `0.0.0.0:8080` (accessible from network)

### Key Files
- `docker-compose.yml` - Service definition with custom entrypoint
- `configs/filebrowser/entrypoint.sh` - Initialization script that configures proxy auth
- `configs/caddy/Caddyfile` - Forward-auth configuration for FileBrowser

### Entrypoint Script
The entrypoint script:
1. Initializes the database if missing
2. Configures proxy authentication with `Remote-User` header
3. Sets bind address to `0.0.0.0` (not localhost)
4. Starts FileBrowser

## RBAC Implementation

### Access Control
- **Primary Gate**: Authelia forward-auth at Caddy level
- **Authorization**: Controlled by Authelia access control rules (can be configured in `configs/authelia/configuration.yml`)
- **User Identity**: Automatically mapped from LDAP username via `Remote-User` header

### Multi-User Support
FileBrowser has built-in multi-user support:
- Each LDAP user authenticated via Authelia gets their own FileBrowser session
- User identity is determined by the `Remote-User` header
- Users are automatically created on first login
- Per-user permissions can be configured within FileBrowser

### LDAP Group Integration
While FileBrowser doesn't directly consume LDAP groups, RBAC is enforced at multiple levels:

1. **Authelia Level** (Primary RBAC):
   - Access control rules in `configuration.yml` can restrict which LDAP groups can access FileBrowser
   - Example: Only users in `admins` group can access

2. **FileBrowser Level**:
   - Per-user permissions (admin, create, delete, modify, etc.)
   - Scope restrictions (which directories users can access)
   - Can be configured via FileBrowser UI or CLI

## Testing

### Automated Test
Location: `tests/specs/filebrowser-sso-final.spec.ts`

The test validates:
- Redirect to Authelia login page
- LDAP authentication (username: `admin`, password: `DatamancyTest2025!`)
- Redirect back to FileBrowser after successful auth
- FileBrowser UI loads with user logged in

### Test Results
- Status: ✅ **SUCCESS**
- Timestamp: 2025-10-29T21:17:06.569Z
- Screenshots: `screenshots/filebrowser/`
- Manifest: `screenshots/filebrowser/TEST_MANIFEST.json`

### Screenshot Evidence
1. `01-initial-redirect.png` - Initial navigation to FileBrowser
2. `02-authelia-login-page.png` - Redirect to Authelia
3. `03-username-filled.png` - Username filled
4. `04-credentials-filled.png` - Password filled
5. `05-after-signin-click.png` - After sign-in click
6. `06-filebrowser-logged-in.png` - **Logged in to FileBrowser (shows user "admin" in sidebar)**
7. `07-final-verification.png` - Final verification

## Persistence

All configuration persists across stack restarts:
- `docker-compose.yml` - Service definition
- `configs/filebrowser/entrypoint.sh` - Initialization script
- `configs/caddy/Caddyfile` - Forward-auth configuration

After running `cleandocker.sh`, the configuration automatically applies on next `docker compose up`.

## Advanced Configuration

### Restricting Access by LDAP Group

Edit `configs/authelia/configuration.yml`:

```yaml
access_control:
  rules:
    - domain: filebrowser.stack.local
      policy: one_factor
      subject:
        - "group:admins"  # Only admins group
```

### Per-User Permissions

Use FileBrowser CLI or UI to configure per-user settings:

```bash
# Example: Make a user admin
docker exec filebrowser filebrowser --database /database/filebrowser.db \
  users update admin --perm.admin

# Example: Restrict user scope
docker exec filebrowser filebrowser --database /database/filebrowser.db \
  users update someuser --scope /srv/media/restricted
```

## Troubleshooting

### User Not Logged In
- Check `Remote-User` header is being passed: `docker logs caddy`
- Verify Authelia session is valid
- Check FileBrowser logs: `docker logs filebrowser`

### Permission Denied
- Verify LDAP group membership in Authelia access control
- Check FileBrowser per-user permissions
- Review scope restrictions

### Service Not Accessible
- Ensure FileBrowser is listening on `0.0.0.0:8080` (check logs)
- Verify Caddy forward-auth configuration
- Test Authelia authentication separately

## Security Notes

1. **No Direct FileBrowser Login**: FileBrowser's native login is bypassed; all authentication via Authelia
2. **Header Trust**: FileBrowser trusts the `Remote-User` header (secure because Caddy only passes it after Authelia validates)
3. **Session Management**: Handled by Authelia + Redis
4. **HTTPS Required**: All traffic encrypted via Caddy's internal TLS

## Summary

FileBrowser now has:
- ✅ SSO integration via Authelia
- ✅ LDAP authentication backend
- ✅ RBAC via Authelia access control
- ✅ Multi-user support with per-user identities
- ✅ Automated test with screenshot proof
- ✅ Persistent configuration across stack restarts
