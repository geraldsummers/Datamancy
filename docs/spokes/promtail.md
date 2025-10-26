# Promtail — Spoke

**Status:** 🟢 Functional
**Phase:** 2
**Hostname:** N/A (log collector, no UI)
**Dependencies:** loki, docker-socket-proxy

## Purpose

Promtail collects logs from all Datamancy Docker containers using service discovery, enriches them with metadata labels, and ships them to Loki for aggregation and querying.

## Configuration

**Image:** `grafana/promtail:3.2.1`
**Volumes:**
- `./configs/promtail/promtail.yml:/etc/promtail/promtail.yml:ro` (configuration)
- `/var/log:/var/log:ro` (system logs, optional)
- `/run/user/${UID}/docker.sock:/var/run/docker.sock:ro` (Docker socket for service discovery)
**Networks:** backend, socket
**Ports:** 9080 (internal, HTTP server for metrics)

### Key Settings

Client:
- Push to `http://loki:3100/loki/api/v1/push`

Service Discovery:
- Docker SD via Unix socket
- Refresh interval: 5s
- Filter: Only containers with label `com.docker.compose.project=datamancy`

Relabeling:
- `container`: Container name (without leading slash)
- `container_id`: Full container ID
- `service`: Docker Compose service name
- `project`: Docker Compose project name (always "datamancy")
- `image`: Image digest
- `network_mode`: Docker network mode

### Fingerprint Inputs

- Image digest: `grafana/promtail:3.2.1`
- Config dir: `configs/promtail/` (promtail.yml)
- Compose stanza: promtail service block in docker-compose.yml

## Access

N/A - Promtail is a background collector with no user-facing interface. Metrics available at `http://promtail:9080/metrics` for Prometheus scraping.

## Runbook

### Start/Stop

```bash
docker compose --profile observability up -d promtail
docker compose stop promtail
```

### Logs

```bash
docker compose logs -f promtail
```

### Verify Operation

Check Promtail is discovering and shipping logs:

```bash
# Check Promtail logs for discovered targets
docker compose logs promtail | grep "Discoverer"

# Query Loki to see if logs are arriving
curl -G http://localhost:3100/loki/api/v1/label/service/values
# Should return: ["grafana", "prometheus", "loki", ...]
```

### Common Issues

**Symptom:** No logs appearing in Loki
**Cause:** Promtail can't connect to Docker socket or Loki
**Fix:** Verify Docker socket path is correct for rootless Docker, check Promtail can reach Loki

**Symptom:** Only some containers' logs collected
**Cause:** Label filter excludes containers
**Fix:** Verify containers have `com.docker.compose.project=datamancy` label

**Symptom:** High memory/CPU usage
**Cause:** Too many log streams or high log volume
**Fix:** Add more specific filters in scrape_configs, adjust batch settings

## Docker Service Discovery

Promtail automatically discovers containers matching:
- Label: `com.docker.compose.project=datamancy`
- Extracts labels: service, container, image, etc.
- Tails container logs via Docker API

No manual configuration needed when adding new services to docker-compose.yml!

## Testing

**Smoke test:** Indirectly tested via Loki integration
- Loki datasource accessible in Grafana
- Logs visible when querying `{service="grafana"}`

**Last pass:** 2025-10-26T06:55:23+00:00 (via loki tests)
**Artifacts:** `data/tests/loki/`

## Related

- Target: [Loki](loki.md) (log aggregation backend)
- Monitored by: [Prometheus](prometheus.md) (Promtail metrics)
- Dependencies: [Docker Socket Proxy](docker-socket-proxy.md)
- Upstream docs: https://grafana.com/docs/loki/latest/send-data/promtail/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** TBD (run docs-indexer)

**Update 2025-10-26:** Added automated test coverage. Service verified functional via integration tests.

