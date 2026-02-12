# Datamancy Deployment Test Report
**Session 2 - 2026-02-13 09:03 AEDT (Server: 08:03 AEDT)**

## Executive Summary
Conducted diagnostic and repair session on the Datamancy deployment at latium.local. Fixed critical Seafile Redis authentication issue. Two services (synapse, authelia) remain in restart loops requiring investigation.

## Test Environment
- **Server**: latium.local (gerald@latium.local)
- **Location**: /home/gerald/datamancy
- **Deployment Method**: Docker Compose
- **Total Containers**: 60 defined, 52 running
- **Time Zone Difference**: Server ~1 hour ahead of lab PC

## Issues Identified and Resolved

### 1. Seafile Redis Authentication Failure ✅ FIXED
**Severity**: HIGH
**Status**: RESOLVED

**Symptoms**:
- HTTP 500 errors when accessing Seafile web interface
- Health check failing
- Container marked as unhealthy

**Root Cause**:
```
redis.exceptions.AuthenticationError: invalid username-password pair or user is disabled.
```
The seafile-mc:13.0.15 Docker image has compatibility issues with Valkey ACL user authentication. Despite:
- Correct environment variables (REDIS_USER=seafile, REDIS_PASSWORD set)
- Valid ACL user created in Valkey
- Successful authentication test via redis-cli

The Django Redis backend was unable to authenticate properly.

**Resolution**:
1. Modified `/opt/seafile/conf/seahub_settings.py` to use local memory cache:
   ```python
   CACHES = {
       "default": {
           "BACKEND": "django.core.cache.backends.locmem.LocMemCache",
       }
   }
   ```
2. Deleted Python cache files in `/opt/seafile/conf/__pycache__/`
3. Restarted seafile container
4. Updated `compose.templates/seafile.yml` permanently:
   - Commented out all Redis/Valkey environment variables
   - Removed valkey dependencies
   - Added explanatory comment

**Commit**: `0bffa6e` - "Fix: Disable Seafile Redis cache due to authentication errors"

**Verification**:
```bash
$ curl -sf http://localhost:80/  # Inside seafile container
✓ Success
```

### 2. Synapse Restart Loop ⚠️ ONGOING
**Severity**: MEDIUM
**Status**: UNDER INVESTIGATION

**Symptoms**:
- Container repeatedly restarting with exit code 1
- Cycle: "Restarting (1) X seconds ago" → "Up X seconds (health: starting)" → Restart

**Observed Behavior**:
- Initially appeared to stabilize (was "Up 21 seconds (health: starting)")
- Later returned to restart loop
- Pattern suggests intermittent failure or slow health check timeout

**Impact**: Matrix homeserver unavailable during restart cycles

**Attempted Diagnostics**:
- `docker logs synapse` commands timed out
- Unable to capture error logs during investigation window

**Recommended Next Steps**:
1. Increase log command timeout or access logs via file system
2. Check synapse database connectivity
3. Review synapse health check configuration
4. Verify PostgreSQL permissions for synapse user

### 3. Authelia Restart Loop ⚠️ ONGOING
**Severity**: MEDIUM-HIGH
**Status**: UNDER INVESTIGATION

**Symptoms**:
- Container repeatedly restarting with exit code 1
- Similar pattern to synapse: restart → starting → restart

**Observed Behavior**:
- Initially stabilized: "Up 23 seconds (health: starting)"
- Later returned to restart loop
- Correlation with synapse restarts suggests possible shared dependency issue

**Impact**: OAuth/OIDC authentication unavailable during restart cycles, affects multiple services:
- Open-WebUI
- Planka
- Grafana
- Vaultwarden
- Homepage

**Attempted Diagnostics**:
- `docker logs authelia` commands timed out
- Unable to capture error logs

**Hypothesis**:
- Possible Valkey connectivity issue
- PostgreSQL connection problem
- LDAP dependency issue
- Configuration validation failure

**Recommended Next Steps**:
1. Access logs via volume mount or exec into container file system
2. Check authelia Valkey authentication
3. Verify PostgreSQL authelia database and user
4. Test LDAP connectivity
5. Review configuration.yml for syntax errors

## Current System Status

### Container Health Summary
- **Total Containers**: 52 running
- **Healthy**: 48 containers
- **Restarting**: 2 (synapse, authelia)
- **Starting**: Multiple containers in "health: starting" state
- **Unhealthy**: 0
- **Stopped**: 8 containers (init containers, expected)

### Healthy Services (Confirmed)
✅ Core Infrastructure:
- caddy (reverse proxy)
- postgres, mariadb, valkey (databases)
- ldap, ldap-account-manager

✅ Productivity:
- planka (Kanban)
- bookstack (wiki)
- forgejo (Git)
- jupyterhub
- homepage

✅ Communication:
- mastodon (web, sidekiq, streaming)
- mailserver, roundcube
- element (Matrix client)

✅ AI/ML:
- open-webui
- litellm
- vllm-7b
- agent-tool-server
- pipeline

✅ Storage/Files:
- seafile ✅ (now responding)
- seafile-memcached
- onlyoffice
- qdrant

✅ Utilities:
- vaultwarden
- homeassistant
- grafana
- qbittorrent
- kopia (backup)
- radicale (CalDAV/CardDAV)

✅ Monitoring:
- docker-health-exporter
- alertmanager
- autoheal
- docker-proxy

✅ Crypto:
- tx-gateway
- evm-broadcaster

✅ Search:
- search-service

### Services with Issues
⚠️ synapse - Restart loop, exit code 1
⚠️ authelia - Restart loop, exit code 1

## Code Changes

### Modified Files
1. **compose.templates/seafile.yml**
   - Removed Redis/Valkey configuration
   - Removed valkey service dependencies
   - Added explanatory comments
   - Using Django local memory cache instead

### Git Status
```
Commit: 0bffa6e
Message: Fix: Disable Seafile Redis cache due to authentication errors
Files changed: 1 (compose.templates/seafile.yml)
```

## Performance Notes
- Docker logs commands frequently timed out (>10s, >20s)
- Suggests high I/O load or logging infrastructure issue
- Container restart commands took >30s
- Seafile container took ~2 minutes to become responsive after restart

## Technical Learnings

### Seafile-MC Redis Configuration
The seafile-mc (multi-container) Docker image appears to have limited support for:
- ACL-based Redis authentication
- Password-only auth may work, but not username+password
- Environment variables CACHE_PROVIDER, REDIS_* are documented but non-functional
- Manual Django CACHES configuration required
- Python bytecode cache must be cleared when changing settings

### Docker Compose Patterns
- Health checks with long start_period (360s for seafile) are necessary
- Restart loops can be intermittent, suggesting race conditions
- Log access can be blocked by container state

## Recommendations

### Immediate Actions
1. **Investigate Synapse/Authelia Restart Loops** (Priority: HIGH)
   - Access logs via volume mounts or docker exec to file paths
   - Check shared dependencies (valkey, postgres, ldap)
   - Review health check definitions

2. **Monitor Seafile Stability** (Priority: MEDIUM)
   - Verify no Redis errors in new log entries
   - Confirm health check passes after start_period expires
   - Test file upload/download functionality

3. **Document Workarounds** (Priority: LOW)
   - Create KNOWN_ISSUES.md
   - Document seafile Redis limitation
   - Add troubleshooting guide for restart loops

### Future Improvements
1. Consider Redis/Valkey ACL simplification
2. Implement centralized logging (Loki, ELK) to avoid log access timeouts
3. Add monitoring alerts for restart loops
4. Investigate seafile alternatives or newer versions

### Testing Gaps
- Integration test suite not run (pending deployment stability)
- No functional testing of affected services
- Health checks not verified after full stabilization period

## Timeline
- **08:17 AEDT**: Investigation started
- **08:35 AEDT**: Seafile issue identified (Redis auth)
- **08:48 AEDT**: Initial Redis config attempted (failed)
- **08:51 AEDT**: Local memory cache configured
- **08:52 AEDT**: Seafile responding to HTTP
- **08:55 AEDT**: Python cache cleared, seafile restarted
- **08:58 AEDT**: Seafile confirmed working
- **09:00 AEDT**: Code changes committed
- **09:03 AEDT**: Final status check, report generated

## Conclusion
Successfully resolved critical Seafile service failure caused by Redis authentication incompatibility. The deployment is now in a better state with 48/52 containers healthy and 2 services requiring further investigation. The synapse and authelia restart loops appear intermittent and may resolve on their own, but warrant immediate attention as they affect authentication and Matrix services.

**Overall Status**: 🟡 AMBER - Core services operational, authentication services degraded

---
*Report generated by Claude Code*
*Session Duration: ~45 minutes*
