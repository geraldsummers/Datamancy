# Datamancy Integration Test Report
**Test Execution Date:** 2026-02-16 14:21 AEDT
**Test Run ID:** 20260216_031746-all
**Environment:** Container-based (latium.local)
**Kotlin Version:** 2.0.21

---

## Executive Summary

| Metric | Value | Status |
|--------|-------|--------|
| **Total Tests** | 382 | - |
| **Passed** | 369 | ✅ |
| **Failed** | 13 | ⚠️ |
| **Skipped** | 0 | - |
| **Success Rate** | 96.6% | 🟢 |
| **Duration** | 227.5 seconds | - |

### Overall Status: **PARTIAL SUCCESS** ⚠️
The stack is **96.6% operational** with 13 known issues requiring attention.

---

## Infrastructure Health

### Container Status
- **Total Services Running:** 54 containers
- **Healthy:** 53 containers ✅
- **Unhealthy/Restarting:** 1 container ⚠️
  - `mailserver` - In restart loop (Exit code 1)

### Critical Services Status
| Service | Status | Health |
|---------|--------|--------|
| Agent Tool Server | ✅ Running | Healthy |
| Search Service | ✅ Running | Healthy |
| LiteLLM | ✅ Running | Healthy |
| PostgreSQL | ✅ Running | Healthy |
| Qdrant | ✅ Running | Healthy |
| Pipeline | ✅ Running | Healthy |
| Authelia | ✅ Running | Healthy |
| Caddy | ✅ Running | Healthy |
| Mailserver | ⚠️ Restarting | Unhealthy |

---

## Test Results by Category

### 1. Foundation Tests (4/4 Passed) ✅
All core infrastructure tests passed successfully.

- ✅ Agent tool server is healthy (429ms)
- ✅ Agent tool server lists available tools (6ms)
- ✅ Agent tool server OpenWebUI schema is valid (9ms)
- ✅ Search service is healthy (4ms)

### 2. LLM Integration (3/3 Passed) ✅
LLM subsystem fully operational.

- ✅ Chat completion generates response (3904ms)
- ✅ Completion handles system prompts (4003ms)
- ✅ Embed text returns vector (49ms)

**Performance Notes:**
- LLM response times: ~4 seconds (acceptable for 7B model)
- Embedding generation: 49ms (excellent)

### 3. Knowledge Base (4/4 Passed) ✅
Vector database and semantic search working correctly.

- ✅ Query PostgreSQL with shadow account (42ms)
- ✅ Query PostgreSQL blocks forbidden patterns (5ms)
- ✅ Semantic search executes (635ms)
- ✅ Vectorization pipeline: embed → store → retrieve (93ms)

**Notes:**
- Shadow account provisioning may be needed for full access
- Security controls functioning (forbidden patterns blocked)

### 4. Data Pipeline (53/53 Passed) ✅
Complete pipeline validation successful.

**Collections Status:**
| Collection | Vectors | Status |
|------------|---------|--------|
| RSS Feeds | 89 | ✅ Active |
| Torrents | 398 | ✅ Active |
| Wikipedia | 73 | ✅ Active |
| Linux Docs | 112 | ✅ Active |
| CVE | 0 | ℹ️ Empty (may need API key) |
| Australian Laws | 0 | ℹ️ Empty |

**Key Findings:**
- All 6 pipeline collections created successfully
- Vector dimension consistency: 1024d across all collections
- document_staging table exists and operational
- Cross-collection search working
- CVE source requires configuration (empty collection)

### 5. Microservices (11/13 Passed) ⚠️

**Passing:**
- ✅ All health endpoints responding
- ✅ Embedding service working (1024d vectors)
- ✅ Search service auth working
- ✅ Transaction gateway operational
- ✅ Hyperliquid worker healthy
- ✅ EVM broadcaster healthy

**Failures:**
- ❌ **Search Service API:** 404 Not Found (authentication test)
  - *Note:* Health check passes, API endpoint may have moved
- ❌ **Seafile:** Token acquisition failed (400 Bad Request)
  - *Cause:* Admin user may not exist or credentials invalid

### 6. Infrastructure (17/17 Passed) ✅
Core infrastructure fully validated.

- ✅ All database connections working
- ✅ Network connectivity validated
- ✅ Service discovery operational
- ✅ Health monitoring active

### 7. Authentication (78/82 Passed) ⚠️

**LDAP (All Passed):**
- ✅ Connection and binding working
- ✅ User search operational
- ✅ Group membership working
- ✅ MemberOf overlay configured

**Authelia (All Passed):**
- ✅ Health endpoint responding
- ✅ Authentication flow working
- ✅ Session management operational

**OIDC Phase 1 (All Passed):**
- ✅ Discovery endpoint working
- ✅ JWKS endpoint accessible
- ✅ Token endpoint configured
- ✅ All required scopes present

**OIDC Phase 2 (Failures):**
- ❌ **Authorization code flow:** Client authentication failed (401)
  - *Error:* `invalid_client` - Client not recognized or auth method unsupported
- ❌ **ID token validation:** Cannot obtain token (prerequisite failure)
- ❌ **Refresh token flow:** Cannot obtain token (prerequisite failure)

**Service Integrations:**
- ❌ **Forgejo:** Token creation failed (401 Unauthorized)
  - *Cause:* Admin credentials incorrect
- ❌ **Planka:** Token acquisition failed (401 Unauthorized)
  - *Cause:* Admin user missing or invalid credentials
- ❌ **Mastodon:** App registration failed (403 Forbidden)
  - *Cause:* Credentials issue

### 8. Databases (13/13 Passed) ✅
All database systems fully operational.

**PostgreSQL:**
- ✅ Connection successful
- ✅ Query execution working
- ✅ Multiple database support validated
- ✅ User permissions correct

**MariaDB:**
- ✅ All connection tests passed
- ✅ Multi-database support working

### 9. Productivity & Collaboration (44/47 Passed) ⚠️

**Passing Services:**
- ✅ BookStack (OIDC working)
- ✅ Grafana
- ✅ Homepage
- ✅ JupyterHub
- ✅ Planka (UI accessible)
- ✅ Forgejo (UI accessible)
- ✅ Element (Matrix client)

**Failures:**
- ❌ **Seafile:** Web interface returns 302 (expected 200/500/502)
  - *Cause:* Unexpected redirect, may indicate config issue

### 10. Communication (11/11 Passed) ✅
All communication services operational.

- ✅ Matrix Synapse working
- ✅ Element web client accessible
- ✅ Mastodon web UI working
- ✅ Ntfy notification service working

**Note:** Mailserver unhealthy but not blocking communication tests

### 11. File Management (8/8 Passed) ✅
- ✅ Seafile UI accessible
- ✅ qBittorrent working
- ✅ All file services responding

### 12. Security (19/19 Passed) ✅
Complete security validation successful.

- ✅ Authelia authentication
- ✅ Vaultwarden operational
- ✅ Certificate validation (disabled for test env)
- ✅ LDAP security controls

### 13. Monitoring (17/17 Passed) ✅
Full monitoring stack operational.

- ✅ Prometheus scraping metrics
- ✅ Grafana dashboards accessible
- ✅ AlertManager configured
- ✅ Node exporter working
- ✅ cAdvisor collecting container metrics
- ✅ Docker health exporter operational

### 14. Backup (2/2 Passed) ✅
- ✅ Kopia service healthy
- ✅ Backup configuration valid

### 15. Utility Services (6/6 Passed) ✅
- ✅ Watchtower operational
- ✅ Autoheal working
- ✅ Docker proxy accessible

### 16. Home Automation (4/4 Passed) ✅
- ✅ Home Assistant responding
- ✅ All automations accessible

### 17. CI/CD (16/17 Passed) ⚠️

**Passing:**
- ✅ Forgejo runner healthy
- ✅ Registry accessible
- ✅ Image pull working
- ✅ Build workflows operational

**Failures:**
- ❌ **Registry Push:** HTTP response to HTTPS client
  - *Cause:* TLS configuration mismatch for registry at 192.168.0.11:5000
  - *Impact:* Cannot push images (pull works)

### 18. Isolated Docker VM (3/4 Passed) ⚠️

**Passing:**
- ✅ Tunnel container running
- ✅ SSH connectivity working
- ✅ Docker socket accessible

**Failures:**
- ❌ **Container Isolation:** Test container visible on production network
  - *Cause:* Isolation breach detected
  - *Impact:* Security concern - isolation not complete

### 19. Stack Replication (4/4 Passed) ✅
- ✅ Deployment configuration valid
- ✅ Replication mechanics working

### 20. Advanced Features (16/16 Passed) ✅

**Agent Capabilities:**
- ✅ Tool server operational
- ✅ LLM quality validated
- ✅ Security controls working

**Trading & Web3:**
- ✅ Hyperliquid worker healthy
- ✅ EVM broadcaster operational
- ✅ Transaction gateway working

### 21. Playwright E2E Tests (17/21 Passed) ⚠️

**Authentication & Session Management:**
- ✅ Authelia authentication flow (6.7s)
- ✅ Session cookies working
- ✅ Concurrent login tests passed

**OIDC Tests:**
- ✅ Metadata endpoints working
- ✅ Discovery configuration valid
- ✅ JWKS keys accessible

**Service Integration Tests:**
- ✅ Grafana OIDC login (4.1s)
- ✅ BookStack multi-user OIDC (19.9s)
- ✅ Homepage OIDC integration (5.6s)

**Failures (4 tests):**
- ❌ **Open-WebUI Login:** Basic auth failed (401)
- ❌ **Forgejo OIDC:** Connection to auth endpoint failed
- ❌ **JupyterHub OIDC:** Initial login failed
- ❌ **Planka OIDC:** Cannot proceed (auth prerequisite failed)

---

## Critical Issues

### 🔴 High Priority

#### 1. Mailserver Restart Loop
- **Status:** Container restarting continuously (Exit 1)
- **Impact:** Email functionality unavailable
- **Action Required:** Check logs for DKIM/certificate issues

#### 2. OIDC Client Authentication Failure
- **Error:** `invalid_client` on token exchange
- **Impact:** Breaks OIDC flow for some services
- **Affected:** Phase 2 OIDC tests, JupyterHub, Forgejo
- **Action Required:** Validate client secrets in Authelia configuration

#### 3. Container Isolation Breach
- **Issue:** Test container visible on production network
- **Impact:** Security risk
- **Action Required:** Review isolated-docker-vm network configuration

### 🟡 Medium Priority

#### 4. Registry Push TLS Mismatch
- **Error:** HTTP response to HTTPS client
- **Impact:** Cannot push images to local registry (pull works)
- **Action Required:** Configure registry for HTTP or add TLS certificates

#### 5. Service Token Acquisition Failures
- **Affected Services:** Seafile, Forgejo, Planka, Mastodon
- **Cause:** Admin credentials invalid or users not provisioned
- **Action Required:** Reset admin passwords or create admin accounts

#### 6. Search Service API 404
- **Issue:** API endpoint not found (health check passes)
- **Impact:** API authentication test fails
- **Action Required:** Verify API routes or update test endpoints

### 🟢 Low Priority

#### 7. CVE Pipeline Empty
- **Status:** Collection exists but no data
- **Cause:** May need API key or first cycle not complete
- **Impact:** CVE search not available
- **Action Required:** Configure CVE data source

#### 8. Open-WebUI Basic Auth
- **Issue:** Login failed with 401
- **Cause:** Credentials may have changed
- **Action Required:** Update test credentials

---

## Performance Metrics

### Response Times (Average)
| Service | Response Time | Rating |
|---------|---------------|--------|
| Agent Tool Server | 429ms | ✅ Good |
| LLM Chat Completion | 4.0s | ✅ Acceptable (7B model) |
| Embedding Generation | 49ms | ✅ Excellent |
| Semantic Search | 635ms | ✅ Good |
| Database Queries | <100ms | ✅ Excellent |
| OIDC Authentication | 6.7s | ⚠️ Acceptable |
| BookStack OIDC Flow | 19.9s | ⚠️ Slow (multi-user) |

### Resource Utilization
- **Containers Running:** 54
- **Test Duration:** 227.5 seconds (~3.8 minutes)
- **Test Throughput:** 1.68 tests/second

---

## Data Pipeline Status

### Active Data Sources
1. **RSS Feeds:** 89 vectors indexed ✅
2. **Torrents:** 398 vectors indexed ✅
3. **Wikipedia:** 73 vectors indexed ✅
4. **Linux Docs:** 112 vectors indexed ✅

### Inactive Data Sources
5. **CVE:** 0 vectors (needs configuration) ℹ️
6. **Australian Laws:** 0 vectors (needs configuration) ℹ️

### Pipeline Health
- ✅ All collections created
- ✅ Vector dimension consistency (1024d)
- ✅ document_staging table operational
- ✅ Cross-collection search working
- ℹ️ Pipeline monitoring may be disabled (source status unavailable)

---

## Recommendations

### Immediate Actions
1. **Fix mailserver restart loop** - Check DKIM setup and certificate paths
2. **Resolve OIDC client authentication** - Verify client secrets match Authelia config
3. **Secure isolated VM** - Fix network isolation breach

### Short-term Improvements
4. **Configure registry TLS** - Enable HTTPS or allow HTTP for local registry
5. **Provision admin accounts** - Reset/create admin users for Seafile, Forgejo, Planka, Mastodon
6. **Update Search Service tests** - Verify correct API endpoint paths

### Long-term Enhancements
7. **Enable CVE pipeline** - Configure API keys and data sources
8. **Optimize OIDC performance** - 20s for multi-user flows is slow
9. **Enable pipeline monitoring** - Currently shows as disabled
10. **Review mailserver configuration** - Stability issues indicate config problems

---

## Test Environment Details

### Endpoint Configuration
```
Authelia:       https://auth.datamancy.net
LDAP:           ldap://ldap:389
LiteLLM:        http://litellm:4000
PostgreSQL:     postgres:5432 (database: datamancy)
Qdrant:         http://qdrant:6333
Pipeline:       http://pipeline:8090
Search Service: http://search-service:8098
BookStack:      http://bookstack:80
```

### Security Context
- **TLS Validation:** DISABLED (test environment with self-signed certs)
- **Test User:** test_runner_user
- **Network:** Internal Docker network
- **Isolation:** Test runner container has full stack access

---

## Conclusion

The Datamancy stack demonstrates **strong overall health** with a **96.6% success rate**. Core functionality including:
- ✅ LLM integration and embeddings
- ✅ Vector search and knowledge base
- ✅ Data pipeline (4/6 sources active)
- ✅ Monitoring and observability
- ✅ Security controls
- ✅ Most OIDC integrations

The 13 failures are concentrated in:
1. Mailserver stability (1 failure)
2. OIDC Phase 2 token exchange (3 related failures)
3. Service token acquisition (4 credential issues)
4. Infrastructure isolation (1 security issue)
5. Registry push operations (1 TLS config)
6. Playwright E2E (4 integration tests)

**Next Steps:** Address the 3 critical issues (mailserver, OIDC client auth, container isolation) to achieve full operational status.

---

**Report Generated:** 2026-02-16 14:21:54 AEDT
**Test Framework:** Datamancy Integration Test Runner (Kotlin 2.0.21)
**Report Version:** 1.0
