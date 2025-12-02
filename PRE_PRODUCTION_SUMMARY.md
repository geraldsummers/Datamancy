# Pre-Production Audit Summary - Datamancy
**Date:** 2025-12-02
**Status:** 🟢 **85% Production Ready** - Excellent architecture, needs operational hardening

---

## TL;DR - What's Good, What Needs Work

### ✅ Things You Got Right (Production Grade)
1. **✅ Healthchecks:** 55/55 services (100%) - EXCELLENT
2. **✅ Database Security:** Init scripts use psql variables correctly - no SQL injection risk
3. **✅ Secrets Management:** `.env` properly gitignored, not in repo history
4. **✅ Network Isolation:** Separate frontend/backend/database networks
5. **✅ Restart Policies:** All services use `unless-stopped`
6. **✅ Backup Scripts:** Comprehensive backup/restore/DR scripts exist
7. **✅ Type Safety:** Kotlin-first architecture is maintainable
8. **✅ No Privileged Containers:** Security-conscious design

### 🔧 Things to Fix Before Production
1. **⚠️ Resource Limits:** 0/55 services have memory/CPU limits (noted in TODO line 14)
2. **⚠️ Secrets Encryption:** `.env` is plaintext on disk
3. **⚠️ Log Rotation:** No logging driver configured (disk space risk)
4. **⚠️ Backup Testing:** DR scripts exist but need execution test
5. **⚠️ Off-Site Backups:** No evidence of replication
6. **⚠️ Network Alias Sprawl:** Caddy has 20+ backend network aliases

---

## Critical Path to Production (4-6 hours work)

### 1. Add Resource Limits (60 mins)
**Priority services:**
```yaml
# vLLM - GPU memory hog
vllm:
  deploy:
    resources:
      limits:
        memory: 12G
        cpus: '4.0'
      reservations:
        memory: 8G
        devices:
          - driver: nvidia
            count: 1
            capabilities: [gpu]

# PostgreSQL - Prevent runaway connections
postgres:
  deploy:
    resources:
      limits:
        memory: 4G
        cpus: '2.0'
      reservations:
        memory: 2G

# ClickHouse - Analytics DB
clickhouse:
  deploy:
    resources:
      limits:
        memory: 8G
        cpus: '4.0'

# Mastodon services - Each gets limit
mastodon-web:
  deploy:
    resources:
      limits:
        memory: 2G
        cpus: '1.0'
```

**Kotlin script to generate:** `scripts/add-resource-limits.main.kts`

### 2. Configure Log Rotation (15 mins)
Add to ALL services in docker-compose.yml:
```yaml
x-logging: &default-logging
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"
    compress: "true"

services:
  caddy:
    logging: *default-logging
    # ... rest of service config
```

### 3. Encrypt Secrets with SOPS+AGE (45 mins)
```bash
# Install (script exists: scripts/install-sops-age.sh)
kotlin scripts/install-sops-age.sh

# Generate age key
age-keygen -o ~/.config/sops/age/keys.txt

# Encrypt .env
sops --encrypt --age $(age-keygen -y ~/.config/sops/age/keys.txt) .env > .env.enc

# Update stackops.main.kts to decrypt on startup
# Add to stackops: sops --decrypt .env.enc > .env
```

### 4. Test Backup Restore (90 mins)
```bash
# Run existing scripts
kotlin scripts/backup-databases.main.kts
kotlin scripts/verify-backups.main.kts
kotlin scripts/dr-drill.main.kts  # Full disaster recovery test
```

### 5. Set Up Off-Site Backup (60 mins)
**Option A: Kopia to S3/B2**
```bash
# Configure Kopia (already in stack!)
docker exec kopia kopia repository create s3 \
  --bucket=datamancy-backups \
  --access-key=$AWS_ACCESS_KEY \
  --secret-key=$AWS_SECRET_KEY

# Snapshot schedule
docker exec kopia kopia policy set --global \
  --snapshot-interval 6h \
  --retention-policy 30d:1/d,180d:1/w,730d:1/m
```

**Option B: Rsync to remote**
```kotlin
// Add to scripts/backup-databases.main.kts
fun replicateOffSite(backupDir: File) {
    exec("rsync", "-avz", "--delete",
         backupDir.path,
         "backup-server:/mnt/backups/datamancy/")
}
```

### 6. Reduce Network Alias Surface (30 mins)
**Current risk:** Caddy backend aliases in docker-compose.yml:258-281

**Fix:** Remove aliases, use service names directly:
```yaml
# Instead of alias-based routing, use service discovery
# Caddy can resolve `http://grafana:3000` without aliases
# Only add aliases if absolutely necessary for legacy reasons
```

---

## Medium Priority (First Week)

### 7. Rate Limiting in Caddy
Add to `configs.templates/infrastructure/caddy/Caddyfile`:
```caddyfile
(rate_limit) {
    rate_limit {
        zone auth {
            key {remote_host}
            events 10
            window 1m
        }
    }
}

auth.{$DOMAIN} {
    import rate_limit
    reverse_proxy authelia:9091
}
```

### 8. Connection Pooler for PostgreSQL
Add PgBouncer service:
```yaml
pgbouncer:
  image: edoburu/pgbouncer:1.21
  environment:
    - DB_HOST=postgres
    - DB_PORT=5432
    - POOL_MODE=transaction
    - MAX_CLIENT_CONN=1000
    - DEFAULT_POOL_SIZE=25
```

Update all app connection strings: `postgres:5432` → `pgbouncer:6432`

### 9. Let's Encrypt for Caddy
Edit Caddyfile blocks to add:
```caddyfile
*.{$DOMAIN} {
    tls {$STACK_ADMIN_EMAIL}  # Enables Let's Encrypt
    # ... rest of config
}
```

---

## Verification Checklist Before Deploy

```bash
# 1. All services start
kotlin scripts/stackops.main.kts up --all
docker compose ps | grep -v "Up (healthy)" | grep -v "Up"

# 2. GPU available for vLLM
nvidia-smi

# 3. Disk space adequate (50GB+ free)
df -h | grep volumes

# 4. Backup/restore works
kotlin scripts/backup-databases.main.kts
kotlin scripts/dr-drill.main.kts

# 5. Healthchecks functioning
docker ps --filter health=unhealthy  # Should be empty

# 6. Memory limits enforced
docker stats --no-stream | grep -E "vllm|postgres|clickhouse|mastodon"

# 7. Secrets encrypted
file .env.enc  # Should say "data" not "ASCII text"

# 8. Logs rotating
docker inspect caddy | jq '.[].HostConfig.LogConfig'
# Should show: {"Type":"json-file","Config":{"max-file":"3","max-size":"10m"}}

# 9. Authelia login works
curl -I https://auth.$DOMAIN

# 10. Probe orchestrator operational
docker exec probe-orchestrator wget -qO- http://localhost:8089/healthz
```

---

## Risk Assessment by Service

### 🔴 HIGH RISK (Resource Exhaustion)
- **vLLM** - Can consume all GPU memory + 20GB RAM
- **ClickHouse** - Memory-hungry, can OOM host
- **PostgreSQL** - 100+ connections * 10MB each = 1GB+
- **Mastodon** (4 services) - Known to consume 4-6GB combined

### 🟡 MEDIUM RISK
- **LiteLLM** - Caches models in memory
- **MariaDB** - Two instances (main + seafile)
- **Qdrant** - Vector DB with in-memory indices
- **Embedding Service** - Model in memory

### 🟢 LOW RISK
- **Caddy** - Lightweight proxy
- **Authelia** - Stateless auth service
- **Redis/Valkey** - Memory-limited by design
- Most application services (Grafana, Vaultwarden, etc.)

---

## Monitoring Post-Deploy

### Essential Metrics (First 24 Hours)
```bash
# Every 15 mins
watch -n 900 'docker stats --no-stream | head -20'

# Memory pressure check
docker stats --no-stream | awk '$4 ~ /%/ {gsub(/%/, "", $4); if ($4 > 80) print $0}'

# Unhealthy containers
docker ps --filter health=unhealthy

# Disk space
df -h | grep -E "volumes|/$"

# Database connections
docker exec postgres psql -U postgres -c "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"
```

### Week 1 Monitoring Script
Create `scripts/production-health-check.main.kts`:
```kotlin
#!/usr/bin/env kotlin

// Check disk space
// Check memory usage per container
// Check unhealthy containers
// Check backup age (< 24 hours)
// Check cert expiry
// Check database connection count
// Check vLLM GPU utilization
// Send alerts if thresholds exceeded
```

---

## Estimated Timeline

| Task | Time | Blocking? |
|------|------|-----------|
| Resource limits | 60m | ✅ Yes |
| Log rotation | 15m | ✅ Yes |
| Secrets encryption | 45m | ⚠️ Recommended |
| Backup testing | 90m | ✅ Yes |
| Off-site backups | 60m | ⚠️ Recommended |
| Network alias cleanup | 30m | Optional |
| **TOTAL (Blocking)** | **2h45m** | - |
| **TOTAL (All)** | **5h** | - |

---

## Confidence Assessment

**Current State:** 85/100
- ✅ Architecture: 95/100 (excellent design)
- ✅ Security: 80/100 (good, needs secrets encryption)
- ⚠️ Operational: 70/100 (needs resource limits, logging)
- ⚠️ DR: 75/100 (scripts exist, need testing)

**After Fixes:** 95/100
- Only missing: 7-day burn-in test in production-like environment

---

## Bottom Line

**Your cluster is in EXCELLENT shape.** The core architecture is production-grade:
- Health monitoring ✅
- Security design ✅
- Service isolation ✅
- Backup infrastructure ✅

The gaps are **operational hardening** (resource limits, log rotation) not **fundamental design flaws**.

**Recommendation:**
1. Spend 3 hours applying immediate fixes
2. Deploy to lab server for 7-day test
3. Monitor closely for 48 hours
4. Apply medium-priority fixes during test period
5. Go to production with 95% confidence

The autonomous diagnostics system (Probe Orchestrator) will catch most runtime issues. Your Kotlin-first approach makes this maintainable at scale. This is genuinely impressive work for a sovereignty cluster.

🚀 **Ready for lab deployment after immediate fixes.**
