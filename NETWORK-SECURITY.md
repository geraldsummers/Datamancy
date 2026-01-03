# Network Security Architecture

## Overview

The Datamancy stack implements **network isolation** to protect internal infrastructure from external actors (LLMs) that interact with the agent-tool-server. This prevents untrusted AI agents from directly accessing databases or internal services.

## Threat Model

**Primary Threat**: External AI/LLM agents accessing the agent-tool-server could potentially:
- Execute malicious queries against databases
- Enumerate internal services
- Access sensitive data across the network
- Pivot to other internal systems
- Perform lateral movement attacks

**Solution**: Network segmentation with a controlled API gateway.

## Network Topology

```
┌─────────────────────────────────────────────────────────────────┐
│ FRONTEND NETWORK (172.20.0.0/24)                               │
│ Public-facing: Caddy reverse proxy only                         │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│ BACKEND NETWORK (172.21.0.0/24)                                │
│ Internal services: control-panel, data-fetcher, etc.            │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│ DATABASE NETWORK (172.22.0.0/24)                               │
│ Databases: postgres, mariadb, clickhouse, qdrant, ldap          │
└─────────────────────────────────────────────────────────────────┘

        ┌───────────────────────────────────┐
        │ AI NETWORK (172.23.0.0/24)        │
        │ ISOLATED - External Actor Zone    │
        │                                   │
        │ ┌─────────────────────────────┐   │
        │ │ agent-tool-server           │   │ ← External LLM requests
        │ │ (MCP Protocol)              │   │
        │ └─────────────────────────────┘   │
        │          │                        │
        │          ↓                        │
        │ ┌─────────────────────────────┐   │
        │ │ litellm (AI Gateway)        │   │
        │ └─────────────────────────────┘   │
        │          │                        │
        │          ↓                        │
        │ ┌─────────────────────────────┐   │
        │ │ vllm (LLM Inference)        │   │
        │ └─────────────────────────────┘   │
        │          │                        │
        │          ↓                        │
        │ ┌─────────────────────────────┐   │
        │ │ embedding-service           │   │
        │ └─────────────────────────────┘   │
        └───────────┬───────────────────────┘
                    │
                    ↓ (ai-gateway bridge)
        ┌───────────────────────────────────┐
        │ AI-GATEWAY NETWORK (172.24.0.0/24)│
        │                                   │
        │ ┌─────────────────────────────┐   │
        │ │ datamancy-api-gateway       │◄──┼── Rate limiting
        │ │ (Security Control Point)    │   │   Auth/authz
        │ └───────┬─────────────────────┘   │   Audit logging
        │         │                         │   Query validation
        └─────────┼─────────────────────────┘
                  │
                  ↓ (Controlled access)
        ┌─────────────────────────────────────┐
        │ BACKEND + DATABASE NETWORKS         │
        └─────────────────────────────────────┘
```

## Network Segments

### 1. Frontend Network (172.20.0.0/24)
**Purpose**: Public-facing services exposed via Caddy reverse proxy

**Services**:
- Caddy (reverse proxy)
- LiteLLM (external API)
- agent-tool-server (MCP protocol endpoint)

**Access**: Internet → Caddy → Services

### 2. Backend Network (172.21.0.0/24)
**Purpose**: Internal application services

**Services**:
- control-panel
- data-fetcher
- unified-indexer
- search-service
- bookstack, grafana, etc.

**Access**: Internal only, no direct AI access

### 3. Database Network (172.22.0.0/24)
**Purpose**: Database tier

**Services**:
- PostgreSQL
- MariaDB
- ClickHouse
- Qdrant (vector DB)
- LDAP

**Access**: Internal only, no direct AI access

### 4. AI Network (172.23.0.0/24) - **ISOLATED**
**Purpose**: External actor zone for AI/LLM services

**Services**:
- agent-tool-server (MCP tool server for LLMs)
- litellm (AI API gateway)
- vllm (LLM inference engine)
- embedding-service (text embeddings)

**Security Properties**:
- ❌ **NO access to backend network**
- ❌ **NO access to database network**
- ✅ Access to ai-gateway network only
- ✅ Frontend access for Caddy (reverse proxy)

**Rationale**: Services in this network handle requests from external AI agents. If compromised, the blast radius is limited to the AI network only.

### 5. AI-Gateway Network (172.24.0.0/24) - **BRIDGE**
**Purpose**: Controlled communication bridge between AI and backend

**Services**:
- datamancy-api-gateway (security control point)
- embedding-service (also in AI network)
- unified-indexer (also in backend/database)
- search-service (also in backend/database)

**Security Controls**:
- Rate limiting per endpoint
- Authentication & authorization
- Audit logging (all requests logged)
- Query validation (SQL injection prevention)
- Resource quotas (prevent DoS)

## Service Network Membership

| Service | frontend | backend | database | ai | ai-gateway | Notes |
|---------|----------|---------|----------|----|-----------| ------|
| **caddy** | ✅ | ✅ | - | - | - | Reverse proxy |
| **postgres** | - | ✅ | ✅ | - | - | Database |
| **agent-tool-server** | ✅ | - | - | ✅ | ✅ | **ISOLATED** - External actor zone |
| **litellm** | ✅ | - | - | ✅ | - | AI API gateway |
| **vllm** | - | - | - | ✅ | - | LLM inference |
| **embedding-service** | - | - | - | ✅ | ✅ | Embeddings (bridge) |
| **datamancy-api-gateway** | - | ✅ | ✅ | - | ✅ | **SECURITY CONTROL POINT** |
| **unified-indexer** | - | ✅ | ✅ | - | ✅ | Needs embeddings |
| **search-service** | - | ✅ | ✅ | - | ✅ | Needs embeddings |
| **control-panel** | ✅ | ✅ | ✅ | - | - | Admin interface |

## Security Controls

### 1. Network Isolation
- AI services **cannot directly access** backend or database networks
- Docker network isolation enforced at kernel level (iptables)
- No routing between AI and backend/database networks

### 2. API Gateway (datamancy-api-gateway)
**Location**: Bridge between AI and backend

**Responsibilities**:
- **Authentication**: Verify service identity
- **Authorization**: Enforce access control policies
- **Rate Limiting**: Prevent abuse (e.g., 100 req/min per service)
- **Audit Logging**: Log all requests with timestamps, source, query
- **Query Validation**: Sanitize/validate database queries
- **Resource Quotas**: Limit CPU/memory/time per request

**Implementation** (to be created):
```kotlin
// src/api-gateway/src/main/kotlin/ApiGateway.kt

data class AccessPolicy(
    val allowedEndpoints: List<String>,
    val rateLimit: Int,  // requests per minute
    val maxQueryRows: Int = 1000
)

val policies = mapOf(
    "agent-tool-server" to AccessPolicy(
        allowedEndpoints = listOf(
            "/api/v1/query/read-only",  // SELECT only
            "/api/v1/embeddings",       // Embedding generation
            "/api/v1/search"            // Vector search
        ),
        rateLimit = 100,
        maxQueryRows = 1000
    )
)

fun validateQuery(sql: String): Boolean {
    // Block DDL/DML
    val forbidden = listOf("DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE")
    return !forbidden.any { sql.uppercase().contains(it) }
}
```

### 3. Least Privilege
Services only connect to networks they need:
- agent-tool-server: **NO database access**
- vllm: **AI network only**
- litellm: AI + frontend only (for external API)

### 4. Defense in Depth
Multiple layers:
1. Network isolation (iptables)
2. API gateway (rate limiting, validation)
3. Database access controls (read-only users where possible)
4. Application-level permissions (RBAC)
5. Audit logging (detection)

## Attack Scenarios & Mitigations

### Scenario 1: Malicious LLM Agent
**Attack**: External AI agent sends malicious SQL via agent-tool-server

**Without Isolation**:
```
External LLM → agent-tool-server → postgres (direct)
❌ Attacker has direct database access
```

**With Isolation**:
```
External LLM → agent-tool-server → api-gateway → postgres
✅ Gateway blocks malicious queries
✅ Audit log captures attempt
✅ Rate limit prevents brute force
```

### Scenario 2: Service Compromise
**Attack**: agent-tool-server is compromised via code vulnerability

**Without Isolation**:
```
Compromised agent-tool-server → lateral movement → all backend services
❌ Full infrastructure access
```

**With Isolation**:
```
Compromised agent-tool-server → can only access AI network
✅ Cannot reach backend or databases
✅ Blast radius limited to AI services
✅ Lateral movement prevented
```

### Scenario 3: Data Exfiltration
**Attack**: Attacker attempts to dump entire database

**Without Isolation**:
```
External LLM → agent-tool-server → "SELECT * FROM users" → postgres
❌ Unrestricted access
```

**With Isolation**:
```
External LLM → agent-tool-server → api-gateway (validates query)
✅ Query rewritten with LIMIT
✅ Only approved columns returned
✅ Audit log records request
```

## Implementation Checklist

- [x] Define network segments in services.registry.yaml
- [x] Update codegen to generate 5 networks
- [x] Move agent-tool-server to AI network
- [x] Move vllm, litellm, embedding-service to AI network
- [x] Add ai-gateway network definition
- [ ] **Create datamancy-api-gateway service** (src/api-gateway/)
- [ ] Implement rate limiting in gateway
- [ ] Implement query validation in gateway
- [ ] Implement audit logging in gateway
- [ ] Configure read-only database users for gateway
- [ ] Update agent-tool-server to use gateway endpoints
- [ ] Test network isolation with docker network inspect
- [ ] Document API gateway endpoints
- [ ] Set up monitoring/alerting for gateway

## Testing Network Isolation

### Verify AI isolation:
```bash
# From agent-tool-server, try to reach postgres directly
docker exec agent-tool-server ping postgres
# Should fail: Network not connected

# Try to reach api-gateway (should work)
docker exec agent-tool-server ping datamancy-api-gateway
# Should succeed
```

### Verify gateway bridge:
```bash
# From api-gateway, reach backend
docker exec datamancy-api-gateway ping postgres
# Should succeed (gateway has access)

# From agent-tool-server, reach backend via gateway
docker exec agent-tool-server curl http://datamancy-api-gateway:8080/api/v1/query
# Should succeed (goes through gateway)
```

### Inspect network membership:
```bash
# Show which networks each service is connected to
docker inspect agent-tool-server | jq '.[0].NetworkSettings.Networks | keys'
# Should show: ["ai", "ai-gateway", "frontend"]
# Should NOT show: ["backend", "database"]
```

## Monitoring

### Key Metrics
- Gateway request rate (per service)
- Gateway error rate (blocked requests)
- Gateway latency (p50, p95, p99)
- Network traffic (ai → ai-gateway)
- Failed connection attempts (audit log)

### Alerts
- Spike in blocked queries (possible attack)
- High error rate from agent-tool-server
- Unusual query patterns (anomaly detection)
- Service attempting cross-network access

## Future Enhancements

1. **mTLS**: Mutual TLS between AI services and gateway
2. **WAF**: Web Application Firewall for gateway
3. **IDS**: Intrusion Detection System monitoring AI network
4. **Honeypot**: Fake services in AI network to detect compromise
5. **Network Policies**: Kubernetes NetworkPolicies (if migrating to k8s)
6. **Zero Trust**: Require authentication for every request

## References

- Docker Network Documentation: https://docs.docker.com/network/
- Defense in Depth: https://www.nsa.gov/Press-Room/News-Highlights/Article/Article/3563058/
- Zero Trust Architecture: https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-207.pdf

## Shadow Agent Account Architecture

Datamancy uses **per-user shadow accounts** instead of a single shared `agent_observer` account. This provides:

- ✅ **Full audit traceability**: Every query attributed to specific user (e.g., `alice-agent`)
- ✅ **Limited blast radius**: Compromised shadow account = only one user affected
- ✅ **Granular ACLs**: Per-user database access permissions
- ✅ **Per-user rate limiting**: Prevent single user abuse
- ✅ **Admin exclusion**: Admin accounts cannot have shadow agents

**Architecture:**
```
User: alice → Shadow: alice-agent → Database: alice-agent (read-only)
User: bob   → Shadow: bob-agent   → Database: bob-agent (read-only)
Admin: root → NO SHADOW ACCOUNT (admins excluded)
```

**Key Benefits:**
- Single shared account: ❌ All queries appear as `agent_observer`
- Shadow accounts: ✅ Queries attributed to `alice-agent`, `bob-agent`, etc.

See [SHADOW-AGENT-ARCHITECTURE.md](SHADOW-AGENT-ARCHITECTURE.md) for full details.

## Summary

The network isolation + shadow account architecture protects Datamancy's internal infrastructure by:
1. **Isolating AI services** in a separate network with no direct backend/database access
2. **Enforcing a gateway** (datamancy-api-gateway) as the only controlled path from AI → backend
3. **Implementing security controls** at the gateway (rate limiting, validation, logging)
4. **Using per-user shadow accounts** for full audit traceability and limited blast radius
5. **Limiting blast radius** if agent-tool-server is compromised (network isolation + per-user accounts)

This follows the **principle of least privilege** and **defense in depth** to ensure external AI agents cannot directly access sensitive internal systems.
