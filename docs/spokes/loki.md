# Loki — Spoke

**Status:** 🟢 Functional
**Phase:** 2
**Hostname:** `loki.stack.local`
**Dependencies:** promtail (log shipper)

## Purpose

Loki aggregates and stores logs from all Datamancy containers, providing a centralized log search and analysis platform optimized for label-based queries.

## Configuration

**Image:** `grafana/loki:3.2.1`
**Volumes:**
- `loki_data:/loki` (persistent log storage)
- `./configs/loki/loki.yml:/etc/loki/loki.yml:ro` (configuration)
**Networks:** backend
**Ports:** 3100 (internal)

### Key Settings

Storage:
- Filesystem backend with TSDB schema (v13)
- Chunks directory: `/loki/chunks`
- Index directory: `/loki/tsdb-index`

Limits:
- Reject samples older than 168h (7 days)
- Ingestion rate: 10MB/s, burst: 20MB/s
- Query freshness: 10m max staleness

Compaction:
- Runs every 10 minutes
- Retention disabled (would require delete-request-store)

### Fingerprint Inputs

- Image digest: `grafana/loki:3.2.1`
- Config dir: `configs/loki/` (loki.yml)
- Compose stanza: loki service block in docker-compose.yml

## Access

- **URL:** `https://loki.stack.local` (API only, no UI)
- **Auth:** None (internal service)
- **Healthcheck:** `GET /ready`

## Runbook

### Start/Stop

```bash
docker compose --profile observability up -d loki
docker compose stop loki
```

### Logs

```bash
docker compose logs -f loki
```

### Query Examples

LogQL queries (use via Grafana Explore):

```logql
# All logs from grafana service
{service="grafana"}

# Error logs across all services
{project="datamancy"} |= "error"

# Logs from specific container
{container="prometheus"}

# Rate of log lines
rate({service="grafana"}[5m])
```

### Direct API Access

```bash
# Health check
curl http://localhost:3100/ready

# Query logs (requires label selectors)
curl -G http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service="grafana"}' \
  --data-urlencode 'start=1h' \
  --data-urlencode 'end=now'

# Push logs (used by Promtail)
curl -X POST http://localhost:3100/loki/api/v1/push \
  -H "Content-Type: application/json" \
  -d '{"streams": [{"stream": {"job": "test"}, "values": [["'$(date +%s)'000000000", "test message"]]}]}'
```

### Common Issues

**Symptom:** Loki returning 503 Service Unavailable
**Cause:** Still initializing or config error
**Fix:** Check logs for startup errors, verify config syntax

**Symptom:** No logs appearing in Grafana
**Cause:** Promtail not connected or no matching labels
**Fix:** Check Promtail is running and pushing to Loki, verify label selectors match

**Symptom:** "too many outstanding requests" errors
**Cause:** Query overload or slow storage
**Fix:** Reduce query parallelism, check disk I/O, adjust `max_outstanding_per_tenant`

## Testing

**Smoke test:** Browser automation with Playwright
- Grafana datasource configuration verified
- Loki accessible via Grafana Explore page

**Last pass:** 2025-10-26T06:55:23+00:00
**Artifacts:** `data/tests/loki/`

## Related

- Dependencies: [Promtail](promtail.md) (log collector)
- Integrations: [Grafana](grafana.md) (datasource for querying)
- Metrics counterpart: [Prometheus](prometheus.md)
- Test runner: [test-runner](test-runner.md)
- Upstream docs: https://grafana.com/docs/loki/latest/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** TBD (run docs-indexer)

**Update 2025-10-26:** Migrated from caddy-docker-proxy labels to static Caddyfile routing. Service route configured in `configs/caddy/Caddyfile`.

