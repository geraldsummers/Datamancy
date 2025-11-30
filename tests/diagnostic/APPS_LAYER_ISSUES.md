# Apps Layer Diagnostic Report
**Date:** 2025-11-30
**Test Type:** Full stack deployment and log analysis

## Executive Summary
Deployed full applications stack with 27+ services. **4 critical database authentication issues** identified preventing service startup. Infrastructure services (caddy, authelia, litellm, kfuncdb, probe-orchestrator) are healthy.

## Service Status Overview

### ✅ Healthy Services (13)
- authelia, caddy, couchdb, docker-proxy, dockge
- grafana, kfuncdb, ldap, ldap-account-manager, litellm
- postgres, redis, redis-synapse, mariadb, mariadb-seafile, memcached
- playwright, probe-orchestrator, vllm

### ⚠️ Unhealthy Services (6)
1. **vllm-router** - unhealthy (healthcheck failing but service running)
2. **homepage** - unhealthy (ownership warnings, but functional)
3. **mailu-admin** - unhealthy (database auth failure)
4. **portainer** - unhealthy (timed out after 5min for security)
5. **portainer-agent** - unhealthy
6. **planka** - restart loop (database auth failure)

### 🔄 Restarting/Crashed Services (2)
1. **outline** - restart loop (database auth failure)
2. **planka** - restart loop (database auth failure)

### 🚫 Registry/Build Issues (2)
- **whisper** - image pull denied
- **piper** - image pull denied

### ⚠️ Configuration Warnings (8 services)
- **Mastodon** services - 8 missing environment variables (DB_PASSWORD, SECRET_KEY_BASE, OTP_SECRET, SMTP credentials, OIDC_SECRET, VAPID keys)

## Critical Issues

### ISSUE #1: PostgreSQL Database Authentication Failures
**Severity:** Critical
**Affected Services:** planka, outline, mailu-admin

**planka error:**
```
error: password authentication failed for user "planka"
code: '28P01'
```

**outline error:**
```
SequelizeConnectionError: password authentication failed for user "outline"
```

**mailu-admin error:**
```
sqlalchemy.exc.OperationalError: password authentication failed for user "mailu"
```

**Root Cause:** Database initialization script (`configs/databases/postgres/init-db.sh`) may not have:
1. Created the user accounts (planka, outline, mailu)
2. Set the correct passwords from environment variables
3. Granted appropriate permissions

**Fix Required:**
- Check `configs/databases/postgres/init-db.sh`
- Verify `PLANKA_DB_PASSWORD`, `OUTLINE_DB_PASSWORD`, `MAILU_DB_PASSWORD` are properly used
- May need to recreate postgres volume or manually fix users

### ISSUE #2: vllm-router Healthcheck Failure
**Severity:** Medium
**Status:** Service is running and responding at http://0.0.0.0:8010

**Logs show:** Normal Netty/Ktor startup, server listening at http://0.0.0.0:8010

**Possible Causes:**
- Healthcheck endpoint misconfiguration (checking wrong URL/port)
- Healthcheck timing too aggressive
- Network connectivity issue between healthcheck and service

**Fix Required:**
- Review vllm-router healthcheck in docker-compose.yml:1488-1491
- Test healthcheck command manually: `wget --spider -q http://localhost:8010/health`

### ISSUE #3: Portainer 5-Minute Timeout
**Severity:** Low
**Status:** Security feature, not a bug

**Log:**
```
the Portainer instance timed out for security purposes, to re-enable your Portainer instance, you will need to restart Portainer
```

**Explanation:** Portainer requires initial setup within 5 minutes of first start.

**Fix Required:** Access https://portainer.${DOMAIN} within 5 min of startup to complete setup, or restart service.

### ISSUE #4: Image Registry Access Denied
**Severity:** Medium
**Affected Services:** whisper, piper

**Error:**
```
error from registry: denied
```

**Possible Causes:**
- Docker Hub rate limiting
- Authentication required
- Image moved/deleted
- Network/proxy issues

**Fix Required:**
- Check if images exist: `onerahmet/openai-whisper-asr-webservice:latest`, `ghcr.io/rhasspy/piper-http:latest`
- Try manual pull to verify access
- May need Docker Hub authentication or alternate registry

### ISSUE #5: Homepage Ownership Warnings
**Severity:** Low (cosmetic)
**Status:** Service is functional

**Log:**
```
Warning: Could not chown /app/config; continuing anyway
```

**Fix Required:** Non-critical, but could adjust container user/group or volume permissions if desired.

### ISSUE #6: Missing Mastodon Configuration
**Severity:** High (if Mastodon deployment intended)
**Status:** 8 environment variables not set

Missing variables:
- MASTODON_DB_PASSWORD
- MASTODON_SECRET_KEY_BASE
- MASTODON_OTP_SECRET
- MASTODON_SMTP_USER
- MASTODON_SMTP_PASSWORD
- MASTODON_OIDC_SECRET
- VAPID_PRIVATE_KEY
- VAPID_PUBLIC_KEY

**Fix Required:**
- Generate secrets using Mastodon's rake tasks
- Add to .env or environment configuration

## Recommended Actions

### Immediate Priority
1. ✅ Fix PostgreSQL user creation in `configs/databases/postgres/init-db.sh`
2. ✅ Restart postgres or manually create users with correct passwords
3. ✅ Verify vllm-router healthcheck configuration
4. ⚠️ Access Portainer within 5 minutes to complete initial setup

### Secondary Priority
5. Investigate whisper/piper image registry issues
6. Generate and configure Mastodon secrets if deployment intended
7. Review homepage volume permissions

## Files to Review
- `configs/databases/postgres/init-db.sh` (PostgreSQL initialization)
- `docker-compose.yml:1488-1491` (vllm-router healthcheck)
- `.env` file (verify all DB passwords match)

## Next Steps: Automated Audit
Once critical database issues are resolved, the probe-orchestrator agent can:
1. Systematically test each service's web interface
2. Capture screenshots of login/home pages
3. Verify SSO integration via Authelia
4. Check API endpoints for each service
5. Generate visual proof of service health
