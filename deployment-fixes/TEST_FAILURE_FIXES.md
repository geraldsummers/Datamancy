# Test Failure Fixes - 2026-02-12

This document describes fixes applied to resolve test failures and remaining issues that require deployment or manual configuration.

---

## ✅ Fixed Issues (Code Changes)

### 1. Valkey Authentication (11 tests)
**Status:** FIXED in code
**Issue:** All Valkey/Redis tests failing with `NOAUTH Authentication required`

**Root Cause:**
Test suite was creating Jedis connections without providing the Valkey admin password.

**Fix Applied:**
Updated `kotlin.src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/CachingLayerTests.kt`:
- Read `VALKEY_ADMIN_PASSWORD` from environment variable
- Added `jedis.auth("default", valkeyPassword)` to all 11 Jedis connection instances
- Updated JedisPool constructor to include password authentication

**Verification:**
After deployment, all 11 Valkey tests should pass:
- Valkey: PING command responds with PONG
- Valkey: SET and GET operations work
- Valkey: TTL expiration works
- Valkey: Connection pooling works
- Valkey: Multiple concurrent connections
- Valkey: Hash operations work
- Valkey: List operations work
- Valkey: Set operations work
- Valkey: Atomic increment operations
- Valkey: Key existence and deletion
- Valkey: Database statistics available

---

### 2. Playwright E2E Setup Failure
**Status:** FIXED in code
**Issue:** Global setup fails with `ERR_CONNECTION_REFUSED at http://localhost/grafana`

**Root Cause:**
Playwright config was using `http://localhost` as baseURL, but tests run inside Docker container where services are accessed via Caddy reverse proxy at `http://caddy`.

**Fix Applied:**
Updated `containers.src/test-runner/playwright-tests/playwright.config.ts`:
```typescript
baseURL: process.env.BASE_URL || (process.env.TEST_ENV === 'container' ? 'http://caddy' : 'http://localhost')
```

**Verification:**
After deployment, Playwright global setup should successfully:
1. Navigate to `http://caddy/grafana`
2. Get redirected to Authelia login
3. Authenticate test user
4. Save auth state for E2E tests

---

## ⚠️  Issues Requiring Deployment Configuration

### 3. Container Registry HTTPS/HTTP Mismatch
**Status:** REQUIRES SERVER-SIDE CONFIGURATION
**Issue:** Docker push fails - `http: server gave HTTP response to HTTPS client`

**Root Cause:**
The test runner's Docker daemon (on isolated-docker-vm) is not configured to allow insecure registry at `192.168.0.11:5000`. Docker client defaults to HTTPS, but the registry serves HTTP.

**Required Fix (ON REMOTE SERVER):**

Option A: Configure isolated-docker-vm Docker daemon to allow insecure registry
```bash
ssh gerald@latium.local
# On isolated-docker-vm host or Docker daemon config
sudo mkdir -p /etc/docker
sudo cat > /etc/docker/daemon.json <<EOF
{
  "insecure-registries": ["192.168.0.11:5000", "localhost:5000", "registry:5000"]
}
EOF
sudo systemctl restart docker
```

Option B: Set up TLS certificates for the registry (more complex, production-grade)

**Verification:**
```bash
# From isolated-docker-vm context
docker pull alpine:latest
docker tag alpine:latest 192.168.0.11:5000/test-image:latest
docker push 192.168.0.11:5000/test-image:latest  # Should succeed
```

**Affected Test:**
- Push image to registry

---

### 4. Search Service Qdrant Connection String
**Status:** INVESTIGATION REQUIRED
**Issue:** Search service logs show `io exception` with invalid connection `/qdrant:6334`

**Root Cause:**
The connection string format looks incorrect - appears to be path format `/qdrant:6334` instead of hostname `qdrant:6334`.

**Investigation Steps:**
1. Check deployed search-service environment variables:
   ```bash
   ssh gerald@latium.local "cd ~/datamancy && docker compose exec search-service env | grep QDRANT"
   ```

2. Verify search-service is reading correct `QDRANT_URL` from environment

3. Check if this is a gRPC vs HTTP URL confusion (gRPC uses `qdrant:6334`, HTTP uses `http://qdrant:6333`)

**Expected Value:**
- For gRPC: `QDRANT_URL=qdrant:6334`
- NOT: `/qdrant:6334`

**Code Reference:**
`kotlin.src/search-service/src/main/kotlin/org/datamancy/searchservice/Main.kt:37`
```kotlin
val qdrantUrl = System.getenv("QDRANT_URL") ?: "qdrant:6334"
```

The code looks correct - likely a deployment configuration issue where the env var is set incorrectly.

**Impact:**
Search service cannot connect to Qdrant vector database, semantic search unavailable.

---

## 🔍 Issues Requiring Investigation

### 5. Container Isolation Breach
**Status:** REQUIRES MANUAL INVESTIGATION
**Issue:** Test "Verify isolated-docker-vm container isolation" fails
**Error:** `Container visible on production - isolation breach!`

**What This Means:**
A container that should be isolated within the isolated-docker-vm environment is visible from the production Docker network. This suggests:
1. Docker network boundaries are not properly configured, OR
2. The test's isolation check is too strict, OR
3. There's intentional connectivity we haven't documented

**Investigation Steps:**

1. **Check which container is visible:**
   ```bash
   ssh gerald@latium.local
   # From production context
   docker ps --format "table {{.Names}}\t{{.Networks}}"

   # From isolated-docker-vm context (if accessible)
   docker ps --format "table {{.Names}}\t{{.Networks}}"
   ```

2. **Review network topology:**
   ```bash
   docker network ls
   docker network inspect <network-name>
   ```

3. **Check test expectations:**
   Look at `kotlin.src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/CICDPipelineTests.kt:100+`
   - What container is it looking for?
   - What's the intended isolation mechanism?

4. **Security implications:**
   - Is this a security issue (multi-tenant workload isolation)?
   - Or is this a test environment where isolation isn't critical?

**Possible Resolutions:**
- Fix Docker network configuration to properly isolate isolated-docker-vm
- Update test expectations if isolation isn't actually required
- Document intentional connectivity between networks

---

### 6. Mastodon Host Authorization (Already Fixed?)
**Status:** MONITORING REQUIRED
**Issue:** Tests received 403 Forbidden - `ActionDispatch::HostAuthorization::DefaultResponseApp Blocked hosts: mastodon-web:3000`

**Analysis:**
The compose file already has extensive host authorization bypass configuration:
```yaml
RAILS_DEVELOPMENT_HOSTS: "mastodon-web,mastodon-web:3000,..."
DISABLE_HOST_CHECK: "true"
DANGEROUSLY_DISABLE_HOST_FILTERING: "true"
ACTION_DISPATCH_HOSTS_PERMIT_ALL: "true"
```

**Possible Causes:**
1. These env vars aren't being read by Mastodon (Rails version mismatch?)
2. Test is hitting Mastodon before it's fully initialized
3. There's a newer Rails 7.x mechanism we're missing

**Verification After Deployment:**
Check if Mastodon OAuth tests pass:
- Mastodon: OAuth endpoint exists
- Mastodon: Federation is configured

If still failing:
```bash
ssh gerald@latium.local "cd ~/datamancy && docker compose logs mastodon-web | grep -i 'Blocked hosts' -A 2 -B 2"
```

---

## 📊 Expected Test Results After Fixes

**Before fixes:**
- Total: 396 tests
- Passed: 365 (92.2%)
- Failed: 17 (4.3%)
- Skipped: 14 (3.5%)

**After code fixes (before deployment config):**
- Expected passed: 376-377 (+11 Valkey, possibly Playwright E2E setup)
- Expected failed: 6-7 (registry, maybe Mastodon)
- Expected skipped: 13-14

**After all fixes (including deployment config):**
- Expected passed: 378-388 (+13 including registry fix)
- Expected failed: 0-8 (depending on Mastodon, isolation, Qdrant)
- Expected skipped: 0-14

---

## 🚀 Deployment Checklist

### Before Deploying:
- [ ] Commit code changes (Valkey auth, Playwright baseURL)
- [ ] Run local test-runner build to verify compilation
- [ ] Check uncommitted changes with build script

### After Deploying:
- [ ] Verify VALKEY_ADMIN_PASSWORD is set in remote environment
- [ ] Configure insecure-registry on isolated-docker-vm Docker daemon
- [ ] Check search-service QDRANT_URL environment variable
- [ ] Run full test suite and compare results
- [ ] Investigate container isolation breach if test still fails
- [ ] Monitor Mastodon logs for host authorization errors

---

## 📝 Notes

- **Test data safety:** All tests use ephemeral test data, safe to wipe containers
- **Partial upgrade system:** Using experimental clean partial upgrade - stop and notify if redeployment breaks anything
- **Build time:** Deployment takes ~10 minutes, use sleep 600 between deploy and test runs
- **Test run time:** Full test suite takes ~3.6 minutes (215 seconds)

---

**Document Updated:** 2026-02-12
**Test Run ID:** 20260212_041427-all
**Fixes Applied By:** Claude Code Analysis
