# Prometheus — Spoke

**Status:** 🟢 Functional
**Phase:** 2
**Hostname:** `prometheus.stack.local`
**Dependencies:** caddy

## Purpose

Prometheus collects and stores time-series metrics from the Datamancy stack, enabling monitoring, alerting, and performance analysis of all services.

## Configuration

**Image:** `prom/prometheus:v3.0.1`
**Volumes:**
- `prometheus_data:/prometheus` (persistent metrics storage)
- `./configs/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro` (configuration)
**Networks:** frontend, backend
**Ports:** 9090 (internal)

### Key Settings

Command-line flags:
- `--config.file=/etc/prometheus/prometheus.yml`
- `--storage.tsdb.path=/prometheus`
- `--web.enable-lifecycle` (enables hot reload via API)

Scrape targets:
- prometheus (self-monitoring on localhost:9090)
- caddy (metrics on caddy:2019)
- grafana (metrics on grafana:3000)

### Fingerprint Inputs

- Image digest: `prom/prometheus:v3.0.1`
- Config dir: `configs/prometheus/` (prometheus.yml)
- Compose stanza: prometheus service block in docker-compose.yml

## Access

- **URL:** `https://prometheus.stack.local`
- **Auth:** None (protected by Caddy routing)
- **Healthcheck:** `GET /-/healthy`

## Runbook

### Start/Stop

```bash
docker compose --profile observability up -d prometheus
docker compose stop prometheus
```

### Logs

```bash
docker compose logs -f prometheus
```

### Query Examples

PromQL queries to try in the UI:

```promql
# All targets up
up

# Prometheus self-metrics
prometheus_tsdb_head_samples
rate(prometheus_http_requests_total[5m])

# Caddy metrics (if exposed)
caddy_http_requests_total
```

### Common Issues

**Symptom:** Targets showing as "down"
**Cause:** Service not exposing metrics endpoint or network connectivity
**Fix:** Check service logs, verify metrics endpoint responds: `curl http://<service>:<port>/metrics`

**Symptom:** Storage growing too large
**Cause:** High cardinality metrics or long retention
**Fix:** Adjust `--storage.tsdb.retention.time` flag, review metric labels

## Testing

**Smoke test:** Browser automation with Playwright
- Load Prometheus UI
- Verify page title contains "Prometheus"

**Last pass:** 2025-10-26T06:55:23+00:00
**Artifacts:** `data/tests/prometheus/`

## Related

- Dependencies: [Caddy](caddy.md)
- Integrations: [Grafana](grafana.md) (datasource), [Loki](loki.md) (logs counterpart)
- Test runner: [test-runner](test-runner.md)
- Upstream docs: https://prometheus.io/docs/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** TBD (run docs-indexer)

**Update 2025-10-26:** Migrated from caddy-docker-proxy labels to static Caddyfile routing. Service route configured in `configs/caddy/Caddyfile`.

