# Datamancy Stack Assistant Guide for Qwen

**Version:** 1.0
**Last Updated:** 2026-01-19
**Purpose:** Complete operational knowledge base for Qwen to manage the Datamancy stack

---

## Table of Contents
1. [Stack Overview](#stack-overview)
2. [Service Architecture](#service-architecture)
3. [Network Topology](#network-topology)
4. [Database Systems](#database-systems)
5. [Operational Procedures](#operational-procedures)
6. [Troubleshooting Guide](#troubleshooting-guide)
7. [Recent Fixes and Known Issues](#recent-fixes-and-known-issues)
8. [Configuration Management](#configuration-management)
9. [Deployment Procedures](#deployment-procedures)
10. [Monitoring and Health Checks](#monitoring-and-health-checks)

---

## Stack Overview

### Deployment Location
- **Host:** latium.local (SSH: gerald@latium.local)
- **Stack Path:** ~/datamancy
- **Domain:** datamancy.net
- **Environment:** Production
- **Container Runtime:** Docker Compose

### Core Purpose
Datamancy is a comprehensive self-hosted infrastructure stack providing:
- AI/LLM services (vLLM, LiteLLM, Open-WebUI)
- Data pipeline (ingestion, transformation, vector indexing, search)
- Collaboration tools (Mastodon, Matrix, Element, Forgejo)
- Knowledge management (BookStack, Seafile)
- Observability (Prometheus, Grafana, cAdvisor)
- Authentication (Authelia OIDC, LDAP)

### Total Services
**50 containers** running across 19 isolated networks

---

## Service Architecture

### Datamancy Core Services (Custom Built)

#### 1. **agent-tool-server** (Port: 8081)
- **Purpose:** MCP-compatible tool server for AI agents
- **Access:** Internal only (no external exposure)
- **Capabilities:**
  - **Docker Operations:**
    - `docker_logs(container, tail)` - Read container logs (last N lines, max 5000)
    - `docker_ps` - List running containers
    - `docker_restart(container)` - Restart containers
    - `docker_inspect(container)` - Get container details
  - **Database Queries:**
    - `query_postgres(database, query)` - PostgreSQL via shadow accounts
    - `query_mariadb(database, query)` - MariaDB via shadow accounts
    - `query_clickhouse(query)` - ClickHouse analytics
  - **SSH Operations:**
    - `ssh_exec_whitelisted(cmd)` - Run allowed commands (e.g., "docker logs vllm --tail 200")
    - `ssh_read_file(path)` - Read files from host
  - **Vector Search:**
    - `search_qdrant` - Query vector database
  - **LDAP Queries:**
    - Read-only LDAP operations
- **Networks:** ai, ai-gateway, postgres, mariadb, clickhouse, qdrant, ldap, docker-proxy
- **Key Config:** `/home/gerald/datamancy/configs/applications/agent-tool-server/`
- **Security:** Uses shadow accounts (agent_observer) with restricted permissions
- **API Endpoint:** `http://agent-tool-server:8081` (POST /call-tool)

#### 2. **control-panel** (Port: 8097)
- **Purpose:** Web dashboard for stack management
- **Access:** https://control.datamancy.net
- **Features:** Service health, logs viewer, configuration management
- **Networks:** caddy, postgres, clickhouse

#### 3. **data-fetcher** (Port: 8095)
- **Purpose:** Scheduled data ingestion from external sources
- **Sources:** RSS feeds, legal documents, market data, weather
- **Storage:** Raw files → `/app/data/`, Metadata → PostgreSQL, Structured → ClickHouse
- **Config:**
  - `/app/config/schedules.yaml` (cron schedules)
  - `/app/config/sources.yaml` (data source definitions)
- **Networks:** postgres, clickhouse, mariadb, qdrant
- **Key Features:**
  - Automatic deduplication via content hashing
  - Canonical path structure: `/raw/{source}/{yyyy}/{mm}/{dd}/{runId}/{itemId}.{ext}`

#### 4. **data-transformer** (Port: 8096)
- **Purpose:** Orchestrates data pipeline workflows
- **Capabilities:**
  - Fetch data from data-fetcher
  - Transform and chunk content
  - Send to BookStack writer
  - Trigger vector indexing
- **Networks:** postgres, mariadb, qdrant

#### 5. **data-bookstack-writer** (Port: 8099)
- **Purpose:** Writes structured content to BookStack wiki
- **Authentication:** Uses BookStack API token (stored in .env)
- **API Endpoint:** http://bookstack:80
- **Networks:** caddy, mariadb
- **Recent Fix:** Token generation script at `scripts/generate-bookstack-api-token.sh`

#### 6. **data-vector-indexer** (Port: 8100)
- **Purpose:** Generates embeddings and indexes vectors in Qdrant
- **Embedding Service:** http://embedding-service:8080 (BGE-base-en-v1.5, 768d)
- **Vector DB:** Qdrant (gRPC port 6334)
- **Networks:** qdrant, ai-gateway
- **Key Feature:** Batch embedding with configurable concurrency

#### 7. **search-service** (Port: 8098)
- **Purpose:** Hybrid search (vector + BM25)
- **Components:**
  - Vector search via Qdrant (gRPC 6334)
  - Full-text search via ClickHouse
  - Embedding generation via embedding-service
- **Networks:** qdrant, clickhouse, ai-gateway

---

## Network Topology

### Network Segmentation (Zero-Trust Architecture)

#### AI & Gateway Networks
- **datamancy_ai:** vLLM models, LiteLLM, Open-WebUI
- **datamancy_ai-gateway:** Embedding service, search service, agent-tool-server

#### Data Layer
- **datamancy_postgres:** PostgreSQL + clients (data-fetcher, control-panel, agent-tool-server)
- **datamancy_clickhouse:** ClickHouse + clients (data-fetcher, search-service)
- **datamancy_mariadb:** MariaDB + clients (Mastodon, BookStack, Seafile)
- **datamancy_qdrant:** Qdrant + vector clients

#### Frontend/Backend
- **datamancy_frontend:** User-facing services
- **datamancy_backend:** Internal APIs
- **datamancy_caddy:** Reverse proxy network

#### Specialized
- **datamancy_ldap:** LDAP + LAM + agent-tool-server
- **datamancy_valkey:** Redis-compatible cache
- **datamancy_monitoring:** Prometheus, Grafana, cAdvisor, node-exporter
- **datamancy_docker-proxy:** Secure Docker socket proxy

#### Communication
- **datamancy_mailserver:** Mail server isolated network
- **datamancy_mastodon-internal:** Mastodon web/sidekiq/streaming
- **datamancy_matrix-internal:** Synapse components

### Port Reference (Internal)
```
8081 - agent-tool-server (MCP tools)
8095 - data-fetcher
8096 - data-transformer
8097 - control-panel (EXPOSED: 0.0.0.0:8097)
8098 - search-service
8099 - data-bookstack-writer
8100 - data-vector-indexer
6333 - Qdrant HTTP API
6334 - Qdrant gRPC (USE THIS for QdrantClient)
5432 - PostgreSQL
3306 - MariaDB
8123 - ClickHouse HTTP
9000 - ClickHouse Native
```

---

## Database Systems

### PostgreSQL (postgres:16.11)
- **Host:** postgres:5432
- **Primary DB:** datamancy
- **Users:**
  - `datamancer` - Service account (full access)
  - `agent_observer` - Shadow account (read-only, restricted schemas)
- **Schemas:**
  - `public` - Fetch metadata, system tables
  - `agent_observer` - Grafana dashboards (optional, may not exist)
- **Key Tables:**
  - `fetch_history` - Data fetcher execution logs
  - `legal_ingestion_status` - Legal document sync tracking
  - `legal_acts_tracking` - Legal document metadata

### MariaDB (mariadb:11.8.5)
- **Host:** mariadb:3306
- **Databases:**
  - `bookstack` - BookStack wiki
  - `seafile_db`, `seahub_db` - Seafile file storage
  - `mastodon_production` - Mastodon social network
- **Root Password Issue:** Persistent volume retains old password, .env may have newer password
- **Init Container:** `mariadb-init` may fail auth - safe to stop if MariaDB is healthy

### ClickHouse (clickhouse/clickhouse-server:24.12)
- **Host:** clickhouse:8123 (HTTP), clickhouse:9000 (Native)
- **Tables:**
  - `market_data` - Time-series financial data
  - `legal_documents` - Full-text legal documents with versions
  - Engine: ReplacingMergeTree (automatic deduplication)
- **Users:**
  - `{STACK_ADMIN_USER}` - Full access
  - Shadow accounts for agent queries

### Qdrant (qdrant/qdrant:v1.16.3)
- **Host:** qdrant:6333 (HTTP), qdrant:6334 (gRPC)
- **IMPORTANT:** Use port **6334** for gRPC clients (QdrantClient/QdrantGrpcClient)
- **Storage:** `/storage` (persistent volume)
- **Collections:** 29+ vector collections (768-dimensional, BGE embeddings)
- **API Key:** ${QDRANT_API_KEY} from .env

### Valkey (valkey:8.1.5)
- **Host:** valkey:6379
- **Purpose:** Redis-compatible cache (sessions, rate limiting)

---

## Operational Procedures

### Accessing the Stack

#### SSH Access
```bash
ssh gerald@latium.local
cd ~/datamancy
```

#### Container Management
```bash
# View all services
docker ps

# Check logs
docker logs <container_name>
docker logs <container_name> --since 10m  # Last 10 minutes
docker logs <container_name> -f           # Follow

# Restart service
docker restart <container_name>

# Execute command in container
docker exec -it <container_name> bash
```

#### Service Health Checks
```bash
# Check all unhealthy containers
docker ps --filter health=unhealthy

# View specific service status
docker inspect <container_name> --format='{{.State.Health.Status}}'
```

### Running Integration Tests

```bash
cd ~/datamancy
docker compose -f docker-compose.yml -f testing.yml --profile testing run --rm integration-test-runner

# Run specific test suite
docker compose --profile testing run --rm integration-test-runner foundation
docker compose --profile testing run --rm integration-test-runner docker
docker compose --profile testing run --rm integration-test-runner e2e
```

### Building and Deploying Updates

#### From Development Machine
```bash
# Build the stack locally
./build-datamancy.main.kts

# Sync to production
rsync -avz --exclude='volumes/' dist/ gerald@latium.local:~/datamancy/

# Rebuild affected containers on production
ssh gerald@latium.local "cd ~/datamancy && docker compose build <service_name>"

# Restart with new images
ssh gerald@latium.local "docker restart <service_name>"
```

### BookStack API Token Generation

```bash
cd ~/datamancy
./scripts/generate-bookstack-api-token.sh datamancy-service 1

# Add output to .env:
# BOOKSTACK_API_TOKEN_ID=...
# BOOKSTACK_API_TOKEN_SECRET=...

# Restart writer
docker restart data-bookstack-writer
```

---

## Troubleshooting Guide

### Recent Fixes (2026-01-19)

#### ✅ FIXED: Qdrant gRPC Connection Errors
**Symptoms:**
- Logs: "First received frame was not SETTINGS"
- "INTERNAL: http2 exception"
- Services: search-service, data-vector-indexer, data-fetcher

**Root Cause:** Port mismatch - services using gRPC client connecting to HTTP port 6333

**Fix Applied:**
```yaml
# Changed in compose.templates/datamancy-services.yml and services.registry.yaml
QDRANT_PORT: 6334  # Changed from 6333
```

**Verification:**
```bash
# Check for HTTP2 errors (should be minimal/zero)
docker logs qdrant --since 10m | grep ERROR | wc -l
```

**Files Modified:**
- `compose.templates/datamancy-services.yml` (lines 77, 166, 236)
- `services.registry.yaml` (lines 1692, 1774)

#### ✅ FIXED: BookStack Writer 401 Authentication
**Symptoms:**
- "No matching API token was found for the provided authorization token"
- HTTP 401 errors

**Root Cause:** Missing API credentials in environment

**Fix Applied:**
1. Created script: `scripts/generate-bookstack-api-token.sh`
2. Generated token and added to `.env`
3. Restarted data-bookstack-writer

**Prevention:** Token script now available for future redeployments

#### ⚠️ KNOWN: MariaDB Init Container Access Denied
**Symptoms:**
- Continuous "Access denied for user 'root'" from 172.21.0.3
- Container: mariadb-init in infinite healthcheck loop

**Root Cause:** Password mismatch - MariaDB volume has old password, .env has new password

**Resolution:**
```bash
docker stop mariadb-init
```

**Why Safe:** MariaDB container is healthy, init scripts already ran, init container no longer needed

**Long-term Fix:** Either reset MariaDB root password or update init container with correct password

#### ⚠️ KNOWN: RSS Feed Parsing Errors (External)
**Symptoms:**
- "Content is not allowed in prolog" for ABC/BBC feeds
- ArXiv file write errors

**Root Cause:**
- ABC/BBC: Geo-blocking or HTML error pages instead of XML
- ArXiv: Actually fixed - directory creation working at DataStore.kt:569

**Status:** External issue, not a code problem. RSS parsing includes proper error handling.

#### ℹ️ EXPECTED: agent_observer.public_dashboards Missing
**Symptoms:**
- ERROR: relation "agent_observer.public_dashboards" does not exist
- Repeated in test logs

**Root Cause:** Test suite checking for optional shadow account Grafana dashboard access

**Status:** Expected behavior - test gracefully handles missing table with informative message

**Action Required:** None - proper error handling in place

---

### Common Issues

#### Service Won't Start
```bash
# Check logs for errors
docker logs <service_name> 2>&1 | tail -50

# Check dependencies
docker ps | grep -E '(postgres|mariadb|clickhouse|qdrant|valkey)'

# Verify environment variables
docker inspect <service_name> --format='{{range .Config.Env}}{{println .}}{{end}}'

# Check network connectivity
docker exec <service_name> ping <dependency_host>
```

#### Database Connection Failures
```bash
# PostgreSQL
docker exec postgres psql -U datamancer -d datamancy -c 'SELECT 1'

# MariaDB (use correct password from volume)
docker exec mariadb mariadb -u root -p<OLD_PASSWORD> -e 'SELECT 1'

# ClickHouse
docker exec clickhouse clickhouse-client --query 'SELECT 1'

# Qdrant
curl http://localhost:6333/healthz
docker exec qdrant ls -la /storage
```

#### Vector Search Not Working
```bash
# Check Qdrant health
docker logs qdrant --since 5m | grep ERROR

# Verify collections exist
curl http://localhost:6333/collections

# Test gRPC port (should connect)
telnet localhost 6334

# Check embedding service
curl http://localhost:8080/health
```

#### High Error Rates
```bash
# Check all containers for recent errors
docker ps --format '{{.Names}}' | xargs -I {} sh -c 'echo "=== {} ==="; docker logs {} --since 10m 2>&1 | grep -iE "(error|exception|fail)" | tail -3'

# Check specific service
docker logs <service_name> --since 1h 2>&1 | grep -i error | sort | uniq -c | sort -rn
```

---

## Configuration Management

### Configuration Structure
```
configs/
├── applications/        # Service-specific configs
│   ├── agent-tool-server/
│   ├── authelia/
│   ├── data-fetcher/
│   │   ├── schedules.yaml
│   │   └── sources.yaml
│   ├── forgejo/
│   ├── grafana/
│   ├── homepage/
│   ├── jupyterhub/
│   ├── litellm/
│   ├── mastodon/
│   ├── open-webui/
│   └── synapse/
├── databases/           # Database init scripts
│   ├── clickhouse/
│   ├── mariadb/
│   └── postgres/
├── infrastructure/      # Core infrastructure
│   ├── authelia/
│   ├── caddy/
│   └── ssh/
├── monitoring/          # Observability configs
│   ├── grafana/
│   └── prometheus/
└── prompts/            # AI prompts directory
```

### Environment Variables (.env)
- **Location:** ~/datamancy/.env
- **Variables:** 79 total
- **Generation:** Auto-generated by `build-datamancy.main.kts`
- **Sensitive Data:** Passwords, API keys, secrets (64-char hex)
- **Naming Conventions:**
  - `*_APP_KEY` - Laravel base64 keys
  - `*_ISSUER_PRIVATE_KEY` - RSA keys
  - `*_PASSWORD` - Standard hex secrets
  - `*_API_KEY` - External service keys

### Build System
```bash
# Main build script
./build-datamancy.main.kts

# What it does:
# 1. Builds JARs (Gradle)
# 2. Creates Docker images
# 3. Processes config templates ({{VAR}} → actual values)
# 4. Generates .env with secrets
# 5. Merges compose files
# 6. Copies to dist/

# Output: dist/ directory ready for deployment
```

---

## Deployment Procedures

### Full Stack Deployment

```bash
# 1. Stop stack (if running)
cd ~/datamancy
docker compose down

# 2. Backup databases
docker exec postgres pg_dumpall -U datamancer > backup_postgres_$(date +%Y%m%d).sql
docker exec mariadb mariadb-dump -u root -p<PASSWORD> --all-databases > backup_mariadb_$(date +%Y%m%d).sql

# 3. Pull updates
git pull origin master

# 4. Rebuild (if needed)
./build-datamancy.main.kts

# 5. Start stack
docker compose up -d

# 6. Verify health
docker ps --filter health=unhealthy
docker compose -f docker-compose.yml -f testing.yml --profile testing run --rm integration-test-runner
```

### Rolling Update (Single Service)

```bash
# 1. Build new image
docker compose build <service_name>

# 2. Restart with new image
docker compose up -d --force-recreate <service_name>

# 3. Monitor logs
docker logs <service_name> -f

# 4. Rollback if needed
docker compose up -d --force-recreate <service_name>  # Reverts to previous image
```

---

## Monitoring and Health Checks

### Service Health
```bash
# All services status
docker ps --format 'table {{.Names}}\t{{.Status}}'

# Unhealthy services only
docker ps --filter health=unhealthy

# Specific service health
docker inspect <service_name> --format='{{.State.Health.Status}}'
```

### Metrics and Observability
- **Prometheus:** http://prometheus:9090 (metrics collection)
- **Grafana:** https://grafana.datamancy.net (dashboards)
- **cAdvisor:** Container metrics
- **node-exporter:** Host metrics

### Log Aggregation
```bash
# Recent errors across all services
docker ps --format '{{.Names}}' | xargs -I {} sh -c 'docker logs {} --since 10m 2>&1 | grep -i error' 2>/dev/null | tail -50

# Service-specific patterns
docker logs <service_name> 2>&1 | grep -E "(ERROR|WARN|FAIL)"
```

### Key Health Indicators
1. **All databases healthy:** postgres, mariadb, clickhouse, qdrant, valkey
2. **Caddy healthy:** Reverse proxy routing traffic
3. **Agent-tool-server healthy:** AI agent capabilities available
4. **Embedding-service healthy:** Vector generation working
5. **Zero HTTP2 errors in Qdrant logs:** gRPC connections correct

---

## Critical Files Reference

### Scripts
- `scripts/generate-bookstack-api-token.sh` - BookStack API credential generator
- `scripts/test-runner/docker-entrypoint.sh` - Test runner initialization

### Configuration Templates
- `compose.templates/datamancy-services.yml` - Datamancy service definitions
- `services.registry.yaml` - Service registry with all env vars
- `testing.yml` - Test overlay configuration

### Build Artifacts
- `build-datamancy.main.kts` - Main build orchestrator
- `build.gradle.kts` - Gradle build configuration
- `settings.gradle.kts` - Gradle project structure

---

## Integration Points for Qwen

### As Stack Assistant, Qwen Should:

1. **Monitor Health**
   - Run daily health checks via docker ps
   - Alert on unhealthy services
   - Check for error patterns in logs

2. **Respond to Issues**
   - Apply known fixes from this guide
   - Restart failed services
   - Escalate unknown issues to human operators

3. **Answer Questions**
   - Service architecture and purpose
   - Configuration locations
   - Troubleshooting procedures
   - Recent changes and fixes

4. **Perform Routine Tasks**
   - Run integration tests
   - Check database connectivity
   - Monitor resource usage
   - Review logs for anomalies

5. **Documentation Maintenance**
   - Update this guide when new fixes are discovered
   - Document new services as they're added
   - Track configuration changes

### Tools Available to Qwen (via agent-tool-server)

#### Docker Operations (Read-Only Safe)
- **`docker_logs(container, tail=200)`** - Read live container logs
  - Example: `docker_logs("search-service", 100)` - Last 100 lines
  - Max lines: 5000
  - Returns: `{exitCode, logs}` structure
- **`docker_ps()`** - List all running containers
- **`docker_inspect(container)`** - Get container details

#### Docker Operations (Requires Approval)
- **`docker_restart(container)`** - Restart a container
  - Use only when documented troubleshooting recommends it

#### Database Queries (Shadow Account - Read-Only)
- **`query_postgres(database, query)`** - PostgreSQL queries
  - Available databases: datamancy, grafana
  - Must use `agent_observer` schema prefix
- **`query_mariadb(database, query)`** - MariaDB queries
  - Available databases: bookstack, seafile_db, mastodon_production
- **`query_clickhouse(query)`** - ClickHouse analytics

#### SSH Operations (Whitelisted Commands)
- **`ssh_exec_whitelisted(cmd)`** - Run allowed commands
  - Example: `ssh_exec_whitelisted("docker logs vllm --tail 200")`
  - Whitelist enforced server-side for security
- **`ssh_read_file(path)`** - Read files from host
  - Use to access this documentation: `ssh_read_file("~/QWEN_STACK_ASSISTANT_GUIDE.md")`

#### Vector & Search
- **`search_qdrant`** - Query vector database

---

## Practical Examples for Qwen

### Example 1: Checking Service Logs for Errors

**User Question:** "Is search-service having any errors?"

**Qwen Should:**
1. Call `docker_logs("search-service", 200)` to get last 200 lines
2. Scan for ERROR, WARN, FAIL keywords
3. Identify patterns (e.g., HTTP2 errors, connection failures)
4. Reference troubleshooting guide for known issues
5. Provide diagnosis and recommended action

**Sample Response:**
```
Checking search-service logs...

Found 15 ERROR entries in last 200 lines. Pattern identified:
"INTERNAL: http2 exception" - Qdrant gRPC connection errors

Diagnosis: This is a KNOWN ISSUE from 2026-01-19.
Root Cause: Port mismatch - using HTTP port 6333 instead of gRPC port 6334

Status: FIXED in commit a057c99
Verification: docker logs qdrant --since 10m | grep ERROR | wc -l
Expected: <5 errors (down from 50+ per 10min before fix)

Action Required: None if services were restarted after 2026-01-19.
If errors persist, run: docker restart search-service
```

### Example 2: Investigating Service Health

**User Question:** "Why is data-fetcher failing?"

**Qwen Should:**
1. Call `docker_ps()` to check if container is running
2. Call `docker_logs("data-fetcher", 300)` for recent logs
3. Look for:
   - Connection errors (database, network)
   - File write errors
   - RSS feed failures
   - Schedule execution errors
4. Cross-reference with known issues
5. Suggest specific troubleshooting steps

### Example 3: Monitoring Multiple Services

**User Question:** "Show me errors from all Datamancy services in the last hour"

**Qwen Should:**
1. Call `docker_ps()` to list services
2. For each Datamancy service (data-fetcher, search-service, etc.):
   - Call `docker_logs(service, 500)`
   - Filter for ERROR/WARN
3. Aggregate and categorize errors
4. Highlight critical vs informational
5. Provide summary with action items

### Example 4: Database Query for Monitoring

**User Question:** "How many RSS feeds were fetched today?"

**Qwen Should:**
1. Call `query_postgres("datamancy", "SELECT COUNT(*) FROM fetch_history WHERE source='rss' AND fetched_at >= CURRENT_DATE")`
2. Parse result
3. Provide context from documentation (RSS feed system, data-fetcher service)

### Example 5: Proactive Health Check

**Qwen Task:** Periodic health monitoring

**Actions:**
1. Call `docker_ps()` - verify all services healthy
2. For critical services (postgres, mariadb, qdrant, agent-tool-server):
   - Call `docker_logs(service, 50)`
   - Scan for ERROR keywords
3. Call `query_postgres("datamancy", "SELECT COUNT(*) FROM fetch_history WHERE fetched_at > NOW() - INTERVAL '1 hour'")`
   - Verify data pipeline is active
4. Report: "All systems healthy" or escalate issues

---

## Quick Reference

### Emergency Commands
```bash
# Restart entire stack
docker compose restart

# Stop specific service
docker stop <service_name>

# View resource usage
docker stats --no-stream

# Force recreate service
docker compose up -d --force-recreate <service_name>

# Emergency log dump
docker logs <service_name> > /tmp/emergency_$(date +%s).log
```

### Configuration Files
```bash
# View service env vars
docker compose config | grep -A 50 "<service_name>:"

# Check .env
cat ~/datamancy/.env | grep <VAR_NAME>

# List all networks
docker network ls | grep datamancy

# List all volumes
docker volume ls | grep datamancy
```

### Contact Information
- **Primary Operator:** gerald@latium.local
- **Documentation Location:** This file + BookStack
- **Issue Tracking:** Git repository
- **Backup Location:** (TODO: Define backup strategy)

---

## Version History

### v1.0 (2026-01-19)
- Initial comprehensive documentation
- Documented Qdrant gRPC port fix
- Documented BookStack authentication fix
- Added MariaDB init container resolution
- Comprehensive service topology
- Network architecture reference
- Database schemas
- Operational procedures
- Troubleshooting guide

---

**Last Updated by:** Claude (Anthropic)
**Next Review:** 2026-02-19
