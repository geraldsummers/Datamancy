# Caddy — Spoke

**Status:** 🟡 In Progress
**Phase:** 0 — Scaffolding
**Hostname:** `*.stack.local` (wildcard)
**Dependencies:** docker-socket-proxy, local CA

## Purpose

Caddy is the **front door** reverse proxy, routing HTTPS traffic to backend services via hostname-based rules discovered from Docker labels.

## Configuration

**Image:** `lucaslorentz/caddy-docker-proxy:2.9`
**Volumes:**
- `/run/user/${UID}/docker.sock:/var/run/docker.sock:ro` — Label discovery
- `caddy_data:/data` — Persistent cache
- `caddy_config:/config` — Runtime config
- `./certs:/certs:ro` — Local CA wildcard cert

**Networks:** frontend, backend, socket
**Ports:** 80 (HTTP), 443 (HTTPS)

### Key Settings

**Environment:**
- `CADDY_DOCKER_CADDYFILE_PATH=/etc/caddy/Caddyfile`
- `CADDY_DOCKER_LABEL_PREFIX=caddy`

**Labels:**
- `caddy=*.${STACK_HOST}` — Match all `*.stack.local` hostnames
- `caddy.tls=/certs/wildcard.crt /certs/wildcard.key` — Use local CA wildcard cert

**Command:** `caddy docker-proxy --log-level INFO`

### Fingerprint Inputs

- Image digest: `lucaslorentz/caddy-docker-proxy@sha256:...` (pin after Phase 0)
- Config dir: `N/A` (Caddy config is label-driven, no static files)
- Secrets: `certs/wildcard.{crt,key}` (hash both files)
- Compose stanza: `services.caddy` block in `docker-compose.yml`

## Access

- **URL:** `https://*.stack.local` (routes to labeled services)
- **Auth:** None at edge (Phase 0); Authelia forward_auth in Phase 3
- **Healthcheck:** Implicit (Caddy logs label discovery on startup)

## Runbook

### Start/Stop

```bash
docker compose --profile core up -d caddy
docker compose stop caddy
```

### Logs

```bash
docker compose logs -f caddy
```

### Verify Label Discovery

```bash
docker compose logs caddy | grep -i "route"
```

Expected: Lines showing discovered routes like `*.stack.local -> <service>`

### Reload Config

Caddy reloads automatically when container labels change. To force reload:

```bash
docker compose restart caddy
```

### Common Issues

**Symptom:** `ERR trust_pool` or certificate errors
**Cause:** `certs/wildcard.{crt,key}` missing or invalid
**Fix:** Run `./scripts/generate-ca.sh` and restart Caddy

**Symptom:** Routes not discovered
**Cause:** docker-socket-proxy not running or wrong permissions
**Fix:** Check `docker compose ps docker-socket-proxy` and verify socket mount

**Symptom:** `connection refused` to service
**Cause:** Target service not on shared network (frontend/backend)
**Fix:** Add service to `networks: [frontend]` or `[backend]`

## Testing

**Smoke test:** `curl -I --cacert certs/ca.crt https://grafana.stack.local` should return 200 or 502 (502 = routed but service down).
**Last pass:** Not yet tested (Phase 0 scaffolding only)
**Artifacts:** N/A (Phase 1 will add browser tests)

## Related

- **ADR:** [ADR-000: Caddy Front Door, Multi-Hostname, No DNS](../adr/ADR-000-caddy-multi-hostname.md)
- **Dependencies:** [Docker Socket Proxy](docker-socket-proxy.md)
- **Upstream docs:** https://github.com/lucaslorentz/caddy-docker-proxy

---

**Last updated:** 2025-10-26
**Last change fingerprint:** `TBD` (will compute after Phase 0 validation)
