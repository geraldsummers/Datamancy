#!/usr/bin/env kotlin

/**
 * Log Centralization Setup for Datamancy
 *
 * Sets up Grafana Loki + Promtail for centralized log aggregation
 *
 * What it does:
 * 1. Creates Loki and Promtail configuration files
 * 2. Creates volume directories
 * 3. Adds Loki datasource to Grafana
 * 4. Provides docker-compose snippet for services
 *
 * Usage:
 *   kotlin scripts/setup-log-centralization.main.kts [--dry-run]
 */

import java.io.File
import kotlin.system.exitProcess

// ANSI colors
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val RED = "\u001B[31m"
val BLUE = "\u001B[34m"
val RESET = "\u001B[0m"

fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg", YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)
fun debug(msg: String) = log("[DEBUG] $msg", BLUE)

val dryRun = args.contains("--dry-run")

fun exec(vararg cmd: String): Pair<String, Int> {
    if (dryRun) {
        info("DRY-RUN: ${cmd.joinToString(" ")}")
        return "" to 0
    }
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return output to exitCode
}

fun createDir(path: String) {
    val dir = File(path)
    if (dir.exists()) {
        debug("Directory already exists: $path")
        return
    }
    if (dryRun) {
        info("DRY-RUN: Would create directory: $path")
    } else {
        dir.mkdirs()
        info("Created directory: $path")
    }
}

fun writeFile(path: String, content: String) {
    val file = File(path)
    if (dryRun) {
        info("DRY-RUN: Would write file: $path (${content.length} bytes)")
        return
    }
    file.parentFile?.mkdirs()
    file.writeText(content)
    info("Created file: $path")
}

// Read .env for VOLUMES_ROOT
val envFile = File(".env")
if (!envFile.exists()) {
    error(".env file not found. Run from project root.")
    exitProcess(1)
}

val volumesRoot = envFile.readLines()
    .find { it.startsWith("VOLUMES_ROOT=") }
    ?.substringAfter("=")
    ?.trim()
    ?: "./volumes"

info("Using VOLUMES_ROOT: $volumesRoot")

// 1. Create volume directories
info("\n=== Creating volume directories ===")
createDir("$volumesRoot/loki_data")
createDir("$volumesRoot/loki_data/chunks")
createDir("$volumesRoot/loki_data/rules")
createDir("configs.templates/infrastructure/loki")
createDir("configs.templates/infrastructure/promtail")
createDir("configs.templates/applications/grafana/provisioning/datasources")

// 2. Create Loki configuration
info("\n=== Creating Loki configuration ===")
val lokiConfig = """
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
""".trimIndent()

writeFile("configs.templates/infrastructure/loki/loki-config.yaml", lokiConfig)

// 3. Create Promtail configuration
info("\n=== Creating Promtail configuration ===")
val promtailConfig = """
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

      # Log stream (stdout/stderr)
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
""".trimIndent()

writeFile("configs.templates/infrastructure/promtail/promtail-config.yaml", promtailConfig)

// 4. Create Grafana datasource configuration
info("\n=== Creating Grafana Loki datasource ===")
val grafanaLokiDatasource = """
apiVersion: 1

datasources:
  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
    isDefault: false
    jsonData:
      maxLines: 1000
    editable: true
""".trimIndent()

writeFile("configs.templates/applications/grafana/provisioning/datasources/loki.yaml", grafanaLokiDatasource)

// 5. Print docker-compose snippet
info("\n=== Docker Compose Configuration ===")
println("""
${YELLOW}Add the following to docker-compose.yml:${RESET}

${BLUE}# In volumes section:${RESET}
  loki_data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: \${VOLUMES_ROOT}/loki_data

${BLUE}# In services section:${RESET}
  loki:
    image: grafana/loki:2.9.3
    container_name: loki
    restart: unless-stopped
    profiles:
      - bootstrap
      - infrastructure
    networks:
      - backend
    volumes:
      - loki_data:/loki
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

${GREEN}=== Setup Complete! ===${RESET}

${YELLOW}Next steps:${RESET}
1. Add the above YAML to docker-compose.yml
2. Generate configs: ${BLUE}kotlin scripts/process-config-templates.main.kts${RESET}
3. Start Loki: ${BLUE}docker compose up -d loki promtail${RESET}
4. Restart Grafana: ${BLUE}docker compose restart grafana${RESET}
5. Access Grafana → Explore → Select "Loki" datasource
6. Query logs: ${BLUE}{job="docker"}${RESET}

${YELLOW}Storage estimate:${RESET}
- ~100 MB/day for 55 services
- 30-day retention = ~1 GB compressed
- Location: $volumesRoot/loki_data

${YELLOW}Common queries:${RESET}
- All logs from service: ${BLUE}{service="postgres"}${RESET}
- Errors only: ${BLUE}{job="docker", level="ERROR"}${RESET}
- Failed logins: ${BLUE}{service="authelia"} |= "authentication failed"${RESET}
- Last 5 minutes: ${BLUE}{service="vllm"} [5m]${RESET}

${YELLOW}Retention policy:${RESET}
- Default: 30 days (configured in loki-config.yaml)
- Edit retention_period for different durations
- Logs automatically deleted after retention period

${GREEN}Documentation: LOG_CENTRALIZATION_PLAN.md${RESET}
""".trimIndent())

if (dryRun) {
    warn("\nDRY-RUN mode - no files were created")
} else {
    info("\n✅ Configuration files created successfully!")
}
