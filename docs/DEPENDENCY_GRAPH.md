# Service Dependency Graph

**Auto-generated**: Run `kotlin scripts/analyze_dependencies.main.kts` to update

## Startup Order (by tier)

### Tier 0: Foundation (22 services)
No dependencies - can start in parallel:
- `caddy` - Reverse proxy
- `clickhouse` - Analytics database
- `couchdb` - Document database
- `docker-proxy` - Docker socket proxy
- `embedding-service` - Text embeddings
- `homepage` - Dashboard
- `kopia` - Backups
- `ldap` - Directory service
- `mailu-redis` - Mail cache
- `mariadb` - MySQL database
- `mariadb-seafile` - Seafile database
- `memcached` - Cache
- `onlyoffice` - Document editor
- `piper` - TTS service
- `playwright` - Browser automation
- `postgres` - Primary database
- `qdrant` - Vector database
- `redis` - Cache/sessions
- `redis-synapse` - Matrix cache
- `ssh-key-bootstrap` - SSH host key scanner
- `whisper` - Speech recognition

### Tier 1: Core Infrastructure (7 services)
Depends only on Tier 0:
- `authelia` → [ldap, redis, postgres] - SSO/OIDC provider
- `bookstack` → [mariadb, authelia] - Wiki
- `dockge` → [docker-proxy] - Stack manager
- `ktspeechgateway` → [whisper, piper] - Speech gateway
- `mailu-admin` → [postgres, mailu-redis] - Mail admin
- `synapse` → [redis-synapse, postgres] - Matrix server
- `vector-bootstrap` → [qdrant] - Vector DB init

### Tier 2: Applications (13 services)
Depends on auth + databases:
- `dockge-init` → [dockge] - Dockge initialization
- `grafana` → [postgres, authelia] - Monitoring dashboards (PostgreSQL)
- `homeassistant` → [authelia, postgres] - Home automation
- `jupyterhub` → [authelia, docker-proxy] - Notebooks
- `ldap-account-manager` → [ldap, authelia] - LDAP admin
- `mailu-antispam` → [mailu-admin] - Email filtering
- `mailu-front` → [mailu-admin] - Mail frontend
- `mailu-imap` → [mailu-admin] - IMAP server
- `mailu-smtp` → [mailu-admin] - SMTP server
- `planka` → [postgres, authelia] - Kanban (PostgreSQL)
- `portainer` → [authelia] - Docker UI
- `seafile` → [memcached, mariadb-seafile, authelia] - File sync
- `vllm` → [authelia] - LLM inference

### Tier 3: Secondary Apps (5 services)
- `mailu-webmail` → [mailu-admin, mailu-imap, mailu-front] - Webmail
- `mastodon-web` → [postgres, redis, authelia, mailu-smtp] - Social network
- `sogo` → [mailu-imap, mailu-smtp] - Groupware
- `vaultwarden` → [postgres, authelia, mailu-smtp] - Password manager (PostgreSQL)
- `vllm-router` → [vllm, authelia] - LLM load balancer

### Tier 4: LLM Stack (4 services)
- `litellm` → [vllm-router, authelia, embedding-service] - LLM gateway
- `mastodon-init` → [mastodon-web] - Mastodon setup
- `mastodon-sidekiq` → [mastodon-web, redis, postgres] - Background jobs
- `mastodon-streaming` → [mastodon-web, redis, postgres] - Real-time updates

### Tier 5: Agent Services (3 services)
- `agent-tool-server` → [ssh-key-bootstrap, authelia, playwright, litellm] - Tool execution (SSH key verification)
- `benthos` → [qdrant, clickhouse, litellm] - Data pipeline (needs litellm for embeddings)
- `open-webui` → [postgres, litellm, authelia] - Chat UI (PostgreSQL)

### Tier 6: Diagnostics (1 service)
- `probe-orchestrator` → [agent-tool-server, litellm, playwright] - Health monitoring

## Critical Paths

**Longest startup chains:**

```
probe-orchestrator (7 hops):
  ldap → authelia → vllm → vllm-router → litellm → agent-tool-server → probe-orchestrator
  Estimated time: 2-3 minutes

open-webui (6 hops):
  postgres → authelia → vllm → vllm-router → litellm → open-webui
  Estimated time: 2-3 minutes

grafana (3 hops):
  postgres → authelia → grafana
  Estimated time: 45-60 seconds

mastodon-web (4 hops):
  postgres → mailu-admin → mailu-smtp → mastodon-web
  Estimated time: 60-90 seconds

litellm (5 hops):
  ldap → authelia → vllm → vllm-router → litellm
  Estimated time: 2-2.5 minutes
```

## Common Failure Modes

### 1. Authelia fails
**Impact**: 90% of applications can't start (entire Tier 2+)

**Causes**:
- LDAP connection failure
- PostgreSQL unavailable
- Redis unavailable
- Invalid OIDC configuration

**Recovery**:
```bash
docker compose logs authelia
docker compose restart ldap redis postgres
docker compose restart authelia
docker compose up -d  # Restart dependent services
```

### 2. PostgreSQL OOM/crash
**Impact**: authelia, planka, grafana, vaultwarden, open-webui, synapse, mastodon, homeassistant, mailu fail

**Causes**:
- No memory limits set
- Runaway query
- Too many connections (10 services use postgres)

**Recovery**:
```bash
docker compose stop postgres
docker compose start postgres
# Wait for healthy
docker compose restart authelia planka grafana vaultwarden open-webui synapse mastodon-web homeassistant mailu-admin
```

**Note**: PostgreSQL serves 10 databases:
- authelia, grafana, planka, synapse, mailu, mastodon, homeassistant
- vaultwarden, openwebui (migrated from SQLite 2025-12-02)
- langgraph, litellm (AI services)

### 3. vLLM GPU allocation failure
**Impact**: litellm fails → probe-orchestrator, open-webui fail

**Causes**:
- GPU already in use
- CUDA driver issue
- Insufficient VRAM

**Recovery**:
```bash
nvidia-smi  # Check GPU status
docker compose logs vllm
docker compose restart vllm
# Wait for healthy
docker compose restart vllm-router litellm
```

### 4. Caddy can't bind port 443
**Impact**: External access broken (internal services still work)

**Causes**:
- Port already in use
- Insufficient permissions
- Firewall blocking

**Recovery**:
```bash
sudo lsof -i :443
sudo systemctl stop nginx  # If conflicting
docker compose restart caddy
```

### 5. SSH key bootstrap fails
**Impact**: agent-tool-server can't start → probe-orchestrator fails

**Causes**:
- SSH host unreachable
- No SSH keys on host
- Network isolation

**Recovery**:
```bash
docker compose logs ssh-key-bootstrap
docker compose restart ssh-key-bootstrap
# Or manually:
docker exec -it ssh-key-bootstrap sh /bootstrap_known_hosts.sh /app/known_hosts
```

**Note**: SSH connections use strict host key checking (MITM protection enabled 2025-12-02)

### 6. SSH host key changed (legitimate rotation)
**Impact**: agent-tool-server SSH commands fail with `HostKeyChangedException`

**Cause**: Host SSH server key was regenerated (e.g., after OS reinstall)

**Recovery**:
```bash
# Refresh known_hosts
curl -X POST http://agent-tool-server:8081/admin/refresh-ssh-keys

# Or manually
docker exec ssh-key-bootstrap sh /bootstrap_known_hosts.sh /app/known_hosts
docker compose restart agent-tool-server
```

## Recovery Strategies

### Graceful restart (preserve data)
```bash
docker compose restart <service-name>
```

### Hard restart (reset container)
```bash
docker compose stop <service-name>
docker compose rm -f <service-name>
docker compose up -d <service-name>
```

### Cascade restart (service + dependents)
```bash
# Example: restart postgres and everything that depends on it
docker compose restart postgres
sleep 10
docker compose restart authelia planka grafana synapse mastodon-web mailu-admin homeassistant
```

### Full stack restart
```bash
docker compose down
docker compose up -d --profile bootstrap
# Wait for bootstrap healthy
docker compose up -d --profile applications
```

## Startup Time Estimates

**From cold start (no volumes):**
- Tier 0 (databases): 30-60s
- Tier 1 (auth): +30s
- Tier 2 (apps): +30-60s
- Tier 3+: +30-60s each
- **Total**: 3-5 minutes for full bootstrap profile
- **With applications**: 8-12 minutes

**From warm start (with volumes):**
- Tier 0: 10-20s
- Tier 1+: +5-10s each tier
- **Total**: 1-2 minutes for bootstrap
- **With applications**: 3-5 minutes

## Service Profiles

### bootstrap (9 services)
Minimal viable stack - can run standalone:
- Core: ldap, redis, postgres, caddy, authelia, portainer
- LLM: vllm, litellm, open-webui
- Tools: playwright, agent-tool-server, probe-orchestrator

### bootstrap_vector_dbs (4 services)
Vector database + data pipeline:
- qdrant, clickhouse, benthos, vector-bootstrap, embedding-service

### applications (30+ services)
Full application suite:
- Collaboration: planka, bookstack, seafile
- Communication: mailu-*, mastodon-*, synapse
- Infrastructure: grafana, vaultwarden, jupyterhub
- Other: onlyoffice, homeassistant, etc.

### databases (7 services)
Additional databases not in bootstrap:
- mariadb, mariadb-seafile, couchdb
- memcached, redis-synapse, mailu-redis

### infrastructure (7 services)
Support services:
- dockge, kopia, docker-proxy
- whisper, piper, ktspeechgateway

## Dependency Health Checks

All `depends_on` use health checks to ensure services are ready:

```yaml
depends_on:
  postgres:
    condition: service_healthy  # Waits for healthcheck to pass
  qdrant:
    condition: service_started  # Just waits for container start (qdrant has no real healthcheck)
  ssh-key-bootstrap:
    condition: service_completed_successfully  # One-time init container
```

## Network Topology

### frontend (172.20.0.0/24)
Public-facing services (via Caddy):
- caddy, open-webui, portainer, grafana, etc.

### backend (172.21.0.0/24)
Internal services communication:
- agent-tool-server, probe-orchestrator, litellm, playwright, etc.

### database (172.22.0.0/24)
Database services only:
- postgres, mariadb, redis, clickhouse, qdrant

**Design principle**: Services connect to minimum required networks (least privilege)

## Testing

### Analyze current dependencies
```bash
kotlin scripts/analyze_dependencies.main.kts
```

### Test cold start
```bash
./scripts/test_cold_start.sh
```

### Test specific service startup
```bash
docker compose up -d postgres
docker compose up -d authelia
docker compose up -d grafana
```

### Verify dependency chain
```bash
# Check a service's dependencies
docker compose config | yq '.services.probe-orchestrator.depends_on'
```

## Troubleshooting

### Service stuck in "starting"
```bash
docker compose logs <service-name>
docker inspect <container-name> | jq '.[].State.Health'
```

### Circular dependency detection
Run dependency analyzer - orphans section will show circular deps:
```bash
kotlin scripts/analyze_dependencies.main.kts | grep -A 20 "ORPHANS"
```

### Why won't service start?
Check if dependencies are healthy:
```bash
docker compose ps | grep -E "(healthy|unhealthy)"
```

---

**Last updated**: 2025-12-02
**Services analyzed**: 55
**Max depth**: 6 tiers
**Longest path**: 7 hops (probe-orchestrator)
