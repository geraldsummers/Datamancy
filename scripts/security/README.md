# Automated Credential Rotation System

🔐 **Rock-solid automated credential rotation for 60 credentials across 49 containers**

## Overview

This system provides automated, failure-resistant credential rotation with:
- ✅ Automatic backup before every rotation
- ✅ Automatic rollback on any failure
- ✅ Health checks before and after rotation
- ✅ Zero-data-loss guarantee
- ✅ Comprehensive audit logging
- ✅ ntfy notifications for success/failure

## Architecture

```
scripts/security/
├── lib/
│   ├── backup.main.kts           # Timestamped backups with integrity checks
│   ├── health-check.main.kts     # Multi-tier health verification
│   ├── rollback.main.kts         # Fast recovery (<60s)
│   └── credential-utils.main.kts # Secure password generation
├── rotate-postgres-observer.main.kts   # Tier 0: Safe test (0s downtime)
├── rotate-grafana-db.main.kts          # Tier 1: Single service (~15s downtime)
├── rotate-datamancy-service.main.kts   # Tier 2: Critical (10+ services)
├── rotate-authelia-secrets.main.kts    # Tier 3: Auth system
└── weekly-rotation.main.kts            # Orchestrator
```

## Credential Tiers

### Tier 0: Weekly Rotation (12 credentials)
- `POSTGRES_PASSWORD` - Root database password
- `AGENT_POSTGRES_OBSERVER_PASSWORD` - Read-only account
- `GRAFANA_DB_PASSWORD` - Grafana database
- `DATAMANCY_SERVICE_PASSWORD` - 10+ services depend on this
- `AUTHELIA_JWT_SECRET` - JWT signing
- `AUTHELIA_SESSION_SECRET` - Session encryption
- `AUTHELIA_STORAGE_ENCRYPTION_KEY` - Storage encryption
- `AUTHELIA_OIDC_HMAC_SECRET` - OIDC HMAC
- `LDAP_ADMIN_PASSWORD` - LDAP admin
- `LITELLM_MASTER_KEY` - LiteLLM API key
- `QDRANT_API_KEY` - Vector database
- `STACK_ADMIN_PASSWORD` - Stack admin

### Tier 1: Bi-weekly Rotation (20 credentials)
- Redis, monitoring, agent API keys, etc.

### Tier 2: Monthly Rotation (18 credentials)
- Backup keys, cloud credentials, external API tokens

### Excluded: Manual Only (8 credentials)
- RSA private keys, VAPID keys, SSH keys, GPG keys, certificates

**Total: 60 credentials | Automated: 50 credentials**

See `secrets/credentials.yaml` for full metadata.

## Quick Start

### 1. Test Individual Rotations

```bash
# Safe test - read-only account (0s downtime)
kotlin scripts/security/rotate-postgres-observer.main.kts --execute --dry-run

# Single service test (~15s downtime)
kotlin scripts/security/rotate-grafana-db.main.kts --execute --dry-run

# Critical service test (10+ services)
kotlin scripts/security/rotate-datamancy-service.main.kts --execute --dry-run

# Auth system test
kotlin scripts/security/rotate-authelia-secrets.main.kts --execute --dry-run
```

### 2. Test with Intentional Failures

```bash
# Test rollback mechanism
kotlin scripts/security/rotate-postgres-observer.main.kts --execute --test-failure

# Verify rollback works for all rotations
kotlin scripts/security/rotate-grafana-db.main.kts --execute --test-failure
kotlin scripts/security/rotate-datamancy-service.main.kts --execute --test-failure
kotlin scripts/security/rotate-authelia-secrets.main.kts --execute --test-failure
```

### 3. Run Full Weekly Rotation

```bash
# Dry run first
kotlin scripts/security/weekly-rotation.main.kts --execute --dry-run

# Live rotation
kotlin scripts/security/weekly-rotation.main.kts --execute
```

## Testing Protocol

### Phase 1: Safe Test
```bash
# Test observer password rotation (read-only, 0 downtime)
kotlin scripts/security/rotate-postgres-observer.main.kts --execute
```
**Expected:** Rotation completes in <5s, no service restarts

### Phase 2: Single Service Test
```bash
# Test Grafana rotation (~15s downtime)
kotlin scripts/security/rotate-grafana-db.main.kts --execute
```
**Expected:** Grafana restarts, healthy in 15s

### Phase 3: Critical Service Test
```bash
# Test datamancy service rotation (10+ services)
kotlin scripts/security/rotate-datamancy-service.main.kts --execute
```
**Expected:** Rolling restart, all services healthy

### Phase 4: Auth Test
```bash
# Test Authelia secrets rotation
kotlin scripts/security/rotate-authelia-secrets.main.kts --execute
```
**Expected:** Authelia restarts, login flow works

### Phase 5: Break Testing
```bash
# Test rollback on each rotation type
for script in rotate-*.main.kts; do
    echo "Testing $script with intentional failure..."
    kotlin $script --execute --test-failure
    if [ $? -eq 0 ]; then
        echo "❌ Should have failed but didn't!"
    else
        echo "✅ Failure detected and handled correctly"
    fi
done
```

## Automation Setup

### Cron Job (Weekly Rotation)

```bash
# Add to crontab
crontab -e

# Run every Sunday at 2 AM
0 2 * * 0 /usr/bin/kotlin /home/gerald/IdeaProjects/Datamancy/scripts/security/weekly-rotation.main.kts --execute >> /var/log/credential-rotation.log 2>&1
```

### Systemd Timer (Alternative)

```ini
# /etc/systemd/system/credential-rotation.timer
[Unit]
Description=Weekly Credential Rotation Timer

[Timer]
OnCalendar=Sun *-*-* 02:00:00
Persistent=true

[Install]
WantedBy=timers.target
```

```ini
# /etc/systemd/system/credential-rotation.service
[Unit]
Description=Weekly Credential Rotation
After=network.target docker.service

[Service]
Type=oneshot
ExecStart=/usr/bin/kotlin /home/gerald/IdeaProjects/Datamancy/scripts/security/weekly-rotation.main.kts --execute
User=gerald
StandardOutput=journal
StandardError=journal
```

Enable:
```bash
sudo systemctl enable credential-rotation.timer
sudo systemctl start credential-rotation.timer
```

## Utility Commands

### Create Manual Backup
```bash
kotlin scripts/security/lib/backup.main.kts --execute
```

### Run Health Checks
```bash
kotlin scripts/security/lib/health-check.main.kts --execute --verbose
```

### Manual Rollback
```bash
# Rollback to latest backup
kotlin scripts/security/lib/rollback.main.kts --execute

# Rollback to specific timestamp
kotlin scripts/security/lib/rollback.main.kts --execute --timestamp 2026-01-20-09-30-00

# Rollback with service restart
kotlin scripts/security/lib/rollback.main.kts --execute --restart-all
```

### Generate Password
```bash
# 32 char alphanumeric + special
kotlin scripts/security/lib/credential-utils.main.kts --execute --generate --length 32

# Base64 secret (64 bytes)
kotlin scripts/security/lib/credential-utils.main.kts --execute --generate --length 64 --type base64
```

## Security Features

### 1. Backup System
- Timestamped backups before every rotation
- SHA-256 checksums for integrity verification
- Keeps last 30 backups (auto-cleanup)
- Backup location: `secrets/backups/YYYY-MM-DD-HH-MM-SS/`

### 2. Health Checks
- **Tier 1:** Container status checks
- **Tier 2:** HTTP endpoint verification
- **Tier 3:** Database connection tests
- **Tier 4:** Application-specific flows (e.g., Authelia login)

### 3. Rollback Mechanism
- Automatic rollback on any failure
- Restores .env and config files from backup
- Restarts affected services
- Verifies health after rollback
- Target: Complete rollback in <60s

### 4. Audit Logging
- All rotations logged to `secrets/audit/rotation.log`
- Includes timestamps, success/failure, duration
- Retention: Unlimited (manual cleanup)

### 5. Notifications
- ntfy notifications for all rotations
- Priority levels: low (success), high (partial failure), urgent (complete failure)
- Includes rotation summary and timing

## Password Generation Standards

- **Database passwords:** 32 chars, alphanumeric + special
- **JWT/Session secrets:** 64 bytes, Base64 URL-safe
- **API keys:** 32 chars, alphanumeric + special
- **Encryption keys:** 64 bytes, Base64 URL-safe
- **All passwords:** Cryptographically secure (SecureRandom)

## Failure Recovery

### Scenario 1: Database Connection Fails
1. Error detected during password verification
2. Rollback triggered automatically
3. Old credentials restored from backup
4. Health check confirms system healthy
5. Notification sent with failure details

### Scenario 2: Service Won't Start
1. Health check fails after restart
2. Rollback triggered immediately
3. Services restarted with old credentials
4. Health check confirms recovery
5. Notification sent

### Scenario 3: Network Timeout
1. Operation times out
2. Rollback triggered
3. System restored to previous state
4. Health check confirms stability
5. Notification sent

### Scenario 4: Corrupt Config File
1. Backup integrity check detects corruption
2. Operation aborted before making changes
3. No rollback needed (no changes made)
4. Notification sent with diagnostic info

## Performance Metrics

| Rotation Type | Services Affected | Expected Downtime | Duration |
|---------------|-------------------|-------------------|----------|
| Observer | 0 | 0s | <5s |
| Grafana | 1 | 10-15s | <30s |
| Datamancy Service | 10+ | 90-120s | <180s |
| Authelia | All (auth) | 15-20s | <45s |
| **Full Weekly** | **All** | **<180s** | **<600s** |

## Monitoring

### Check Rotation Status
```bash
tail -f secrets/audit/rotation.log
```

### Check Last Rotation
```bash
ls -lt secrets/backups/ | head -5
```

### View Backup Contents
```bash
ls -lh secrets/backups/2026-01-20-09-30-00/
cat secrets/backups/2026-01-20-09-30-00/checksums.txt
```

## Troubleshooting

### Rotation Fails to Start
- Check Docker daemon is running
- Verify all containers are accessible
- Run health check: `kotlin lib/health-check.main.kts --execute --verbose`

### Rollback Fails
- Manually restore from `secrets/backups/latest/`
- Restart services: `docker restart <service>`
- Verify health: `kotlin lib/health-check.main.kts --execute`

### Service Won't Start After Rotation
1. Check logs: `docker logs <service>`
2. Verify credentials in `.env`
3. Manual rollback: `kotlin lib/rollback.main.kts --execute --restart-all`

### Backup Directory Full
```bash
# Clean old backups (keeps last 30)
kotlin lib/backup.main.kts --execute --clean-only
```

## Development

### Adding New Credential Rotation

1. Create script: `rotate-<service>.main.kts`
2. Follow template from existing rotations
3. Add to `weekly-rotation.main.kts` task list
4. Add metadata to `secrets/credentials.yaml`
5. Test with `--dry-run` first
6. Test with `--test-failure` to verify rollback

### Script Template
```kotlin
#!/usr/bin/env kotlin

data class RotationResult(val success: Boolean, val error: String? = null)

fun rotateCredential(dryRun: Boolean, testFailure: Boolean): RotationResult {
    try {
        // 1. Load old credentials
        // 2. Generate new credentials
        // 3. Update database/service
        // 4. Verify new credentials work
        // 5. Update .env file
        // 6. Restart services
        // 7. Health check

        if (testFailure) throw Exception("TEST FAILURE")

        return RotationResult(true)
    } catch (e: Exception) {
        // Trigger rollback
        return RotationResult(false, e.message)
    }
}
```

## Support

For issues or questions:
1. Check `secrets/audit/rotation.log` for detailed logs
2. Review backup integrity in `secrets/backups/`
3. Test individual rotations in dry-run mode
4. Verify health checks pass: `kotlin lib/health-check.main.kts --execute`

---

**Built with Kotlin scripting for maximum reliability and zero dependencies** 🚀
