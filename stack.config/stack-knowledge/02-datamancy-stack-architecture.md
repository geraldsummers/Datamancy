# Datamancy Stack Architecture

## Overview

Datamancy is a comprehensive self-hosted stack with 50+ services organized into functional layers.

## Network Topology

```
┌─────────────────────────────────────────────────┐
│                  Internet                        │
└──────────────────┬──────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────┐
│              Caddy (Reverse Proxy)              │
│  - TLS termination                              │
│  - Forward auth to Authelia                     │
│  - Routes to internal services                  │
└──────────────────┬──────────────────────────────┘
                   │
        ┌──────────┴──────────┐
        │                     │
┌───────▼───────┐    ┌───────▼────────┐
│   Authelia    │    │   Services     │
│  (SSO/Auth)   │    │  (Apps/APIs)   │
└───────┬───────┘    └───────┬────────┘
        │                     │
┌───────▼─────────────────────▼────────┐
│           LDAP Directory              │
│        (User Database)                │
└───────────────────────────────────────┘
```

## Service Categories

### 1. Infrastructure Layer
- **Caddy**: Reverse proxy, TLS termination, forward auth
- **Docker Proxy**: Safe Docker socket access
- **Authelia**: SSO authentication provider
- **LDAP**: Centralized user directory

**Networks**: `caddy`, `authelia`, `ldap`

### 2. Data Layer
- **PostgreSQL**: Relational data (port 5432)
  - Multiple databases: grafana, bookstack, market_data
  - Per-service users with limited permissions
- **MariaDB**: MySQL-compatible storage
  - Used by: BookStack, Mastodon
- **Valkey**: Redis-compatible cache
- **Qdrant**: Vector database (port 6333)
  - Collections: rss_feeds, cve, torrents, wikipedia, bookstack, etc.

**Networks**: `postgres`, `mariadb`, `valkey`, `qdrant`

### 3. AI/ML Layer
- **LiteLLM**: LLM gateway (port 4000)
  - Models: qwen2.5-7b-instruct, qwen-7b (vLLM)
- **Embedding Service**: Text embeddings (bge-m3, bge-base-en-v1.5)
- **Agent Tool Server**: Tool execution for LLM agents (port 8081)
- **Search Service**: RAG provider with hybrid search (port 8098)

**Networks**: `ai`

**Key URLs**:
- LiteLLM: `http://litellm:4000`
- Agent Tool Server: `http://agent-tool-server:8081`
- Search Service: `http://search-service:8098`
- Embedding: `http://embedding-service:8080`

### 4. Data Pipeline
- **Pipeline Service**: Data ingestion, vectorization, storage (port 8090)
  - Sources: RSS, CVE, Torrents, Wikipedia, Australian Laws, Linux Docs
  - Sinks: Qdrant, PostgreSQL

**Networks**: `postgres`, `qdrant`

### 5. Knowledge Management
- **BookStack**: Wiki/documentation
- **Seafile**: File sync and share
- **OnlyOffice**: Office document editing
- **Vaultwarden**: Password manager

**Networks**: `caddy`, `postgres`/`mariadb` (depending on service)

### 6. Communication
- **Mailserver**: Email (SMTP, IMAP)
- **Synapse**: Matrix homeserver
- **Element**: Matrix web client
- **Mastodon**: Social networking
- **Ntfy**: Push notifications

**Networks**: `ldap`, `caddy`, `postgres`/`mariadb`

### 7. Development
- **Forgejo**: Git hosting
- **Forgejo Runner**: CI/CD
- **JupyterHub**: Notebook environment
- **Registry**: Docker registry

**Networks**: `caddy`, `postgres`

### 8. Monitoring
- **Prometheus**: Metrics collection
- **Grafana**: Dashboards
- **Alertmanager**: Alert routing
- **cAdvisor**: Container metrics
- **Node Exporter**: Host metrics

**Networks**: `monitoring`

## Service Communication Patterns

### Internal Service-to-Service
Services communicate using Docker network names:
```
http://service-name:port
```

Examples:
- App → Database: `postgresql://postgres:5432/dbname`
- App → LiteLLM: `http://litellm:4000`
- App → Search: `http://search-service:8098/search`

### External Access (via Caddy)
Services exposed externally follow pattern:
```
https://service.domain.com
```

Examples:
- `https://grafana.datamancy.net`
- `https://bookstack.datamancy.net`
- `https://litellm.datamancy.net`

## Authentication Flow

1. **User** → `https://app.datamancy.net`
2. **Caddy** checks forward auth
3. **Caddy** → **Authelia**: "Is user authenticated?"
4. If not authenticated:
   - Redirect to `https://auth.datamancy.net`
   - Authelia checks **LDAP** for credentials
   - Sets session cookie
5. If authenticated:
   - Forward request to service
   - Service trusts Authelia headers (X-Forwarded-User, etc.)

## Configuration Patterns

### Environment Variables
All services use `${VARIABLE}` substitution from `.credentials` file.

Common variables:
- `DOMAIN`: Base domain (e.g., datamancy.net)
- `POSTGRES_*_PASSWORD`: Per-service database passwords
- `*_API_KEY`: API keys for services
- `LDAP_BASE_DN`: LDAP base DN (dc=datamancy,dc=net)

### Volume Mounts
- **Configs**: `./configs/service:/config`
- **Data**: Named volumes (e.g., `postgres_data:/var/lib/postgresql/data`)
- **Logs**: Usually ephemeral or sent to stdout/stderr

### Health Checks
Critical services have health checks:
```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:port/health"]
  interval: 30s
  timeout: 10s
  retries: 3
```

## Common Troubleshooting

### Service Won't Start
1. Check logs: `docker compose logs service-name`
2. Check dependencies: Is the database ready?
3. Check network: `docker network ls | grep datamancy`
4. Check volumes: `docker volume ls | grep service`

### Can't Access Service
1. Check if service is up: `docker compose ps`
2. Check Caddy config: `docker compose logs caddy | grep service-name`
3. Check Authelia: `docker compose logs authelia`
4. Test internal connectivity: `docker exec container-name curl http://service:port`

### Database Connection Issues
1. Verify credentials in `.credentials`
2. Check database is healthy: `docker compose ps postgres`
3. Check user exists: `docker exec postgres psql -U postgres -c "\du"`
4. Check network connectivity: Service must be on postgres network

## Security Considerations

### Network Isolation
- Frontend services: `caddy` network only
- Backend services: Internal networks only (postgres, qdrant, etc.)
- Never expose databases directly to internet

### Password Management
- All passwords generated and stored in `.credentials`
- Never commit `.credentials` to git (in .gitignore)
- Use strong, unique passwords per service

### User Permissions
- Database users have minimal required permissions
- LDAP users follow principle of least privilege
- Service accounts are separate from admin accounts

## Deployment Workflow

1. Edit configuration in local repo
2. Run `./build-datamancy-v3.main.kts` to build
3. Build creates `dist/` with:
   - `docker-compose.yml`
   - `configs/` (processed templates)
   - `stack.kotlin/` (JARs)
   - `stack.containers/` (Dockerfiles)
4. Deploy `dist/` to server
5. Run `docker compose up -d` on server
6. Monitor with `docker compose ps` and `docker compose logs`
