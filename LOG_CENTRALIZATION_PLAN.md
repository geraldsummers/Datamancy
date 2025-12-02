# Log Centralization Plan for Datamancy
**Date:** 2025-12-02
**Goal:** Centralize all container logs with retention policies, searchability, and backup integration

---

## Architecture: Grafana Loki Stack

```
┌─────────────────────────────────────────────────────────────┐
│                    55 Docker Containers                     │
│              (stdout/stderr → Docker logging)               │
└─────────────────────────┬───────────────────────────────────┘
                          │ promtail scrapes
                          ↓
                  ┌───────────────┐
                  │   Promtail    │ ← Log collector/shipper
                  │  (container)  │    - Scrapes Docker socket
                  └───────┬───────┘    - Parses JSON logs
                          │            - Adds labels
                          │ push
                          ↓
                  ┌───────────────┐
                  │     Loki      │ ← Log aggregation DB
                  │  (container)  │    - Indexes by labels
                  └───────┬───────┘    - Chunks storage
                          │            - Retention policies
                          │
            ┌─────────────┼─────────────┐
            │             │             │
            ↓             ↓             ↓
      ┌─────────┐   ┌─────────┐   ┌──────────┐
      │ Grafana │   │  Probe  │   │  Backup  │
      │   UI    │   │  Orch.  │   │  Script  │
      └─────────┘   └─────────┘   └──────────┘
      Query logs    Alert on       Archive logs
                    patterns       to off-site
```

---

## Why Loki (Not ELK/Fluentd)?

| Factor | Loki | Elasticsearch | Fluentd |
|--------|------|---------------|---------|
| **Memory** | ~200MB | ~2-4GB | ~100MB |
| **Disk** | Efficient (chunks) | Index-heavy | N/A (shipper) |
| **Grafana** | Native | Plugin | Plugin |
| **Query** | LogQL (Prometheus-like) | DSL | N/A |
| **Complexity** | Low | High | Medium |
| **Kotlin-friendly** | REST API | REST API | N/A |

**Decision:** Loki + Promtail = lightweight, integrates with existing Grafana, LogQL is easy

---

## Implementation

### 1. Add Loki + Promtail Services

Add to `docker-compose.yml`:

```yaml
  loki:
    image: grafana/loki:2.9.3
    container_name: loki
    restart: unless-stopped
    profiles:
      - bootstrap
      - infrastructure
    networks:
      - backend
    ports:
      - "3100:3100"  # Internal only, not exposed via Caddy
    volumes:
      - ${VOLUMES_ROOT}/loki_data:/loki
      - ./configs/infrastructure/loki/loki-config.yaml:/etc/loki/config.yaml:ro
    command: -config.file=/etc/loki/config.yaml
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:3100/ready"]
      interval: 30s
      timeout: 10s
      retries: 3
    deploy:
      resources:
        limits:
          memory: 512M
          cpus: '0.5'

  promtail:
    image: grafana/promtail:2.9.3
    container_name: promtail
    restart: unless-stopped
    profiles:
      - bootstrap
      - infrastructure
    networks:
      - backend
    volumes:
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./configs/infrastructure/promtail/promtail-config.yaml:/etc/promtail/config.yaml:ro
    command: -config.file=/etc/promtail/config.yaml
    depends_on:
      loki:
        condition: service_healthy
    deploy:
      resources:
        limits:
          memory: 256M
          cpus: '0.25'
```

### 2. Loki Configuration

Create `configs.templates/infrastructure/loki/loki-config.yaml`:

```yaml
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  instance_addr: 127.0.0.1
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

# Retention and compaction
limits_config:
  retention_period: 30d  # Keep logs for 30 days
  enforce_metric_name: false
  reject_old_samples: true
  reject_old_samples_max_age: 168h
  ingestion_rate_mb: 16
  ingestion_burst_size_mb: 32
  per_stream_rate_limit: 12MB
  per_stream_rate_limit_burst: 15MB

# Compaction to reduce storage
compactor:
  working_directory: /loki/compactor
  shared_store: filesystem
  compaction_interval: 10m
  retention_enabled: true
  retention_delete_delay: 2h
  retention_delete_worker_count: 150

schema_config:
  configs:
    - from: 2024-01-01
      store: boltdb-shipper
      object_store: filesystem
      schema: v11
      index:
        prefix: index_
        period: 24h

ruler:
  storage:
    type: local
    local:
      directory: /loki/rules
  rule_path: /loki/rules-temp
  alertmanager_url: http://localhost:9093
  ring:
    kvstore:
      store: inmemory
  enable_api: true

# Query performance
query_range:
  align_queries_with_step: true
  max_retries: 5
  cache_results: true

frontend:
  compress_responses: true

querier:
  max_concurrent: 4
```

### 3. Promtail Configuration

Create `configs.templates/infrastructure/promtail/promtail-config.yaml`:

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  # Scrape all Docker container logs
  - job_name: docker
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 5s
    relabel_configs:
      # Container name as label
      - source_labels: ['__meta_docker_container_name']
        regex: '/(.*)'
        target_label: 'container'

      # Container ID
      - source_labels: ['__meta_docker_container_id']
        target_label: 'container_id'

      # Image name
      - source_labels: ['__meta_docker_container_image']
        target_label: 'image'

      # Compose service name
      - source_labels: ['__meta_docker_container_label_com_docker_compose_service']
        target_label: 'service'

      # Compose project
      - source_labels: ['__meta_docker_container_label_com_docker_compose_project']
        target_label: 'project'

      # Log path
      - source_labels: ['__meta_docker_container_log_stream']
        target_label: 'stream'

    pipeline_stages:
      # Parse JSON logs from Docker
      - json:
          expressions:
            log: log
            stream: stream
            time: time

      # Extract timestamp
      - timestamp:
          source: time
          format: RFC3339Nano

      # Output the log line
      - output:
          source: log

      # Extract log levels (ERROR, WARN, INFO, DEBUG)
      - regex:
          expression: '(?P<level>(ERROR|WARN|INFO|DEBUG|FATAL|TRACE))'

      - labels:
          level:

      # Extract HTTP status codes
      - regex:
          expression: '(?P<http_status>\d{3})'

      - labels:
          http_status:
```

### 4. Configure Grafana Data Source

Create `configs.templates/applications/grafana/provisioning/datasources/loki.yaml`:

```yaml
apiVersion: 1

datasources:
  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
    isDefault: false
    jsonData:
      maxLines: 1000
      derivedFields:
        # Link to trace if trace_id exists
        - datasourceUid: tempo  # If you add Tempo later
          matcherRegex: "trace_id=(\\w+)"
          name: TraceID
          url: "$${__value.raw}"
    editable: true
```

### 5. Update Volume Definitions

Add to `docker-compose.yml` volumes section:

```yaml
volumes:
  loki_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: ${VOLUMES_ROOT}/loki_data
```

### 6. Remove Per-Service Log Rotation

**Before:** Each service with:
```yaml
logging:
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"
```

**After:** Let Docker use default JSON logging, Promtail scrapes, Loki manages retention:
```yaml
# No logging: section needed
# Loki handles retention via retention_period: 30d
```

---

## Retention Policy Strategy

### Default: 30 Days for All Logs
```yaml
# In loki-config.yaml
limits_config:
  retention_period: 30d
```

### Per-Service Custom Retention (via Loki rules)

Create `configs.templates/infrastructure/loki/rules/retention.yaml`:

```yaml
# Keep critical service logs longer
- match: '{service="postgres"}'
  retention: 90d

- match: '{service="authelia"}'
  retention: 90d

- match: '{service="vllm"}'
  retention: 60d

# Short retention for noisy services
- match: '{service="caddy"}'
  retention: 14d

- match: '{service="probe-orchestrator"}'
  retention: 7d

# Keep errors longer than info logs
- match: '{level="ERROR"}'
  retention: 90d

- match: '{level="WARN"}'
  retention: 60d

- match: '{level="INFO"}'
  retention: 30d
```

### Backup Integration

Update `scripts/backup-databases.main.kts` to include logs:

```kotlin
fun backupLogs(backupDir: File) {
    info("Backing up Loki logs...")
    val lokiBackupDir = File(backupDir, "loki")
    lokiBackupDir.mkdirs()

    // Stop Loki to ensure consistent backup
    exec("docker", "stop", "loki")

    // Copy Loki data directory
    exec("rsync", "-av",
         "${System.getenv("VOLUMES_ROOT")}/loki_data/",
         lokiBackupDir.absolutePath)

    // Restart Loki
    exec("docker", "start", "loki")

    info("Loki logs backed up: ${lokiBackupDir.absolutePath}")
}
```

**Or** use Loki's built-in export:
```kotlin
fun exportLogsToArchive(start: String, end: String, output: File) {
    // Export all logs for date range
    exec("curl",
         "http://localhost:3100/loki/api/v1/query_range",
         "-G",
         "--data-urlencode", "query={job=\"docker\"}",
         "--data-urlencode", "start=$start",
         "--data-urlencode", "end=$end",
         "-o", output.absolutePath)
}
```

---

## Query Examples (LogQL)

### In Grafana Explore:

```logql
# All logs from PostgreSQL
{service="postgres"}

# Errors from any service
{job="docker"} |= "ERROR"

# vLLM request latency over 1s
{service="vllm"} | json | duration > 1s

# Failed login attempts
{service="authelia"} |= "authentication failed"

# Database connection errors
{service=~"postgres|mariadb"} |= "connection refused"

# Rate of 5xx errors from Caddy
rate({service="caddy"} | json | http_status >= 500 [5m])

# Top 10 noisiest containers by log volume
topk(10, sum by (container) (count_over_time({job="docker"}[1h])))
```

---

## Alerting Integration

Create `configs.templates/infrastructure/loki/rules/alerts.yaml`:

```yaml
groups:
  - name: datamancy-alerts
    interval: 1m
    rules:
      # Alert on high error rate
      - alert: HighErrorRate
        expr: |
          rate({job="docker", level="ERROR"}[5m]) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High error rate detected"
          description: "{{ $labels.service }} is logging errors at {{ $value }} errors/sec"

      # Alert on database down
      - alert: DatabaseDown
        expr: |
          absent_over_time({service=~"postgres|mariadb"}[2m])
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Database stopped logging"
          description: "{{ $labels.service }} has not logged in 2 minutes"

      # Alert on authentication failures
      - alert: AuthenticationFailureSpike
        expr: |
          rate({service="authelia"} |= "authentication failed" [5m]) > 5
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High authentication failure rate"
          description: "{{ $value }} failed login attempts per second"

      # Alert on disk space (from container logs)
      - alert: LowDiskSpace
        expr: |
          {job="docker"} |~ "no space left on device"
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Disk space exhausted"
          description: "{{ $labels.service }} reported disk full"
```

---

## Probe Orchestrator Integration

Update `src/probe-orchestrator/src/main/kotlin/org/example/diagnostics/LogAnalyzer.kt`:

```kotlin
class LokiLogAnalyzer(
    private val lokiUrl: String = "http://loki:3100"
) {
    suspend fun queryErrors(service: String, since: Duration): List<LogEntry> {
        val query = """{service="$service", level="ERROR"}"""
        val url = "$lokiUrl/loki/api/v1/query_range" +
                  "?query=${URLEncoder.encode(query, "UTF-8")}" +
                  "&start=${Instant.now().minus(since).epochSecond}000000000"

        val response = httpClient.get(url).bodyAsText()
        return parseLogEntries(response)
    }

    suspend fun analyzePatterns(service: String): Map<String, Int> {
        // Get last hour of logs
        val logs = queryLogs(service, Duration.ofHours(1))

        // Extract error patterns
        return logs
            .filter { it.level == "ERROR" }
            .groupingBy { extractErrorPattern(it.message) }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(10)
            .toMap()
    }
}
```

---

## Storage Estimates

### Log Volume Assumptions:
- **55 services** × **~1 MB/day average** = **55 MB/day**
- **High-traffic services** (Caddy, vLLM): **10 MB/day each** = **20 MB/day**
- **Total estimate:** ~**100 MB/day** = **3 GB/month**

### Retention Scenarios:

| Retention | Uncompressed | Compressed (Loki) | Cost |
|-----------|-------------|-------------------|------|
| 7 days | 700 MB | ~200 MB | Minimal |
| 30 days | 3 GB | ~800 MB | Low |
| 90 days | 9 GB | ~2.4 GB | Medium |
| 1 year | 36 GB | ~9 GB | High |

**Recommendation:** 30-day retention = ~1GB disk space (negligible)

---

## Migration Plan

### Phase 1: Deploy Loki (No Impact)
```bash
# Add Loki + Promtail to docker-compose.yml
# Generate configs
kotlin scripts/process-config-templates.main.kts

# Start log stack
docker compose --profile infrastructure up -d loki promtail

# Verify ingestion
curl http://localhost:3100/ready
curl http://localhost:3100/metrics | grep loki_ingester_streams_created_total
```

### Phase 2: Configure Grafana Data Source
```bash
# Add Loki datasource config
# Restart Grafana
docker compose restart grafana

# Test in Grafana Explore
# Query: {job="docker"}
```

### Phase 3: Remove Per-Service Logging Config (Optional)
```bash
# No longer need `logging:` blocks in docker-compose
# Loki handles retention
# Can remove to simplify config
```

### Phase 4: Integrate with Probe Orchestrator
```kotlin
// Update probe-orchestrator to query Loki for diagnostics
// See LokiLogAnalyzer example above
```

---

## Benefits of Centralized Logs

### 1. **Unified Search**
```logql
# Find all errors across entire stack in last hour
{job="docker", level="ERROR"} | line_format "{{.service}}: {{.log}}"
```

### 2. **Correlation**
```logql
# Trace request through multiple services
{job="docker"} |= "request_id=abc123"
```

### 3. **Retention Control**
- Set policy once (30d default)
- Override per service or log level
- Automatic cleanup (no cron jobs)

### 4. **Backup Integration**
- Single directory: `${VOLUMES_ROOT}/loki_data`
- Or export via API to S3/archive

### 5. **Performance**
- No log file proliferation
- Indexed by labels (fast queries)
- Compressed chunks (efficient storage)

### 6. **Autonomous Diagnostics**
- Probe Orchestrator can query logs via API
- LLM can analyze aggregated error patterns
- Automatic incident correlation

---

## Next Steps

1. **Create Kotlin script:** `scripts/setup-log-centralization.main.kts`
   - Generates Loki/Promtail configs from templates
   - Updates docker-compose.yml
   - Creates Grafana datasource config
   - Sets up backup integration

2. **Add to stackops:** `kotlin scripts/stackops.main.kts up --with-logging`
   - Includes Loki/Promtail in bootstrap profile

3. **Document queries:** Create `docs/LOG_QUERIES.md` with common LogQL patterns

4. **Test retention:** Verify logs rotate after 30 days automatically

**Timeline:** 2-3 hours to implement, test, and document

---

## Alternative: Vector (Already in Stack?)

I noticed `vector-bootstrap` in your compose file. If you're using **Vector.dev** for data ingestion, you could also use it for log aggregation:

```toml
# Vector can replace Promtail
[sources.docker]
  type = "docker_logs"

[sinks.loki]
  type = "loki"
  endpoint = "http://loki:3100"
  labels = { job = "docker", container = "{{ container_name }}" }
```

**Vector vs Promtail:**
- Vector: More powerful (transform, route, aggregate), written in Rust
- Promtail: Purpose-built for Loki, simpler, lighter

**Recommendation:** Stick with Promtail for simplicity unless you need Vector's advanced features.
