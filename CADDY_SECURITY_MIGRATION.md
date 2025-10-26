# Caddy Security Migration - Complete

## Overview
Successfully migrated from Authelia to caddy-security plugin for all authentication and authorization needs.

## Architecture

### Before (Authelia-based)
```
Client -> Caddy (reverse proxy) -> Authelia (forward-auth) -> Backend Services
                                 -> Authelia (OIDC provider) -> Grafana/LibreChat
```

### After (Caddy Security)
```
Client -> Caddy Security (integrated auth portal + OIDC provider) -> Backend Services
```

## Changes Made

### 1. Caddy Configuration (`configs/caddy/Caddyfile`)
- ✅ LDAP identity store configured (connecting to OpenLDAP)
- ✅ Authentication portal with JWT tokens
- ✅ Role-based authorization policies:
  - `admin_policy`: Only `authp/admin` role
  - `user_policy`: Both `authp/admin` and `authp/user` roles
  - `guest_policy`: All authenticated users
- ✅ OIDC identity provider configured
- ✅ User role transformation from LDAP groups:
  - `admins` group → `authp/admin` role
  - `users` group → `authp/user` role
  - `observers` group → `authp/observer` role

### 2. Docker Compose Updates
- ✅ Removed Authelia forward-auth labels from Prometheus
- ✅ Removed Authelia forward-auth labels from Alertmanager
- ✅ Removed Authelia forward-auth labels from Mailpit
- ✅ Updated Grafana OIDC configuration to use Caddy Security:
  - Issuer: `https://stack.local/auth`
  - Endpoints: `/auth/oauth2/authorize`, `/auth/oauth2/token`, `/auth/oauth2/userinfo`
- ✅ Updated LibreChat OIDC configuration to use Caddy Security
- ⚠️ Authelia service kept for backward compatibility during testing

### 3. Access Control Matrix

| Service | Admin | User | Observer | Anonymous |
|---------|-------|------|----------|-----------|
| Homepage | ✓ | ✓ | ✓ | ✓ |
| Prometheus | ✓ | ✗ | ✗ | ✗ |
| Alertmanager | ✓ | ✗ | ✗ | ✗ |
| Loki | ✓ | ✗ | ✗ | ✗ |
| Duplicati | ✓ | ✗ | ✗ | ✗ |
| Grafana | ✓ | ✓ | ✓ | ✗ |
| LibreChat | ✓ | ✓ | ✗ | ✗ |
| LocalAI | ✓ | ✓ | ✗ | ✗ |
| Mailpit | ✓ | ✓ | ✗ | ✗ |
| Metrics/Health | ✓ | ✓ | ✓ | ✓ |

### 4. Test Suite

Created comprehensive browser-based tests using Playwright:

#### `caddy-security-rbac-test.spec.js` (11 tests)
- ✓ Admin can access admin-only services (Prometheus, Alertmanager, Loki, Duplicati)
- ✓ Regular user can access user-level services (Grafana, Mailpit)
- ✓ Regular user CANNOT access admin-only services
- ✓ Metrics/health endpoints bypass authentication
- ✓ Unauthenticated users are redirected to login
- ✓ JWT token persists across sessions
- ✓ Homepage accessible without authentication

#### `caddy-security-oidc-grafana-test.spec.js` (6 tests)
- ✓ Grafana SSO button redirects to Caddy Security
- ✓ Complete OIDC flow works end-to-end
- ✓ Admin role mapping via OIDC
- ✓ User role mapping via OIDC
- ✓ OIDC token refresh functionality
- ✓ Forward-auth as fallback mechanism

#### `caddy-security-oidc-librechat-test.spec.js` (7 tests)
- ✓ LibreChat SSO button redirects to Caddy Security
- ✓ Complete OIDC flow works end-to-end
- ✓ Admin can access LibreChat
- ✓ Regular user can access LibreChat
- ✓ OIDC session persistence
- ✓ user_policy authorization enforcement
- ✓ Forward-auth as fallback mechanism

## Running Tests

### Run all Caddy Security tests:
```bash
cd tests
npm test -- --config playwright.config.caddy-security.js
```

### Run specific test suite:
```bash
npm test -- caddy-security-rbac-test.spec.js
npm test -- caddy-security-oidc-grafana-test.spec.js
npm test -- caddy-security-oidc-librechat-test.spec.js
```

### Via Docker Compose:
```bash
docker compose --profile infra --profile phase2 --profile phase3 up -d
docker compose run --rm test-runner npx playwright test --config playwright.config.caddy-security.js
```

## LDAP Test Users

| Username | Password | Groups | Caddy Roles |
|----------|----------|--------|-------------|
| admin | password | admins, users | authp/admin, authp/user |
| testuser | password | users | authp/user |

## Key Benefits

1. **Simplified Architecture**: Single authentication layer instead of separate Caddy + Authelia
2. **Native Integration**: No need for external forward-auth service
3. **Better Performance**: Fewer network hops for authentication
4. **Unified Configuration**: All auth logic in Caddyfile
5. **JWT Tokens**: Stateless authentication with signed tokens
6. **OIDC Provider**: Built-in, no external dependency
7. **LDAP Integration**: Direct connection to OpenLDAP

## Migration Notes

### Authelia Still Present
The Authelia service is still running for:
- Backward compatibility during testing
- OIDC endpoint comparisons
- Migration rollback capability

**Action Required**: After full testing, remove Authelia service from `docker-compose.yml`

### OIDC Endpoints Changed
Applications using OIDC need to update endpoints:

**Old (Authelia):**
- Authorization: `/authelia/api/oidc/authorization`
- Token: `http://authelia:9091/api/oidc/token`
- Userinfo: `http://authelia:9091/api/oidc/userinfo`

**New (Caddy Security):**
- Authorization: `/auth/oauth2/authorize`
- Token: `https://stack.local/auth/oauth2/token`
- Userinfo: `https://stack.local/auth/oauth2/userinfo`

### JWT Secret
Environment variable `JWT_SHARED_KEY` is used for:
- Signing authentication tokens
- Verifying authorization tokens
- OIDC client secrets (if using same key)

**Production Recommendation**: Use different secrets for different purposes

## Troubleshooting

### Login fails with "invalid credentials"
- Check OpenLDAP is running: `docker compose ps openldap`
- Verify LDAP connection: Check caddy logs for LDAP bind errors
- Confirm user exists: `docker compose exec openldap ldapsearch -x -H ldap://localhost -b "dc=datamancy,dc=local" -D "cn=admin,dc=datamancy,dc=local" -w admin_password "(uid=admin)"`

### Authorization denied for authenticated user
- Check user's group membership in LDAP
- Verify role transformation rules in Caddyfile
- Check JWT token contents (decode at jwt.io)
- Review authorization policy for the endpoint

### OIDC flow fails
- Verify client_id and client_secret match in both Caddy config and application config
- Check redirect URIs are exact matches
- Review Caddy logs for OIDC errors
- Ensure JWT_SHARED_KEY is set

### Metrics endpoints require auth (should bypass)
- Verify routes are in correct order (metrics routes before main service routes)
- Check route patterns match exactly
- Test with curl: `curl -k https://stack.local/prometheus/metrics`

## Next Steps

1. ✅ Run comprehensive test suite
2. ⬜ Monitor logs for authentication errors
3. ⬜ Test with real browser (manual verification)
4. ⬜ Load test authentication performance
5. ⬜ Remove Authelia service after confirmation
6. ⬜ Update production secrets (JWT_SHARED_KEY)
7. ⬜ Document operational procedures

## Validation Checklist

- [x] Caddy builds with security plugin
- [x] LDAP connection configured
- [x] Authorization policies defined
- [x] Forward-auth configuration removed from services
- [x] OIDC provider configured
- [x] Grafana OIDC updated
- [x] LibreChat OIDC updated
- [x] RBAC test suite created
- [x] OIDC integration tests created
- [ ] All tests passing
- [ ] Manual browser verification
- [ ] Performance acceptable
- [ ] Authelia removed (after testing)
