# Datamancy Stack Architecture Overview

## System Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         DATAMANCY STACK                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌──────────────────┐     ┌──────────────────┐    ┌──────────────────┐ │
│  │   Data Pipeline  │────▶│  Document Staging │───▶│  Search Service  │ │
│  │   (Ingestion)    │     │   (PostgreSQL)    │    │  (Hybrid Search) │ │
│  └──────────────────┘     └──────────────────┘    └──────────────────┘ │
│           │                         │                        │           │
│           │                         │                        │           │
│           ▼                         ▼                        ▼           │
│  ┌──────────────────┐     ┌──────────────────┐    ┌──────────────────┐ │
│  │  Embedding Svc   │     │  Qdrant Vector   │    │  Agent-Tool-Svr  │ │
│  │  (BGE/Transform) │     │   Database       │    │  (LLM Tools)     │ │
│  └──────────────────┘     └──────────────────┘    └──────────────────┘ │
│           │                         │                        │           │
│           └─────────────────────────┴────────────────────────┘           │
│                                     │                                    │
│                                     ▼                                    │
│                            ┌──────────────────┐                         │
│                            │   BookStack      │                         │
│                            │  (Knowledge Base)│                         │
│                            └──────────────────┘                         │
│                                                                           │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                      Authentication Layer                         │  │
│  │  OpenLDAP ◀──▶ Authelia (SSO) ◀──▶ Caddy (Reverse Proxy)        │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                      Monitoring & Testing                         │  │
│  │  Prometheus ◀─ Grafana    │    Test-Runner (Integration Tests)   │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Module Responsibilities

### 1. Pipeline Module
**Purpose**: Data ingestion, transformation, and distribution
**Location**: `kotlin.src/pipeline/`

**Key Responsibilities**:
- Fetch data from external sources (RSS, CVE, Wikipedia, torrents, legal docs)
- Transform and chunk content for embedding models
- Deduplicate documents
- Stage documents in PostgreSQL
- Coordinate embedding generation and vector storage
- Publish to BookStack knowledge base

**External Dependencies**:
- **PostgreSQL** (`document_staging` table) - Staging area for all documents
- **Qdrant** (gRPC:6334) - Vector storage for semantic search
- **Embedding Service** (HTTP:8000) - Text-to-vector transformation
- **BookStack API** (HTTP) - Knowledge base publishing

**Data Flow**:
```
Source → Deduplication → Chunking → Staging (PostgreSQL)
                                          ↓
                    EmbeddingScheduler polls for PENDING docs
                                          ↓
                    Embedder → Vector → Qdrant (COMPLETED)
                                          ↓
                    BookStackWriter polls for COMPLETED docs
                                          ↓
                    BookStack API (pages created)
```

---

### 2. Agent-Tool-Server Module
**Purpose**: Expose LLM-callable tools for autonomous agents
**Location**: `kotlin.src/agent-tool-server/`

**Key Responsibilities**:
- Plugin-based tool registration system
- HTTP endpoints for tool discovery and invocation
- OpenAI-compatible function calling proxy
- Capability-based security enforcement
- Multi-tenancy via user-context headers

**Plugin Categories**:
- **CoreToolsPlugin**: String/JSON/math utilities (no external access)
- **HostToolsPlugin**: Docker host inspection and management
- **BrowserToolsPlugin**: Headless browser automation (Playwright)
- **LlmCompletionPlugin**: LLM chat and embedding generation
- **OpsSshPlugin**: Remote host operations via SSH
- **DataSourceQueryPlugin**: Read-only database queries with shadow accounts
- **DockerContainerPlugin**: Ephemeral container lifecycle
- **VmProvisioningPlugin**: Libvirt VM management

**External Dependencies**:
- **LiteLLM** (HTTP:4000) - Model routing and function calling proxy
- **Playwright** (HTTP:3000) - Browser automation service
- **PostgreSQL** (JDBC) - Query tool for Grafana/Planka/Mastodon/Forgejo databases
- **MariaDB** (JDBC) - Query tool for BookStack database
- **Qdrant** (HTTP:6333) - Vector search tool
- **OpenLDAP** (LDAP:389) - Directory search tool
- **Search Service** (HTTP:8098) - Semantic search tool
- **Docker daemon** (Unix socket or TCP) - Container management
- **Embedding Service** (HTTP:8000) - Text embedding tool

**Integration with Stack**:
- Agents call tools via `/call-tool` endpoint (JSON body: `{name, args}`)
- Tools interact with virtually every service in the stack
- Shadow accounts prevent privilege escalation in database queries
- User-context header enables per-user isolation

---

### 3. Search-Service Module
**Purpose**: Unified hybrid search gateway
**Location**: `kotlin.src/search-service/`

**Key Responsibilities**:
- Vector similarity search (semantic understanding)
- Full-text search (keyword precision via PostgreSQL)
- Hybrid search with Reciprocal Rank Fusion (RRF)
- Content type classification and capability inference
- Audience-based filtering (human vs. agent)

**External Dependencies**:
- **Qdrant** (gRPC:6334) - Vector search backend
- **PostgreSQL** (`document_staging` table) - Full-text search backend
- **Embedding Service** (HTTP:8000) - Query vectorization

**Data Flow**:
```
Query → Embedding Service → Vector
                                ↓
        ┌───────────────────────┴───────────────────────┐
        ▼                                               ▼
  Qdrant Vector Search                    PostgreSQL Full-Text Search
  (Cosine Similarity)                     (ts_rank on tsvector)
        │                                               │
        └───────────────────┬───────────────────────────┘
                            ▼
                  Reciprocal Rank Fusion (RRF)
                            ▼
                  Ranked, Deduplicated Results
```

**Integration with Stack**:
- Used by agent-tool-server's `semantic_search` tool
- Provides RAG (Retrieval-Augmented Generation) capabilities for LLMs
- Reads same data that pipeline writes to PostgreSQL and Qdrant

---

### 4. Test-Runner Module
**Purpose**: Integration testing for entire stack
**Location**: `kotlin.src/test-runner/`

**Key Responsibilities**:
- Validate authentication flows (LDAP → Authelia → OIDC → Services)
- Test data pipeline end-to-end (fetch → transform → index → publish)
- Verify agent tool functionality and performance
- Validate search service accuracy
- Test database integrity and performance
- Probabilistic/latency/throughput testing

**Test Coverage**:
- **FoundationTests**: Health checks, basic connectivity
- **AuthenticationTests**: LDAP, Authelia, service auth requirements
- **EnhancedAuthenticationTests**: OIDC flows, SSO, forward-auth
- **DatabaseTests**: PostgreSQL/MariaDB transactions, performance
- **SearchServiceTests**: Vector/BM25/hybrid search, content filtering
- **BookStackIntegrationTests**: Pipeline-generated books validation
- **AgentCapabilityTests**: Tool reliability, latency, throughput, error handling
- **DataPipelineTests**: End-to-end ingestion workflows

**Integration with Stack**:
- Tests run inside Docker network or via localhost port mapping
- Creates ephemeral LDAP users for isolated testing
- Validates cross-service interactions (e.g., Authelia SSO across multiple apps)
- Tests simulate agent workflows to validate tool chains

---

## Critical Inter-Module Data Flows

### Flow 1: Document Ingestion → Vector Search

```
┌──────────────┐
│   Pipeline   │
└──────┬───────┘
       │ 1. Fetch RSS/CVE/Wikipedia/etc.
       │ 2. Deduplicate (DeduplicationStore)
       │ 3. Chunk if needed (Chunker)
       ▼
┌──────────────────┐
│  PostgreSQL      │
│  document_staging│  (status: PENDING)
└──────┬───────────┘
       │
       │ 4. EmbeddingScheduler polls
       ▼
┌──────────────────┐
│ Embedding Service│  (BGE-M3 model)
└──────┬───────────┘
       │ 5. Returns vector (FloatArray)
       ▼
┌──────────────────┐
│  Qdrant Vector DB│  (indexed for search)
└──────┬───────────┘
       │
       │ 6. Search-Service queries
       ▼
┌──────────────────┐
│  Search Results  │  (sent to agent or user)
└──────────────────┘
```

**Key Insight**: PostgreSQL acts as the buffer between ingestion and embedding, enabling fault tolerance and rate limiting.

---

### Flow 2: Agent Tool Execution

```
┌──────────────┐
│  LLM Agent   │  (via LiteLLM)
└──────┬───────┘
       │ 1. LLM requests tool execution
       │    {"name": "semantic_search", "args": {"query": "k8s docs"}}
       ▼
┌──────────────────┐
│ Agent-Tool-Server│
└──────┬───────────┘
       │ 2. Routes to DataSourceQueryPlugin.semantic_search()
       ▼
┌──────────────────┐
│  Search-Service  │
└──────┬───────────┘
       │ 3. Hybrid search (vector + BM25)
       │    - Calls Embedding Service for query vector
       │    - Searches Qdrant and PostgreSQL
       │    - Merges results with RRF
       ▼
┌──────────────────┐
│   LLM Agent      │  (receives ranked documents)
└──────────────────┘
       │ 4. LLM synthesizes answer from documents
       ▼
┌──────────────────┐
│   User/Client    │  (final response)
└──────────────────┘
```

**Key Insight**: Agent-tool-server acts as a bridge between LLMs and the entire Datamancy infrastructure.

---

### Flow 3: Authentication & Authorization

```
┌──────────────┐
│    User      │
└──────┬───────┘
       │ 1. Access protected service (e.g., Grafana)
       ▼
┌──────────────────┐
│  Caddy Proxy     │
└──────┬───────────┘
       │ 2. Forward-auth check to Authelia
       ▼
┌──────────────────┐
│   Authelia       │
└──────┬───────────┘
       │ 3. No session → redirect to /login
       │ 4. User submits credentials
       ▼
┌──────────────────┐
│   OpenLDAP       │
└──────┬───────────┘
       │ 5. LDAP bind validates credentials
       │ 6. Returns user attributes and group membership
       ▼
┌──────────────────┐
│   Authelia       │  (creates session)
└──────┬───────────┘
       │ 7. Sets authelia_session cookie
       │ 8. Redirects back to Caddy
       ▼
┌──────────────────┐
│  Caddy Proxy     │  (validates session)
└──────┬───────────┘
       │ 9. Proxies to Grafana
       ▼
┌──────────────────┐
│    Grafana       │  (user authenticated)
└──────────────────┘
```

**Key Insight**: Single sign-on (SSO) is implemented via Authelia + Caddy forward-auth. One login grants access to all services.

---

### Flow 4: OIDC Integration (for OpenWebUI, Grafana, etc.)

```
┌──────────────┐
│  Open-WebUI  │
└──────┬───────┘
       │ 1. User clicks "Login with Authelia"
       │ 2. Redirects to Authelia /authorize
       ▼
┌──────────────────┐
│   Authelia       │
└──────┬───────────┘
       │ 3. User already has session (SSO) OR logs in
       │ 4. Returns authorization code
       ▼
┌──────────────────┐
│  Open-WebUI      │
└──────┬───────────┘
       │ 5. Exchanges code for tokens at /token
       ▼
┌──────────────────┐
│   Authelia       │
└──────┬───────────┘
       │ 6. Returns access_token, id_token, refresh_token
       ▼
┌──────────────────┐
│  Open-WebUI      │  (decodes id_token to get user info)
└──────────────────┘
```

**Key Insight**: Authelia acts as both SSO provider (session-based) and OIDC provider (token-based) for different integration patterns.

---

## Shared Infrastructure

### PostgreSQL
**Databases**:
- `datamancy` - Main database
  - `document_staging` - Pipeline staging table (used by Pipeline and Search-Service)
- `grafana` - Grafana configuration
- `planka` - Project management data
- `mastodon` - Social network data
- `forgejo` - Git hosting data

**Agent Access**:
- Agent-tool-server provides `query_postgres` tool
- Shadow accounts (e.g., `alice-agent`) prevent privilege escalation
- Only `agent_observer` schema accessible (safe views only)

---

### Qdrant Vector Database
**Collections**:
- `rss_feeds` - RSS articles
- `cve` - CVE vulnerability reports
- `torrents` - Torrent metadata
- `wikipedia` - Wikipedia articles
- `australian_laws` - Legal corpus
- `linux_docs` - Linux documentation
- `debian_wiki` - Debian wiki pages
- `arch_wiki` - Arch wiki pages

**Writers**: Pipeline (via EmbeddingScheduler → QdrantSink)
**Readers**: Search-Service, Agent-Tool-Server (search_qdrant tool)

---

### Embedding Service
**Model**: BGE-M3 (1024-dimensional vectors)
**Max Tokens**: 8192
**Clients**:
- Pipeline (Embedder processor)
- Search-Service (query vectorization)
- Agent-Tool-Server (llm_embed_text tool)

**API**:
- `POST /embed` - Input: `{"inputs": "text"}`, Output: `[[float, ...]]`

---

### LiteLLM Proxy
**Purpose**: Unified LLM gateway with model routing, fallback, and rate limiting
**Models Supported**: Hermes-2-Pro-Mistral-7B, Qwen-7B, Claude, GPT-4, etc.

**Clients**:
- Agent-Tool-Server (OpenAIProxyHandler, LlmCompletionPlugin)
- Open-WebUI (primary chat interface)

**Function Calling**:
- Agent-Tool-Server's `/v1/chat/completions` endpoint injects tools automatically
- LiteLLM calls tools via agent-tool-server's `/call-tool` endpoint
- Multi-turn conversation until LLM provides final answer

---

### OpenLDAP
**Base DN**: `dc=datamancy,dc=net`
**Organizational Units**:
- `ou=users` - User accounts
- `ou=groups` - Group memberships

**Integrations**:
- Authelia (authentication backend)
- LDAP Account Manager (user management UI)
- Agent-Tool-Server (search_ldap tool)

**Test-Runner**:
- Creates ephemeral users for isolated testing
- Validates group-based access control
- Tests LDAP bind authentication

---

### BookStack
**Purpose**: Human-readable knowledge base
**Data Source**: Pipeline's BookStackWriter

**Hierarchy**:
- Books (e.g., "RSS Feeds", "CVE Database")
- Chapters (e.g., "Hacker News", "High Severity CVEs")
- Pages (individual documents with HTML content)

**Agent Access**:
- Agent-Tool-Server doesn't have BookStack tools yet (future enhancement)
- Tests validate BookStack API functionality via direct HTTP

---

## Monitoring & Observability

### Prometheus
**Metrics Collection**:
- Pipeline exposes metrics via MonitoringServer (port 8090)
- Services expose `/metrics` endpoints
- Prometheus scrapes all services every 15 seconds

### Grafana
**Dashboards**:
- Pipeline status (documents processed, failed, queue sizes)
- Agent tool latency/throughput
- Search service performance
- Database connection pools
- System metrics (CPU, memory, disk, network)

**Authentication**: Authelia SSO or OIDC

---

## Key Design Patterns

### 1. Staging Pattern (Pipeline)
**Problem**: Embedding service may be slow or unavailable
**Solution**: Stage documents in PostgreSQL, process asynchronously
**Benefit**: Ingestion and embedding are decoupled, fault-tolerant

### 2. Plugin Architecture (Agent-Tool-Server)
**Problem**: Need extensibility without modifying core
**Solution**: Plugin interface + factory registration + manifest validation
**Benefit**: New tools can be added as JARs without recompilation

### 3. Hybrid Search (Search-Service)
**Problem**: Vector search alone misses exact keyword matches; keyword search alone misses semantics
**Solution**: Run both in parallel, merge with RRF
**Benefit**: Best of both worlds - precision and recall

### 4. Shadow Accounts (Agent-Tool-Server)
**Problem**: Agents shouldn't use admin credentials for database queries
**Solution**: Per-user shadow accounts (e.g., `alice-agent`) with row-level security
**Benefit**: Prevents privilege escalation, enables audit trails

### 5. Ephemeral Resources (Test-Runner)
**Problem**: Tests should be isolated and repeatable
**Solution**: Create temporary LDAP users, test collections, containers
**Benefit**: Tests don't interfere with each other or production data

---

## Security Model

### Authentication Layers
1. **LDAP** - Authoritative user directory
2. **Authelia** - SSO and OIDC provider
3. **Caddy** - Forward-auth enforcement
4. **Services** - Session validation or OIDC token validation

### Agent Tool Security
1. **Capability Policy** - Plugins declare required capabilities (e.g., `host.docker.write`)
2. **Command Whitelisting** - Only safe commands allowed (e.g., `cat`, `ls`, but not `rm`)
3. **Input Sanitization** - All inputs validated for injection attacks
4. **Shadow Accounts** - Database queries use per-user limited accounts
5. **Query Validation** - SQL parsed and validated (SELECT only, no dangerous functions)
6. **Timeouts** - All operations have timeouts to prevent DoS

### Network Isolation
- Internal Docker network for service-to-service communication
- Caddy reverse proxy as single public entry point
- PostgreSQL/MariaDB not exposed publicly
- Qdrant not exposed publicly

---

## Future Enhancements

### 1. Pipeline Enhancements
- **Incremental updates** for Wikipedia (currently full re-scan)
- **More sources**: Academic papers (arXiv), GitHub repositories, StackOverflow
- **Content quality scoring** for better search relevance
- **Multi-language support** for non-English documents

### 2. Agent-Tool-Server Enhancements
- **BookStack write tools** (create/update pages programmatically)
- **Playwright interaction tools** (click, type, extract specific elements)
- **VM provisioning tools** (create/manage long-lived VMs)
- **More data sources** (Elasticsearch, Redis, MongoDB)

### 3. Search-Service Enhancements
- **Result caching** for common queries
- **Query expansion** using synonyms or related terms
- **Faceted search** (filter by date range, source, content type)
- **Personalization** based on user history

### 4. Test-Runner Enhancements
- **Visual regression testing** using Playwright screenshots
- **Performance regression detection** (alert if latency increases >10%)
- **Chaos engineering** (randomly kill services, test recovery)
- **Load testing** (simulate 100s of concurrent users)

---

## Deployment Architecture

### Container Network
**Docker Compose** manages all services on a shared network: `datamancy-stack_default`

**Service Dependencies**:
- Pipeline → PostgreSQL, Qdrant, Embedding Service, BookStack
- Search-Service → PostgreSQL, Qdrant, Embedding Service
- Agent-Tool-Server → LiteLLM, Playwright, PostgreSQL, MariaDB, Qdrant, OpenLDAP, Search-Service, Docker daemon
- Test-Runner → All services (for integration testing)

**Volume Mounts**:
- `/data` - Persistent storage for documents, dumps, caches
- `/var/run/docker.sock.host` - Docker daemon access (bind-mounted from host)
- `/run/secrets/datamancy` - Shadow account credentials

---

## Key Takeaways for Adding Inline Comments

### What to Comment
1. **Cross-service interactions** - Where one module calls another
2. **Data format expectations** - What structure is expected from external APIs
3. **Failure modes** - How the code handles external service failures
4. **Performance considerations** - Why specific batch sizes, timeouts, or concurrency limits
5. **Integration assumptions** - What environment variables or configurations are required
6. **Security boundaries** - Where access control or validation happens

### What NOT to Comment
1. **Obvious code** - Self-explanatory variable names and logic
2. **Standard library usage** - Common Kotlin/Java patterns
3. **Internal logic** - Implementation details that are clear from reading the code

### Example Pattern
```kotlin
// Pipeline writes documents with embedding_status=PENDING
// Search-Service only queries documents with embedding_status=COMPLETED
// This ensures only fully indexed documents appear in search results
val documents = stagingStore.getByStatus(EmbeddingStatus.COMPLETED)
```

This comment explains **how the component interacts with the broader stack**, not just what the code does.
