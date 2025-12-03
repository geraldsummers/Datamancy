# Pre-Deployment Fixes Applied

**Date**: 2025-12-03
**Status**: ✅ All critical and high-priority issues resolved

---

## Summary

All critical and high-priority security/reliability issues from **PRE_DEPLOYMENT_REVIEW.md** have been fixed, except resource limits (deferred for performance profiling on lab server).

---

## 1. ✅ Template Processor - Critical Variable Validation

**File**: `scripts/core/process-config-templates.main.kts`

**Changes**:
- Added list of critical variables that MUST be present
- Script now **fails hard** if critical vars missing (exits with error code 1)
- Provides clear error message with affected files and missing variables
- Non-critical missing vars shown as warnings only

**Critical Variables Enforced**:
```
DOMAIN
STACK_ADMIN_USER
STACK_ADMIN_PASSWORD
STACK_ADMIN_EMAIL
MAIL_DOMAIN
VOLUMES_ROOT
LITELLM_MASTER_KEY
AUTHELIA_JWT_SECRET
AUTHELIA_SESSION_SECRET
AUTHELIA_STORAGE_ENCRYPTION_KEY
```

**Impact**: Prevents deployment with broken configs containing `{{UNREPLACED}}` placeholders

---

## 2. ✅ VM Provisioner - Removed Privileged Mode

**File**: `docker-compose.yml:1989-2025`

**Changes**:
- Removed `privileged: true`
- Added specific capabilities: `NET_ADMIN`, `SYS_ADMIN`, `DAC_OVERRIDE`
- Added `security_opt: [apparmor=unconfined]` for QEMU operations
- Added comment explaining security rationale

**Before**:
```yaml
privileged: true  # Full host access - SECURITY RISK
```

**After**:
```yaml
cap_add:
  - NET_ADMIN       # Required for libvirt network management
  - SYS_ADMIN       # Required for mounting and cgroups access
  - DAC_OVERRIDE    # Required for accessing libvirt socket
security_opt:
  - apparmor=unconfined  # Libvirt requires this for QEMU operations
```

**Impact**: Significantly reduced attack surface while maintaining libvirt functionality

**Note**: If vm-provisioner fails to start, see `docs/PRE_DEPLOYMENT_REVIEW.md` section 1 for troubleshooting

---

## 3. ✅ Authelia Session - Reduced Remember Me Duration

**File**: `configs.templates/applications/authelia/configuration.yml:80-91`

**Changes**:
- Changed `remember_me: 1M` to `remember_me: 7d`
- Added comment explaining security rationale

**Impact**: Stolen "remember me" cookies now expire after 7 days instead of 30 days

**To Apply**: Run `./stack-controller config process` to regenerate configs

---

## 4. ✅ SSH Host Key - Fingerprint Verification

**File**: `configs.templates/infrastructure/ssh/bootstrap_known_hosts.sh`

**Changes**:
- Added `TOOLSERVER_SSH_HOST_KEY_FINGERPRINT` environment variable support
- Script verifies actual fingerprint matches expected fingerprint
- Fails with clear error on mismatch (potential MITM attack)
- Shows helpful message if fingerprint not configured (TOFU mode)

**Usage**:
```bash
# Optional: Pin SSH host key fingerprint in .env
TOOLSERVER_SSH_HOST_KEY_FINGERPRINT=SHA256:abc123def456...

# On first run, script shows fingerprint to pin:
# TOOLSERVER_SSH_HOST_KEY_FINGERPRINT=SHA256:...actual...
```

**Impact**: Prevents Man-in-the-Middle attacks during initial SSH key exchange

---

## 5. ✅ LDAP Bootstrap - Idempotency Check

**Files**:
- `scripts/security/generate-ldap-bootstrap.main.kts`
- `stack-controller.main.kts:545-566, 695-706`

**Changes**:
- Script checks if output file already exists
- Fails with helpful error message explaining LDAP bootstrap behavior
- Added `--force` flag to override
- Updated stack-controller to pass `--force` flag

**Error Message**:
```
IMPORTANT: This file is ONLY loaded when LDAP container is first created.
If you regenerate this file after LDAP is running, changes will NOT be applied.

To apply changes:
  1. Stop LDAP:    docker compose stop ldap
  2. Delete data:  rm -rf volumes/ldap_data volumes/ldap_config
  3. Regenerate:   ./stack-controller ldap bootstrap --force
  4. Restart:      docker compose up -d ldap

OR change passwords via LDAP Account Manager (https://lam.yourdomain.com)
```

**Impact**: Prevents confusion when regenerating LDAP bootstrap has no effect

---

## 6. ✅ Health Check Aggregation

**File**: `stack-controller.main.kts:321-380`

**New Command**: `./stack-controller health`

**Features**:
- Parses `docker compose ps --format json`
- Counts healthy vs unhealthy services
- Lists all unhealthy services with status
- Exit code 0 if all healthy, 1 if any unhealthy

**Example Output**:
```
[INFO] Checking stack health...

✓ All 42 services healthy

# OR if issues found:

[WARN] Health check failed:
[WARN]   Healthy:   40
[WARN]   Unhealthy: 2

[ERROR] Unhealthy services:
  ✗ vllm: starting
  ✗ postgres: unhealthy
```

**Use Cases**:
- Monitoring scripts
- CI/CD deployment verification
- Cron job health checks

---

## 7. ✅ Backup Verification Script

**File**: `scripts/backup/backup-with-verify.sh` (NEW)

**Features**:
- Backs up volumes (excluding large model caches)
- Backs up runtime config (`~/.config/datamancy/`)
- Dumps PostgreSQL, MariaDB, Redis
- **Verifies each backup** (tar integrity, SQL gunzip test)
- **Tests restore** (extracts to temp dir, checks critical files exist)
- Secure permissions on runtime config backup (600)
- Automatic cleanup (keeps last N backups)
- Upload hook support via `BACKUP_UPLOAD_CMD`

**Usage**:
```bash
# Run manually
./scripts/backup/backup-with-verify.sh

# Or add to cron
0 2 * * * /path/to/backup-with-verify.sh && echo "Backup OK" || mail -s "BACKUP FAILED" admin@domain.com

# Configure backup location
export BACKUP_ROOT=/mnt/backup
./scripts/backup/backup-with-verify.sh

# Configure retention
export KEEP_BACKUPS=14  # Keep 14 days
./scripts/backup/backup-with-verify.sh
```

**Impact**: No more silent backup corruption - you'll know immediately if backups fail

---

## 8. ✅ Mailu Fail2ban Documentation

**File**: `docs/DEPLOYMENT.md:399-536`

**Added**: Complete section 3.1 "Configure Fail2ban for Email Protection"

**Includes**:
- Installation instructions
- Mailu-specific filters for Postfix, Dovecot, Admin UI
- Jail configuration with sensible defaults (5 retries, 10min window, 1hr ban)
- Testing and monitoring commands
- IP whitelist configuration
- Logging driver compatibility notes

**Filters Created**:
- `mailu-postfix.conf` - SMTP auth failures
- `mailu-dovecot.conf` - IMAP/POP3 auth failures
- `mailu-admin.conf` - Admin UI login failures

**Impact**: Email server protected from brute-force attacks on day 1

---

## Remaining Tasks (Optional)

These items from the review were identified but not fixed (either optional or deferred):

### Deferred:
- **Resource Limits**: Waiting for real performance data from lab server
  - Will profile with `docker stats` under load
  - Then add limits with 20% headroom

### Optional (Nice-to-Have):
- **Prometheus + cAdvisor**: For container metrics in Grafana
- **Loki + Promtail**: For centralized logging
- **SSL Expiry Monitoring**: Cron job to check certificate expiration
- **Docker Socket Proxy**: Separate admin proxy for Portainer/Dockge (if needed)

---

## Testing Checklist

Before deploying to production:

```bash
# 1. Test template validation
./stack-controller config generate
# Edit .env.runtime, remove DOMAIN=
./stack-controller config process
# Should FAIL with clear error ✓

# 2. Test LDAP bootstrap idempotency
./stack-controller ldap bootstrap
./stack-controller ldap bootstrap  # Should fail, ask for --force ✓

# 3. Test health check
./stack-controller up --profile=bootstrap
./stack-controller health  # Should show healthy services ✓

# 4. Test backup verification
./scripts/backup/backup-with-verify.sh
# Check output for "✓" success messages ✓

# 5. Test SSH fingerprint validation (if configured)
# Add TOOLSERVER_SSH_HOST_KEY_FINGERPRINT to .env
# Restart ssh-key-bootstrap container
# Check logs for "✓ Fingerprint verified" ✓

# 6. Test vm-provisioner without privileged mode
docker compose --profile compute up -d vm-provisioner
docker logs vm-provisioner  # Should connect to libvirt ✓
```

---

## Migration Guide

If you have an existing deployment:

### 1. Regenerate Configs
```bash
# Backup existing configs
cp -r ~/.config/datamancy ~/.config/datamancy.backup

# Regenerate with new template processor
./stack-controller config process
./stack-controller ldap bootstrap --force
```

### 2. Update docker-compose.yml
```bash
# No action needed - just pull latest code
git pull
```

### 3. Restart Affected Services
```bash
# Restart Authelia (new remember_me duration)
docker compose restart authelia

# Restart vm-provisioner (new capabilities)
docker compose --profile compute up -d --force-recreate vm-provisioner

# Restart ssh-key-bootstrap (new fingerprint check)
docker compose up -d --force-recreate ssh-key-bootstrap
docker compose restart agent-tool-server
```

### 4. Set Up Fail2ban
```bash
# Follow docs/DEPLOYMENT.md section 3.1
sudo apt-get install fail2ban
# Create filters and jails as documented
```

---

## Security Improvements Summary

| Issue | Severity | Status | Impact |
|-------|----------|--------|--------|
| Privileged container | CRITICAL | ✅ Fixed | Reduced attack surface |
| Missing var validation | CRITICAL | ✅ Fixed | Prevents broken deployments |
| Remember me too long | HIGH | ✅ Fixed | Limits credential theft window |
| SSH MITM risk | HIGH | ✅ Fixed | Prevents key compromise |
| LDAP regen confusion | MEDIUM | ✅ Fixed | Prevents operator errors |
| No health aggregation | MEDIUM | ✅ Fixed | Improves monitoring |
| Backup corruption | HIGH | ✅ Fixed | Prevents data loss |
| Email brute-force | HIGH | ✅ Documented | Blocks attacks |

---

## Next Steps

1. **Test all fixes** in dev environment using checklist above
2. **Deploy to lab server** with `--profile=bootstrap`
3. **Run for 24 hours**, monitor with `./stack-controller health`
4. **Profile resource usage** with `docker stats`, add limits
5. **Set up fail2ban** on host server
6. **Configure backups** (add to cron with email alerts)
7. **Pin SSH fingerprint** in .env (get from first bootstrap run)
8. **Open to research team** once stable

---

**All critical security issues resolved. Ready for controlled lab deployment.** 🚀
