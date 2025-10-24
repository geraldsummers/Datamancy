# HTTPS-Only OIDC with Internal Wildcard DNS and Forward Auth

## Overview

This upgrade implements a unified HTTPS architecture for all services with:
- **CoreDNS** for internal wildcard DNS (*.lab.localhost → host-gateway)
- **Dex OIDC** issuer on HTTPS
- **OIDC clients** using consistent HTTPS endpoints
- **Forward auth** for non-OIDC services via Dex-backed gateway

## Architecture Changes

### 1. DNS Service (CoreDNS)

**File**: `src/coredns/Corefile`

**Purpose**: Provides wildcard DNS resolution for all subdomains.

**Configuration**:
```
.:53 {
    # Wildcard A record for *.lab.localhost → 172.17.0.1 (host-gateway)
    template IN A lab.localhost {
        match "^([a-z0-9-]+\.)*lab\.localhost\.$"
        answer "{{ .Name }} 60 IN A 172.17.0.1"
        fallthrough
    }

    # Forward other queries to public DNS
    forward . 8.8.8.8 8.8.4.4 1.1.1.1
    log
    errors
    cache 30
}
```

**Network**:
- Static IP: `172.18.0.53` on `app_net`
- All containers configured with `dns: [172.18.0.53, 8.8.8.8]`

**Rationale**: Eliminates per-service `extra_hosts` entries and scales cleanly for new services.

---

### 2. Dex HTTPS Issuer

**File**: `src/dex/config.yaml`

**Change**:
```yaml
# OLD
issuer: http://dex.lab.localhost

# NEW
issuer: https://dex.lab.localhost
```

**Redirect URIs**: All changed from `http://` to `https://` only.

**Rationale**: OIDC requires exact issuer match. Mixing HTTP/HTTPS breaks token validation and cookies.

---

### 3. OIDC Clients (HTTPS Endpoints)

**Services Updated**:
- **Grafana**: `docker-compose.yml:229-232`
- **LibreChat**: `docker-compose.yml:328-333`
- **Planka**: `docker-compose.yml:483`
- **Outline**: `docker-compose.yml:699-701`
- **JupyterHub**: `jupyterhub/jupyterhub_config.py:36-41`
- **Auth-status-dashboard**: `docker-compose.yml:724`

**Pattern**:
```yaml
# OLD
- GF_AUTH_GENERIC_OAUTH_AUTH_URL=http://dex.${BASE_DOMAIN}:5556/auth
- GF_AUTH_GENERIC_OAUTH_TOKEN_URL=http://dex:5556/token

# NEW
- GF_AUTH_GENERIC_OAUTH_AUTH_URL=https://dex.${BASE_DOMAIN}/auth
- GF_AUTH_GENERIC_OAUTH_TOKEN_URL=https://dex.${BASE_DOMAIN}/token
```

**Rationale**: All OIDC interactions now use the same HTTPS hostname, ensuring cookie security and issuer consistency.

---

### 4. Forward Auth Service

**Files**:
- `src/forward-auth/server.js` - Node.js auth gateway
- `src/forward-auth/package.json` - Dependencies
- `src/forward-auth/Dockerfile` - Container build

**Purpose**: Intercepts requests to non-OIDC services and validates Dex sessions.

**Flow**:
1. Caddy forwards auth check to `forward-auth:4180/validate`
2. If valid session exists → passes request with user headers
3. If no session → redirects to Dex login
4. After login → callback returns to original URL

**Protected Services** (via Caddy labels):
- prometheus.lab.localhost
- alertmanager.lab.localhost
- loki.lab.localhost
- benthos.lab.localhost
- browserless.lab.localhost
- watchtower.lab.localhost
- duplicati.lab.localhost
- dockge.lab.localhost
- topology-api.lab.localhost

**Caddy Label Pattern**:
```yaml
caddy.forward_auth: forward-auth:4180
caddy.forward_auth.uri: /validate
caddy.forward_auth.copy_headers: "X-Forwarded-User X-Forwarded-Email X-Forwarded-Name X-Forwarded-Groups"
```

**Rationale**: Provides uniform authentication for legacy services without native OIDC support.

---

### 5. Browserless Certificate Handling

**File**: `docker-compose.yml:390`

**Change**:
```yaml
# OLD
- CHROME_ARGS=--ignore-certificate-errors --proxy-server=http://caddy:80 --proxy-bypass-list=<-loopback>

# NEW
- CHROME_ARGS=--ignore-certificate-errors
```

**Rationale**: Browserless now resolves HTTPS URLs via CoreDNS and ignores Caddy's internal CA cert.

---

## Validation Checklist

### DNS Resolution

**From Host**:
```bash
# Should resolve to 127.0.0.1 (assuming lab.localhost in /etc/hosts)
dig dex.lab.localhost
dig grafana.lab.localhost
```

**From Container**:
```bash
docker run --rm --network app_net --dns 172.18.0.53 alpine nslookup dex.lab.localhost
# Expected: 172.18.0.1 (host-gateway)
```

---

### Dex Issuer

**Test**:
```bash
curl -k https://dex.lab.localhost/.well-known/openid-configuration | jq '.issuer'
# Expected: "https://dex.lab.localhost"
```

---

### OIDC App Login Flow

**Example: Grafana**

1. Navigate to `https://grafana.lab.localhost`
2. Click "Login with Dex"
3. Redirected to `https://dex.lab.localhost/auth`
4. Enter LDAP credentials (e.g., `authtest / TestAuth123!`)
5. Redirected back to Grafana with valid session

**Expected**: No certificate warnings (if Caddy CA installed) or accepted self-signed cert.

---

### Forward Auth Protected Service

**Example: Prometheus**

**Direct Backend Access** (bypasses proxy):
```bash
docker exec -it prometheus wget -q -O- http://localhost:9090/
# Expected: 200 OK (no auth on backend)
```

**HTTPS Public Access** (via forward auth):
```bash
curl -I https://prometheus.lab.localhost
# Expected: 401 Unauthorized + Location header to Dex login
```

**After Login**:
1. Navigate to `https://prometheus.lab.localhost` in browser
2. Redirected to Dex login
3. After successful login → access granted to Prometheus UI
4. Session cookie persists for 24 hours

---

### Test Commands

**Start Stack**:
```bash
cd src
docker compose down -v  # Clean start
docker compose up -d
docker compose logs -f coredns dex forward-auth
```

**Check DNS**:
```bash
docker run --rm --network app_net --dns 172.18.0.53 alpine sh -c '
  nslookup dex.lab.localhost &&
  nslookup grafana.lab.localhost &&
  nslookup prometheus.lab.localhost
'
```

**Check Dex Discovery**:
```bash
curl -k https://dex.lab.localhost/.well-known/openid-configuration | jq
```

**Check Forward Auth**:
```bash
# Should return 401 without session
curl -I https://prometheus.lab.localhost

# Check auth service health
curl https://auth.lab.localhost/health
```

**Test OIDC Login** (Grafana):
```bash
# Open in browser
xdg-open https://grafana.lab.localhost
# Or use browserless for automated test
```

---

## Expected Results

### Successful DNS Resolution
- All `*.lab.localhost` domains resolve to `172.18.0.1` from containers
- CoreDNS logs show successful queries

### Dex OIDC Discovery
- Issuer field matches `https://dex.lab.localhost`
- All endpoints use HTTPS scheme

### OIDC App Login
- Clean redirect flow: app → dex → app
- No mixed content warnings
- Session cookies properly scoped to HTTPS

### Forward Auth Protection
- Unauthenticated requests return 401 + redirect
- Post-login requests pass through with user headers
- Backend services receive `X-Forwarded-User`, `X-Forwarded-Email`, etc.

---

## Troubleshooting

### "Issuer mismatch" errors
- Check Dex config has `issuer: https://dex.lab.localhost`
- Verify client configs use `https://dex.lab.localhost` (not `http://dex:5556`)

### DNS not resolving
- Check CoreDNS logs: `docker logs coredns`
- Verify service has `dns: [172.18.0.53]` in compose
- Test: `docker exec <container> nslookup dex.lab.localhost`

### Forward auth redirect loop
- Check forward-auth logs: `docker logs forward-auth`
- Verify callback URL: `https://auth.lab.localhost/callback`
- Ensure Dex client includes callback in redirectURIs

### Certificate errors in browser
- Install Caddy root CA: `docker cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root-ca.crt`
- Import to browser trust store
- Or use `--ignore-certificate-errors` for testing

---

## Constraints Met

✅ All changes in compose and config files
✅ No HTTP/HTTPS mixing in OIDC settings
✅ CoreDNS wildcard zone (no extra_hosts)
✅ Single HTTPS URL plane
✅ Forward auth for non-OIDC services
✅ Browserless trusts/ignores certs
✅ Scalable for new services

---

## Summary

This upgrade provides:
- **Consistent HTTPS** for all external access
- **Exact issuer match** for OIDC tokens
- **Wildcard DNS** that scales without config changes
- **Uniform authentication** via forward auth for legacy services
- **Production-ready** architecture for internal lab services
