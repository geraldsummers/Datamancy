# Architectural Decisions

## ADR-001: Browserless as Internal Service Only

**Date:** 2025-10-25
**Status:** Accepted
**Context:** Phase 1 implementation

### Problem
Browserless UI (Next.js app) fails when served under Traefik's path-based routing at `/browserless/`:
- JavaScript errors: `Cannot read properties of null (reading 'classList')`
- Asset loading failures (framework bundles expecting root path)
- Next.js app is subpath-unaware without build-time `basePath` configuration

### Options Considered

1. **Configure Next.js basePath**
   - Requires custom Browserless build with `next.config.js` changes
   - Breaks portability principle (custom images)
   - No upstream support for this use case

2. **Give Browserless a dedicated hostname**
   - Violates "single hostname" principle
   - Requires additional DNS/hosts entries
   - Contradicts no-DNS strategy

3. **Use complex Traefik rewrite rules**
   - Fragile; Next.js asset paths are dynamic
   - High maintenance burden
   - Still breaks on hydration errors

4. **Make Browserless internal-only** ✅
   - Browserless is a **testing tool**, not a user-facing service
   - Test Runner accesses via Docker network (`http://browserless:3000`)
   - No human UI access needed in normal operation
   - Debugger access available via `docker exec -it browserless sh`

### Decision
**Browserless will NOT be exposed via Traefik.** It remains an internal service accessible only via Docker network.

**Rationale:**
- **Alignment with purpose**: Browserless is automation infrastructure, not a user application
- **Single Front Door preserved**: All *user-facing* services still via `stack.local`
- **Portability maintained**: No custom builds or DNS required
- **Security improved**: Reduced attack surface (one less public endpoint)
- **Test coverage**: Test Runner validates Browserless via internal HTTP check

### Implementation
- Set `traefik.enable=false` on browserless service
- Add `datamancy.service.internal=true` label
- Update Homepage to show Browserless as "internal only"
- Change Phase 1 smoke test from UI check to HTTP healthcheck via Docker network
- Document debugger access: `docker exec -it browserless /bin/bash`

### Consequences

**Positive:**
- Eliminates subpath routing fragility
- Cleaner separation: public UI vs. internal tooling
- One less service to monitor for Freshness Rule (no public UI to test)
- Faster test execution (HTTP check vs. browser automation)

**Negative:**
- No web UI for debugging Browserless (must use `docker exec` + CLI tools)
- Slightly less discoverable for operators (not in Landing page links)

**Mitigation:**
- Document internal access in README
- Landing page shows Browserless status via Docker API (container health)
- Add optional `--profile debug` to expose Browserless on demand (future)

### Alternatives for Future
If web UI access is required later:
1. **Dedicated port binding**: `ports: - "127.0.0.1:3001:3000"` (localhost-only)
2. **Temporary proxy**: `docker run -p 9222:3000 --network datamancy --rm alpine/socat TCP-LISTEN:3000,fork TCP:browserless:3000`
3. **Debug profile**: Add `traefik.enable=true` label under optional profile

---

## ADR-002: Freshness Rule Enforcement via Timestamp Comparison

**Date:** 2025-10-25
**Status:** Accepted
**Context:** Phase 1 implementation

### Problem
Need a mechanism to ensure services are validated **after** any change, preventing false confidence in stale test results.

### Decision
Services are marked **"Functional"** only when:
```
Last Passing UI Test Timestamp > Container Creation Timestamp
```

**Implementation:**
- Tests record `epochMs` timestamps to `data/tests/freshness/{service}.json`
- `freshness-check.sh` compares test timestamp vs. `docker inspect -f '{{.Created}}'`
- Status outcomes:
  - ✓ **Functional**: `test_time > container_time && status == 'pass'`
  - ✗ **Test Failed**: `status == 'fail'`
  - ⚠ **Needs Re-test**: `test_time <= container_time`
  - ⚠ **Unknown**: No test results file

### Rationale
- **Simple**: No complex dependency tracking, just timestamp comparison
- **Portable**: Works with any CI/CD system (just compare JSON timestamps)
- **Observable**: Clear JSON files for debugging
- **Enforceable**: Scripts/CI can gate on Freshness status

### Limitations
- Container creation time used as proxy for "change" (doesn't detect config file edits without restart)
- No automatic re-test triggering (manual or CI-driven)
- Timezone-dependent (uses ISO timestamps for human readability)

**Future enhancements:**
- Add config file hash tracking for non-container changes
- CI webhook to trigger tests on compose file changes
- Dashboard widget showing real-time Freshness status

---

## ADR-003: Single Hostname with Path-Based Routing

**Date:** 2025-10-25
**Status:** Accepted
**Context:** Phase 0-1 architecture

### Decision
All services accessible via single hostname (`stack.local`) with path prefixes:
- `/` → Homepage
- `/grafana/` → Grafana
- `/dashboard/` → Traefik
- (Internal services not routed)

**No custom DNS server.** Just one `/etc/hosts` entry + container `extra_hosts: stack.local:host-gateway`.

### Rationale
- **Portability**: No DNS server container; works on any Docker host
- **Simplicity**: One hostname to map, one TLS cert to trust
- **Agent-friendly**: Containers resolve `stack.local` via `host-gateway` (Docker's special DNS value)
- **No DNS poisoning**: Explicit mappings, no network-wide DNS changes

### Trade-offs
- Apps must support subpath routing (requires `GF_SERVER_SERVE_FROM_SUB_PATH`, base URLs, etc.)
- Path collisions require careful priority tuning in Traefik
- Some apps (like Browserless) cannot be exposed without custom builds → kept internal

**Alternative considered:** Subdomain routing (`grafana.stack.local`, `traefik.stack.local`)
**Rejected because:** Requires wildcard DNS or multiple `/etc/hosts` entries; breaks "single hostname" goal.

---

## ADR-004: Rootless Docker with Socket Proxy

**Date:** 2025-10-25
**Status:** Accepted
**Context:** Phase 0 security

### Decision
- All containers run under rootless Docker (`/run/user/1000/docker.sock`)
- Docker socket exposed via `tecnativa/docker-socket-proxy` with minimal permissions
- No raw socket mounts in application containers

### Implementation
- Socket-proxy container: read-only socket mount, filtered API endpoints
- Traefik: `endpoint: tcp://socket-proxy:2375`
- Homepage: `DOCKER_HOST=tcp://socket-proxy:2375`

### Rationale
- **Security**: Limits Docker API access to specific endpoints (no container creation from Traefik)
- **Rootless**: No privilege escalation via Docker socket
- **Auditability**: Socket-proxy logs all API requests

### Trade-offs
- Extra container to manage
- Slight latency increase (proxy hop)
- Requires `DOCKER_HOST` env var in dependent services

**Rejected alternative:** Direct socket mount with read-only flag
**Reason:** Read-only socket still allows privileged operations; socket-proxy enforces allowlist.
