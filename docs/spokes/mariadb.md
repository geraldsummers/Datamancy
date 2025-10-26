# MariaDB — Spoke

**Status:** 🟢 Functional
**Phase:** 4
**Hostname:** `mariadb:3306` (internal only)
**Dependencies:** None

## Purpose

MariaDB provides relational database storage for the Datamancy stack, optimized for transactional workloads, structured data, and SQL queries.

## Configuration

**Image:** `mariadb:11.2`
**Volumes:**
- `mariadb_data:/var/lib/mysql` (database files)
- `./configs/mariadb:/docker-entrypoint-initdb.d:ro` (initialization scripts)
**Networks:** backend
**Ports:** 3306 (MySQL protocol) - internal only

### Key Settings

Database:
- Root password: `${MARIADB_ROOT_PASSWORD}` (default: root_password_change_me)
- Database: `datamancy`
- User: `datamancy`
- Password: `${MARIADB_PASSWORD}` (default: datamancy_password_change_me)

Tables:
- `metrics`: Time-series metrics with JSON labels
- `events`: Event log with JSON data

### Fingerprint Inputs

- Image digest: `mariadb:11.2`
- Environment variables (passwords, database name)
- Initialization SQL: configs/mariadb/init.sql
- Compose stanza: mariadb service block

## Access

- **Internal URL:** `mariadb:3306`
- **Auth:** Username/password authentication
- **Query:** `mariadb -h mariadb -u datamancy -p datamancy`

## Schema

### metrics table
```sql
CREATE TABLE metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metric_name VARCHAR(255) NOT NULL,
    metric_value DOUBLE NOT NULL,
    labels JSON,
    INDEX idx_timestamp (timestamp),
    INDEX idx_metric_name (metric_name)
);
```

### events table
```sql
CREATE TABLE events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_type VARCHAR(100) NOT NULL,
    event_data JSON,
    source VARCHAR(255),
    INDEX idx_timestamp (timestamp),
    INDEX idx_event_type (event_type)
);
```

## Runbook

### Start/Stop

```bash
docker compose --profile datastores up -d mariadb
docker compose stop mariadb
```

### Logs

```bash
docker compose logs -f mariadb
```

### Connect

```bash
# From host
docker exec -it mariadb mariadb -u datamancy -p datamancy

# From another container on backend network
mariadb -h mariadb -u datamancy -pdatamancy_password_change_me datamancy
```

### Query Data

```bash
# Count metrics
docker exec mariadb mariadb -u datamancy -pdatamancy_password_change_me datamancy \
  -e "SELECT COUNT(*) FROM metrics;"

# View recent events
docker exec mariadb mariadb -u datamancy -pdatamancy_password_change_me datamancy \
  -e "SELECT * FROM events ORDER BY timestamp DESC LIMIT 10;"

# Query metrics with JSON
docker exec mariadb mariadb -u datamancy -pdatamancy_password_change_me datamancy \
  -e "SELECT metric_name, metric_value, JSON_EXTRACT(labels, '$.host') as host FROM metrics;"
```

### Backup

```bash
# Dump database
docker exec mariadb mariadb-dump -u root -proot_password_change_me datamancy > backup.sql

# Restore database
docker exec -i mariadb mariadb -u root -proot_password_change_me datamancy < backup.sql
```

### Common Issues

**Symptom:** "Can't connect to MySQL server"
**Cause:** MariaDB not started or wrong hostname
**Fix:** Verify service is running: `docker ps | grep mariadb`

**Symptom:** "Access denied for user"
**Cause:** Wrong credentials
**Fix:** Check environment variables in docker-compose.yml

**Symptom:** "Unknown database 'datamancy'"
**Cause:** Database not initialized
**Fix:** Restart container to run init scripts: `docker compose restart mariadb`

**Symptom:** Slow queries
**Cause:** Missing indexes or too much data
**Fix:** Add indexes: `CREATE INDEX idx_name ON table(column);`

## Related

- Upstream docs: https://mariadb.com/kb/en/documentation/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** TBD
