# Grafana — Spoke

**Status:** 🟢 Functional
**Phase:** 1
**Hostname:** `grafana.stack.local`
**Dependencies:** caddy, docker-socket-proxy

## Purpose

Grafana provides the observability UI for the Datamancy stack, offering dashboards, alerting, and data visualization capabilities for metrics and logs.

## Configuration

**Image:** `grafana/grafana:11.0.0`
**Volumes:**
- `grafana-data:/var/lib/grafana` (persistent storage)
**Networks:** frontend, backend
**Ports:** 3000 (internal)

### Key Settings

Environment variables:
- `GF_SERVER_ROOT_URL=https://grafana.stack.local`
- `GF_SERVER_DOMAIN=grafana.stack.local`
- `GF_AUTH_ANONYMOUS_ENABLED=false`
- `GF_SECURITY_ADMIN_USER=admin`
- `GF_SECURITY_ADMIN_PASSWORD=admin` (default, change in production)

### Fingerprint Inputs

- Image digest: `grafana/grafana:11.0.0`
- Config dir: None (default configuration)
- Secrets: `GF_SECURITY_ADMIN_USER`, `GF_SECURITY_ADMIN_PASSWORD`
- Compose stanza: grafana service block in docker-compose.yml

## Access

- **URL:** `https://grafana.stack.local`
- **Auth:** Basic auth (admin/admin by default)
- **Healthcheck:** `GET /api/health`

## Runbook

### Start/Stop

```bash
docker compose --profile observability up -d grafana
docker compose stop grafana
```

### Logs

```bash
docker compose logs -f grafana
```

### Common Issues

**Symptom:** Cannot access Grafana UI
**Cause:** Caddy routing not configured or Grafana container not running
**Fix:** Check Caddy labels on grafana service, verify container is up

**Symptom:** Login fails with correct credentials
**Cause:** Database initialization issue or volume permissions
**Fix:** Check `docker compose logs grafana` for errors, verify volume ownership

## Testing

**Smoke test:** Browser automation with Playwright
- Load login page
- Login with admin credentials
- Navigate to dashboards

**Last pass:** 2025-10-26T06:35:04+00:00
**Artifacts:** `data/tests/grafana/`

## Related

- Dependencies: [Caddy](caddy.md), [Docker Socket Proxy](docker-socket-proxy.md)
- Test runner: [test-runner](test-runner.md)
- Upstream docs: https://grafana.com/docs/grafana/latest/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** e50e0b0e73abb0a5

**Update 2025-10-26:** Migrated from caddy-docker-proxy labels to static Caddyfile routing. Service route configured in `configs/caddy/Caddyfile`.

