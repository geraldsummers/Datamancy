# Pre-Deployment Security & Reliability Review

**Generated**: 2025-12-03
**Reviewer**: Claude (Automated Analysis)
**Target**: Production deployment to lab server

---

## Executive Summary

Overall assessment: **STRONG** ✓ with some **CRITICAL** items to address before production.

Your sovereign compute cluster is well-architected with excellent security fundamentals:
- ✅ Secrets isolated in `~/.config/datamancy/` outside git
- ✅ SSHA password hashing for LDAP
- ✅ Comprehensive OIDC/SSO via Authelia
- ✅ Docker socket proxy with restricted permissions
- ✅ Network segmentation (frontend/backend/database)
- ✅ Health checks on all services
- ⚠️ Resource limits missing (TODO noted)
- ⚠️ One privileged container (vm-provisioner)

---

## CRITICAL: Must Fix Before Production

### 1. ⚠️ Privileged Container - VM Provisioner
**Location**: `docker-compose.yml:2001`

```yaml
vm-provisioner:
  privileged: true  # Required for libvirt/QEMU access
```

**Risk**: Full host access, container escape possible
**Impact**: If vm-provisioner is compromised, attacker gains root on host

**Recommendations**:
```yaml
# Option A: Use specific capabilities instead of privileged
cap_add:
  - NET_ADMIN
  - SYS_ADMIN  # Only if truly needed for libvirt
security_opt:
  - apparmor=unconfined  # Or custom AppArmor profile

# Option B: Run libvirt on host, access via socket
volumes:
  - /var/run/libvirt/libvirt-sock:/var/run/libvirt/libvirt-sock
# Remove privileged: true
```

**Action**:
1. Test with capabilities first
2. If that fails, document WHY privileged is needed
3. Add network isolation (separate network for vm-provisioner)
4. Consider moving VM provisioning to separate physical host

---

### 2. ⚠️ Missing Resource Limits
**Location**: `docker-compose.yml:15-17` (TODO comment)

```yaml
# TODO PRE-PRODUCTION:
# - Add memory/CPU resource limits after real lab deployment profiling
# - Priority services needing limits: vllm, clickhouse, postgres, mastodon-*
```

**Risk**: One service can consume all resources, causing stack-wide outage

**Current State**: Only 3 services have limits:
- postgres: 4G RAM, 2 CPUs ✓
- vllm: 8G RAM, 4 CPUs ✓
- mastodon-web/sidekiq: 2G RAM, 2 CPUs ✓

**Missing Limits** (HIGH PRIORITY):
```yaml
clickhouse:
  deploy:
    resources:
      limits:
        memory: 4G
        cpus: '2.0'
      reservations:
        memory: 2G

qdrant:
  deploy:
    resources:
      limits:
        memory: 2G
        cpus: '2.0'

redis:
  deploy:
    resources:
      limits:
        memory: 512M  # Valkey/Redis is memory-efficient

mariadb:
  deploy:
    resources:
      limits:
        memory: 2G
        cpus: '2.0'
```

**Action Plan**:
1. Deploy to test server first
2. Run load tests, monitor `docker stats`
3. Add limits with 20% headroom above observed peak
4. Test stack restart with limits enabled

---

### 3. ⚠️ LDAP Admin Password in Environment Variable
**Location**: `docker-compose.yml:352` (healthcheck)

```yaml
test: ["CMD", "ldapsearch", "-x", "-H", "ldap://localhost:389",
       "-D", "cn=admin,dc=stack,dc=local",
       "-w", "${STACK_ADMIN_PASSWORD}"]
```

**Risk**: Password visible in `docker inspect ldap` and process list

**Mitigation**: This is acceptable for healthchecks (low risk) but be aware:
- Password only visible to root and container user
- Healthchecks run inside container, not exposed externally
- Already using SSHA hashes in bootstrap_ldap.ldif ✓

**Best Practice**: Consider using LDAP EXTERNAL auth (SASL) for future hardening

---

### 4. ⚠️ Unvalidated Template Variables
**Location**: `scripts/core/process-config-templates.main.kts:137-139`

```kotlin
if (missingVars.isNotEmpty()) {
    warn("Missing variables in $filePath: ${missingVars.joinToString(", ")}")
}
// WARNING: Script continues even with missing vars!
```

**Risk**: Config files deployed with `{{UNSET_VARIABLE}}` placeholders

**Fix**:
```kotlin
if (missingVars.isNotEmpty()) {
    error("Missing variables in $filePath: ${missingVars.joinToString(", ")}")
    exitProcess(1)  // FAIL HARD - don't deploy broken configs
}
```

**Alternative** (non-breaking):
```kotlin
val criticalVars = setOf("DOMAIN", "STACK_ADMIN_PASSWORD", "LITELLM_MASTER_KEY")
val criticalMissing = missingVars.filter { it in criticalVars }
if (criticalMissing.isNotEmpty()) {
    error("CRITICAL variables missing: ${criticalMissing.joinToString(", ")}")
    exitProcess(1)
}
if (missingVars.isNotEmpty()) {
    warn("Optional variables missing: ${missingVars.joinToString(", ")}")
}
```

---

## HIGH PRIORITY: Security Hardening

### 5. SSH Key Bootstrap - MITM Risk
**Location**: `configs.templates/infrastructure/ssh/bootstrap_known_hosts.sh`

**Current Behavior**: Scans SSH host keys on first run, trusts them permanently

**Risk**: If attacker performs MITM during initial bootstrap, malicious keys are trusted

**Mitigation Options**:

**Option A**: Pin expected SSH host key fingerprint in .env
```bash
# In .env
TOOLSERVER_SSH_HOST_KEY_FINGERPRINT=SHA256:abc123def456...

# In bootstrap script
EXPECTED_FP="${TOOLSERVER_SSH_HOST_KEY_FINGERPRINT}"
ACTUAL_FP=$(ssh-keyscan $HOST | ssh-keygen -lf -)
if [ "$ACTUAL_FP" != "$EXPECTED_FP" ]; then
    echo "ERROR: SSH host key mismatch!" >&2
    exit 1
fi
```

**Option B**: Manual verification step
```bash
# Add to stack-controller.main.kts up --profile=bootstrap
echo "Verify SSH host key fingerprint:"
ssh-keyscan $TOOLSERVER_SSH_HOST | ssh-keygen -lf -
read -p "Does this match your server's fingerprint? (yes/no): " confirm
[ "$confirm" != "yes" ] && exit 1
```

**Current Risk Level**: Medium (only affects agent-tool-server SSH operations)

---

### 6. Authelia Session Lifetime Too Long
**Location**: `configs.templates/applications/authelia/configuration.yml:87-88`

```yaml
session:
  expiration: 1h
  inactivity: 5m
  remember_me: 1M  # ← 1 MONTH
```

**Risk**: Stolen "remember me" cookie valid for 30 days

**Recommendation**:
```yaml
remember_me: 7d  # 1 week is more reasonable
```

**For Production Systems**:
```yaml
remember_me: 1d  # 24 hours for high-security environments
```

---

### 7. Docker Socket Proxy Permissions
**Location**: `docker-compose.yml:898-918`

**Current State**: Excellent! ✓
```yaml
CONTAINERS=1   # Read-only container status
POST=0         # No creation/modification ✓
EXEC=0         # No command execution ✓
IMAGES=0       # No image enumeration ✓
```

**However**: `portainer` and `dockge` services depend on this proxy.

**Issue**: Portainer typically needs write access to manage containers, but your proxy blocks it.

**Check**: Does Portainer work correctly? If not:
```yaml
# For Portainer/Dockge to work (controlled access)
docker-proxy-admin:
  image: tecnativa/docker-socket-proxy:v0.4.1
  environment:
    - CONTAINERS=1
    - POST=1        # Allow container operations
    - START=1       # Allow start/stop
    - STOP=1
    - RESTART=1
    - DELETE=0      # Prevent deletion (optional)
    - IMAGES=1      # Allow image listing
  networks:
    - admin  # Separate network for admin tools only
```

Then point Portainer to `tcp://docker-proxy-admin:2375`

---

### 8. Mailu Email Ports Exposed
**Location**: `docker-compose.yml:601-607`

```yaml
ports:
  - "25:25"     # SMTP - open to internet
  - "465:465"   # SMTPS
  - "587:587"   # Submission
  - "993:993"   # IMAPS
```

**Risk**: These ports are REQUIRED for email, but also attacked constantly.

**Recommendations**:

1. **Rate Limiting** (add to Mailu config):
```python
# In mailu.env
POSTFIX_MESSAGE_SIZE_LIMIT=50000000  # 50MB
RATELIMIT_STORAGE_URL=redis://redis:6379/1
RATELIMIT_PER_USER=100/hour
```

2. **Fail2ban** (add to host):
```bash
# On host server
apt-get install fail2ban
# Add Mailu filters (they provide fail2ban configs)
```

3. **DMARC/DKIM/SPF** (verify these are configured):
```bash
# Check DKIM keys generated
docker exec mailu-admin dkim-keygen
# Add to DNS:
# TXT _dmarc.yourdomain.com "v=DMARC1; p=quarantine; rua=mailto:postmaster@yourdomain.com"
```

---

## MEDIUM PRIORITY: Operational Concerns

### 9. No Backup Verification
**Location**: `docs/DEPLOYMENT.md:576-639` (Backup section)

**Current State**: Backup scripts exist ✓, but no verification

**Problem**: Backups might be corrupted and you won't know until disaster strikes

**Add Verification**:
```bash
#!/bin/bash
# backup-with-verify.sh

BACKUP_DIR="/backup/datamancy-$(date +%Y%m%d)"
mkdir -p "$BACKUP_DIR"

# Backup
tar -czf "$BACKUP_DIR/volumes.tar.gz" volumes/
tar -czf "$BACKUP_DIR/runtime-config.tar.gz" ~/.config/datamancy/

# VERIFY
echo "Verifying backups..."
tar -tzf "$BACKUP_DIR/volumes.tar.gz" >/dev/null || { echo "CORRUPTED: volumes.tar.gz"; exit 1; }
tar -tzf "$BACKUP_DIR/runtime-config.tar.gz" >/dev/null || { echo "CORRUPTED: runtime-config.tar.gz"; exit 1; }

# Test restore (dry-run)
mkdir -p /tmp/backup-test
tar -xzf "$BACKUP_DIR/runtime-config.tar.gz" -C /tmp/backup-test
[ -f "/tmp/backup-test/$HOME/.config/datamancy/.env.runtime" ] || { echo "MISSING .env.runtime"; exit 1; }

echo "✓ Backups verified successfully"
```

**Add to cron**:
```cron
0 2 * * * /root/backup-with-verify.sh && echo "Backup OK" || mail -s "BACKUP FAILED" admin@yourdomain.com
```

---

### 10. No Centralized Logging
**Current State**: Logs only in `docker logs` (ephemeral)

**Risk**: Logs lost on container restart, hard to debug multi-service issues

**Recommendation**: Add Loki + Promtail (already have Grafana)
```yaml
loki:
  image: grafana/loki:2.9.3
  networks: [backend]
  volumes:
    - loki_data:/loki

promtail:
  image: grafana/promtail:2.9.3
  networks: [backend]
  volumes:
    - /var/lib/docker/containers:/var/lib/docker/containers:ro
    - ./promtail-config.yml:/etc/promtail/config.yml
```

**Alternative**: Use existing `docker-compose logs` with rotation:
```yaml
# Add to all services
logging:
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"
```
(Already done for some services ✓, apply to all)

---

### 11. No Health Check Aggregation
**Current State**: 40+ services with health checks, no central dashboard

**Problem**: Hard to see "is stack healthy?"

**Quick Fix**: Add to stack-controller.main.kts:
```kotlin
private fun cmdHealth() {
    info("Checking stack health...")
    val result = run("docker", "compose", "ps", "--format", "json", allowFail = true)
    val services = result.lines()
        .filter { it.trim().isNotEmpty() }
        .map { Json.decodeFromString<ServiceStatus>(it) }

    val unhealthy = services.filter { it.health != "healthy" && it.health != null }

    if (unhealthy.isEmpty()) {
        success("All services healthy")
    } else {
        warn("Unhealthy services:")
        unhealthy.forEach {
            println("  - ${it.service}: ${it.health}")
        }
        exitProcess(1)
    }
}

// Add to main():
"health" -> cmdHealth()
```

---

### 12. LDAP Bootstrap Regenerates on Every Config Change
**Location**: `stack-controller.main.kts:545-565`

**Behavior**: `./stack-controller ldap bootstrap` regenerates SSHA hashes every time

**Problem**: LDAP admin password hash changes, but running LDAP service has old hash

**Impact**: Admin can't login after regenerating bootstrap file

**Solution**: Add to docs/DEPLOYMENT.md:
```markdown
**WARNING**: LDAP bootstrap file is ONLY loaded on first container creation.

If you regenerate bootstrap_ldap.ldif after LDAP is running:
1. Stop LDAP: `docker compose stop ldap`
2. Delete LDAP data: `rm -rf volumes/ldap_data volumes/ldap_config`
3. Restart: `docker compose up -d ldap`

OR change password via LDAP Account Manager instead.
```

**Alternative**: Add idempotency check to generator:
```kotlin
// In generate-ldap-bootstrap.main.kts
if (outputFile.exists()) {
    warn("LDAP bootstrap already exists: ${outputFile.absolutePath}")
    warn("This file is only loaded on first LDAP startup.")
    warn("To regenerate: docker compose stop ldap && rm -rf volumes/ldap_*")
    if (!args.contains("--force")) {
        error("Use --force to overwrite")
        exitProcess(1)
    }
}
```

---

## LOW PRIORITY: Nice-to-Haves

### 13. Hardcoded Database Passwords in Init Scripts
**Location**: `docker-compose.yml:471` (example)

```yaml
volumes:
  - ${HOME}/.config/datamancy/configs/databases/mariadb/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
```

**Question**: Does init.sql contain hardcoded passwords or use env vars?

**Check**:
```bash
grep -i password ~/.config/datamancy/configs/databases/*/init*
```

**Best Practice**: Use env var substitution:
```sql
-- Good
CREATE USER 'grafana'@'%' IDENTIFIED BY '${GRAFANA_DB_PASSWORD}';

-- Bad (if present)
CREATE USER 'grafana'@'%' IDENTIFIED BY 'hardcoded_password_123';
```

---

### 14. Missing Prometheus for Metrics
**Current**: Grafana ✓, but no metrics source (besides Authelia telemetry)

**Add**:
```yaml
prometheus:
  image: prom/prometheus:v2.47.0
  networks: [backend]
  volumes:
    - prometheus_data:/prometheus
    - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro

cadvisor:
  image: gcr.io/cadvisor/cadvisor:v0.47.2
  networks: [backend]
  volumes:
    - /:/rootfs:ro
    - /var/run:/var/run:ro
    - /sys:/sys:ro
    - /var/lib/docker/:/var/lib/docker:ro
  privileged: true  # Required for cgroups access
```

**Benefit**: CPU/RAM/disk metrics per container in Grafana

---

### 15. No Automated SSL Certificate Renewal Check
**Current**: Caddy handles Let's Encrypt auto-renewal ✓

**Issue**: If renewal fails (rate limit, DNS issue), you won't know until certs expire

**Add Monitoring**:
```bash
#!/bin/bash
# check-ssl-expiry.sh
DOMAIN="yourdomain.com"
EXPIRY=$(echo | openssl s_client -servername $DOMAIN -connect $DOMAIN:443 2>/dev/null | openssl x509 -noout -enddate | cut -d= -f2)
EXPIRY_EPOCH=$(date -d "$EXPIRY" +%s)
NOW_EPOCH=$(date +%s)
DAYS_LEFT=$(( ($EXPIRY_EPOCH - $NOW_EPOCH) / 86400 ))

if [ $DAYS_LEFT -lt 7 ]; then
    echo "WARNING: SSL expires in $DAYS_LEFT days!" | mail -s "SSL Expiry Warning" admin@$DOMAIN
fi
```

---

## POSITIVE FINDINGS ✓

### What You Got Right

1. **Secrets Management** ✓✓✓
   - All secrets in `~/.config/datamancy/`, never in git
   - SSHA password hashing for LDAP
   - Separate secret generation script

2. **Network Segmentation** ✓✓
   - frontend/backend/database networks
   - Services only join networks they need
   - No unnecessary `network_mode: host`

3. **OIDC/SSO** ✓✓
   - Centralized auth via Authelia
   - All major services integrated
   - RBAC with admins/users groups

4. **Health Checks** ✓✓
   - Every service has a health check
   - Proper dependency ordering with `condition: service_healthy`

5. **Docker Socket Proxy** ✓
   - Read-only access
   - Minimized permissions (EXEC=0, POST=0)

6. **Documentation** ✓
   - Comprehensive DEPLOYMENT.md
   - Stack controller with help text
   - Architecture documented

7. **SSH Security** ✓
   - Ed25519 keys (better than RSA)
   - Known hosts validation
   - Forced command wrapper for stackops user

8. **Reproducible Config** ✓
   - Template processing script
   - Automated secret generation
   - Clear separation: templates (git) vs runtime (local)

---

## Pre-Deployment Checklist

Copy this to your deployment runbook:

```markdown
## Pre-Flight Checks

### Security
- [ ] Fix: Change template processor to fail on missing critical vars
- [ ] Fix: Add resource limits to clickhouse, qdrant, redis, mariadb
- [ ] Review: vm-provisioner needs privileged? If yes, document why
- [ ] Test: Verify Portainer/Dockge work with restricted docker-proxy
- [ ] Configure: Reduce Authelia remember_me from 1M to 7d
- [ ] Deploy: Add fail2ban for Mailu ports (25, 587, 993)

### Configuration
- [ ] Set DOMAIN=your-production-domain.com in ~/.config/datamancy/.env.runtime
- [ ] Set HUGGINGFACEHUB_API_TOKEN if using private models
- [ ] Regenerate configs: ./stack-controller config process
- [ ] Regenerate LDAP: ./stack-controller ldap bootstrap
- [ ] Verify: Check for {{UNREPLACED}} placeholders in configs

### DNS
- [ ] Configure A records for *.yourdomain.com -> SERVER_IP
- [ ] Test: nslookup auth.yourdomain.com
- [ ] Test: nslookup open-webui.yourdomain.com

### SSL/TLS
- [ ] Edit Caddyfile: Add tls admin@yourdomain.com to each site block
- [ ] Or: Keep self-signed for internal network (current default)
- [ ] Test: curl -k https://yourdomain.com (should return Caddy 404, not connection error)

### Backups
- [ ] Create backup directory: mkdir -p /backup/datamancy
- [ ] Add backup-with-verify.sh to cron (see section 9)
- [ ] Test restore process once before going live
- [ ] Backup ~/.config/datamancy/ to secure location (contains ALL secrets)

### Monitoring
- [ ] Deploy Prometheus + cAdvisor (optional, see section 14)
- [ ] Create Grafana dashboard for container metrics
- [ ] Set up email alerts for failed health checks
- [ ] Add SSL expiry monitoring (see section 15)

### Testing
- [ ] Deploy to test server first
- [ ] Run for 24 hours with load testing
- [ ] Monitor `docker stats` to set proper resource limits
- [ ] Test LDAP login: ldapsearch -x -H ldap://localhost:389 -D uid=admin,ou=users,dc=stack,dc=local -w "$STACK_ADMIN_PASSWORD" -b dc=stack,dc=local
- [ ] Test SSO: Login to Grafana, Open WebUI, Portainer via Authelia
- [ ] Test service restarts: docker compose restart <service>
- [ ] Test full stack restart: docker compose down && docker compose up -d

### Day 0 Operations
- [ ] Document any manual steps taken during deployment
- [ ] Create admin accounts in LDAP (via LAM: https://lam.yourdomain.com)
- [ ] Set up Mailu email accounts for vaultwarden, notifications
- [ ] Configure DNS: DMARC, DKIM, SPF records for email
- [ ] Enable 2FA for admin accounts (Authelia TOTP)
```

---

## Recommended Deployment Timeline

### Phase 1: Test Environment (Week 1)
1. Deploy to test server
2. Fix critical issues (privileged container, resource limits)
3. Load testing (run probe orchestrator, stress vLLM)
4. Tune resource limits based on observed usage
5. Verify backups and restores work

### Phase 2: Production Staging (Week 2)
1. Deploy to production server (limited users)
2. Configure DNS and SSL (Let's Encrypt)
3. Set up monitoring and alerting
4. Add fail2ban for email ports
5. Create admin/user accounts
6. Run for 1 week with team members only

### Phase 3: Production (Week 3+)
1. Open to research team
2. Document any issues encountered
3. Weekly backup verification
4. Monthly security review (check for CVEs in images)

---

## Final Notes

This is an **impressive** piece of infrastructure engineering. The architecture is sound, security is mostly excellent, and the documentation is thorough.

The critical issues are:
1. **Resource limits** - Must add before production
2. **Privileged container** - Try to remove or isolate
3. **Template validation** - Make it fail on missing vars

Everything else is medium/low priority or operational concerns you can address iteratively.

**You're in good shape for a production deployment with the fixes above.** 🚀

---

## Questions for Operator

Before deployment, answer these:

1. **VM Provisioner**: Do you actually need vm-provisioner in production? If not, disable the `compute` profile.

2. **GPU**: Is NVIDIA GPU available on target server? vLLM will fail without it.

3. **Email**: Are you sending real email via Mailu? If not, you can disable the `applications` profile for Mailu services.

4. **Backups**: Where will backups be stored? External NAS? Cloud storage (S3)?

5. **Monitoring**: Who monitors the stack? Email alerts or PagerDuty integration needed?

6. **Users**: How many concurrent users expected? May need to increase resource limits.

---

**Review Complete.** Let me know what you'd like to tackle first!
