# Kopia

**Service:** Kopia
**Phase:** 4 - Datastores & Backups
**Profile:** `backup`
**Hostname:** `kopia.stack.local`
**Port:** 51515

## Purpose

Kopia provides fast, encrypted, and deduplicated backups with restore capabilities. Backs up all critical data and configurations for disaster recovery.

## Configuration

- **Image:** `kopia/kopia:0.18.1`
- **Repository Type:** Filesystem
- **Location:** `./backups/` (local directory)
- **Encryption:** AES256-GCM-HMAC-SHA256
- **Compression:** Disabled by default

## Access

**URL:** https://kopia.stack.local

**Credentials:**
- **Username:** `admin`
- **Password:** Set via `KOPIA_SERVER_PASSWORD` env var (default: `changeme_admin_password`)

## Repository

Repository initialized with:
- **Hash:** BLAKE2B-256-128
- **Encryption:** AES256-GCM-HMAC-SHA256
- **Splitter:** DYNAMIC-4M-BUZHASH
- **Password:** Set via `KOPIA_PASSWORD` env var

## Backup Sources

Kopia has **read-only** access to:

**Data directories:**
- `/data` - Test artifacts, application data
- `/configs` - All service configurations

**Volume mounts:**
- `mariadb_data` → `/backup-sources/mariadb`
- `mongodb_data` → `/backup-sources/mongodb`
- `clickhouse_data` → `/backup-sources/clickhouse`
- `grafana_data` → `/backup-sources/grafana`
- `prometheus_data` → `/backup-sources/prometheus`
- `loki_data` → `/backup-sources/loki`

## Retention Policy

- **Annual snapshots:** 3
- **Monthly snapshots:** 24
- **Weekly snapshots:** 4
- **Daily snapshots:** 7
- **Hourly snapshots:** 48
- **Latest snapshots:** 10

## Maintenance

Kopia automatically performs:
- **Quick maintenance:** Every 1 hour
- **Full maintenance:** Every 24 hours

## Creating Snapshots

**Via CLI:**
```bash
# Backup configs and data
docker exec kopia kopia snapshot create /configs /data

# Backup specific volume
docker exec kopia kopia snapshot create /backup-sources/mariadb

# List snapshots
docker exec kopia kopia snapshot list
```

**Via UI:**
1. Visit https://kopia.stack.local
2. Login with admin credentials
3. Navigate to "Snapshots"
4. Click "New Snapshot" and select source

## Restoring Data

**Via CLI:**
```bash
# List snapshots to find snapshot ID
docker exec kopia kopia snapshot list

# Restore to temporary location
docker exec kopia kopia snapshot restore <snapshot-id> /tmp/restore

# Copy restored files to original location
docker cp kopia:/tmp/restore/. ./restored-data/
```

**Via UI:**
1. Navigate to "Snapshots"
2. Select snapshot to restore
3. Click "Restore"
4. Choose destination path

## Backup Schedule

**Recommended schedule:**
- **Hourly:** Application data (`/data`)
- **Daily:** Configurations (`/configs`)
- **Weekly:** All database volumes

**To schedule via cron:**
```bash
# Add to host crontab
0 * * * * docker exec kopia kopia snapshot create /data
0 2 * * * docker exec kopia kopia snapshot create /configs
0 3 * * 0 docker exec kopia kopia snapshot create /backup-sources/mariadb
```

## Restore Drills

**Monthly restore drill procedure:**
1. Select a random snapshot from last 7 days
2. Restore to temporary location
3. Verify file integrity and contents
4. Document results in `data/tests/restore-drill-YYYY-MM-DD.txt`
5. Clean up temporary files

## Troubleshooting

**Repository not connecting:**
- Check `/app/backups/` directory permissions
- Verify `KOPIA_PASSWORD` environment variable
- Review logs: `docker logs kopia`

**Backup failing:**
- Check available disk space in `./backups/`
- Verify source paths are mounted correctly
- Check permissions on source directories

**Slow backups:**
- Enable compression for better performance
- Increase cache size in Kopia settings
- Check disk I/O performance

**UI not accessible:**
- Verify Caddy routing: `docker logs caddy | grep kopia`
- Check Kopia is listening: `docker logs kopia | grep "listening"`
- Test direct connection: `curl http://localhost:51515`

## Security

- **Encryption:** AES256-GCM-HMAC-SHA256 (always on)
- **Password:** PBKDF2 key derivation (scrypt-65536-8-1)
- **Network:** Frontend (UI) + Backend (source access)
- **Capabilities:** DAC_OVERRIDE, DAC_READ_SEARCH, CHOWN, FOWNER (required for filesystem access)

## References

- [Kopia Documentation](https://kopia.io/docs/)
- [Repository Configuration](https://kopia.io/docs/reference/command-line/common/repository-create-filesystem/)
- [Maintenance](https://kopia.io/docs/advanced/maintenance/)
- [Encryption](https://kopia.io/docs/advanced/encryption/)
