# Integration Test Report - 2026-02-12

**Test Run ID:** 20260212_041427-all
**Duration:** 215.71s (3 minutes 36 seconds)
**Environment:** Datamancy production deployment on latium.local

---

## Executive Summary

| Metric | Count | Percentage |
|--------|-------|------------|
| **Total Tests** | 396 | 100% |
| **✓ Passed** | 365 | 92.2% |
| **✗ Failed** | 17 | 4.3% |
| **⊘ Skipped** | 14 | 3.5% |

**Overall Status:** ❌ **FAILED** - Multiple service authentication and connectivity issues detected

---

## Critical Issues

### 1. Playwright E2E Test Setup Failure
**Severity:** HIGH
**Impact:** All browser-based end-to-end tests blocked

**Root Cause:**
- Playwright global setup failed with `ERR_CONNECTION_REFUSED` when attempting to reach `http://localhost/grafana`
- Test framework unable to authenticate and set up test environment

**Error Details:**
```
Error: page.goto: net::ERR_CONNECTION_REFUSED at http://localhost/grafana
Location: /app/playwright-tests/auth/global-setup.ts:80
```

**Recommendation:**
- Verify reverse proxy (Caddy/Traefik) routing configuration
- Check if Grafana service is accessible from test container network
- Validate authentication flow for automated testing

---

## Service-Specific Failures

### 2. Valkey (Redis) Authentication Failures
**Severity:** HIGH
**Impact:** All Valkey operations blocked (11 test failures)

**Failed Tests:**
- Valkey: PING command responds with PONG
- Valkey: SET and GET operations
- Valkey: TTL expiration
- Valkey: Connection pooling
- Valkey: Hash operations
- Valkey: List operations
- Valkey: Set operations
- Valkey: Atomic increment operations
- Valkey: Key existence and deletion
- Valkey: Database statistics

**Root Cause:**
```
NOAUTH Authentication required
```

**Recommendation:**
- Add Valkey password to test configuration
- Update test suite with proper authentication credentials
- Verify Valkey ACL configuration allows test operations

---

### 3. Container Registry HTTPS/HTTP Mismatch
**Severity:** MEDIUM
**Impact:** CI/CD image push operations failing

**Failed Test:** Push image to registry

**Root Cause:**
```
failed to do request: Head "https://192.168.0.11:5000/v2/cicd-test-push/blobs/sha256:...":
http: server gave HTTP response to HTTPS client
```

**Analysis:**
- Docker client attempting HTTPS connection to registry at `192.168.0.11:5000`
- Registry responding with HTTP instead of HTTPS
- Protocol mismatch preventing image push operations

**Recommendation:**
- Configure registry as insecure in Docker daemon config OR
- Set up proper TLS certificates for registry OR
- Update test to use HTTP explicitly for local registry

---

### 4. Isolated Docker VM Network Isolation Breach
**Severity:** HIGH (Security)
**Impact:** Container isolation not working as designed

**Failed Test:** Verify isolated-docker-vm container isolation

**Root Cause:**
```
Container visible on production - isolation breach!
```

**Analysis:**
- Test container should be isolated from production network
- Container is visible where it shouldn't be, indicating network isolation failure
- Potential security concern if isolation is required for multi-tenant workloads

**Recommendation:**
- Review Docker network configuration for isolated-docker-vm
- Verify network namespace isolation
- Check if container is accidentally attached to production network

---

### 5. Mastodon Federation and OAuth Issues
**Severity:** MEDIUM
**Impact:** Mastodon integration and federation tests failing

**Failed Tests:**
- Mastodon: OAuth endpoint exists
- Mastodon: Federation is configured

**Root Cause:**
```
Expected 403 to be one of [200, 400, 401, 302, 404]
```

**Associated Errors from Logs:**
- Multiple "Blocked hosts: mastodon-web:3000" errors
- ActionDispatch::HostAuthorization blocking requests

**Analysis:**
- Mastodon returning 403 Forbidden instead of expected status codes
- Host authorization blocking test requests
- Tests attempting OAuth registration failing with "403 Forbidden"

**Recommendation:**
- Add test hostnames to Mastodon `ALLOWED_HOSTS` configuration
- Review Rails `ActionDispatch::HostAuthorization` settings
- Ensure test environment can bypass host authorization or is properly configured

---

### 6. Ntfy Push Notification Endpoint
**Severity:** LOW
**Impact:** Push notification publishing test failing

**Failed Test:** Ntfy: Message publishing endpoint

**Root Cause:**
```
Expected 200 OK but got 403 Forbidden
```

**Recommendation:**
- Check Ntfy authentication/authorization configuration
- Verify test credentials or token for publishing
- Review Ntfy ACL settings

---

### 7. qBittorrent WebUI Authentication
**Severity:** LOW
**Impact:** qBittorrent API access test failing

**Failed Test:** qBittorrent: Login required for API access

**Root Cause:**
```
Expected 200 to be one of [401, 403, 404]
```

**Analysis:**
- Test expects unauthenticated API access to be blocked (401/403)
- Receiving 200 OK instead, suggesting no authentication requirement
- Potential security concern or test misconfiguration

**Recommendation:**
- Verify qBittorrent WebUI authentication is enabled
- Review expected vs. actual security configuration
- Update test expectations if authentication is intentionally disabled

---

## Skipped Tests

### Forgejo Token Acquisition
**Test:** Forgejo: Acquire token and list repositories
**Reason:** Token acquisition failed with `401 Unauthorized`
**Recommendation:** Verify Forgejo test credentials

### Mastodon OAuth Token
**Test:** Mastodon: Acquire OAuth token and verify credentials
**Reason:** App registration failed with `403 Forbidden`
**Recommendation:** Related to Mastodon host authorization issue above

---

## Container Health Status

### Unhealthy Containers

#### Seafile (seafileltd/seafile-mc:13.0.15)
**Status:** UNHEALTHY (running for 23 minutes)
**Issue:** "Error happened during creating seafile admin"
**Impact:** File storage/collaboration service degraded

**Recommendation:**
- Check Seafile admin account configuration
- Review database connectivity and initialization
- Verify Seafile healthcheck endpoint expectations

---

## Application-Level Warnings & Errors

### 1. Authelia OIDC Client Secret Warnings
**Severity:** LOW (Deprecation Warning)
**Services Affected:** pgadmin, open-webui, dim, planka, vaultwarden, mastodon, bookstack, forgejo, matrix

**Issue:**
```
option 'client_secret' is plaintext but should be a hashed value as plaintext values
are deprecated and will be removed in the near future
```

**Recommendation:**
- Hash OIDC client secrets in Authelia configuration
- Use `authelia crypto hash generate pbkdf2` to generate hashed secrets
- Update configuration before plaintext support is removed

---

### 2. Docker Health Exporter Timeout
**Issue:** `UnixHTTPConnectionPool: Read timed out (read timeout=60)`
**Impact:** Monitoring metrics may be incomplete
**Recommendation:** Increase timeout or investigate Docker socket performance

---

### 3. Search Service Qdrant Connection Failure
**Error:**
```
io.grpc.StatusRuntimeException: UNAVAILABLE: io exception
Caused by: io.grpc.netty.shaded.io.netty.channel.AbstractChannel$AnnotatedConnectException:
connect(..) failed: Invalid argument: /qdrant:6334
```

**Analysis:**
- Search service cannot connect to Qdrant vector database
- Invalid socket address format (`/qdrant:6334` looks like path + port)
- Should likely be hostname `qdrant:6334`

**Recommendation:**
- Fix Qdrant connection string in search-service configuration
- Verify Qdrant service is running and accessible

---

### 4. PostgreSQL Cancellation Errors
**Issue:** Multiple "canceling statement due to user request" errors
**Context:** Updating `action_runner` table
**Severity:** LOW
**Analysis:** Likely Forgejo runner heartbeat updates being cancelled (normal during shutdown/restart)

---

### 5. Authelia Authentication Failures (Test Data)
**Examples:**
- `user not found` for empty username
- `user not found` for 'admin'
- Invalid LDAP credentials for test users

**Analysis:** Expected failures during test authentication attempts

---

## Performance Notes

- **Total test duration:** 215.71 seconds (~3.6 minutes)
- **Longest individual test:** Multi-stage Docker build (1729ms)
- **Test parallelism:** 12 workers (Playwright configuration)
- **Test infrastructure:** Healthy (pipeline, agent-tool-server, tx-gateway all healthy)

---

## Services Operating Normally

✅ **Healthy Services (18 of 19 running services):**
- Pipeline (datamancy/pipeline:local-build)
- Agent Tool Server (datamancy/agent-tool-server:local-build)
- TX Gateway (datamancy/tx-gateway:local-build)
- Search Service (datamancy/search-service:local-build) - despite Qdrant warnings
- Mastodon Web, Sidekiq, Streaming
- Matrix Synapse
- Open WebUI
- Grafana
- Authelia
- Element (Matrix client)
- Homepage
- Planka
- Roundcube
- Vaultwarden
- Mailserver
- Forgejo Runner

---

## Priority Recommendations

### Immediate Actions (P0)
1. **Fix Valkey authentication** - Blocking 11 tests, critical for caching operations
2. **Resolve Playwright setup** - Blocking all E2E browser tests
3. **Investigate container isolation breach** - Security concern for isolated-docker-vm

### High Priority (P1)
4. **Fix Mastodon host authorization** - Impacting federation and OAuth tests
5. **Configure registry for insecure/HTTP** - CI/CD pipeline affected
6. **Resolve Seafile unhealthy status** - Service degraded

### Medium Priority (P2)
7. **Fix search-service Qdrant connection** - Vector search functionality impaired
8. **Hash Authelia OIDC secrets** - Deprecation warning, future breaking change
9. **Fix Ntfy and qBittorrent test expectations** - Minor service validation issues

### Low Priority (P3)
10. **Investigate Docker health exporter timeouts** - Monitoring quality of life
11. **Review PostgreSQL statement cancellations** - Likely benign, verify normal operation

---

## Test Coverage Assessment

**Strong Coverage Areas:**
- Database connectivity (PostgreSQL, MySQL)
- Core service health checks
- Multi-stage Docker builds
- Service-to-service communication
- Static asset serving

**Gaps/Limitations:**
- Browser-based E2E testing blocked by setup failure
- Limited coverage of authenticated workflows (due to auth failures)
- Container networking isolation testing flagged issues

---

## Conclusion

The Datamancy platform demonstrates **92.2% test pass rate** with core infrastructure services operating normally. However, critical authentication and connectivity issues with Valkey, Playwright E2E setup, Mastodon, and container isolation require immediate attention.

The majority of failures stem from **configuration issues** rather than code defects:
- Missing/incorrect authentication credentials (Valkey, Forgejo, Mastodon)
- Network/protocol mismatches (registry HTTPS/HTTP, Qdrant connection)
- Host authorization restrictions (Mastodon, Ntfy)

**Recommended Next Steps:**
1. Address P0 issues (Valkey auth, Playwright setup, isolation breach)
2. Update test configurations with proper credentials
3. Re-run test suite to validate fixes
4. Consider adding health monitoring for services with warnings

---

**Report Generated:** 2026-02-12
**Generated By:** Claude Code Analysis
**Test Runner Version:** Datamancy Integration Test Runner
**Playwright Version:** 1.58.2
