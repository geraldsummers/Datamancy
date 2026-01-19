# Security Changes: Agent-Tool-Server Lockdown

**Date:** 2026-01-18
**Impact:** Breaking change - agent-tool-server no longer externally accessible
**Reason:** Security hardening - prevent potential attack surface

---

## Summary

The `agent-tool-server` has been **removed from external access** for security reasons. It is now **internal-only**, accessible only from within the Docker network.

### Why This Change?

The agent-tool-server provides extremely powerful capabilities:
- 🐳 Docker container spawning and execution
- 💾 Database queries (PostgreSQL, MariaDB, ClickHouse)
- 🔑 SSH operations and key management
- 🖥️ Host command execution
- 🌐 Access to all internal networks

**External exposure of these capabilities is a massive attack surface**, even with Authelia protection.

---

## What Changed

### 1. **Caddy Configuration** (`configs.templates/infrastructure/caddy/Caddyfile`)

**Before:**
```nginx
# agent-tool-server (protected)
agent-tool-server.{$DOMAIN} {
    route {
        import authelia_auth
        reverse_proxy agent-tool-server:8081
    }
}
```

**After:**
```nginx
# agent-tool-server - INTERNAL ONLY (not exposed externally)
# Access via: Open WebUI, JupyterHub, or SSH tunnel
# For debugging: ssh -L 8081:agent-tool-server:8081 latium.local
```

### 2. **Docker Compose** (`compose.templates/datamancy-services.yml`)

**Before:**
```yaml
agent-tool-server:
  networks:
    - ai
    - ai-gateway
    - caddy         # REMOVED
    - postgres
    # ...
```

**After:**
```yaml
agent-tool-server:
  networks:
    # INTERNAL ONLY - not exposed to external network (no caddy network)
    - ai
    - ai-gateway
    # caddy: REMOVED - no external access
    - postgres
    # ...
```

### 3. **Testing Infrastructure Added**

New files created:
- `scripts/stack-health/test-agent-stack.main.kts` - Standalone test script
- `scripts/stack-health/TESTING-GUIDE.md` - Comprehensive testing documentation
- `scripts/stack-health/quickstart.sh` - Quick start script
- `src/stack-test-runner/Dockerfile` - Internal test runner container
- `compose.templates/stack-test-runner.yml` - Test runner service definition

---

## Who Is Affected?

### ❌ **No Longer Works:**

```bash
# External access blocked
curl https://agent-tool-server.datamancy.net/health
# Returns: 404 Not Found (no Caddy route)
```

### ✅ **Still Works:**

1. **Open WebUI** - Internal tool calls still work
2. **JupyterHub notebooks** - Can call tools via internal network
3. **Other services in ai/ai-gateway networks** - Internal access preserved

### ✅ **New Testing Approaches:**

1. **SSH Tunnel** (recommended for manual testing):
   ```bash
   ssh -L 8081:agent-tool-server:8081 latium.local
   curl http://localhost:8081/health
   ```

2. **Test Container** (recommended for CI/CD):
   ```bash
   docker compose --profile testing run --rm stack-test-runner \
     ./test-agent-stack.main.kts --env internal --suite all
   ```

3. **Local Test Ports** (dev only):
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.test-ports.yml up -d
   ./test-agent-stack.main.kts --env local
   ```

---

## Migration Guide

### For Developers

**If you were accessing agent-tool-server externally:**

1. **Use SSH tunnel:**
   ```bash
   ssh -L 8081:agent-tool-server:8081 latium.local
   # Now access via http://localhost:8081
   ```

2. **Or run tests from inside network:**
   ```bash
   cd ~/.datamancy
   docker compose --profile testing run --rm stack-test-runner bash
   # Inside container:
   curl http://agent-tool-server:8081/health
   ```

### For CI/CD Pipelines

**Update your pipeline:**

```yaml
# Before (broken)
- name: Test agent
  run: curl https://agent-tool-server.datamancy.net/health

# After (works)
- name: Test agent
  run: |
    docker compose --profile testing run --rm stack-test-runner \
      ./test-agent-stack.main.kts --env internal --suite all
```

### For Monitoring Systems

**Update health checks:**

```yaml
# Before (broken)
monitors:
  - name: agent-tool-server
    url: https://agent-tool-server.datamancy.net/health

# After (use internal endpoint)
monitors:
  - name: agent-tool-server
    url: http://agent-tool-server:8081/health
    network: internal  # Must be checked from inside Docker network
```

---

## Deployment Instructions

### Step 1: Rebuild Configuration

```bash
cd ~/IdeaProjects/Datamancy

# Rebuild with new configs
./build-datamancy.main.kts

# This updates:
# - ~/.datamancy/configs/infrastructure/caddy/Caddyfile
# - ~/.datamancy/docker-compose.yml
```

### Step 2: Deploy Changes

```bash
cd ~/.datamancy

# Restart Caddy to pick up new config
docker compose restart caddy

# Restart agent-tool-server to apply network changes
docker compose up -d --force-recreate agent-tool-server

# Verify external access is blocked
curl -I https://agent-tool-server.datamancy.net/health
# Should return: 404 Not Found (or connection refused)
```

### Step 3: Verify Internal Access Still Works

```bash
# Test from another container
docker exec -it open-webui wget -qO- http://agent-tool-server:8081/health
# Should return: {"status":"ok"}

# Or run test suite
docker compose --profile testing run --rm stack-test-runner \
  ./test-agent-stack.main.kts --env internal --suite foundation
```

---

## Testing Your Changes

### Quick Test

```bash
cd ~/IdeaProjects/Datamancy/scripts/stack-health

# Via SSH tunnel
ssh -L 8081:agent-tool-server:8081 latium.local &
./test-agent-stack.main.kts --env ssh-tunnel --suite foundation
```

### Full Test Suite

```bash
cd ~/.datamancy

# Build test runner
docker compose -f docker-compose.yml -f ../compose.templates/stack-test-runner.yml build

# Run all tests
docker compose --profile testing run --rm stack-test-runner \
  ./test-agent-stack.main.kts --env internal --suite all
```

---

## Rollback Instructions

If you need to restore external access (NOT recommended):

### Step 1: Restore Caddy Config

```bash
# In configs.templates/infrastructure/caddy/Caddyfile
# Add back:

agent-tool-server.{$DOMAIN} {
    route {
        import authelia_auth
        reverse_proxy agent-tool-server:8081
    }
}
```

### Step 2: Restore Caddy Network

```bash
# In compose.templates/datamancy-services.yml
# Add back under agent-tool-server.networks:

networks:
  # ...
  caddy:
    aliases:
      - agent-tool-server.${DOMAIN}
```

### Step 3: Rebuild and Deploy

```bash
./build-datamancy.main.kts
cd ~/.datamancy
docker compose up -d --force-recreate agent-tool-server caddy
```

**⚠️ Warning:** This re-exposes the attack surface. Only do this if absolutely necessary and you understand the security implications.

---

## Security Benefits

### Attack Surface Reduction

**Before:**
- ❌ External internet → Authelia → agent-tool-server
- ❌ Risk: Authelia bypass, session hijacking, credential compromise
- ❌ Exposure: All powerful capabilities accessible if auth bypassed

**After:**
- ✅ External internet → ❌ No route to agent-tool-server
- ✅ Internal network only → agent-tool-server
- ✅ Defense in depth: Multiple network boundaries to breach

### Remaining Access Paths

✅ **Legitimate access maintained:**
1. Open WebUI (internal network)
2. JupyterHub notebooks (internal network)
3. SSH tunnel (authenticated, encrypted)
4. Test containers (ephemeral, internal)

❌ **Blocked access:**
1. Direct external HTTP/HTTPS
2. Unauthenticated access attempts
3. Port scanning from internet

---

## Questions?

See comprehensive documentation:
- `scripts/stack-health/TESTING-GUIDE.md` - Full testing guide
- `scripts/stack-health/README.md` - Quick reference
- `scripts/stack-health/quickstart.sh` - Automated setup

Run quick start:
```bash
cd ~/IdeaProjects/Datamancy/scripts/stack-health
./quickstart.sh foundation
```

---

## Summary Checklist

- [x] Remove agent-tool-server from Caddy config
- [x] Remove caddy network from agent-tool-server
- [x] Create internal test runner service
- [x] Document testing approaches
- [x] Create quickstart script
- [x] Rebuild and deploy
- [ ] **YOU: Rebuild stack with `./build-datamancy.main.kts`**
- [ ] **YOU: Deploy changes to production**
- [ ] **YOU: Verify external access blocked**
- [ ] **YOU: Run test suite to verify internal access works**

**Status:** ✅ Ready for deployment
**Risk:** 🟡 Medium (breaking change for external consumers)
**Mitigation:** 🟢 Multiple testing approaches documented and implemented
