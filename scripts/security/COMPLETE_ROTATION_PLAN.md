# Complete Credential Rotation Implementation Plan

## 📊 Current Status vs. Target

**Target:** 50 automated credentials (Tier 0, 1, 2)
**Currently Implemented:** 4 rotation scripts

### ✅ Implemented (4 credentials)
- [x] AGENT_POSTGRES_OBSERVER_PASSWORD (Tier 0)
- [x] GRAFANA_DB_PASSWORD (Tier 0)
- [x] DATAMANCY_SERVICE_PASSWORD (Tier 0)
- [x] AUTHELIA_JWT_SECRET + SESSION + STORAGE + OIDC (Tier 0, 4 secrets in one script)

### ⏳ Missing (46 credentials)

---

## 🎯 TIER 0: Weekly Rotation (12 credentials total)

**Status: 8 remaining**

### Database Credentials
- [x] ~~AGENT_POSTGRES_OBSERVER_PASSWORD~~ (✅ Done)
- [ ] **POSTGRES_PASSWORD** - Root PostgreSQL password
  - Script: `rotate-postgres-root.main.kts`
  - Complexity: CRITICAL - All DB users must be recreated
  - Downtime: 0s (ALTER USER for all dependent accounts)
  - Dependencies: Must rotate BEFORE all other DB passwords

- [x] ~~GRAFANA_DB_PASSWORD~~ (✅ Done)

- [x] ~~DATAMANCY_SERVICE_PASSWORD~~ (✅ Done)

### Authelia Secrets
- [x] ~~AUTHELIA_JWT_SECRET~~ (✅ Done)
- [x] ~~AUTHELIA_SESSION_SECRET~~ (✅ Done)
- [x] ~~AUTHELIA_STORAGE_ENCRYPTION_KEY~~ (✅ Done)
- [x] ~~AUTHELIA_OIDC_HMAC_SECRET~~ (✅ Done)

### LDAP
- [ ] **LDAP_ADMIN_PASSWORD**
  - Script: `rotate-ldap-admin.main.kts`
  - Complexity: HIGH - Must update LDAP server + all LDAP clients
  - Downtime: 10s (restart lldap + authelia)
  - Affected: lldap, authelia, mailserver

### API Keys
- [ ] **LITELLM_MASTER_KEY**
  - Script: `rotate-litellm.main.kts`
  - Complexity: CRITICAL - All agents depend on this
  - Downtime: 30s (restart litellm + all agent services)
  - Affected: litellm + 10+ agent services

- [ ] **QDRANT_API_KEY**
  - Script: `rotate-qdrant.main.kts`
  - Complexity: MEDIUM
  - Downtime: 15s (restart qdrant + vector search services)
  - Affected: qdrant, vector-search-services

### Admin Passwords
- [ ] **STACK_ADMIN_PASSWORD**
  - Script: `rotate-stack-admin.main.kts`
  - Complexity: LOW - Single service
  - Downtime: 5s (update config + restart qwen)
  - Affected: qwen-stack-assistant

---

## 🎯 TIER 1: Bi-weekly Rotation (20 credentials)

**Status: 20 remaining**

### Database & Caching
- [ ] **REDIS_PASSWORD**
  - Script: `rotate-redis.main.kts`
  - Affected: redis, authelia, caching-services
  - Downtime: 30s

### Monitoring & Alerts
- [ ] **NTFY_PASSWORD**
  - Script: `rotate-ntfy.main.kts`
  - Affected: ntfy, notification-services
  - Downtime: 10s

- [ ] **GRAFANA_ADMIN_PASSWORD**
  - Script: `rotate-grafana-admin.main.kts`
  - Affected: grafana
  - Downtime: 5s (admin user only, not DB)

- [ ] **PROMETHEUS_PASSWORD**
  - Script: `rotate-prometheus.main.kts`
  - Affected: prometheus, grafana
  - Downtime: 15s

- [ ] **LOKI_PASSWORD**
  - Script: `rotate-loki.main.kts`
  - Affected: loki, grafana
  - Downtime: 15s

### Infrastructure
- [ ] **TRAEFIK_API_TOKEN**
  - Script: `rotate-traefik.main.kts`
  - Affected: traefik (reverse proxy)
  - Downtime: 10s

### Agent API Keys (14 credentials)
- [ ] **AGENT_SUPERVISOR_API_KEY**
  - Script: `rotate-agent-keys.main.kts` (handles all agent keys)
  - Can be rotated as a batch
  - Update .env + restart each agent

- [ ] AGENT_CODE_WRITER_API_KEY
- [ ] AGENT_CODE_READER_API_KEY
- [ ] AGENT_DATA_FETCHER_API_KEY
- [ ] AGENT_ORCHESTRATOR_API_KEY
- [ ] AGENT_TRIAGE_API_KEY
- [ ] SCHEDULER_API_KEY
- [ ] API_SERVICE_KEY
- [ ] WEBHOOK_SECRET
- [ ] ENCRYPTION_KEY_DATA
- [ ] ENCRYPTION_KEY_LOGS
- [ ] SESSION_SECRET_API
- [ ] CSRF_TOKEN_SECRET
- [ ] JWT_SECRET_API

---

## 🎯 TIER 2: Monthly Rotation (18 credentials)

**Status: 18 remaining**

### Backup & Storage
- [ ] **BACKUP_ENCRYPTION_KEY**
  - Script: `rotate-backup-encryption.main.kts`
  - CRITICAL: Must re-encrypt existing backups or keep old key for restore

- [ ] **S3_ACCESS_KEY + S3_SECRET_KEY**
  - Script: `rotate-s3.main.kts`
  - Affected: MinIO/S3 storage, all services using object storage
  - Note: Must update both keys together

### Email
- [ ] **SMTP_PASSWORD**
  - Script: `rotate-smtp.main.kts`
  - Affected: mailserver, notification services

### External API Tokens (Provider-Managed)
- [ ] **GITHUB_TOKEN**
  - Script: `rotate-github-token.main.kts`
  - Note: Generate new token via GitHub UI, update .env

- [ ] **GITLAB_TOKEN**
  - Script: `rotate-gitlab-token.main.kts`

- [ ] **DISCORD_BOT_TOKEN**
  - Script: `rotate-discord-bot.main.kts`

- [ ] **SLACK_BOT_TOKEN**
  - Script: `rotate-slack-bot.main.kts`

- [ ] **OPENAI_API_KEY**
  - Script: `rotate-openai-key.main.kts`
  - Note: User must generate via OpenAI dashboard

- [ ] **ANTHROPIC_API_KEY**
  - Script: `rotate-anthropic-key.main.kts`
  - Note: User must generate via Anthropic console

### Monitoring & Observability
- [ ] **MONITORING_API_KEY**
  - Script: `rotate-monitoring-keys.main.kts` (batch)
- [ ] METRICS_COLLECTOR_KEY
- [ ] LOG_AGGREGATOR_KEY
- [ ] ALERT_MANAGER_KEY

### Certificates & Keystores
- [ ] **CERTIFICATE_PASSWORD**
  - Script: `rotate-cert-passwords.main.kts` (batch)
- [ ] KEYSTORE_PASSWORD
- [ ] TRUSTSTORE_PASSWORD

### VPN
- [ ] **VPN_SHARED_SECRET**
  - Script: `rotate-vpn-secret.main.kts`

---

## 🚫 EXCLUDED: Manual Only (8 credentials)

**Status: Never automated (by design)**

- [ ] AUTHELIA_OIDC_ISSUER_PRIVATE_KEY (RSA key - too complex)
- [ ] VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY (coordinated client update needed)
- [ ] SSH_PRIVATE_KEY (requires distribution)
- [ ] GPG_PRIVATE_KEY (trust chain updates)
- [ ] TLS_CERTIFICATE / TLS_PRIVATE_KEY (Let's Encrypt handles this)
- [ ] ROOT_CA_KEY (never rotate automatically)

---

## 📋 Implementation Checklist

### Phase 1: Complete Tier 0 (8 remaining) - Priority: CRITICAL
- [ ] 1.1 Create `rotate-postgres-root.main.kts`
- [ ] 1.2 Create `rotate-ldap-admin.main.kts`
- [ ] 1.3 Create `rotate-litellm.main.kts`
- [ ] 1.4 Create `rotate-qdrant.main.kts`
- [ ] 1.5 Create `rotate-stack-admin.main.kts`
- [ ] 1.6 Update `weekly-rotation.main.kts` to include all 12 Tier 0
- [ ] 1.7 Test all Tier 0 rotations with --test-failure
- [ ] 1.8 Verify weekly rotation completes in <30 min

### Phase 2: Tier 1 Database & Infrastructure (6 credentials) - Priority: HIGH
- [ ] 2.1 Create `rotate-redis.main.kts`
- [ ] 2.2 Create `rotate-ntfy.main.kts`
- [ ] 2.3 Create `rotate-grafana-admin.main.kts`
- [ ] 2.4 Create `rotate-prometheus.main.kts`
- [ ] 2.5 Create `rotate-loki.main.kts`
- [ ] 2.6 Create `rotate-traefik.main.kts`

### Phase 3: Tier 1 Agent Keys (14 credentials) - Priority: MEDIUM
- [ ] 3.1 Create `rotate-agent-keys.main.kts` (batch script for all agents)
- [ ] 3.2 Test rolling restart of agent services
- [ ] 3.3 Create `bi-weekly-rotation.main.kts` orchestrator

### Phase 4: Tier 2 Critical (6 credentials) - Priority: MEDIUM
- [ ] 4.1 Create `rotate-backup-encryption.main.kts`
- [ ] 4.2 Create `rotate-s3.main.kts`
- [ ] 4.3 Create `rotate-smtp.main.kts`
- [ ] 4.4 Create `rotate-external-tokens.main.kts` (GitHub, GitLab, etc.)

### Phase 5: Tier 2 Monitoring & Certs (12 credentials) - Priority: LOW
- [ ] 5.1 Create `rotate-monitoring-keys.main.kts` (batch)
- [ ] 5.2 Create `rotate-cert-passwords.main.kts` (batch)
- [ ] 5.3 Create `rotate-vpn-secret.main.kts`
- [ ] 5.4 Create `monthly-rotation.main.kts` orchestrator

### Phase 6: Integration & Testing
- [ ] 6.1 Update systemd service for bi-weekly timer
- [ ] 6.2 Update systemd service for monthly timer
- [ ] 6.3 Test full Tier 0 rotation (12 creds)
- [ ] 6.4 Test full Tier 1 rotation (20 creds)
- [ ] 6.5 Test full Tier 2 rotation (18 creds)
- [ ] 6.6 Break testing for all new scripts

---

## 🎯 Batch Rotation Strategy

Some credentials can be rotated together to reduce complexity:

### Batch 1: Agent API Keys (14 credentials)
**Single script:** `rotate-agent-keys.main.kts`
- All agent keys are simple .env updates
- No database changes needed
- Can restart services in rolling fashion

### Batch 2: Monitoring Keys (4 credentials)
**Single script:** `rotate-monitoring-keys.main.kts`
- All monitoring services follow same pattern
- Update config + restart

### Batch 3: Certificate Passwords (3 credentials)
**Single script:** `rotate-cert-passwords.main.kts`
- Keystore/truststore passwords
- Low risk, rare changes

### Batch 4: External Tokens (6 credentials)
**Semi-automated:** `rotate-external-tokens.main.kts`
- Guide user through provider dashboards
- Update .env with new tokens
- Test connectivity

---

## ⏱️ Estimated Timeline

### Aggressive (This Week)
- **Day 1:** Complete Tier 0 (8 scripts) - 8 hours
- **Day 2:** Test Tier 0 + Start Tier 1 (6 scripts) - 6 hours
- **Day 3:** Complete Tier 1 (14 agent keys) - 4 hours
- **Day 4:** Tier 2 critical (6 scripts) - 4 hours
- **Day 5:** Tier 2 remainder + testing - 4 hours

**Total:** ~26 hours over 5 days

### Conservative (This Month)
- **Week 1:** Tier 0 complete + tested
- **Week 2:** Tier 1 database/infrastructure
- **Week 3:** Tier 1 agent keys
- **Week 4:** Tier 2 + comprehensive testing

---

## 📊 Final Stats

| Category | Total | Implemented | Remaining | Priority |
|----------|-------|-------------|-----------|----------|
| **Tier 0 (weekly)** | 12 | 4 | 8 | 🔴 CRITICAL |
| **Tier 1 (bi-weekly)** | 20 | 0 | 20 | 🟠 HIGH |
| **Tier 2 (monthly)** | 18 | 0 | 18 | 🟡 MEDIUM |
| **Excluded (manual)** | 8 | - | - | ⚪ N/A |
| **TOTAL AUTOMATED** | **50** | **4** | **46** | |

---

## 🚀 Next Immediate Actions

1. **Complete Tier 0 (8 remaining scripts)** - These rotate weekly and are critical
2. **Update weekly-rotation.main.kts** - Add all 12 Tier 0 rotations
3. **Test complete Tier 0 rotation** - Verify <30 min execution time
4. **Move to Tier 1** - After Tier 0 proven stable

---

Want me to start implementing the remaining Tier 0 scripts NOW? We need:
- `rotate-postgres-root.main.kts`
- `rotate-ldap-admin.main.kts`
- `rotate-litellm.main.kts`
- `rotate-qdrant.main.kts`
- `rotate-stack-admin.main.kts`

Then update `weekly-rotation.main.kts` to orchestrate all 12 Tier 0 credentials.

After that, we'll tackle Tier 1 and Tier 2 systematically.
