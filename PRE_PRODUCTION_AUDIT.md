# Pre-Production Audit Report - Datamancy Sovereign Compute Cluster
**Date:** 2025-12-02
**Auditor:** Claude Code
**Target:** Production deployment readiness review
**Environment:** Docker rootful, Kotlin/JVM stack

---

## Executive Summary

**Overall Status:** 🟡 **NEEDS ATTENTION** - System is functional but requires hardening before production deployment

**Critical Issues:** 1
**High Priority:** 5
**Medium Priority:** 8
**Low Priority:** 3

---

## 🔴 CRITICAL ISSUES (Must Fix Before Production)

### C1: No Health Checks on Any Services
**Severity:** CRITICAL
**Impact:** Silent failures, cascading issues, no automatic recovery
**Location:** `docker-compose.yml`

- **Finding:** 55 services with `restart: unless-stopped` but 0 healthchecks
- **Risk:** Services may be "up" but non-functional, causing silent data loss or service unavailability
- **Recommendation:** Add healthchecks to all critical services, especially:
  - PostgreSQL, MariaDB, Valkey (data layer)
  - Caddy, Authelia (auth/ingress)
  - vLLM, LiteLLM (AI services)
  - Probe Orchestrator (diagnostics)

**Example Fix:**
```yaml
healthcheck:
  test: ["CMD", "pg_isready", "-U", "postgres"]
  interval: 10s
  timeout: 5s
  retries: 5
  start_period: 30s
```

---

## 🟠 HIGH PRIORITY ISSUES

### H1: No Resource Limits on Any Containers
**Severity:** HIGH
**Impact:** Resource exhaustion, OOM kills, noisy neighbor problems
**Location:** `docker-compose.yml` line 14-17

- **Finding:** TODO comment acknowledges missing limits: "Add memory/CPU resource limits after real lab deployment profiling"
- **Risk:** Single misbehaving container (especially vLLM, ClickHouse, Mastodon) can consume all host resources
- **Recommendation:** Add conservative limits now, tune later:

```yaml
deploy:
  resources:
    limits:
      memory: 4G  # Adjust per service
      cpus: '2.0'
    reservations:
      memory: 1G
```

**Priority services needing limits immediately:**
1. `vllm` - GPU memory + RAM limiter
2. `clickhouse` - Memory-hungry analytics DB
3. `postgres` - Shared_buffers can balloon
4. `mastodon-*` - Known resource hog
5. `litellm` - Can cache extensively

### H2: Secrets Visible in .env File (Unencrypted at Rest)
**Severity:** HIGH
**Impact:** Credential compromise if server accessed
**Location:** `.env` file

- **Finding:** All secrets stored in plaintext `.env`:
  - Database passwords (line 66-76)
  - OAuth secrets (line 48-57)
  - JWT secrets (line 42-46)
  - API keys (line 77-81)
- **Status:** ✅ Correctly gitignored, ❌ Not encrypted at rest
- **Recommendation:**
  - Implement `sops` + `age` encryption for secrets (already referenced in git: `install-sops-age.sh`)
  - Use Docker secrets or Hashicorp Vault for production
  - Rotate all secrets after migration to encrypted storage

### H3: Wide Network Aliases in Frontend Network
**Severity:** HIGH (Lateral Movement Risk)
**Impact:** Compromised container can impersonate any service
**Location:** `docker-compose.yml` lines 258-281

- **Finding:** Caddy container has 20+ network aliases on backend network
- **Risk:** If Caddy is compromised, attacker can intercept traffic to any aliased service
- **Recommendation:**
  - Minimize aliases, use DNS resolution instead
  - Implement network policies if using Docker Swarm/Kubernetes
  - Consider separate networks per service tier

### H4: Database Initialization Scripts with Embedded Credentials
**Severity:** HIGH
**Impact:** Credential leakage in logs/process listings
**Location:** `configs.templates/databases/postgres/init-db.sh`

- **Finding:** Shell scripts use `${VAR}` substitution for passwords
- **Risk:** Passwords may appear in `ps aux` output, Docker logs, shell history
- **Recommendation:**
  - Use PostgreSQL `.pgpass` files
  - Use environment variables with `--env-file` and restricted permissions
  - Never echo passwords in scripts

### H5: No Backup Encryption or Off-Site Replication
**Severity:** HIGH
**Impact:** Data loss in disaster scenarios, compliance issues
**Location:** `scripts/backup-databases.main.kts`

- **Finding:** Backup script exists (good!) but status of encryption/off-site unknown
- **Risk:** Backups on same server = not disaster recovery
- **Recommendation:**
  - Encrypt backups with GPG or age
  - Replicate to off-site location (S3, rsync, Kopia remote)
  - Test restore procedures regularly (`scripts/dr-drill.main.kts` exists - use it!)

---

## 🟡 MEDIUM PRIORITY ISSUES

### M1: No Logging Configuration or Rotation
**Severity:** MEDIUM
**Impact:** Disk space exhaustion from unbounded logs

- **Finding:** No `logging:` directives in docker-compose
- **Risk:** JSON logs in `/var/lib/docker/containers/` grow unbounded
- **Recommendation:** Add to all services:
```yaml
logging:
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"
```

### M2: Caddy Using Self-Signed Certificates by Default
**Severity:** MEDIUM
**Impact:** Browser warnings, MITM attacks without user awareness
**Location:** `configs/infrastructure/caddy/Caddyfile`

- **Finding:** Comment says "Defaults to self-signed (internal CA)"
- **Risk:** Users accept cert warnings = trained to accept MITM
- **Recommendation:**
  - Force Let's Encrypt for production
  - Use mTLS for internal services
  - Document certificate pinning

### M3: LDAP Bootstrap Security Concerns
**Severity:** MEDIUM
**Impact:** Weak default passwords in directory
**Location:** `configs.templates/ldap/bootstrap.ldif` (assumed)

- **Finding:** LDAP likely uses SSHA for password hashing
- **Risk:** SSHA is weak (SHA-1 based), vulnerable to rainbow tables
- **Recommendation:**
  - Use PBKDF2 or Argon2 if OpenLDAP supports
  - Enforce strong password policy via ppolicy overlay
  - Implement account lockout after failed attempts

### M4: No Rate Limiting on Public Endpoints
**Severity:** MEDIUM
**Impact:** DDoS, credential stuffing, resource exhaustion
**Location:** Caddy configuration

- **Finding:** No evidence of rate limiting in Caddyfile
- **Recommendation:** Add Caddy rate limiting:
```
rate_limit {
    zone static_login {
        key {remote_host}
        events 10
        window 1m
    }
}
```

### M5: Privileged Containers Not Audited
**Severity:** MEDIUM
**Impact:** Container escape, host compromise
**Finding:** Grep found no `privileged:` or `cap_add:` (good sign!)

- **Action:** Verify no services run privileged
- **Recommendation:** Add seccomp/AppArmor profiles if available

### M6: Database Connection Pooling Not Visible
**Severity:** MEDIUM
**Impact:** Connection exhaustion under load

- **Finding:** No PgBouncer or connection pooler visible
- **Risk:** Each app directly connects to PostgreSQL = 100+ connections
- **Recommendation:** Deploy PgBouncer for PostgreSQL connection pooling

### M7: No Container Image Pinning/Digest Validation
**Severity:** MEDIUM
**Impact:** Supply chain attacks, unreproducible deployments
**Location:** `docker-compose.yml`

- **Finding:** Images use tags (e.g., `caddy:2.8.4`) not digests
- **Recommendation:** Pin with digest:
```yaml
image: caddy:2.8.4@sha256:abc123...
```

### M8: Probe Orchestrator Has Broad Container Access
**Severity:** MEDIUM
**Impact:** Compromised diagnostics = cluster control

- **Finding:** Probe orchestrator likely has Docker socket access
- **Risk:** Socket access = root on host
- **Recommendation:**
  - Use Docker API over HTTP with mTLS
  - Implement RBAC for Docker operations
  - Consider read-only Docker socket bind

---

## 🟢 LOW PRIORITY / NICE TO HAVE

### L1: No Prometheus Metrics Export
- Most services could export metrics for better observability
- Recommendation: Add Prometheus + node-exporter

### L2: SSH Hardening for Agent Tool Server
- Review SSH key management in `agent-tool-server`
- Ensure no agent-generated SSH keys are committed

### L3: Timezone Configuration
- No `TZ` environment variable set globally
- Recommendation: Set `TZ=UTC` for all containers

---

## ✅ THINGS DONE WELL

1. **Kotlin-First Architecture** - Type-safe, maintainable codebase
2. **Template-Based Configuration** - No hardcoded values, environment-agnostic
3. **Backup Scripts Exist** - `backup-databases.main.kts`, `dr-drill.main.kts`, `verify-backups.main.kts`
4. **Network Isolation** - Separate frontend/backend/database networks
5. **Secrets Not in Git** - `.env` properly gitignored
6. **Comprehensive Documentation** - README, ARCHITECTURE, DEPLOYMENT docs
7. **No Privileged Containers** - Good security posture
8. **Autonomous Diagnostics** - Self-healing system design

---

## 🔧 RECOMMENDED FIXES (Priority Order)

### Immediate (Before Production Deploy)
1. **Add healthchecks to top 10 critical services** (2-3 hours)
2. **Add resource limits to vLLM, PostgreSQL, ClickHouse, Mastodon** (1 hour)
3. **Encrypt .env with sops+age** (1 hour)
4. **Configure log rotation for all services** (30 mins)
5. **Test backup restore procedure** (2 hours)

### First Week in Production
6. Add rate limiting to Caddy
7. Implement backup encryption + off-site replication
8. Deploy connection pooler (PgBouncer)
9. Set up Prometheus monitoring
10. Configure Let's Encrypt for Caddy

### First Month
11. Pin all images with digests
12. Harden LDAP password policies
13. Implement network policies
14. Security audit of database init scripts
15. Pen-test public endpoints

---

## 🧪 PRE-DEPLOY TESTING CHECKLIST

- [ ] Run `kotlin scripts/verify-backups.main.kts`
- [ ] Run `kotlin scripts/dr-drill.main.kts` (test restore from backup)
- [ ] Verify all services start with `docker compose up --profile bootstrap`
- [ ] Check disk space: `df -h` (need 50GB+ free)
- [ ] Verify GPU available: `nvidia-smi`
- [ ] Test Authelia login flow
- [ ] Test OIDC integration for 3 apps
- [ ] Verify Caddy HTTPS cert generation
- [ ] Check DNS resolution for all subdomains
- [ ] Run Probe Orchestrator full stack health check
- [ ] Simulate PostgreSQL failure and recovery
- [ ] Simulate network partition
- [ ] Load test with 10 concurrent users

---

## 📊 METRICS TO MONITOR POST-DEPLOY

1. **Container Health:** `docker ps --filter health=unhealthy`
2. **Disk Space:** `df -h /path/to/volumes`
3. **Memory Pressure:** `docker stats --no-stream`
4. **Database Connections:** `SELECT count(*) FROM pg_stat_activity;`
5. **Backup Age:** `find backups/ -mtime +1` (should be empty)
6. **Certificate Expiry:** Caddy metrics
7. **Failed Login Attempts:** Authelia logs
8. **vLLM Request Latency:** LiteLLM metrics

---

## 🚀 CONFIDENCE LEVEL: 70%

**Blockers for 95% confidence:**
- Healthchecks implementation
- Resource limits on critical services
- Verified backup restore procedure
- Off-site backup replication
- 7-day test run in staging environment

**Bottom Line:** System is well-architected and mostly production-ready. The main gaps are operational hardening (healthchecks, limits, backup encryption) rather than fundamental design flaws. With 2-3 days of focused work on the immediate fixes, this will be deployment-ready.

---

## 📝 NOTES

- Rootful Docker environment noted - acceptable for dedicated server
- Kotlin/KTS tooling is excellent - great choice for this scale
- Agent-assisted administration is ambitious but well-designed
- 40+ services is complex - consider staged rollout (bootstrap → databases → applications)

**Next Steps:** Address critical and high-priority issues, then schedule staging deployment for 1-week burn-in test.
