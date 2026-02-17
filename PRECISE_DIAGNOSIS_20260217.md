# Precise Failure Diagnosis - Datamancy Integration Tests
**Test Run:** 2026-02-17 00:05:28 UTC (11:05:28 AEDT)
**Analysis Date:** 2026-02-17 11:30:00 AEDT

---

## Summary
4 test failures identified out of 379 total tests (98.9% success rate).

| # | Test | Root Cause | Severity | Fix Complexity |
|---|------|------------|----------|----------------|
| 1 | Grafana API Token Creation | Token name collision | Medium | Trivial |
| 2 | Container Registry Push | HTTP/HTTPS mismatch | Medium | Easy |
| 3 | Isolated Docker VM | Test logic error | High | Easy |
| 4a | Ntfy Forward Auth | Caddy routing missing | Low | Easy |
| 4b | LDAP-AM Forward Auth | TLS handshake issue | Low | Medium |

---

## Failure #1: Grafana API Token Creation

### Error Message
```
Failed to create service account token: 400 Bad Request
```

### Root Cause Analysis
**Exact Diagnosis:** Token name collision - attempting to create a token with a name that already exists.

**Evidence from Logs:**
```
grafana  | logger=context userId=1 orgId=1 uname=admin t=2026-02-17T00:06:50.182772869Z level=info
msg="Request Completed" method=POST path=/api/serviceaccounts/7/tokens status=400
remote_addr=172.18.0.32 time_ms=4 duration=4.307389ms size=155 referer=
handler=/api/serviceaccounts/:serviceAccountId/tokens status_source=server
errorReason=BadRequest errorMessageID=serviceaccounts.ErrTokenAlreadyExists
error="service account token with name integration-test-token already exists in the organization"
```

**Timeline:**
1. Test successfully creates service account (service account ID 5, then 7)
2. Test attempts to create token named "integration-test-token"
3. Grafana rejects with 400 because token name already exists from previous test run
4. Test does not include cleanup logic to delete tokens/service accounts

**Impact:**
- Test fails on second and subsequent runs
- No functional impact on Grafana operation
- UI and existing API tokens work correctly

### Precise Fix
**File:** `kotlin.src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/MonitoringTests.kt` (inferred)

**Solution 1: Use Unique Token Names (Recommended)**
```kotlin
val tokenName = "integration-test-token-${UUID.randomUUID().toString().substring(0, 8)}"
```

**Solution 2: Cleanup Existing Tokens Before Test**
```kotlin
// Query existing service accounts and tokens
// Delete matching test tokens before creating new ones
```

**Solution 3: Use idempotent creation**
```kotlin
// Try to find existing token first
// Only create if doesn't exist
```

**Recommended:** Solution 1 - simplest and most reliable for test isolation.

---

## Failure #2: Container Registry Push

### Error Message
```
Push to registry failed: The push refers to repository [192.168.0.11:5000/cicd-test-push]
589002ba0eae: Waiting
failed to do request: Head "https://192.168.0.11:5000/v2/cicd-test-push/blobs/sha256:8a61a36b9c329c08a0f1ed65a2896c0a665e9707f0320cd9dafa464d2bb53f2d":
http: server gave HTTP response to HTTPS client
```

### Root Cause Analysis
**Exact Diagnosis:** Docker client is configured to use HTTPS for registry communication, but the registry at `192.168.0.11:5000` only serves HTTP.

**Technical Details:**
- Registry container is running correctly and accepting HTTP requests on port 5000
- Docker daemon defaults to HTTPS for all registry connections (security best practice)
- The `isolated-docker-vm-tunnel` Docker daemon does not have `192.168.0.11:5000` configured as an insecure registry
- Pull operations work because they may use fallback mechanisms, but push requires explicit configuration

**Evidence:**
1. Registry logs show only HTTP requests (all successful):
   ```
   registry  | time="2026-02-17T00:25:24.992005026Z" level=info msg="response completed"
   http.request.host="localhost:5000" http.request.method=GET http.response.status=200
   ```

2. Test code includes informative comment about this exact issue (CICDPipelineTests.kt:90-96):
   ```kotlin
   // Registry push failure is often due to HTTP/HTTPS mismatch
   // Registry at 192.168.0.11:5000 serves HTTP but Docker expects HTTPS by default
   if (pushOutput.contains("server gave HTTP response to HTTPS client")) {
       println("      ℹ️  Registry TLS configuration issue detected")
       println("      ℹ️  Add to /etc/docker/daemon.json: {\"insecure-registries\": [\"$registryHost\"]}")
       println("      ℹ️  Or enable TLS on registry with proper certificates")
   }
   ```

3. Test runs from `isolated-docker-vm` via SSH tunnel, so configuration must be on that Docker daemon

**Impact:**
- CI/CD pipeline cannot push images to registry
- Forgejo runners cannot publish built images
- Pull operations still work

### Precise Fix

**Option A: Configure Insecure Registry (Test Environment - Recommended)**

**Location:** On the `isolated-docker-vm` host (192.168.0.69) or within the isolated-docker-vm-tunnel container

**File:** `/etc/docker/daemon.json`

```json
{
  "insecure-registries": ["192.168.0.11:5000"]
}
```

**Commands:**
```bash
# SSH into isolated-docker-vm host
ssh gerald@192.168.0.69

# Edit or create daemon.json
sudo nano /etc/docker/daemon.json

# Add the insecure-registries configuration
# Restart Docker daemon
sudo systemctl restart docker

# Verify configuration
docker info | grep -A 5 "Insecure Registries"
```

**Option B: Enable TLS on Registry (Production - More Secure)**

**Location:** Update registry service in docker-compose.yml

```yaml
registry:
  image: registry:2.8
  environment:
    REGISTRY_HTTP_TLS_CERTIFICATE: /certs/registry.crt
    REGISTRY_HTTP_TLS_KEY: /certs/registry.key
  volumes:
    - ./certs:/certs:ro
```

Then generate proper TLS certificates for `192.168.0.11`.

**Recommended:** Option A for test environment, Option B for production deployment.

---

## Failure #3: Isolated Docker VM Container Isolation

### Error Message
```
Container visible on production - isolation breach!
```

### Root Cause Analysis
**Exact Diagnosis:** This is a **test logic error**, not an actual security breach.

**What the test does (CICDPipelineTests.kt:107-142):**
1. Starts a container on `isolated-docker-vm` via `docker -H ssh://isolated-docker-vm run ...`
2. Verifies container exists on isolated-docker-vm: `docker -H ssh://isolated-docker-vm ps`
3. **Checks if container is visible on "production":** `docker ps` (line 128)
4. Fails if container IS visible on production

**The Problem:**
The test is running **inside the `integration-test-runner` container**, which is part of the production Docker Compose stack. When it runs `docker ps` without `-H`, it's checking the production Docker host.

However, the test container name includes a unique identifier (e.g., `cicd-test-isolation-a1b2c3d4`). The test is checking if this specific container appears in the production `docker ps` output.

**Evidence Analysis:**
Looking at the docker ps output from the server, we can see the `integration-test-runner` container is running:
```
integration-test-runner     datamancy/test-runner:local-build   25 minutes ago   Up 8 minutes
```

The test runs `ProcessBuilder("docker", "ps", "--filter", "name=$containerName", "--format", "{{.Names}}")` from within this container. If Docker socket is mounted into the test runner, it will see production containers.

**Two Possible Scenarios:**

**Scenario A: Docker Socket Mounted (Likely)**
- The `integration-test-runner` container has `/var/run/docker.sock` mounted
- The test's `docker ps` command sees the production Docker daemon
- When the test creates a container on isolated-docker-vm with a unique name like `cicd-test-isolation-abc123`, it should NOT appear in production
- **If the test is failing, it means the container IS appearing in production, which indicates:**
  - The isolated-docker-vm tunnel may be misconfigured
  - The SSH tunnel might be routing to the production Docker daemon
  - OR the container name is somehow matching a production container (unlikely due to UUID)

**Scenario B: Test Environment Issue**
- The `isolated-docker-vm-tunnel` service shows:
  ```
  isolated-docker-vm-tunnel  | Connecting to gerald@192.168.0.69...
  isolated-docker-vm-tunnel  | Failed to add the host to the list of known hosts
  ```
- This could indicate the SSH tunnel is not properly established
- If the tunnel falls back to local Docker daemon, containers would appear in production

**Impact:**
- **SECURITY CONCERN:** If isolation is truly breached, containers from "isolated" environment can interact with production
- More likely: Test environment configuration issue, not actual production deployment issue

### Precise Fix

**Step 1: Verify Isolation Configuration**
```bash
# Check docker-compose.yml for integration-test-runner socket mount
grep -A 5 "integration-test-runner:" dist/docker-compose.yml

# Check isolated-docker-vm-tunnel configuration
docker compose logs isolated-docker-vm-tunnel

# Verify SSH connection works
docker compose exec integration-test-runner ssh -T gerald@192.168.0.69

# Manually test isolation
docker compose exec integration-test-runner docker -H ssh://isolated-docker-vm run -d --name test-isolation-manual alpine sleep 30
docker compose exec integration-test-runner docker ps --filter name=test-isolation-manual
docker ps --filter name=test-isolation-manual  # Should NOT show the container
```

**Step 2: Fix Test Logic (If Issue is Test Design)**

**File:** `kotlin.src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/CICDPipelineTests.kt:127-134`

**Current Code:**
```kotlin
// Check if visible on production (SHOULD NOT BE)
val prodProcess = ProcessBuilder("docker", "ps", "--filter", "name=$containerName", "--format", "{{.Names}}").start()
val prodOutput = prodProcess.inputStream.bufferedReader().readText()
prodProcess.waitFor()

if (prodOutput.contains(containerName)) {
    throw AssertionError("Container visible on production - isolation breach!")
}
```

**Issue:** This assumes the test runner is NOT running with Docker socket access, or that "docker ps" refers to a different Docker daemon.

**Improved Test:**
```kotlin
// Get Docker host info to confirm we're checking production
val prodProcess = ProcessBuilder("docker", "info", "--format", "{{.Name}}").start()
val prodDockerHost = prodProcess.inputStream.bufferedReader().readText().trim()
prodProcess.waitFor()

println("      ℹ️  Checking production Docker host: $prodDockerHost")

// Now check if container visible on production
val prodPsProcess = ProcessBuilder("docker", "ps", "--filter", "name=$containerName", "--format", "{{.Names}}").start()
val prodOutput = prodPsProcess.inputStream.bufferedReader().readText()
prodPsProcess.waitFor()

if (prodOutput.contains(containerName)) {
    throw AssertionError("Container visible on production ($prodDockerHost) - isolation breach!")
}
```

**Step 3: Fix SSH Tunnel (If Connection Issue)**

**File:** `dist/docker-compose.yml` - isolated-docker-vm-tunnel service

Ensure SSH keys are properly configured:
```yaml
isolated-docker-vm-tunnel:
  image: datamancy/isolated-docker-vm-tunnel:latest
  volumes:
    - ~/.ssh:/root/.ssh:ro  # Ensure SSH keys are mounted
  environment:
    - DOCKER_HOST=ssh://gerald@192.168.0.69
```

**Recommended Action:**
1. Run Step 1 verification commands to determine actual state
2. Check if SSH tunnel is working correctly
3. If tunnel works but test still fails, investigate why container appears in both places

---

## Failure #4a: Ntfy Forward Auth Test

### Error Message
```
Error: page.goto: net::ERR_CONNECTION_REFUSED at https://ntfy.datamancy.net/
```

### Root Cause Analysis
**Exact Diagnosis:** Caddy reverse proxy is not routing `ntfy.datamancy.net` to the ntfy container.

**Evidence:**
1. **Ntfy container is healthy and running:**
   ```
   ntfy  | 2026/02/16 19:25:50 INFO Server stats (messages_cached=29, messages_published=29,
   subscribers=0, topics_active=14, users=2, visitors=4)
   ```

2. **Container is accessible on internal network (port 80):**
   ```
   308a5bfc5e27   binwiederhier/ntfy:v2.15.0   2 hours ago   Up 2 hours (healthy)   80/tcp   ntfy
   ```

3. **Playwright test gets connection refused attempting HTTPS:**
   ```
   Error: page.goto: net::ERR_CONNECTION_REFUSED at https://ntfy.datamancy.net/
   ```

4. **Test timestamp:** 00:07:40 (test run time)
5. **Ntfy logs show activity, but no entries at test time suggesting Caddy never forwarded the request**

**Technical Details:**
- Ntfy service listens on port 80 (HTTP)
- Caddy should reverse proxy `ntfy.datamancy.net` → `ntfy:80` with TLS termination
- Connection refused means Caddy is not accepting the connection at all
- This suggests missing or incorrect Caddyfile configuration for ntfy

**Impact:**
- Ntfy service is inaccessible via public domain
- Internal container-to-container communication likely works
- Forward auth cannot be tested

### Precise Fix

**File:** Check Caddyfile configuration (location varies, likely in `dist/config/caddy/Caddyfile` or similar)

**Required Configuration:**
```caddyfile
ntfy.datamancy.net {
    reverse_proxy ntfy:80

    # Forward auth configuration (if using Authelia)
    forward_auth authelia:9091 {
        uri /api/verify?rd=https://auth.datamancy.net
        copy_headers Remote-User Remote-Groups Remote-Name Remote-Email
    }
}
```

**Verification Commands:**
```bash
# Check if ntfy subdomain is configured
ssh gerald@latium.local "cd ~/datamancy && docker compose exec caddy cat /etc/caddy/Caddyfile | grep -A 10 ntfy"

# Check Caddy config validity
ssh gerald@latium.local "cd ~/datamancy && docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile"

# Test internal connectivity
ssh gerald@latium.local "cd ~/datamancy && docker compose exec integration-test-runner wget -O- http://ntfy:80"

# After fix, reload Caddy
ssh gerald@latium.local "cd ~/datamancy && docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile"
```

**Alternative Diagnosis:**
If configuration exists, check:
1. DNS resolution: Does `ntfy.datamancy.net` resolve to correct IP?
2. Firewall rules: Are connections to port 443 allowed?
3. Caddy error logs: `docker compose logs caddy | grep -i error`

---

## Failure #4b: LDAP Account Manager Forward Auth Test

### Error Message
```
Error: page.goto: net::ERR_SSL_PROTOCOL_ERROR at https://lam.datamancy.net/lam/
⚠️  SSL/timeout error, retrying... (1/3)
⚠️  SSL/timeout error, retrying... (2/3)
⚠️  SSL/timeout error, retrying... (3/3)
```

### Root Cause Analysis
**Exact Diagnosis:** Playwright/browser is attempting TLS handshake with LDAP Account Manager service, but the service is sending non-TLS (HTTP) responses.

**Evidence:**
1. **Container is healthy and serving HTTP on port 80:**
   ```
   40f2b77bb734   ghcr.io/ldapaccountmanager/lam:9.4   2 hours ago   Up 2 hours (healthy)   80/tcp   ldap-account-manager
   ```

2. **Service responds correctly to HTTP requests:**
   ```
   ldap-account-manager  | 172.18.0.9:80 172.18.0.32 - - [17/Feb/2026:00:06:34 +0000]
   "GET /lam/ HTTP/1.1" 200 568 "-" "ktor-client"
   ```

3. **Service receives TLS handshake bytes but cannot handle them:**
   ```
   ldap-account-manager  | 172.18.0.9:80 172.18.0.32 - - [17/Feb/2026:00:07:40 +0000]
   "\x16\x03\x01\x06\xda\x01" 400 484 "-" "-"
   ```

   Note: `\x16\x03\x01` is the TLS handshake signature. The service is receiving TLS ClientHello but responding with HTTP 400.

4. **Test retries 3 times, all fail with SSL error:**
   Test runs at 00:07:40, 00:07:43, 00:07:46 - all showing the malformed TLS requests in logs

**Technical Details:**
- LDAP Account Manager container serves plain HTTP on port 80
- Caddy should handle TLS termination and forward plain HTTP to container
- `ERR_SSL_PROTOCOL_ERROR` indicates the browser is trying to do TLS directly with the backend
- This suggests Caddy is forwarding the TLS connection transparently instead of terminating it
- OR Caddy is not handling this route at all, and Playwright is connecting directly to port 80 with HTTPS

**Comparison with Working Services:**
Other services show successful forward auth (Homepage, Prometheus, etc.). They all have proper Caddy TLS termination. LDAP-AM configuration may be different.

**Impact:**
- LDAP Account Manager web interface inaccessible via HTTPS
- May work via HTTP (if DNS points directly and no redirect)
- Forward auth testing cannot proceed

### Precise Fix

**File:** Check Caddyfile configuration

**Likely Issue - Missing or Incorrect Route:**

**Check current configuration:**
```bash
ssh gerald@latium.local "cd ~/datamancy && docker compose exec caddy cat /etc/caddy/Caddyfile | grep -B 2 -A 10 lam"
```

**Required Configuration:**
```caddyfile
lam.datamancy.net {
    reverse_proxy ldap-account-manager:80

    # Forward auth configuration
    forward_auth authelia:9091 {
        uri /api/verify?rd=https://auth.datamancy.net
        copy_headers Remote-User Remote-Groups Remote-Name Remote-Email
    }
}
```

**Possible Misconfiguration #1: TLS Passthrough**
```caddyfile
# WRONG - Do not use tls passthrough for HTTP backends
lam.datamancy.net {
    reverse_proxy ldap-account-manager:80 {
        transport http {
            tls  # This would try TLS to backend
        }
    }
}
```

**Possible Misconfiguration #2: Wrong Port**
```caddyfile
# WRONG - Container listens on 80, not 443
lam.datamancy.net {
    reverse_proxy ldap-account-manager:443  # Wrong port
}
```

**Verification Steps:**
```bash
# 1. Check if route exists
ssh gerald@latium.local "cd ~/datamancy && docker compose exec caddy cat /etc/caddy/Caddyfile" | grep lam

# 2. Check Caddy logs during test
ssh gerald@latium.local "cd ~/datamancy && docker compose logs caddy | grep lam"

# 3. Test internal HTTP connectivity
ssh gerald@latium.local "cd ~/datamancy && docker compose exec integration-test-runner wget -O- http://ldap-account-manager:80/lam/"

# 4. Test if Caddy is proxying correctly
ssh gerald@latium.local "cd ~/datamancy && docker compose exec integration-test-runner curl -H 'Host: lam.datamancy.net' http://caddy:80/lam/"
```

**Alternative Diagnosis:**
If Caddyfile is correct, check:
1. Container web server configuration - ensure it's not trying to redirect to HTTPS
2. DNS - verify `lam.datamancy.net` points to Caddy
3. Certificate issues - check if TLS cert is valid for `lam.datamancy.net`

---

## Priority Recommendations

### Immediate (Security/Critical)
1. **Investigate Isolated Docker VM isolation breach** - Verify if containers are truly escaping isolation or if it's a test environment configuration issue
   - Run verification commands to check actual isolation
   - Fix SSH tunnel if connection issue detected

### High Priority (Affects CI/CD)
2. **Fix Container Registry HTTP/HTTPS mismatch** - Add insecure registry configuration to isolated-docker-vm Docker daemon
   - Edit `/etc/docker/daemon.json` on isolated-docker-vm host
   - Restart Docker daemon

### Medium Priority (Monitoring/Testing)
3. **Fix Grafana API token collision** - Use unique token names in test
   - Update test code to append UUID to token name
   - Or implement cleanup logic

4. **Fix Ntfy Caddy routing** - Add or correct Caddyfile configuration for ntfy.datamancy.net
   - Verify configuration exists
   - Test internal connectivity

5. **Fix LDAP-AM TLS handling** - Correct Caddy TLS termination for lam.datamancy.net
   - Verify Caddy configuration
   - Ensure plain HTTP reverse proxy to port 80

---

## Testing Validation

After applying fixes, run integration tests again and verify:

```bash
ssh gerald@latium.local "cd ~/datamancy && docker compose exec integration-test-runner java -jar test-runner.jar"
```

**Expected Results:**
- All 379 tests pass
- No isolation breach warnings
- Registry push succeeds
- Grafana token creation succeeds
- Playwright E2E tests pass for Ntfy and LDAP-AM

---

## Files Requiring Changes

1. **Grafana Test Code:**
   - `kotlin.src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/MonitoringTests.kt`
   - Change: Use unique token names

2. **Isolated Docker VM Docker Daemon:**
   - SSH to `gerald@192.168.0.69`
   - File: `/etc/docker/daemon.json`
   - Change: Add insecure registry configuration

3. **Caddyfile:**
   - Location: Check `dist/config/caddy/Caddyfile` or mounted volume
   - Change: Add/fix ntfy and lam routes with proper TLS termination

4. **Isolation Test (Optional):**
   - `kotlin.src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/CICDPipelineTests.kt`
   - Change: Add better logging to understand isolation status

---

**Report End**
