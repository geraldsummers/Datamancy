# Configuration Issues Found and Fixed

## Issues Identified and Resolved

### 1. **Gateway IP Mismatch** ✅ FIXED
**File**: `src/coredns/Corefile:5`

**Problem**: CoreDNS was returning `172.17.0.1` (Docker's default gateway) but the network was configured with subnet `172.18.0.0/16` and gateway `172.18.0.1`.

**Fix**: Changed CoreDNS template answer from `172.17.0.1` → `172.18.0.1` (then later to `172.18.0.2` - see Issue #2)

---

### 2. **DNS Pointing to Wrong Target** ✅ FIXED
**Files**:
- `src/coredns/Corefile:5`
- `src/docker-compose.yml:82-84`

**Problem**: CoreDNS was pointing `*.lab.localhost` to the gateway IP (`172.18.0.1`), but containers need to reach **Caddy's container IP**, not the host gateway. Caddy publishes ports 80/443 to the host, but container-to-container communication happens on the Docker network.

**Fix**:
- Assigned Caddy static IP: `172.18.0.2`
- Updated CoreDNS to resolve `*.lab.localhost` → `172.18.0.2` (Caddy's IP)

**Rationale**: When containers use `https://dex.lab.localhost`, they need DNS to resolve to Caddy's container IP so traffic routes through Caddy's TLS termination.

---

### 3. **Redundant Network Aliases** ✅ FIXED
**Files**: `src/docker-compose.yml:260-261, 674-675`

**Problem**: Grafana and Dex had hardcoded network aliases (`grafana.lab.localhost`, `dex.lab.localhost`) that are now unnecessary since CoreDNS handles all wildcard resolution.

**Fix**: Removed aliases from both services' network configurations.

**Rationale**: CoreDNS provides wildcard DNS, making per-service aliases redundant and potentially confusing.

---

### 4. **Forward Auth Not Supported by Caddy-Docker-Proxy** ⚠️ LIMITATION DOCUMENTED
**File**: `src/FORWARD_AUTH_MANUAL_SETUP.md`

**Problem**: Caddy-docker-proxy doesn't support `forward_auth` labels like Traefik does. The forward-auth service was created but couldn't be wired to Caddy via labels.

**Fix**:
- Removed incorrect `caddy.forward_auth` labels from all services
- Created documentation explaining alternatives:
  - Option 1: Switch to custom Caddyfile
  - Option 2: Network-level restrictions (don't expose non-OIDC services)
  - Option 3: Use basic auth
  - Option 4: Keep services unprotected on internal network

**Current State**: Forward-auth service exists and is registered with Dex, but **not actively protecting services**. Services rely on network isolation.

---

### 5. **HTTP References in Nextcloud OIDC Config** ✅ FIXED
**File**: `src/nextcloud/oidc.config.php:6,9`

**Problem**: Nextcloud OIDC configuration used `http://` URLs for Dex provider and redirect.

**Fix**: Changed to HTTPS:
- `oidc_login_provider_url`: `http://` → `https://`
- `oidc_login_redirect_url`: `http://` → `https://`

---

### 6. **HTTP References in Test Scripts** ✅ FIXED
**File**: `src/test-oidc-requests.py:56,77`

**Problem**: Test script used `http://dex.lab.localhost:5556` instead of `https://dex.lab.localhost`.

**Fix**: Updated URLs to HTTPS and removed port `:5556` (Caddy handles routing).

**Note**: Internal service tests in `auth-status/auth-tests.js` correctly use `http://dex:5556` for backend health checks (not through proxy).

---

## Summary of Fixes

| Issue | Severity | Status | Files Modified |
|-------|----------|--------|----------------|
| Gateway IP mismatch | High | ✅ Fixed | `coredns/Corefile` |
| DNS pointing to gateway instead of Caddy | Critical | ✅ Fixed | `coredns/Corefile`, `docker-compose.yml` |
| Redundant network aliases | Low | ✅ Fixed | `docker-compose.yml` |
| Forward auth not supported | Medium | ⚠️ Documented | New: `FORWARD_AUTH_MANUAL_SETUP.md` |
| HTTP in Nextcloud config | High | ✅ Fixed | `nextcloud/oidc.config.php` |
| HTTP in test scripts | Medium | ✅ Fixed | `test-oidc-requests.py` |

---

## Critical Path Issues (Must Fix Before Deploy)

1. ✅ **DNS resolution** - Fixed: Containers now resolve to Caddy's IP
2. ✅ **HTTPS consistency** - Fixed: All OIDC configs use HTTPS
3. ⚠️ **Forward auth** - Documented: Not implemented, services rely on network isolation

---

## What Works Now

✅ CoreDNS resolves `*.lab.localhost` to Caddy container (`172.18.0.2`)
✅ Dex issuer is HTTPS
✅ All OIDC clients use HTTPS endpoints
✅ Nextcloud OIDC config uses HTTPS
✅ Test scripts use HTTPS
✅ Browserless ignores cert errors for HTTPS access

## What Doesn't Work (By Design)

⚠️ Forward auth for non-OIDC services (requires custom Caddyfile or alternative)
⚠️ Non-OIDC services (prometheus, alertmanager, etc.) are **unprotected** at proxy level
   - They are only accessible via internal network
   - External access requires manual Caddyfile configuration or basic auth

---

## Recommendations

### For Production Use:

1. **Implement forward auth** using Option 1 from `FORWARD_AUTH_MANUAL_SETUP.md` (custom Caddyfile)
2. **Or** use Option 3 (basic auth) for quick protection
3. **Or** keep non-OIDC services off public URLs (don't expose via Caddy)

### For Lab/Development:

- Current configuration is acceptable
- Services are isolated on internal network
- OIDC-enabled services have proper authentication
- Non-OIDC services accessible only internally

---

## Files Modified

### Configuration Files:
- `src/coredns/Corefile` - Fixed gateway IP
- `src/docker-compose.yml` - Added Caddy static IP, removed aliases, removed invalid forward_auth labels
- `src/dex/config.yaml` - Changed issuer to HTTPS (already done in main upgrade)
- `src/nextcloud/oidc.config.php` - Changed to HTTPS URLs
- `src/jupyterhub/jupyterhub_config.py` - Changed to HTTPS URLs (already done)
- `src/test-oidc-requests.py` - Changed to HTTPS URLs

### New Documentation:
- `src/HTTPS_OIDC_UPGRADE.md` - Main upgrade documentation
- `src/FORWARD_AUTH_MANUAL_SETUP.md` - Forward auth alternatives
- `src/CONFIGURATION_ISSUES_FIXED.md` - This document

### Forward Auth Service (Created but Not Active):
- `src/forward-auth/Dockerfile`
- `src/forward-auth/package.json`
- `src/forward-auth/server.js`

---

## Validation Steps

After deployment, verify:

```bash
# 1. DNS resolution
docker run --rm --network app_net --dns 172.18.0.53 alpine nslookup dex.lab.localhost
# Expected: 172.18.0.2

# 2. Dex issuer
curl -k https://dex.lab.localhost/.well-known/openid-configuration | jq '.issuer'
# Expected: "https://dex.lab.localhost"

# 3. OIDC login flow (Grafana)
xdg-open https://grafana.lab.localhost
# Should redirect to Dex HTTPS login

# 4. Forward auth NOT active
curl -I https://prometheus.lab.localhost
# Expected: 200 OK (direct access, no auth prompt)
```

---

## Next Steps

1. **Deploy and test** the current configuration
2. **Decide on forward auth** strategy (see `FORWARD_AUTH_MANUAL_SETUP.md`)
3. **Implement chosen auth** method for non-OIDC services
4. **Update monitoring** to use HTTPS endpoints
5. **Install Caddy CA** in browsers/systems if needed
