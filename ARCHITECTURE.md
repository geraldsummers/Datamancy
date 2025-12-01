# Datamancy Architecture

This document provides a comprehensive overview of the Datamancy infrastructure stack architecture, including service relationships, data flows, and design decisions.

## Table of Contents

- [Architectural Vision](#architectural-vision)
- [System Overview](#system-overview)
- [Network Architecture](#network-architecture)
- [Service Layers](#service-layers)
- [Data Flow](#data-flow)
- [Technology Stack](#technology-stack)
- [Design Patterns](#design-patterns)
- [Security Architecture](#security-architecture)
- [Scalability Considerations](#scalability-considerations)

## Architectural Vision

### Digital Sovereignty Through Automation

Datamancy's architecture is designed around a core principle: **drastically reducing the admin-to-user ratio** through agent-assisted administration.

#### Traditional vs Datamancy Operations Model

| Aspect | Traditional Enterprise | Datamancy Sovereignty Cluster |
|--------|----------------------|------------------------------|
| **Admin-to-User Ratio** | 1:50 to 1:100 | **1:1,000+** (target) |
| **Issue Detection** | User reports → ticket queue | Autonomous monitoring (proactive) |
| **Diagnosis Time** | 15-60 minutes (manual) | < 2 minutes (AI-powered) |
| **Fix Execution** | Manual (risk of human error) | Automated with verification |
| **Expertise Required** | Specialized teams | Single generalist + AI agents |
| **On-Call Burden** | 24/7 rotation required | Minimal (agents handle routine issues) |
| **Data Location** | Cloud vendor silos | **Complete self-hosting** |

#### Agent-Assisted Administration Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    SOVEREIGNTY CLUSTER                       │
│              (Self-Healing Infrastructure)                   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Layer 1: Continuous Monitoring                             │
│  ┌──────────────┐    ┌──────────────┐   ┌──────────────┐  │
│  │ Probe Agent  │───→│  LLM Analysis│──→│ Fix Proposal │  │
│  │ - Screenshot │    │ - Log Review │   │ - Confidence │  │
│  │ - DOM Check  │    │ - Metrics    │   │ - Safety     │  │
│  │ - HTTP Test  │    │ - Patterns   │   │ - Automation │  │
│  └──────────────┘    └──────────────┘   └──────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  Layer 2: Automated Remediation                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Safe Actions (Auto-Execute)  │ Needs Review          │  │
│  │ - Container restart          │ - Config changes      │  │
│  │ - Health check wait          │ - Data migrations     │  │
│  │ - Resource scaling           │ - Security patches    │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  Layer 3: Verification & Learning                           │
│  - Post-fix probing confirms resolution                     │
│  - Failure patterns stored for future reference             │
│  - Admin notified only if automation fails                  │
└─────────────────────────────────────────────────────────────┘
```

**Sovereignty Benefits:**
1. **Data Ownership**: All user data stays on-premises, zero cloud dependencies
2. **Reduced Costs**: Fewer admins needed (10-20x improvement in ratio)
3. **Faster Recovery**: Issues resolved in minutes, not hours
4. **Skill Accessibility**: Generalists can operate complex infrastructure
5. **Independence**: No vendor lock-in, portable to any hardware

## System Overview

Datamancy is a multi-layered infrastructure stack built on Docker Compose, organized into distinct service profiles for modular deployment:

```
┌─────────────────────────────────────────────────────────────────────┐
│                        DATAMANCY ARCHITECTURE                        │
└─────────────────────────────────────────────────────────────────────┘

Profile: bootstrap (Core Services)
┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│    Caddy    │  │  Authelia   │  │  OpenLDAP   │  │    Redis    │
│  (Proxy)    │◄─┤   (OIDC)    │◄─┤ (Directory) │  │   (Cache)   │
│   :80/443   │  │    :9091    │  │    :389     │  │    :6379    │
└──────┬──────┘  └─────────────┘  └─────────────┘  └─────────────┘
       │
       ├─────────────────────────────────────────────────────────────┐
       │                                                             │
Profile: applications                      Profile: infrastructure
┌──────▼──────┐  ┌─────────────┐         ┌─────────────┐  ┌─────────────┐
│  Open WebUI │  │   Grafana   │         │  Portainer  │  │   Dockge    │
│    :8080    │  │    :3000    │         │    :9000    │  │    :5001    │
└─────────────┘  └─────────────┘         └─────────────┘  └─────────────┘

Profile: databases                         Profile: bootstrap
┌─────────────┐  ┌─────────────┐         ┌─────────────┐  ┌─────────────┐
│ PostgreSQL  │  │   MariaDB   │         │    vLLM     │  │  LiteLLM    │
│    :5432    │  │    :3306    │         │    :8000    │  │    :4000    │
└─────────────┘  └─────────────┘         └─────────────┘  └─────────────┘

Profile: bootstrap (Diagnostics)           Profile: bootstrap (AI)
┌─────────────┐  ┌─────────────┐         ┌─────────────┐  ┌─────────────┐
│  KFuncDB    │  │  Probe Orch │         │ vLLM Router │  │ Embedding   │
│    :8081    │◄─┤    :8089    │         │    :8010    │  │    :8080    │
└─────────────┘  └──────┬──────┘         └─────────────┘  └─────────────┘
                        │
                 ┌──────▼──────┐
                 │ Playwright  │
                 │    :3000    │
                 └─────────────┘
```

## Network Architecture

Datamancy uses three isolated Docker networks for security and traffic segregation:

### Network Topology

```
Internet
   │
   ▼
┌────────────────────────────────────────────┐
│         frontend (172.20.0.0/24)          │  ← Public-facing services
│  - Caddy                                  │
│  - Authelia                               │
│  - Open WebUI, Grafana, etc.             │
└───────────────┬────────────────────────────┘
                │
                ▼
┌────────────────────────────────────────────┐
│         backend (172.21.0.0/24)           │  ← Internal services
│  - KFuncDB, Probe Orchestrator           │
│  - LiteLLM, vLLM                         │
│  - Application backends                   │
└───────────────┬────────────────────────────┘
                │
                ▼
┌────────────────────────────────────────────┐
│        database (172.22.0.0/24)           │  ← Data layer (isolated)
│  - PostgreSQL, MariaDB                    │
│  - Redis, Qdrant, ClickHouse             │
└────────────────────────────────────────────┘
```

### Network Policies

| Network | Purpose | Access Rules |
|---------|---------|--------------|
| `frontend` | External access via Caddy | Internet → frontend only |
| `backend` | Service-to-service communication | frontend ↔ backend allowed |
| `database` | Data persistence layer | backend → database only |

**Benefits:**
- Database servers never exposed to frontend
- Services can only communicate with necessary peers
- Clear security boundaries
- Simplified firewall rules

## Service Layers

### Layer 1: Edge & Security

**Caddy (Reverse Proxy)**
- Terminates TLS connections
- Routes traffic to backend services via virtual hosts
- Automatic HTTPS with self-signed CA or Let's Encrypt
- Request forwarding to Authelia for authentication

**Authelia (Authentication & OIDC)**
- Single Sign-On (SSO) provider
- OIDC authorization server for OAuth flows
- Session management (Redis-backed)
- LDAP authentication backend
- MFA support (TOTP)

**OpenLDAP (Directory Services)**
- Centralized user directory
- Group management (admins, users, app-specific groups)
- Bootstrap LDIF for initial schema
- LDAP Account Manager (LAM) for web-based administration

```
User Request Flow:
1. HTTPS request → Caddy
2. Caddy → Authelia (auth check)
3. If unauthenticated → Authelia login page
4. Authelia → OpenLDAP (verify credentials)
5. If authenticated → Caddy forwards to application
6. Application optionally validates OIDC token with Authelia
```

### Layer 2: Application Services

**Categories:**

1. **AI/LLM Services**
   - **vLLM**: GPU-accelerated inference (Mistral-7B-AWQ quantized)
   - **vLLM Router**: LRU-based model memory management
   - **LiteLLM**: Unified OpenAI-compatible API gateway
   - **Embedding Service**: Sentence transformers for RAG
   - **Open WebUI**: ChatGPT-like interface

2. **Productivity Tools**
   - **Outline**: Markdown-based wiki (PostgreSQL + Redis)
   - **Planka**: Kanban boards (PostgreSQL)
   - **JupyterHub**: Multi-user Jupyter notebooks (DockerSpawner)
   - **Seafile**: File sync/share (MariaDB + object storage)

3. **Communication**
   - **Mailu**: Complete email stack (front, admin, IMAP, SMTP, webmail)
   - **SOGo**: Webmail and calendar client
   - **Synapse**: Matrix homeserver (PostgreSQL)
   - **Mastodon**: Federated social network

4. **Monitoring & Operations**
   - **Grafana**: Dashboards and visualization
   - **Portainer**: Docker container management UI
   - **Dockge**: Compose stack orchestrator
   - **Homepage**: Service dashboard aggregator

### Layer 3: Diagnostic & Automation

**KFuncDB (Agent Tool Server)**
- Plugin-based architecture for extensible tools
- OpenAI function calling compatible API
- Built-in plugins:
  - `CoreToolsPlugin`: Basic utilities
  - `HostToolsPlugin`: Docker operations (inspect, logs, restart)
  - `BrowserToolsPlugin`: Screenshot, DOM extraction
  - `LlmCompletionPlugin`: LLM proxy with tool injection
  - `OpsSshPlugin`: SSH command execution

**Probe Orchestrator**
- Autonomous health monitoring
- Visual probing workflow:
  1. Screenshot capture via Playwright
  2. OCR text extraction (optional vision model)
  3. DOM analysis
  4. LLM-based wellness assessment
- Diagnostic analysis with fix proposals
- Repair agent for automated remediation

**Playwright Service**
- Headless Firefox browser automation
- Python HTTP API for screenshots and DOM extraction
- Isolated browser contexts for security

```
Diagnostic Flow:
1. Probe Orchestrator receives probe request
2. Calls KFuncDB's browser_screenshot tool
3. KFuncDB → Playwright → Captures screenshot
4. Returns base64 image
5. Probe Orchestrator → OCR via vision model (optional)
6. LLM analyzes OCR + DOM + HTTP status
7. Generates wellness report
8. If unhealthy, analyzes logs and proposes fixes
```

### Layer 4: Data Persistence

**Relational Databases**
- **PostgreSQL 16**: Primary RDBMS
  - Databases: planka, outline, synapse, mailu
  - Init scripts for schema setup
  - Connection pooling (max 200 connections)

- **MariaDB 11**: MySQL-compatible
  - Used by Seafile
  - UTF8MB4 encoding

**NoSQL & Caching**
- **Redis 7**: Session store, caching (Authelia, Outline, Synapse)
- **CouchDB 3**: Document database
- **ClickHouse 24**: Columnar database for analytics
- **Qdrant**: Vector database for embeddings

**Data Volumes**
All data persisted to `./volumes/` via bind mounts:
```
volumes/
├── postgres_data/       # PostgreSQL data directory
├── mariadb_data/       # MariaDB data
├── redis_data/         # Redis RDB snapshots
├── qdrant_data/        # Vector embeddings
├── open_webui_data/    # Chat history
├── grafana_data/       # Dashboards
└── ...
```

## Data Flow

### LLM Request Flow

```
User (Open WebUI)
   │ HTTP POST /v1/chat/completions
   ├─► LiteLLM :4000
   │      │ Authenticates with LITELLM_MASTER_KEY
   │      │ Routes based on model name
   │      ├─► vLLM Router :8010
   │      │      │ Checks if model loaded in VRAM
   │      │      │ Unloads LRU model if needed
   │      │      │ Loads requested model
   │      │      └─► vLLM :8000
   │      │             │ GPU inference (CUDA)
   │      │             └─► Streaming response
   │      │
   │      └─► Embedding Service :8080 (for embed models)
   │             └─► CPU-based embedding generation
   │
   └─► Response (streamed chunks)
```

### Authentication Flow (OIDC)

```
User → https://grafana.domain.com
   │
   ├─► Caddy :443
   │      │ Checks auth cookie
   │      └─► Authelia :9091 (forward auth)
   │             │ No valid session
   │             └─► 302 Redirect to auth.domain.com
   │
User sees Authelia login page
   │ Submits credentials
   │
   ├─► Authelia :9091
   │      │ Validates against OpenLDAP
   │      ├─► OpenLDAP :389 (LDAP bind)
   │      │      └─► Returns user DN + groups
   │      │
   │      │ Creates session (stored in Redis)
   │      └─► 302 Redirect back to Grafana
   │
User → https://grafana.domain.com (with session cookie)
   │
   ├─► Caddy → Authelia (validates session) → OK
   └─► Caddy → Grafana :3000
          │ Grafana validates OIDC token
          │ Maps groups to roles
          └─► Renders dashboard
```

### Diagnostic Probe Flow

```
HTTP POST /start-stack-probe → Probe Orchestrator :8089
   │
   ├─► Reads configs/probe-orchestrator/services_manifest.json
   │      {
   │        "services": [
   │          {"name": "grafana", "internal": ["http://grafana:3000"], ...}
   │        ]
   │      }
   │
   ├─► For each service:
   │      │
   │      ├─► LLM Agent (probe workflow)
   │      │      │ System prompt: "You are a probe agent..."
   │      │      │ Tools: browser_screenshot, browser_dom, http_get, finish
   │      │      │
   │      │      ├─► Tool Call: browser_screenshot(url)
   │      │      │      └─► KFuncDB :8081/call-tool
   │      │      │             └─► Playwright :3000
   │      │      │                    └─► Returns base64 PNG
   │      │      │
   │      │      ├─► OCR (optional, if vision model configured)
   │      │      │      └─► POST LLM_BASE_URL/chat/completions
   │      │      │             (with image_url in messages)
   │      │      │
   │      │      ├─► Generate wellness report
   │      │      │      └─► LLM analyzes OCR + DOM + HTTP status
   │      │      │
   │      │      └─► Tool Call: finish(status, reason)
   │      │
   │      └─► Save screenshot to volumes/proofs/screenshots/
   │
   └─► Return JSON report:
          {
            "summary": {"total": 10, "healthy": 8, "failed": 2},
            "services": [ {...}, {...} ]
          }
```

## Technology Stack

### Core Languages & Runtimes

| Component | Language | Runtime | Framework |
|-----------|----------|---------|-----------|
| KFuncDB | Kotlin 2.0.21 | JVM 21 | None (lightweight) |
| Probe Orchestrator | Kotlin 2.0.21 | JVM 21 | Ktor 3.0 |
| Speech Gateway | Kotlin 2.0.21 | JVM 21 | Ktor 3.0 |
| vLLM Router | Kotlin 2.0.21 | JVM 21 | Ktor 3.0 |
| Stack Discovery | Kotlin 2.0.21 | JVM 21 | None |
| Playwright Service | Python 3.11 | CPython | FastAPI |

### Build & Deployment

- **Build Tool**: Gradle 8.14 with Kotlin DSL
- **Containerization**: Docker multi-stage builds
- **Orchestration**: Docker Compose v2
- **JVM Toolchain**: Eclipse Temurin 21
- **Gradle Plugins**: Shadow (fat JARs), Kotlin Serialization

### Libraries & Dependencies

**Kotlin Services:**
```kotlin
// HTTP Server & Client
io.ktor:ktor-server-netty:3.0.0
io.ktor:ktor-client-cio:3.0.0

// Serialization
kotlinx-serialization-json:1.7.3

// SSH Operations (KFuncDB)
com.hierynomus:sshj:0.37.0

// JSON (Jackson for compatibility)
com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2

// Testing
org.junit.jupiter:junit-jupiter:5.11.3
kotlin-test:2.0.21
```

**Python Services:**
```python
# Browser Automation
playwright==1.49.0

# Web Framework
fastapi==0.100+
uvicorn==0.23+
```

### Infrastructure Technologies

| Category | Technology | Version |
|----------|-----------|---------|
| **Reverse Proxy** | Caddy | 2.8.4 |
| **Authentication** | Authelia | 4.39.13 |
| **Directory** | OpenLDAP | 1.5.0 |
| **LLM Serving** | vLLM | latest |
| **API Gateway** | LiteLLM | 1.74.9 |
| **Embedding** | Hugging Face TEI | cpu-1.2 |
| **Browser** | Playwright (Firefox) | 1.49.0 |
| **Databases** | PostgreSQL, MariaDB, Redis | 16, 11, 7 |
| **Vector DB** | Qdrant | latest |

## Design Patterns

### 1. Plugin Architecture (KFuncDB)

**Problem**: Need extensible tool system for diverse capabilities (browser, SSH, Docker, etc.)

**Solution**: Plugin interface with capability-based access control

```kotlin
interface Plugin {
    fun manifest(): Manifest
    fun init(ctx: PluginContext)
    fun registerTools(registry: ToolRegistry)
    fun shutdown()
}

// Capability enforcement
val allowedCaps = System.getenv("KFUNCDB_ALLOW_CAPS")
    .split(',')
    .toSet()

plugins.forEach { plugin ->
    val missingCaps = plugin.manifest().capabilities
        .filterNot { it in allowedCaps }
    if (missingCaps.isEmpty()) {
        plugin.init(context)
    }
}
```

**Benefits:**
- Zero reflection (explicit factory registration)
- Fine-grained capability control
- External plugin support

### 2. LRU Model Management (vLLM Router)

**Problem**: Limited GPU VRAM, multiple models needed

**Solution**: Least Recently Used eviction with lazy loading

```kotlin
class ModelManager(val maxResident: Int = 2) {
    private val lruQueue = LinkedHashMap<String, Model>()

    suspend fun ensureLoaded(modelName: String) {
        if (modelName in lruQueue) {
            // Refresh LRU position
            lruQueue.remove(modelName)
            lruQueue[modelName] = model
        } else {
            // Evict LRU if at capacity
            if (lruQueue.size >= maxResident) {
                val evictId = lruQueue.keys.first()
                unloadModel(evictId)
            }
            loadModel(modelName)
        }
    }
}
```

### 3. Agent-Oriented Probing

**Problem**: Determine service health without hardcoded checks

**Solution**: LLM agent with tools that adapts to different service types

```kotlin
val SYSTEM_PROMPT = """
You are an autonomous probe agent.
Goal: Capture screenshot, OCR, optionally fetch DOM/HTTP,
      then decide if service is healthy.
Tools: browser_screenshot, browser_dom, http_get, finish
"""

// Agent executes tool calls based on observations
repeat(MAX_STEPS) {
    val response = llm.chat(messages, tools)
    val toolCall = response.toolCalls.first()

    when (toolCall.name) {
        "browser_screenshot" -> {
            val screenshot = kfuncdb.call("browser_screenshot", args)
            val ocrText = ocrModel.extract(screenshot)
            messages.add(toolResponse(ocrText))
        }
        "finish" -> return ProbeResult(...)
    }
}
```

**Benefits:**
- No hardcoded health checks per service
- Adapts to UI changes
- Human-readable evidence (screenshots)

### 4. Composition Over Inheritance

All Kotlin services use composition for HTTP clients, JSON serialization, etc. rather than framework inheritance:

```kotlin
// Not: class MyService : BaseService()
// Instead:
class ProbeOrchestrator {
    private val client = HttpClient(CIO) { ... }
    private val json = Json { ... }

    suspend fun probe(url: String): Result {
        val response = client.get(url)
        return json.decodeFromString(response.body())
    }
}
```

## Security Architecture

### Authentication & Authorization

1. **User Authentication**: LDAP-backed via Authelia
2. **Service Authentication**: Bearer tokens (LITELLM_MASTER_KEY, OIDC secrets)
3. **Container Isolation**: Services run as non-root where possible
4. **Secrets Management**: Environment variables (`.env` file, gitignored)

### Network Security

- **No Direct Database Access**: Applications → backend network → database network
- **TLS Termination**: Caddy handles all HTTPS (internal HTTP)
- **Internal CA**: Self-signed certificates for internal services
- **IP Allowlisting**: `API_LITELLM_ALLOWLIST` for API endpoint

### Capability-Based Access (KFuncDB)

```bash
KFUNCDB_ALLOW_CAPS=host.shell.read,host.docker.write,host.network.http
```

Only plugins declaring these capabilities in their manifest are loaded.

### SSH Key-Based Operations

```
KFuncDB → SSH (key auth) → Host (forced command wrapper)
                                   └─► Only allowed commands
```

## Scalability Considerations

### Current Limitations

- **Single-node deployment**: All services on one Docker host
- **Shared GPU**: vLLM monopolizes GPU (via vLLM Router for multi-model)
- **No horizontal scaling**: Stateful services (Authelia sessions in Redis)

### Scalability Paths

1. **Database Scaling**
   - PostgreSQL read replicas
   - Redis Sentinel for HA
   - Qdrant distributed mode

2. **LLM Scaling**
   - Multiple vLLM instances (different GPUs)
   - vLLM Router with backend pool
   - Ray-based distributed inference

3. **Application Scaling**
   - Kubernetes migration
   - Stateless app replicas behind load balancer
   - Shared session store (already Redis-backed)

### Resource Requirements by Profile

| Profile | Services | Min RAM | Min Disk | GPU Required |
|---------|----------|---------|----------|--------------|
| `bootstrap` | 12 | 8GB | 20GB | Yes (vLLM) |
| `+ databases` | +6 | 12GB | 40GB | Yes |
| `+ applications` | +25 | 16GB | 60GB | Yes |
| `+ bootstrap_vector_dbs` | +3 | 18GB | 80GB | Yes |

**Recommendations:**
- **Development**: 16GB RAM, 100GB SSD, RTX 3060 (12GB VRAM)
- **Production**: 32GB RAM, 500GB NVMe, RTX 4090 (24GB VRAM)

---

## Diagrams

### Container Dependency Graph

```
Caddy
 ├─► Authelia
 │    ├─► OpenLDAP
 │    └─► Redis
 │
 ├─► Open WebUI
 │    └─► LiteLLM
 │         ├─► vLLM Router
 │         │    └─► vLLM (GPU)
 │         └─► Embedding Service
 │
 ├─► Probe Orchestrator
 │    ├─► KFuncDB
 │    │    └─► Playwright
 │    └─► LiteLLM
 │
 ├─► Grafana
 │    ├─► PostgreSQL
 │    └─► ClickHouse
 │
 └─► Planka, Outline, Vaultwarden, etc.
      ├─► PostgreSQL
      └─► Redis
```

### Service Startup Order

```
Phase 1: Infrastructure
  → OpenLDAP
  → Redis
  → Authelia (depends: LDAP, Redis)
  → Caddy (depends: Authelia)

Phase 2: Databases
  → PostgreSQL
  → MariaDB
  → Qdrant
  → ClickHouse

Phase 3: AI
  → vLLM (GPU required)
  → Embedding Service
  → vLLM Router (depends: vLLM)
  → LiteLLM (depends: vLLM Router, Embedding)

Phase 4: Diagnostics
  → Playwright
  → KFuncDB (depends: Playwright)
  → Probe Orchestrator (depends: KFuncDB, LiteLLM)

Phase 5: Applications
  → Open WebUI (depends: LiteLLM)
  → Grafana, Planka, Outline (depend: PostgreSQL, Redis)
  → Mailu, Seafile, etc.
```

---

**Next**: See [DEVELOPMENT.md](DEVELOPMENT.md) for building and extending the stack.
