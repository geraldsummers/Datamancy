# Benthos — Spoke

**Status:** 🟢 Functional
**Phase:** 8
**Hostname:** `benthos.stack.local`
**Dependencies:** Caddy
**Profile:** tools

## Purpose

Benthos is a high-performance, declarative data streaming service that connects various sources and sinks. It provides stream processing, transformation, and routing capabilities for event-driven architectures.

## Configuration

**Image:** `jeffail/benthos:4.38`
**Volumes:**
- `benthos_data:/benthos` (Runtime data and state)
- `./configs/benthos:/benthos/config:ro` (Configuration files)

**Networks:** frontend, backend
**Ports:** 4195 (HTTP API and metrics)

### Key Settings

```yaml
command: -c /benthos/config/benthos.yaml
user: "1000"  # Non-root
```

### Security Hardening (Phase 6)

- Runs as non-root user (UID 1000)
- Drops all capabilities
- Read-only config mount
- Behind Caddy reverse proxy with HTTPS

### Configuration File

Located at `configs/benthos/benthos.yaml`:

```yaml
http:
  enabled: true
  address: 0.0.0.0:4195
  debug_endpoints: true

input:
  label: "stdin_input"
  stdin:
    codec: lines

pipeline:
  threads: 1
  processors: []

output:
  label: "stdout_output"
  stdout:
    codec: lines

metrics:
  prometheus:
    push_url: ""
    push_interval: ""
    push_job_name: benthos

logger:
  level: INFO
  format: json
  add_timestamp: true
```

### Fingerprint Inputs

- Image: `jeffail/benthos:4.38`
- Config file: `configs/benthos/benthos.yaml`
- Compose stanza: `services.benthos`

## Access

- **URL:** `https://benthos.stack.local`
- **API Endpoints:**
  - `/ping` - Health check
  - `/ready` - Readiness check
  - `/metrics` - Prometheus metrics
  - `/stats` - Stream statistics
  - `/endpoints` - List all endpoints

## Runbook

### Start/Stop

```bash
# Start Benthos
docker compose --profile tools up -d benthos

# Stop
docker compose stop benthos
```

### Logs

```bash
docker compose logs -f benthos
```

### Configuration Validation

Validate config before deployment:

```bash
docker run --rm -v $(pwd)/configs/benthos:/config jeffail/benthos:4.38 \
  -c /config/benthos.yaml lint
```

### Common Issues

**Symptom:** "Failed to create input"
**Cause:** Input configuration error or missing dependencies
**Fix:** Validate config and check input connectivity:
```bash
docker compose logs benthos | grep -i error
```

**Symptom:** High memory usage
**Cause:** Large buffer sizes or memory-intensive processors
**Fix:** Tune buffer limits in config:
```yaml
buffer:
  memory:
    limit: 50000000  # 50MB
```

**Symptom:** Messages not flowing
**Cause:** Pipeline processor blocking or output unavailable
**Fix:** Check pipeline metrics at `https://benthos.stack.local/stats`

## Use Cases

### 1. Log Aggregation

Collect logs from multiple sources and route to Loki:

```yaml
input:
  http_server:
    path: /logs
    address: 0.0.0.0:4195

pipeline:
  processors:
    - bloblang: |
        root = {
          "timestamp": now(),
          "level": this.level,
          "message": this.message,
          "service": this.service,
        }

output:
  http_client:
    url: http://loki:3100/loki/api/v1/push
    verb: POST
```

### 2. Data Transformation

Transform JSON between different schemas:

```yaml
input:
  kafka:
    addresses: [ "kafka:9092" ]
    topics: [ "raw-events" ]

pipeline:
  processors:
    - bloblang: |
        root.id = this.event_id
        root.timestamp = this.created_at.parse_timestamp("2006-01-02")
        root.user = this.user_data.email

output:
  mongodb:
    url: mongodb://mongodb:27017
    database: events
    collection: processed
```

### 3. API Gateway / Webhook Proxy

Receive webhooks and fan out to multiple destinations:

```yaml
input:
  http_server:
    path: /webhook
    address: 0.0.0.0:4195

output:
  broker:
    pattern: fan_out
    outputs:
      - http_client:
          url: http://service1:8080/events
      - http_client:
          url: http://service2:8080/events
      - mongodb:
          url: mongodb://mongodb:27017
          database: webhooks
```

## Processors

Benthos supports many processors for data transformation:

- **bloblang:** Custom transformation language
- **jq:** JSON query language
- **awk:** Text processing
- **mapping:** Field remapping
- **compress/decompress:** gzip, zlib, etc.
- **dedupe:** Remove duplicates
- **filter:** Conditional message dropping
- **rate_limit:** Throttle message flow
- **sleep:** Add delays
- **split:** Split messages into batches

## Inputs

Supported input types:

- HTTP Server, AMQP, AWS Kinesis, AWS S3, AWS SQS
- Azure Blob Storage, Azure Queue Storage
- File, GCP Pub/Sub, HDFS, HTTP Client
- Kafka, MQTT, NATS, NSQ, Pulsar, RabbitMQ
- Redis Streams, Socket, SQL, Stdin, TCP, UDP, Webhook

## Outputs

Supported output types:

- AMQP, AWS DynamoDB, AWS Kinesis, AWS S3, AWS SQS
- Azure Blob Storage, Azure Queue Storage
- Cassandra, ClickHouse, Elasticsearch, File
- GCP Pub/Sub, HDFS, HTTP Client, Kafka, MongoDB
- MQTT, NATS, NSQ, Pulsar, Redis, Socket, SQL
- Stdout, TCP, UDP, WebSocket

## Monitoring

### Prometheus Metrics

Benthos exposes Prometheus metrics at `/metrics`:

```bash
curl -s https://benthos.stack.local/metrics | grep benthos_
```

### Add to Prometheus

Update `configs/prometheus/prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'benthos'
    static_configs:
      - targets: ['benthos:4195']
```

### Health Checks

```bash
# Liveness
curl https://benthos.stack.local/ping

# Readiness
curl https://benthos.stack.local/ready

# Statistics
curl https://benthos.stack.local/stats
```

## Testing

**Smoke test:** Visit `https://benthos.stack.local/ping`, verify "pong" response
**Integration tests:** `tests/specs/phase8-extended-apps.spec.ts`
**Last pass:** Check `data/tests/benthos/last_pass.json`

## Performance Tuning

### Threading

```yaml
pipeline:
  threads: 4  # Parallel processing
  processors:
    - bloblang: root = this.uppercase()
```

### Batching

```yaml
output:
  elasticsearch:
    urls: [ "http://elasticsearch:9200" ]
    batching:
      count: 100
      period: 5s
```

### Buffering

```yaml
buffer:
  memory:
    limit: 100000000  # 100MB
    batch_policy:
      count: 1000
      period: 1s
```

## Security Considerations

1. **Input authentication:** Add auth to HTTP server inputs
2. **TLS:** Enable TLS for Kafka, MQTT, and other inputs
3. **Secrets:** Use environment variables for credentials
4. **Rate limiting:** Protect inputs from abuse
5. **Metrics access:** Restrict access to metrics endpoint

## Related

- Dependencies: [Caddy](caddy.md)
- Integration: [Prometheus](prometheus.md), [Loki](loki.md), [MongoDB](mongodb.md), [ClickHouse](clickhouse.md)
- Alternative: [Apache Kafka](https://kafka.apache.org/), [Apache Flink](https://flink.apache.org/)
- Upstream docs: https://www.benthos.dev/docs/about

---

**Last updated:** 2025-10-27
**Last change fingerprint:** phase8-initial-implementation
