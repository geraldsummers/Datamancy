# Datamancy Integration Test Report
**Generated:** Friday, 13 February 2026 18:57 AEDT
**Test Run ID:** 20260213_075139-all
**Server:** latium.local
**Environment:** Production Test Data

---

## Executive Summary

**Total Tests:** 382
**✓ Passed:** 362 (94.8%)
**✗ Failed:** 20 (5.2%)
**Duration:** 147.5 seconds

### Overall Status: ⚠️ PARTIAL SUCCESS

The test suite achieved a 94.8% pass rate with **362 successful tests**. However, **20 tests failed**, primarily due to a **Playwright authentication setup failure** that cascaded to downstream E2E tests, plus **2 isolated Docker VM isolation issues**.

---

## Test Results Breakdown

### ✅ Passing Test Categories (362 tests)

The following systems passed all integration tests:
- **Core Infrastructure:** Docker, networking, health checks
- **Database Systems:** Postgres, MariaDB, Valkey (Redis)
- **API Gateway:** Caddy reverse proxy
- **Monitoring Stack:** Prometheus, Grafana datasources
- **CI/CD Pipeline:** Most isolated-docker-vm operations
- **Security:** Keycloak OIDC Phase 1 flows
- **Backend Services:** Search service, agent-tool-server (non-E2E tests)
- **Container Management:** Docker Compose orchestration
- **Backup Systems:** Kopia infrastructure
- **Communication:** Email, notification services
- **Development Tools:** Build pipelines, multi-stage Docker builds

### ❌ Failed Tests (20 failures)

#### Category 1: Playwright E2E Authentication Cascade (18 tests)
**Root Cause:** `ERR_SSL_PROTOCOL_ERROR at http://caddy/grafana`

The Playwright global setup failed when attempting to authenticate to Grafana, causing all downstream E2E tests to fail:

1. **Phase 2: OIDC authorization code flow completes successfully** - ERROR
2. **Phase 2: ID token contains required claims** - ERROR
3. **Phase 2: Refresh token can obtain new access token** - ERROR
4. **Grafana: Acquire API key and query datasources** - ERROR
5. **Seafile: Acquire token and list libraries** - ERROR
6. **Forgejo: Acquire token and list repositories** - ERROR
7. **Planka: Acquire token and list boards** - ERROR
8. **Qbittorrent: Acquire session and get version** - ERROR
9. **Mastodon: Acquire OAuth token and verify credentials** - ERROR
10. **Open-WebUI: Acquire JWT and list models** - ERROR
11. **JupyterHub: Authenticate and access hub API** - ERROR
12. **LiteLLM: Authenticate and access API** - ERROR
13. **Ntfy: Authenticate and access notification API** - ERROR
14. **Kopia: Authenticate and access backup UI** - ERROR
15. **Radicale: Authenticate and access CalDAV/CardDAV** - ERROR
16. **Roundcube: Authenticate and access webmail** - ERROR
17. **Search Service: Authenticate and access API** - ERROR
18. **Playwright E2E test suite** - ERROR (exit code 1)

**Technical Details:**
- Location: `/app/playwright-tests/auth/global-setup.ts:110`
- Error: SSL protocol error on HTTP endpoint
- URL: `http://caddy/grafana`
- Symptoms: Playwright unable to navigate to Grafana URL

**Impact:** All E2E tests that depend on Playwright authentication are blocked

#### Category 2: Isolated Docker VM Issues (2 tests)

1. **Push image to registry** - FAIL
   - Error: Push to repository [192.168.0.11:5000/cicd-test-push] failed
   - Image: 589002ba0eae (waiting state)
   - Impact: CI/CD pipeline cannot push to internal registry

2. **Verify isolated-docker-vm container isolation** - FAIL
   - Error: Container visible on production - isolation breach!
   - Impact: Security isolation not properly enforced

---

## System Health Analysis

### Container Status
- **Unhealthy Containers:** 1 (Seafile - expected/known issue)
- **Healthy Containers:** All critical services operational

### Log Analysis - Known Issues (Non-Critical)

#### Warning-Level Issues:
1. **Docker Health Exporter:** Timeout reading Docker socket (60s)
2. **Vaultwarden:** Invalid token claims (expected during test cleanup)
3. **Planka:** CA certificate warning for caddy-ca.crt
4. **Search Service:** Qdrant connection errors (transient, retries working)
5. **Agent Tool Server:** Missing `agent_observer.public_dashboards` relation (schema issue)
6. **Valkey:** Memory overcommit warning (performance advisory)

#### Info-Level Issues:
- SSH tunnel host key warnings (cosmetic)
- Alertmanager gossip settling (normal startup)
- Homepage config initialization (normal)

---

## Root Cause Analysis

### Primary Issue: Playwright SSL Protocol Error

**Problem:** Playwright is attempting to connect to `http://caddy/grafana` but encountering an SSL protocol error, despite using an HTTP URL.

**Possible Causes:**
1. **HTTPS Redirect Misconfiguration:** Caddy may be forcing HTTPS redirects even for internal HTTP requests
2. **Mixed Content Policy:** The test runner container may have strict SSL policies
3. **Certificate Chain Issues:** The caddy-ca.crt certificate warnings in logs suggest SSL/TLS configuration problems
4. **Network Policy Conflict:** Docker network routing may be interfering with HTTP connections
5. **Recent Configuration Change:** The DEPLOYMENT_FIXES_SUMMARY.md mentions changing BASE_URL and CADDY_URL from HTTPS to HTTP (commit f62c019) - this may need further adjustment

**Reference:** `containers.src/test-runner/playwright-tests/auth/global-setup.ts:110`

### Secondary Issue: Registry Push Failure

**Problem:** The isolated-docker-vm cannot push images to the internal registry at `192.168.0.11:5000`

**Possible Causes:**
1. Network connectivity between isolated-docker-vm and registry
2. Registry authentication/authorization
3. Insecure registry configuration mismatch
4. Storage/disk space issues on registry

### Tertiary Issue: Container Isolation Breach

**Problem:** Containers built in isolated-docker-vm are visible on the production Docker socket

**Impact:** Security concern - build isolation is not functioning as designed

---

## Recommendations

### Priority 1: Fix Playwright Authentication (Blocks 18 tests)

1. **Investigate Caddy HTTPS redirect behavior**
   - Check Caddyfile configuration for forced HTTPS
   - Verify internal routing uses HTTP correctly
   - Review recent BASE_URL/CADDY_URL changes

2. **Review SSL certificate chain**
   - Fix caddy-ca.crt loading warnings
   - Ensure test runner container trusts internal CA
   - Check certificate installation in Playwright container

3. **Update Playwright configuration**
   - Verify `http://caddy/grafana` is the correct endpoint
   - Consider using direct service URLs for testing
   - Add SSL bypass for internal test traffic if appropriate

### Priority 2: Fix Docker Registry Access

1. Verify network connectivity from isolated-docker-vm to registry
2. Check registry authentication credentials
3. Confirm insecure registry settings in daemon.json
4. Test manual docker push from isolated-docker-vm

### Priority 3: Restore Container Isolation

1. Review isolated-docker-vm configuration
2. Verify separate Docker socket/context
3. Audit container visibility and network segmentation
4. Test isolation with fresh container builds

### Priority 4: Clean Up Known Warnings

1. Extend Docker health exporter timeout
2. Clean up expired Vaultwarden tokens
3. Fix agent_observer schema (missing public_dashboards table)
4. Address Valkey memory overcommit warning

---

## Test Environment Details

- **Server Hostname:** latium.local
- **Working Directory:** ~/datamancy
- **Container Engine:** Docker Compose (not docker-compose legacy)
- **Test Data:** ALL TEST DATA - safe to wipe containers
- **Build Info:** Clean partial upgrade system (experimental)
- **Recent Commits:**
  - ce5f2cb: Adjust healthcheck intervals for Valkey, Postgres, MariaDB
  - f62c019: Document critical fixes and production readiness
  - 3f4097b: **Change BASE_URL/CADDY_URL from HTTPS to HTTP** ⚠️

---

## Next Steps

1. **Immediate:** Investigate and fix Playwright SSL/HTTPS configuration issue
2. **Short-term:** Resolve registry push and container isolation problems
3. **Medium-term:** Address warning-level issues in logs
4. **Validation:** Re-run test suite after fixes to confirm 100% pass rate

---

## Notes

- Test suite ran for 147.5 seconds (2.5 minutes)
- Build time is significant; avoid unnecessary rebuilds
- Server timezone differs from lab PC
- Clean partial upgrade system is experimental - monitor closely during redeployment
- Results saved to: `/app/test-results/20260213_075139-all`
  - `summary.txt`: Test summary
  - `detailed.log`: Full test output
  - `failures.log`: Failed test details
  - `metadata.txt`: Run metadata

---

**Report Generated by:** Claude Code Integration Test Analysis
**Confidence Level:** High (based on comprehensive log analysis)
**Action Required:** Yes - Fix Playwright authentication to unblock 18 E2E tests
