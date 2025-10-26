# Caddy — Spoke

**Status:** 🟢 Functional
**Phase:** 0 — Scaffolding
**Hostname:** `*.stack.local` (wildcard)
**Dependencies:** local CA

## Purpose

Caddy is the **front door** reverse proxy, routing HTTPS traffic to backend services via **static Caddyfile** configuration.

## Configuration

**Image:** `caddy:2.9-alpine` (vanilla, no plugins)
**Volumes:**
- `caddy_data:/data` — Persistent cache
- `caddy_config:/config` — Runtime config
- `./certs:/certs:ro` — Local CA wildcard cert
- `./configs/caddy/Caddyfile:/etc/caddy/Caddyfile:ro` — Static routing config

**Networks:** frontend, backend
**Ports:** 80 (HTTP), 443 (HTTPS), 2019 (Admin API)

### Key Settings

**Static Caddyfile Routes:**
- `auth.stack.local` → authelia:9091
- `grafana.stack.local` → grafana:3000
- `prometheus.stack.local` → prometheus:9090
- `loki.stack.local` → loki:3100
- `clickhouse.stack.local` → clickhouse:8123
- `adminer.stack.local` → adminer:8080
- `mongo-express.stack.local` → mongo-express:8081
- `portainer.stack.local` → portainer:9000
- `stack.local` → 200 response "Datamancy Stack - Phase 5 Complete"

**TLS:** Wildcard cert snippet `(tls_config)` using `/certs/wildcard.{crt,key}`
**Auto HTTPS:** Disabled (using custom wildcard cert)

### Fingerprint Inputs

- Image digest: `caddy:2.9-alpine` (vanilla image)
- Config dir: `configs/caddy/Caddyfile`
- Secrets: `certs/wildcard.{crt,key}` (hash both files)
- Compose stanza: `services.caddy` block in `docker-compose.yml`

## Access

- **URL:** `https://*.stack.local` (routes defined in static Caddyfile)
- **Auth:** Application-level (no edge auth)
- **Healthcheck:** Admin API at `:2019/metrics`

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

### Verify Routes

```bash
curl http://localhost:2019/config/ | jq '.apps.http.servers.srv0.routes'
```

Expected: JSON showing all configured routes

### Reload Config

After editing `configs/caddy/Caddyfile`:

```bash
docker exec caddy caddy reload --config /etc/caddy/Caddyfile
# Or restart container
docker compose restart caddy
```

### Common Issues

**Symptom:** `ERR trust_pool` or certificate errors
**Cause:** `certs/wildcard.{crt,key}` missing or invalid
**Fix:** Run `./scripts/generate-ca.sh` and restart Caddy

**Symptom:** 404 on hostname
**Cause:** Route not defined in Caddyfile
**Fix:** Add route block to `configs/caddy/Caddyfile` and reload

**Symptom:** `connection refused` to service
**Cause:** Target service not on shared network (frontend/backend)
**Fix:** Add service to `networks: [frontend]` or `[backend]`

## Testing

**Smoke test:** `curl --cacert certs/ca.crt https://stack.local` should return "Datamancy Stack - Phase 5 Complete"
**Last pass:** Verified 2025-10-26 (manual curl tests)
**Artifacts:** Browser test coverage added in Phase 1

## Related

- **ADR:** [ADR-000: Caddy Front Door, Multi-Hostname, No DNS](../adr/ADR-000-caddy-multi-hostname.md)
- **Config:** `configs/caddy/Caddyfile`
- **Upstream docs:** https://caddyserver.com/docs/

---

**Update 2025-10-26:** Migrated from `caddy-docker-proxy` (label-driven) to vanilla `caddy:2.9-alpine` with static Caddyfile. Removed caddy-security plugin and docker-socket-proxy dependency. All routes now explicitly defined in `configs/caddy/Caddyfile`. This resolves RBAC complexity and provides clearer routing provenance.

**Last updated:** 2025-10-26
