# Credential Rotation Deployment Guide

Complete guide for deploying automated credential rotation on your server.

---

## 📋 Quick Start (3 Options)

### Option 1: Systemd Timer (Recommended) ⭐

**Best for:** Production servers, automatic failure recovery, persistent scheduling

```bash
# One-line installation
cd /home/gerald/IdeaProjects/Datamancy
./scripts/security/install-systemd.sh

# Verify it's working
sudo systemctl status credential-rotation.timer
```

**Advantages:**
- ✅ Runs even if system was offline
- ✅ Integrated with systemd logging (journalctl)
- ✅ Automatic restart policies
- ✅ Pre-flight health checks
- ✅ Security hardening (sandboxing)

---

### Option 2: Cron with Wrapper Script

**Best for:** Simple setups, familiar cron syntax

```bash
# Edit crontab
crontab -e

# Add this line (runs every Sunday at 2 AM)
0 2 * * 0 /home/gerald/IdeaProjects/Datamancy/scripts/security/cron-wrapper.sh
```

**Advantages:**
- ✅ Simple and familiar
- ✅ Easy to customize schedule
- ✅ Built-in logging and notifications

---

### Option 3: Manual Cron (Minimal)

**Best for:** Testing, one-off runs

```bash
# Edit crontab
crontab -e

# Add this line
0 2 * * 0 /usr/bin/kotlin /home/gerald/IdeaProjects/Datamancy/scripts/security/weekly-rotation.main.kts --execute >> /home/gerald/logs/credential-rotation.log 2>&1
```

---

## 🔧 Detailed Setup

### Prerequisites

1. **Kotlin installed:**
   ```bash
   # Check if Kotlin is installed
   which kotlin
   kotlin -version

   # If not installed, install via snap:
   sudo snap install kotlin --classic
   ```

2. **Docker running:**
   ```bash
   # Check Docker is accessible
   docker ps
   ```

3. **Project deployed:**
   ```bash
   # Verify rotation scripts exist
   ls -lh /home/gerald/IdeaProjects/Datamancy/scripts/security/
   ```

4. **Containers running:**
   ```bash
   # Check critical containers
   docker ps | grep -E "postgres|authelia|grafana"
   ```

---

## 📦 Build Integration

The rotation scripts are **automatically packaged** during build:

```bash
# Build Datamancy (includes rotation scripts)
./build-datamancy.main.kts

# Verify rotation scripts in dist/
ls -lh dist/scripts/security/
ls -lh dist/secrets/
```

**What gets packaged:**
- All rotation scripts (`.kts`)
- Library utilities (`lib/`)
- Systemd service files (`systemd/`)
- Wrapper scripts (`.sh`)
- Documentation (`.md`)
- Credentials metadata (`credentials.yaml`)
- Empty directories for backups and audit logs

---

## 🚀 Systemd Installation (Step-by-Step)

### 1. Run Installer

```bash
cd /home/gerald/IdeaProjects/Datamancy
./scripts/security/install-systemd.sh
```

The installer will:
1. Check prerequisites (Kotlin, Docker, project files)
2. Create systemd service file
3. Create systemd timer file
4. Reload systemd daemon
5. Enable timer (starts on boot)
6. Start timer immediately
7. Verify installation

### 2. Verify Installation

```bash
# Check timer is active
sudo systemctl status credential-rotation.timer

# Check when next run is scheduled
sudo systemctl list-timers --all | grep credential

# View service configuration
sudo systemctl cat credential-rotation.service
```

### 3. Test Run (Dry Run)

```bash
# Test without making changes
cd /home/gerald/IdeaProjects/Datamancy
./scripts/security/cron-wrapper.sh --dry-run

# Or directly
kotlin scripts/security/weekly-rotation.main.kts --execute --dry-run
```

### 4. Manual Trigger (Optional)

```bash
# Trigger rotation manually (useful for testing)
sudo systemctl start credential-rotation.service

# Watch logs in real-time
sudo journalctl -u credential-rotation.service -f
```

---

## 📊 Monitoring & Logs

### Systemd Logs (journalctl)

```bash
# View all rotation logs
sudo journalctl -u credential-rotation.service

# Follow logs in real-time
sudo journalctl -u credential-rotation.service -f

# View last 50 lines
sudo journalctl -u credential-rotation.service -n 50

# View logs from last week
sudo journalctl -u credential-rotation.service --since "1 week ago"

# View logs with timestamps
sudo journalctl -u credential-rotation.service -o short-precise
```

### Audit Logs

```bash
# View rotation audit log
tail -f /home/gerald/IdeaProjects/Datamancy/secrets/audit/rotation.log

# View last 100 rotations
tail -100 /home/gerald/IdeaProjects/Datamancy/secrets/audit/rotation.log
```

### Wrapper Script Logs (if using cron)

```bash
# View main log
tail -f /home/gerald/logs/credential-rotation.log

# View error log
tail -f /home/gerald/logs/credential-rotation-errors.log
```

### Check Backups

```bash
# List all backups (sorted by date)
ls -lt /home/gerald/IdeaProjects/Datamancy/secrets/backups/

# View latest backup
ls -lh /home/gerald/IdeaProjects/Datamancy/secrets/backups/$(ls -t /home/gerald/IdeaProjects/Datamancy/secrets/backups/ | head -1)/
```

---

## 🔔 Notifications

The system sends notifications via **ntfy** to `http://localhost:5555/datamancy-security`

### Notification Levels:

- **Low priority:** Successful rotations (silent)
- **High priority:** Partial failures with rollback
- **Urgent priority:** Complete failures requiring attention

### Testing Notifications:

```bash
# Test ntfy is working
curl -H "Title: Test" -d "Credential rotation notification test" http://localhost:5555/datamancy-security

# Check notification endpoint in wrapper script
grep NTFY_URL /home/gerald/IdeaProjects/Datamancy/scripts/security/cron-wrapper.sh
```

---

## ⏰ Schedule Configuration

### Systemd Timer Schedule

Default: **Every Sunday at 2:00 AM**

To change:
```bash
# Edit timer file
sudo systemctl edit --full credential-rotation.timer

# Change this line:
OnCalendar=Sun *-*-* 02:00:00

# Examples:
# Daily at 3 AM:     OnCalendar=*-*-* 03:00:00
# Every Monday:      OnCalendar=Mon *-*-* 02:00:00
# First of month:    OnCalendar=*-*-01 02:00:00
# Every 6 hours:     OnCalendar=*-*-* 00,06,12,18:00:00

# Reload and restart
sudo systemctl daemon-reload
sudo systemctl restart credential-rotation.timer
```

### Cron Schedule

```bash
# Edit crontab
crontab -e

# Format: MIN HOUR DAY MONTH WEEKDAY COMMAND
# Examples:
# Every Sunday at 2 AM:    0 2 * * 0 /path/to/script
# Every day at 3 AM:       0 3 * * * /path/to/script
# Every Monday at 1 AM:    0 1 * * 1 /path/to/script
# Twice a week (Mon/Thu):  0 2 * * 1,4 /path/to/script
# First of month:          0 2 1 * * /path/to/script
```

---

## 🧪 Testing Scenarios

### 1. Dry Run Test

```bash
# No actual changes
kotlin scripts/security/weekly-rotation.main.kts --execute --dry-run
```

**Expected:** Logs show what would be done, no credentials changed

### 2. Backup Test

```bash
# Create backup
kotlin scripts/security/lib/backup.main.kts --execute

# List backups
ls -lt secrets/backups/
```

**Expected:** New timestamped directory with .env, config files, checksums

### 3. Health Check Test

```bash
# Check all services
kotlin scripts/security/lib/health-check.main.kts --execute --verbose
```

**Expected:** All critical containers show as healthy

### 4. Rollback Test

```bash
# Create a backup first
kotlin scripts/security/lib/backup.main.kts --execute

# Test rollback to latest
kotlin scripts/security/lib/rollback.main.kts --execute --dry-run
```

**Expected:** Shows which files would be restored

### 5. Intentional Failure Test

```bash
# Test observer rotation with intentional failure
kotlin scripts/security/rotate-postgres-observer.main.kts --execute --test-failure
```

**Expected:**
- Rotation fails as intended
- Rollback is triggered automatically
- System restores to previous state
- Health checks pass after rollback

### 6. Full Rotation Test (Safe)

```bash
# Start with safe rotation (read-only account)
kotlin scripts/security/rotate-postgres-observer.main.kts --execute

# Check it worked
docker exec postgres psql -U postgres -c "SELECT 1"
```

---

## 🛠️ Troubleshooting

### Timer Not Running

```bash
# Check timer status
sudo systemctl status credential-rotation.timer

# Check timer is enabled
sudo systemctl is-enabled credential-rotation.timer

# Enable if needed
sudo systemctl enable credential-rotation.timer
sudo systemctl start credential-rotation.timer
```

### Service Fails

```bash
# Check service logs
sudo journalctl -u credential-rotation.service -n 100

# Check last run
sudo systemctl status credential-rotation.service

# Test manually
sudo systemctl start credential-rotation.service
```

### Kotlin Not Found

```bash
# Find Kotlin
which kotlin

# Update service file with correct path
sudo systemctl edit --full credential-rotation.service

# Change ExecStart path
ExecStart=/snap/bin/kotlin /home/gerald/...
```

### Permission Errors

```bash
# Check file permissions
ls -l /home/gerald/IdeaProjects/Datamancy/scripts/security/

# Make scripts executable
chmod +x /home/gerald/IdeaProjects/Datamancy/scripts/security/*.main.kts
chmod +x /home/gerald/IdeaProjects/Datamancy/scripts/security/*.sh

# Check secrets directory permissions
ls -ld /home/gerald/IdeaProjects/Datamancy/secrets/
```

### Docker Not Accessible

```bash
# Check Docker is running
sudo systemctl status docker

# Check user can access Docker
docker ps

# Add user to docker group if needed
sudo usermod -aG docker $USER
newgrp docker
```

---

## 🔐 Security Considerations

### File Permissions

```bash
# Secrets directory should be restricted
chmod 700 /home/gerald/IdeaProjects/Datamancy/secrets/
chmod 600 /home/gerald/IdeaProjects/Datamancy/secrets/backups/*/.env

# Scripts should be executable by owner only
chmod 750 /home/gerald/IdeaProjects/Datamancy/scripts/security/*.kts
```

### Systemd Hardening

The systemd service includes security features:
- `NoNewPrivileges=true` - Prevents privilege escalation
- `PrivateTmp=true` - Isolated /tmp directory
- `ProtectSystem=strict` - Read-only system directories
- `ProtectHome=read-only` - Read-only home directories (except secrets/)
- `ReadWritePaths=.../secrets` - Only secrets/ is writable

### Audit Trail

All rotations are logged with:
- Timestamp
- Credentials rotated
- Success/failure status
- Duration
- Services restarted
- Rollback events

**Retention:** Audit logs kept indefinitely (manual cleanup)

---

## 🔄 Maintenance

### Check System Health

```bash
# Run weekly checks
./scripts/security/cron-wrapper.sh --dry-run

# Verify backups exist
ls -lt secrets/backups/ | head -5

# Check disk space
df -h secrets/backups/
```

### Cleanup Old Backups

```bash
# Automatic cleanup (keeps last 30)
kotlin scripts/security/lib/backup.main.kts --execute

# Manual cleanup (delete backups older than 60 days)
find secrets/backups/ -type d -mtime +60 -exec rm -rf {} +
```

### Cleanup Old Logs

```bash
# Cleanup systemd logs older than 30 days
sudo journalctl --vacuum-time=30d

# Cleanup wrapper script logs
find /home/gerald/logs/ -name "credential-rotation*.log" -mtime +30 -delete
```

### Update Rotation Scripts

```bash
# Pull latest changes
cd /home/gerald/IdeaProjects/Datamancy
git pull

# Rebuild and redeploy
./build-datamancy.main.kts

# Copy to production if using separate deployment
rsync -av dist/scripts/security/ /path/to/production/scripts/security/

# No need to reinstall systemd (paths are absolute)
```

---

## 📞 Emergency Procedures

### Rollback Immediately

```bash
# Rollback to latest backup
cd /home/gerald/IdeaProjects/Datamancy
kotlin scripts/security/lib/rollback.main.kts --execute --restart-all

# Verify services are healthy
kotlin scripts/security/lib/health-check.main.kts --execute --verbose
```

### Disable Automatic Rotation

```bash
# Stop and disable timer
sudo systemctl stop credential-rotation.timer
sudo systemctl disable credential-rotation.timer

# Or for cron
crontab -e
# Comment out the rotation line with #
```

### Manual Recovery

```bash
# 1. Stop all containers
docker-compose down

# 2. Restore .env from backup
cp secrets/backups/YYYY-MM-DD-HH-MM-SS/.env .

# 3. Restore configs from backup
cp secrets/backups/YYYY-MM-DD-HH-MM-SS/*.yml configs/

# 4. Start containers
docker-compose up -d

# 5. Verify health
docker ps
kotlin scripts/security/lib/health-check.main.kts --execute
```

---

## 📈 Performance Metrics

Expected timings (from IMPLEMENTATION_COMPLETE.md):

| Rotation | Services | Downtime | Duration |
|----------|----------|----------|----------|
| Observer | 0 | 0s | <5s |
| Grafana | 1 | 10-15s | <30s |
| Datamancy | 10+ | 90-120s | <180s |
| Authelia | All | 15-20s | <45s |
| **Full Weekly** | **All** | **<180s** | **<600s** |

---

## ✅ Post-Deployment Checklist

- [ ] Systemd timer installed and active
- [ ] First dry-run test successful
- [ ] Backup system creates backups
- [ ] Rollback system tested
- [ ] Health checks pass
- [ ] Notifications working (ntfy)
- [ ] Logs accessible and readable
- [ ] Schedule configured correctly
- [ ] Security permissions set
- [ ] Emergency procedures documented
- [ ] Team notified of schedule

---

## 📚 Additional Resources

- **Full Documentation:** `scripts/security/README.md`
- **Implementation Details:** `scripts/security/IMPLEMENTATION_COMPLETE.md`
- **Credentials Metadata:** `secrets/credentials.yaml`
- **Test Suite:** `scripts/security/test-all.sh`

---

**Questions? Check the logs first:**
```bash
sudo journalctl -u credential-rotation.service -n 100
tail -100 secrets/audit/rotation.log
```
