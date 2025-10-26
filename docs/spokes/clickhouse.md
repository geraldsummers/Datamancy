# ClickHouse — Spoke

**Status:** 🟢 Functional
**Phase:** 4
**Hostname:** `clickhouse.stack.local` (web UI), `clickhouse:8123` (HTTP), `clickhouse:9000` (TCP)
**Dependencies:** None

## Purpose

ClickHouse provides columnar OLAP database storage for the Datamancy stack, optimized for analytics, aggregations, and time-series data with billions of rows.

## Configuration

**Image:** `clickhouse/clickhouse-server:23.12`
**Volumes:**
- `clickhouse_data:/var/lib/clickhouse` (database files)
- `./configs/clickhouse:/etc/clickhouse-server/config.d:ro` (configuration)
**Networks:** backend, frontend
**Ports:**
- 8123 (HTTP interface) - exposed via Caddy
- 9000 (Native TCP) - internal only
- 9004 (MySQL compatibility) - internal only
- 9005 (PostgreSQL compatibility) - internal only

### Key Settings

Database:
- User: `datamancy`
- Password: `${CLICKHOUSE_PASSWORD}` (default: datamancy_password_change_me)
- Default database: `datamancy`

### Fingerprint Inputs

- Image digest: `clickhouse/clickhouse-server:23.12`
- Environment variables (user, password, database)
- Configuration XML: configs/clickhouse/config.xml
- Compose stanza: clickhouse service block

## Access

- **Web UI:** `https://clickhouse.stack.local/play`
- **HTTP API:** `http://clickhouse:8123`
- **Native TCP:** `clickhouse:9000`
- **Auth:** Username/password authentication

## Query Examples

### HTTP Interface

```bash
# Ping
curl http://clickhouse:8123/ping

# Query
curl 'http://clickhouse:8123/?user=datamancy&password=datamancy_password_change_me' \
  --data-binary "SELECT version();"

# Create table
curl 'http://clickhouse:8123/?user=datamancy&password=datamancy_password_change_me' \
  --data-binary "CREATE TABLE IF NOT EXISTS metrics (
    timestamp DateTime,
    metric_name String,
    metric_value Float64,
    labels Map(String, String)
  ) ENGINE = MergeTree()
  ORDER BY (timestamp, metric_name);"

# Insert data
curl 'http://clickhouse:8123/?user=datamancy&password=datamancy_password_change_me' \
  --data-binary "INSERT INTO metrics VALUES
    (now(), 'cpu_usage', 45.2, {'host': 'server-01'}),
    (now(), 'memory_usage', 2048, {'host': 'server-01'});"

# Query data
curl 'http://clickhouse:8123/?user=datamancy&password=datamancy_password_change_me' \
  --data-binary "SELECT * FROM metrics ORDER BY timestamp DESC LIMIT 10;"
```

### Native Client

```bash
# Connect via clickhouse-client
docker exec -it clickhouse clickhouse-client \
  --user datamancy --password datamancy_password_change_me

# Or from host (if clickhouse-client installed)
clickhouse-client --host clickhouse --port 9000 \
  --user datamancy --password datamancy_password_change_me
```

### Aggregation Queries

```sql
-- Count by metric name
SELECT
    metric_name,
    count() as cnt,
    avg(metric_value) as avg_value
FROM metrics
GROUP BY metric_name;

-- Time-series aggregation
SELECT
    toStartOfHour(timestamp) as hour,
    metric_name,
    avg(metric_value) as avg_value,
    max(metric_value) as max_value
FROM metrics
GROUP BY hour, metric_name
ORDER BY hour DESC;

-- Top values
SELECT
    metric_name,
    metric_value,
    labels
FROM metrics
ORDER BY metric_value DESC
LIMIT 10;
```

## Runbook

### Start/Stop

```bash
docker compose --profile datastores up -d clickhouse
docker compose stop clickhouse
```

### Logs

```bash
docker compose logs -f clickhouse
```

### Web UI Access

Navigate to: `https://clickhouse.stack.local/play`

Use credentials:
- User: `datamancy`
- Password: `datamancy_password_change_me`

### Query Performance

```sql
-- Check query execution time
SELECT
    query,
    query_duration_ms,
    read_rows,
    read_bytes
FROM system.query_log
WHERE type = 'QueryFinish'
ORDER BY event_time DESC
LIMIT 10;

-- Check table sizes
SELECT
    database,
    table,
    formatReadableSize(sum(bytes)) as size,
    sum(rows) as rows
FROM system.parts
WHERE active
GROUP BY database, table
ORDER BY sum(bytes) DESC;
```

### Backup

```bash
# Backup database
docker exec clickhouse clickhouse-client \
  --user datamancy --password datamancy_password_change_me \
  --query "BACKUP DATABASE datamancy TO Disk('backups', 'datamancy_backup.zip');"

# Restore database
docker exec clickhouse clickhouse-client \
  --user datamancy --password datamancy_password_change_me \
  --query "RESTORE DATABASE datamancy FROM Disk('backups', 'datamancy_backup.zip');"
```

### Common Issues

**Symptom:** "Connection refused"
**Cause:** ClickHouse not started or wrong port
**Fix:** Verify service is running: `docker ps | grep clickhouse`

**Symptom:** "Authentication failed"
**Cause:** Wrong credentials
**Fix:** Check CLICKHOUSE_USER and CLICKHOUSE_PASSWORD environment variables

**Symptom:** "Table doesn't exist"
**Cause:** Table not created
**Fix:** Create table with proper ENGINE (MergeTree recommended)

**Symptom:** Slow queries on large tables
**Cause:** Poor table design or missing ORDER BY
**Fix:** Use appropriate ORDER BY clause in table definition for common query patterns

**Symptom:** "Too many parts"
**Cause:** Too frequent small inserts
**Fix:** Batch inserts or increase merge settings

**Symptom:** IPv6 binding errors
**Cause:** Container doesn't support IPv6
**Fix:** Use `<listen_host>0.0.0.0</listen_host>` in config (already fixed)

## Performance Tips

1. **Use appropriate ENGINE:** MergeTree for most use cases
2. **ORDER BY matters:** Put most filtered columns first
3. **Batch inserts:** Insert thousands of rows at once, not one by one
4. **Compression:** ClickHouse compresses data automatically
5. **Materialized views:** Pre-aggregate data for faster queries
6. **Partitioning:** Use PARTITION BY for time-series data

## Related

- Web UI: https://clickhouse.stack.local/play
- Upstream docs: https://clickhouse.com/docs/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** TBD
