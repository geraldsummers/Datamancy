# Datamancy Deployment Guide

Self-hosted data platform with AI agents, vector search, and knowledge management.

---

## Prerequisites

- **Server Requirements**:
  - Docker and Docker Compose v2.0+
  - Minimum 16GB RAM (32GB+ recommended for AI services)
  - 100GB+ disk space
  - Linux host (tested on Debian/Ubuntu)

- **Development Machine** (for building):
  - JDK 17+
  - Kotlin (for build script)
  - Git

---

## Quick Deployment

### 1. Build Distribution

From the project root on your development machine:

```bash
# Build deployment-ready distribution
./build-datamancy.main.kts

# Optional flags:
# --clean        Clean dist/ before building
# --skip-gradle  Skip Gradle build (use existing JARs)
```

**What this does:**
- Compiles Kotlin services (control-panel, data-fetcher, unified-indexer, search-service)
- Generates Docker Compose files from `services.registry.yaml`
- Processes configuration templates
- Creates `.env.example` with all required variables
- Outputs everything to `dist/` directory

**Output structure:**
```
dist/
├── docker-compose.yml              # Master compose file
├── docker-compose.test-ports.yml   # Port exposure overlay for testing
├── compose/                        # Modular compose files
│   ├── core/                       # Networks, volumes, infrastructure
│   ├── databases/                  # Postgres, MariaDB, ClickHouse, Qdrant
│   ├── applications/               # Web apps, communication, files
│   └── datamancy/                  # Custom services + AI
├── configs/                        # Application configurations
├── services/                       # Built Kotlin JARs
├── scripts/                        # Runtime management scripts
├── .env.example                    # Environment template
└── .build-info                     # Build metadata
```

---

### 2. Package for Deployment

```bash
# Create versioned tarball
tar -czf datamancy-$(git rev-parse --short HEAD).tar.gz -C dist .
```

---

### 3. Deploy to Server

#### Transfer and Extract

```bash
# Copy to server
scp datamancy-*.tar.gz server:/opt/datamancy/

# SSH into server
ssh server

# Extract distribution
cd /opt/datamancy
tar -xzf datamancy-*.tar.gz
```

#### Configure Environment

```bash
# Create environment file from template
cp .env.example .env
vim .env  # or nano, your preferred editor
```

**Required configuration in `.env`:**

```bash
# Domain & Email
DOMAIN=yourdomain.com
MAIL_DOMAIN=yourdomain.com
STACK_ADMIN_EMAIL=admin@yourdomain.com
STACK_ADMIN_USER=admin

# Paths (adjust as needed)
VOLUMES_ROOT=/opt/datamancy/volumes
HOME=/opt/datamancy

# Secrets (GENERATE THESE - Do NOT use defaults!)
# Generate with: openssl rand -hex 32

LDAP_ADMIN_PASSWORD=<generate>
STACK_ADMIN_PASSWORD=<generate>
STACK_USER_PASSWORD=<generate>
AGENT_LDAP_OBSERVER_PASSWORD=<generate>

AUTHELIA_JWT_SECRET=<generate>
AUTHELIA_SESSION_SECRET=<generate>
AUTHELIA_STORAGE_ENCRYPTION_KEY=<generate>

POSTGRES_PASSWORD=<generate>
MARIADB_ROOT_PASSWORD=<generate>
MARIADB_PASSWORD=<generate>

LITELLM_MASTER_KEY=<generate>
QDRANT_API_KEY=<generate>

# API Configuration
API_LITELLM_ALLOWLIST=127.0.0.1,localhost,datamancy-api-gateway
```

**Generate all secrets:**
```bash
# Generate a single secret
openssl rand -hex 32

# Generate all required secrets at once
for var in LDAP_ADMIN_PASSWORD STACK_ADMIN_PASSWORD STACK_USER_PASSWORD \
           AGENT_LDAP_OBSERVER_PASSWORD AUTHELIA_JWT_SECRET AUTHELIA_SESSION_SECRET \
           AUTHELIA_STORAGE_ENCRYPTION_KEY POSTGRES_PASSWORD MARIADB_ROOT_PASSWORD \
           MARIADB_PASSWORD LITELLM_MASTER_KEY QDRANT_API_KEY; do
  echo "$var=$(openssl rand -hex 32)"
done
```

#### Create Volume Directories

```bash
# Create required volume directories with proper permissions
./scripts/create-volume-dirs.main.kts
```

---

### 4. Start Services

```bash
# Start all services
docker compose up -d

# View logs
docker compose logs -f

# Check service status
docker compose ps
```

---

## Service Architecture

Datamancy consists of 43 services across 5 categories:

### Core Infrastructure
- **Traefik**: Reverse proxy and SSL termination
- **Authelia**: Authentication and SSO
- **LDAP**: User directory
- **Docker Proxy**: Secure Docker API access
- **Benthos**: Data streaming

### Databases
- **Postgres**: Relational database for most services
- **MariaDB**: MySQL-compatible database
- **ClickHouse**: Analytics database
- **Qdrant**: Vector database for embeddings
- **Redis**: Cache and session store

### AI/ML Services
- **vLLM**: LLM inference server
- **LiteLLM**: Unified LLM API gateway
- **Embedding Service**: Text embedding generation
- **Agent Tool Server**: AI agent tools and functions

### Applications
- **Web**: Grafana, Open WebUI, Vaultwarden, Planka, BookStack, Homepage, JupyterHub, Forgejo, etc.
- **Communication**: Synapse (Matrix), Element, Mastodon, Roundcube
- **Files**: Seafile, OnlyOffice, Radicale

### Datamancy Custom Services
- **Control Panel**: Service management UI
- **Data Fetcher**: Data ingestion service
- **Unified Indexer**: Search index management
- **Search Service**: Unified search API

---

## Network Architecture

Services are isolated across 5 networks:

- **frontend** (172.20.0.0/24): Public-facing services behind Traefik
- **backend** (172.21.0.0/24): Internal application services
- **database** (172.22.0.0/24): Database services (restricted access)
- **ai** (172.23.0.0/24): ISOLATED - AI/LLM services
- **ai-gateway** (172.24.0.0/24): BRIDGE - Controlled AI ↔ backend communication

---

## Service Management

### Start/Stop Services

```bash
# Start all services
docker compose up -d

# Start specific service group
docker compose up -d postgres mariadb qdrant

# Stop all services
docker compose down

# Stop and remove volumes (CAUTION: destroys data)
docker compose down -v
```

### View Logs

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f datamancy-control-panel

# Last 100 lines
docker compose logs --tail=100 -f
```

### Restart Services

```bash
# Restart all
docker compose restart

# Restart specific service
docker compose restart datamancy-control-panel
```

### Update Services

```bash
# Rebuild and deploy updated services
./build-datamancy.main.kts
tar -czf datamancy-new.tar.gz -C dist .
# Transfer to server, extract, then:
docker compose up -d --build
```

---

## Testing Locally

### Test with Exposed Ports

Use the test overlay to expose service ports to localhost:

```bash
cd dist
cp .env.example .env
vim .env  # Configure for local testing

# Start with port exposure
docker compose -f docker-compose.yml -f docker-compose.test-ports.yml up -d
```

**Exposed ports** (see `docker-compose.test-ports.yml` for full list):
- Control Panel: `localhost:18097`
- Search Service: `localhost:18098`
- Postgres: `localhost:15432`
- Qdrant: `localhost:16333`
- Grafana: `localhost:13001`
- And many more...

---

## Troubleshooting

### Check Service Health

```bash
# View service status
docker compose ps

# Inspect specific service
docker inspect datamancy-control-panel

# Check health checks
docker compose ps | grep -i healthy
```

### Common Issues

**Services won't start:**
- Check `.env` file exists and has all required variables
- Verify volume directories exist: `ls -la $VOLUMES_ROOT`
- Check logs: `docker compose logs <service-name>`

**Port conflicts:**
- Use test overlay only for testing: `docker-compose.test-ports.yml`
- Check for services already using ports: `netstat -tlnp | grep <port>`

**Database connection errors:**
- Ensure database services are healthy: `docker compose ps postgres mariadb`
- Check network connectivity: `docker compose exec control-panel ping postgres`
- Verify credentials in `.env` match database initialization

**Out of memory:**
- Check resource limits in compose files under `deploy.resources`
- Monitor Docker memory: `docker stats`
- Consider disabling non-essential services

---

## Security Considerations

### Secrets Management
- **NEVER commit `.env` to version control**
- Generate unique secrets for each deployment
- Rotate secrets regularly
- Use `openssl rand -hex 32` for cryptographic secrets

### Network Isolation
- AI services are isolated on separate network
- Database network is restricted to backend services only
- Frontend network only accessible via Traefik

### SSL/TLS
- Configure Traefik with Let's Encrypt for production
- Use proper domain names, not IP addresses
- Ensure Authelia is properly configured for SSO

---

## Backup and Recovery

### Backup Volumes

```bash
# Stop services
docker compose down

# Backup volumes directory
tar -czf datamancy-volumes-$(date +%Y%m%d).tar.gz -C $VOLUMES_ROOT .

# Restart services
docker compose up -d
```

### Restore from Backup

```bash
# Stop services
docker compose down

# Restore volumes
tar -xzf datamancy-volumes-YYYYMMDD.tar.gz -C $VOLUMES_ROOT

# Restart services
docker compose up -d
```

---

## Development Workflow

### Build Kotlin Services

```bash
# Build all services
./gradlew build

# Build specific service
./gradlew :control-panel:build

# Run tests
./gradlew test

# Skip tests
./gradlew build -x test
```

### Rebuild Single Service

```bash
# Build specific service
./gradlew :control-panel:build

# Copy to dist
cp src/control-panel/build/libs/*.jar dist/services/

# Rebuild and restart
cd dist
docker compose up -d --build datamancy-control-panel
```

---

## Monitoring

### Service Status Dashboard

Access via **Control Panel**: `https://control-panel.yourdomain.com` (when configured)

### Grafana

Access metrics and logs: `https://grafana.yourdomain.com`

### Health Endpoints

Most Datamancy services expose health endpoints:
- Control Panel: `http://control-panel:8097/health`
- Search Service: `http://search-service:8098/health`
- Data Fetcher: `http://data-fetcher:8095/health`

---

## Updating

### Update Process

1. **Pull latest changes**:
   ```bash
   git pull origin master
   ```

2. **Review changes**:
   ```bash
   git log
   git diff HEAD~1
   ```

3. **Rebuild distribution**:
   ```bash
   ./build-datamancy.main.kts
   ```

4. **Package and deploy**:
   ```bash
   tar -czf datamancy-$(git rev-parse --short HEAD).tar.gz -C dist .
   # Transfer to server and extract
   ```

5. **Update services**:
   ```bash
   cd /opt/datamancy
   docker compose up -d --build
   ```

---

## Architecture Notes

### Single Source of Truth
- `services.registry.yaml` defines all 43 services
- Build script generates all compose files from this registry
- Image versions are hardcoded at build time
- Only secrets/paths use runtime `${VARIABLES}`

### Build System
- `build-datamancy.main.kts`: Kotlin build script
- Generates deployment-ready `dist/` directory
- No templates in output - production-ready compose files
- Configuration processed with runtime variable substitution

### Phased Startup
Services start in phases (defined in registry):
1. **Phase 0**: Core infrastructure (networks, volumes)
2. **Phase 1**: Databases and LDAP
3. **Phase 2**: Authentication and middleware
4. **Phase 3**: Applications and AI services
5. **Phase 4**: Datamancy custom services

---

## Support

- **Documentation**: See `README.md` for quick reference
- **Issues**: Check `services.registry.yaml` for service definitions
- **Logs**: Use `docker compose logs` for troubleshooting
- **Configuration**: Review `configs/` for app-specific settings

---

## License

See project root for license information.
