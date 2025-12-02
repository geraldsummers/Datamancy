# Datamancy Backup & Recovery Strategy

## Overview

Sovereign compute requires sovereign backups - no cloud dependencies.

**RTO (Recovery Time Objective):** 4 hours
**RPO (Recovery Point Objective):** 24 hours (daily backups)

---

## Backup Architecture

```
┌──────────────────────────────────────────────────────────┐
│                  Backup Orchestration                     │
│                  (Kopia + Kotlin Script)                  │
└──────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
   ┌────▼─────┐      ┌──────▼──────┐     ┌─────▼──────┐
   │ Database │      │   Volumes   │     │   Configs  │
   │  Dumps   │      │  Snapshots  │     │    Repo    │
   └──────────┘      └─────────────┘     └────────────┘
        │                   │                   │
        └───────────────────┴───────────────────┘
                            │
                   ┌────────▼─────────┐
                   │  Kopia Repository │
                   │   (deduplicated)  │
                   └───────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
   ┌────▼─────┐      ┌──────▼──────┐     ┌─────▼──────┐
   │  Local   │      │   Network   │     │   Offsite  │
   │ Storage  │      │    NAS      │     │    Drive   │
   └──────────┘      └─────────────┘     └────────────┘
```

---

## Critical Data Classification

### Tier 1: Irreplaceable (Daily + Incremental)
**Must back up frequently, test restores monthly**

- **PostgreSQL** - All databases (authelia, grafana, vaultwarden, openwebui, planka, synapse, mailu)
- **LDAP** - User directory and authentication data
- **Authelia** - OIDC state, 2FA secrets
- **Vaultwarden** - Password vault (encrypted but critical)
- **Mailu** - Email data (mailboxes, DKIM keys)
- **Git repos** - If self-hosting code

### Tier 2: Important (Daily)
**Can recreate but time-consuming**

- **Grafana** - Dashboards and alerts
- **Planka** - Project boards
- **BookStack** - Knowledge base
- **Open WebUI** - Chat history, custom models
- **JupyterHub** - Notebooks and user work
- **Seafile** - File storage
- **Synapse** - Matrix chat history
- **Mastodon** - Social media content

### Tier 3: Recoverable (Weekly)
**Can rebuild from configs**

- **Redis/Valkey** - Cache (ephemeral)
- **ClickHouse** - Analytics (can repopulate)
- **Qdrant** - Vectors (can re-embed)
- **LiteLLM** - Config and logs
- **Caddy** - Certificates (Let's Encrypt can reissue)

### Tier 4: Expendable (No Backup)
**Rebuild from images/configs**

- **Container logs** - Rotate and discard
- **Temporary files** - Ephemeral
- **Model caches** - Re-download if needed

---

## Backup Schedule

```kotlin
// Automated via cron + Kotlin script

// Daily 02:00 UTC
- PostgreSQL full dump (all databases)
- LDAP slapcat export
- Mailu mailbox snapshot
- Vaultwarden vault export
- Volume snapshots (incremental via Kopia)

// Weekly Sunday 04:00 UTC
- Full repository verification
- Test restore of random database
- Offsite sync (rsync to external drive)

// Monthly 1st Sunday
- Full disaster recovery drill
- Restore to test environment
- Verify all services come up clean
```

---

## Implementation

### 1. Database Backup Script

Create: `scripts/backup-databases.main.kts`

```kotlin
#!/usr/bin/env kotlin

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
val backupDir = "/volumes/backups/databases/$timestamp"

fun exec(cmd: String) = ProcessBuilder("sh", "-c", cmd)
    .redirectErrorStream(true)
    .start()
    .also { it.waitFor() }
    .inputStream.readBytes().toString(Charsets.UTF_8)

// PostgreSQL - all databases
listOf("authelia", "grafana", "vaultwarden", "openwebui", "planka", "synapse", "mailu").forEach { db ->
    println("Backing up postgres/$db...")
    exec("docker exec postgres pg_dump -U admin -Fc $db > $backupDir/postgres_$db.dump")
}

// LDAP export
println("Backing up LDAP...")
exec("docker exec ldap slapcat -n 1 -l /tmp/ldap.ldif")
exec("docker cp ldap:/tmp/ldap.ldif $backupDir/ldap.ldif")

// MariaDB
listOf("bookstack", "seafile").forEach { db ->
    println("Backing up mariadb/$db...")
    exec("docker exec mariadb mariadb-dump -u root -p\$STACK_ADMIN_PASSWORD $db > $backupDir/mariadb_$db.sql")
}

// Vaultwarden SQLite (if using) - now Postgres but keep for reference
// exec("docker exec vaultwarden sqlite3 /data/db.sqlite3 '.backup /tmp/vw.db'")
// exec("docker cp vaultwarden:/tmp/vw.db $backupDir/vaultwarden.db")

println("Database backups complete: $backupDir")
```

### 2. Kopia Configuration

Update: `configs/applications/kopia/init-kopia.sh`

```bash
#!/bin/sh
set -e

REPO_PATH=${KOPIA_REPO_PATH:-/repository}
KOPIA_PASSWORD=${KOPIA_PASSWORD:-changeme}

# Initialize repository if not exists
if ! kopia repository status >/dev/null 2>&1; then
    echo "Initializing Kopia repository..."
    kopia repository create filesystem --path="$REPO_PATH" --password="$KOPIA_PASSWORD"
fi

# Connect to repository
kopia repository connect filesystem --path="$REPO_PATH" --password="$KOPIA_PASSWORD"

# Configure snapshot policies
kopia policy set --global \
    --compression=zstd \
    --keep-latest=30 \
    --keep-daily=7 \
    --keep-weekly=4 \
    --keep-monthly=12 \
    --keep-annual=3

# Create snapshot manifests for critical volumes
kopia snapshot create /app/volumes/postgres_data --description="PostgreSQL data"
kopia snapshot create /app/volumes/ldap_data --description="LDAP directory"
kopia snapshot create /app/volumes/vaultwarden_data --description="Vaultwarden vault"
kopia snapshot create /app/volumes/mailu_data --description="Mailu mailboxes"

# Start Kopia server UI
exec kopia server start \
    --address=0.0.0.0:51515 \
    --server-username=admin \
    --server-password="$KOPIA_PASSWORD" \
    --without-password
```

### 3. Restore Procedure

Create: `scripts/restore-from-backup.main.kts`

```kotlin
#!/usr/bin/env kotlin

// Interactive restore wizard

println("Datamancy Disaster Recovery")
println("===========================\n")

println("Available backup snapshots:")
exec("kopia snapshot list")

print("\nEnter snapshot ID to restore: ")
val snapshotId = readLine()!!.trim()

print("Restore destination [./restore]: ")
val dest = readLine()?.takeIf { it.isNotBlank() } ?: "./restore"

println("\nRestoring to $dest...")
exec("kopia snapshot restore $snapshotId $dest")

println("""
Restore complete!

Next steps:
1. Stop running stack: docker compose down
2. Move restored data to volumes/
3. Restore databases:
   - pg_restore -U admin -d dbname < postgres_dbname.dump
   - ldapadd -x -D "cn=admin,dc=stack,dc=local" -w PASSWORD -f ldap.ldif
4. Start stack: docker compose up -d
5. Verify services with probe-orchestrator
""".trimIndent())
```

### 4. Automated Backup Cron

```bash
# Add to host crontab (or systemd timer)

# Daily backups at 02:00 UTC
0 2 * * * cd /path/to/Datamancy && kotlin scripts/backup-databases.main.kts >> /var/log/datamancy-backup.log 2>&1

# Weekly verification Sunday 04:00
0 4 * * 0 cd /path/to/Datamancy && kotlin scripts/verify-backups.main.kts >> /var/log/datamancy-verify.log 2>&1

# Monthly DR drill (first Sunday)
0 6 1-7 * 0 cd /path/to/Datamancy && kotlin scripts/dr-drill.main.kts >> /var/log/datamancy-dr.log 2>&1
```

---

## Verification & Testing

### Daily Automated Checks
```kotlin
// scripts/verify-backups.main.kts

- Check backup file sizes (detect 0-byte failures)
- Verify Kopia repository integrity
- Test PostgreSQL dump file can be parsed
- Confirm backup age < 36 hours
- Alert if verification fails
```

### Monthly DR Drill
```bash
# Full restoration to test environment
1. Provision clean VM/container
2. Restore from latest Kopia snapshot
3. Restore database dumps
4. Start stack with test config
5. Run automated test suite
6. Verify all services pass health checks
7. Document time to recovery (measure against RTO)
```

---

## Offsite Replication

### Option A: Rsync to External Drive
```bash
# Weekly sync to USB drive or network mount
rsync -avz --delete \
    /volumes/kopia_repository/ \
    /mnt/external/datamancy-backup/
```

### Option B: Remote Kopia Repository
```bash
# Secondary Kopia repo on remote server (over SSH)
kopia repository create sftp \
    --path=/backup/datamancy \
    --host=backup.example.com \
    --username=datamancy \
    --keyfile=/path/to/key \
    --password="$KOPIA_PASSWORD"

# Sync snapshots
kopia snapshot copy --all
```

### Option C: Encrypted Cloud Backup (Compromise on Sovereignty)
```bash
# Rclone to S3/Backblaze with client-side encryption
rclone sync /volumes/kopia_repository/ crypt:datamancy-backup/
```

---

## Encryption

**All backups are encrypted at rest:**

- Kopia uses AES-256-GCM with PBKDF2 key derivation
- Database dumps stored within Kopia are encrypted
- Offsite replicas inherit encryption
- Key management: Store KOPIA_PASSWORD in KeePass/Vaultwarden

**Never store backup encryption keys in the same location as backups!**

---

## Monitoring & Alerts

### Grafana Dashboard
- Backup job success/failure (last 7 days)
- Backup repository size trend
- Time since last successful backup
- Kopia deduplication ratio

### Alerting Rules
```yaml
# Alert if backup older than 36 hours
- alert: BackupStale
  expr: time() - last_backup_timestamp > 129600
  annotations:
    summary: "Backup is stale (>36h)"

# Alert if backup failed
- alert: BackupFailed
  expr: last_backup_status != 0
  annotations:
    summary: "Backup job failed"

# Alert if restore test fails
- alert: RestoreTestFailed
  expr: monthly_restore_test_status != 0
  annotations:
    summary: "DR drill failed - investigate immediately"
```

---

## Cost Analysis

**Storage Requirements (Estimated):**

| Data Type | Size | Daily Growth | 30-day Total |
|-----------|------|--------------|--------------|
| PostgreSQL | 2GB | 100MB | 5GB |
| LDAP | 50MB | 1MB | 80MB |
| Mailu | 5GB | 200MB | 11GB |
| Volumes | 100GB | 1GB | 130GB |
| **Total** | **107GB** | **1.3GB/day** | **~150GB** |

**With Kopia deduplication:** Expect 60-70% reduction = ~60GB actual storage.

**Hardware:**
- 500GB local SSD: $50
- 2TB external HDD: $60
- Remote VPS (1TB storage): $10/month

**Total initial investment:** ~$110 + $10/month for offsite.

---

## Recovery Scenarios

### Scenario 1: Single Database Corruption
**RTO: 15 minutes | RPO: 24 hours**

```bash
docker exec postgres pg_restore -U admin -d grafana < postgres_grafana.dump
docker restart grafana
```

### Scenario 2: Volume Data Loss
**RTO: 1 hour | RPO: 24 hours**

```bash
kopia snapshot restore <snapshot-id> /volumes/grafana_data/
docker restart grafana
```

### Scenario 3: Complete Host Failure
**RTO: 4 hours | RPO: 24 hours**

```bash
# On new host:
1. Install Docker + Docker Compose
2. Clone Datamancy repo
3. Restore volumes from Kopia
4. Restore database dumps
5. Run: kotlin scripts/stackops.main.kts up
6. Verify with probe-orchestrator
```

### Scenario 4: Ransomware Attack
**RTO: 6 hours | RPO: 24 hours**

```bash
# Assumes offsite backup unaffected
1. Provision clean system (burn infected one)
2. Restore from offsite Kopia snapshot (pre-infection)
3. Rotate ALL secrets (assume compromised)
4. Audit access logs before restoring services
5. Restore to test env first, scan for malware
6. Promote to production after verification
```

---

## TODO: Implementation Checklist

- [ ] Create `scripts/backup-databases.main.kts`
- [ ] Create `scripts/restore-from-backup.main.kts`
- [ ] Create `scripts/verify-backups.main.kts`
- [ ] Create `scripts/dr-drill.main.kts`
- [ ] Configure Kopia retention policies
- [ ] Set up backup cron jobs / systemd timers
- [ ] Configure Grafana backup dashboard
- [ ] Set up alerting (email/Slack/Matrix)
- [ ] Document key storage procedure
- [ ] Provision offsite backup location
- [ ] Run first DR drill and document findings
- [ ] Train team on restore procedures

---

## Security Considerations

1. **Backup Isolation:** Backups should be read-only or append-only to prevent ransomware from destroying them.
2. **Key Rotation:** Rotate Kopia password quarterly, re-encrypt repository.
3. **Access Control:** Limit who can delete backups (separate from who can create).
4. **Audit Logs:** Track all backup/restore operations.
5. **Physical Security:** Offsite backups in locked fireproof safe.

---

**Last Updated:** 2025-12-02
**Owner:** Platform Team
**Review Cadence:** Quarterly
