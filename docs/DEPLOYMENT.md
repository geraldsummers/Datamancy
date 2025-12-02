# Deployment Guide

Complete guide for deploying Datamancy in development and production environments.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Environment Configuration](#environment-configuration)
- [Development Deployment](#development-deployment)
- [Production Deployment](#production-deployment)
- [Service Profiles](#service-profiles)
- [Startup Sequence](#startup-sequence)
- [Health Verification](#health-verification)
- [Backup & Restore](#backup--restore)
- [Troubleshooting](#troubleshooting)
- [Monitoring](#monitoring)

## Prerequisites

### System Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| **CPU** | 4 cores | 8+ cores |
| **RAM** | 8 GB | 16-32 GB |
| **Storage** | 50 GB SSD | 500 GB NVMe |
| **GPU** | NVIDIA 12GB VRAM | NVIDIA 24GB VRAM |
| **OS** | Linux (Ubuntu 22.04+) | Ubuntu 24.04 LTS |
| **Network** | 100 Mbps | 1 Gbps |

### Software Prerequisites

```bash
# Docker Engine
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-plugin

# Verify Docker version
docker --version  # Should be 24.0+
docker compose version  # Should be v2.20+

# NVIDIA Container Toolkit (for GPU support)
distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
curl -s -L https://nvidia.github.io/nvidia-docker/gpgkey | sudo apt-key add -
curl -s -L https://nvidia.github.io/nvidia-docker/$distribution/nvidia-docker.list | \
  sudo tee /etc/apt/sources.list.d/nvidia-docker.list

sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit
sudo systemctl restart docker

# Verify GPU access
docker run --rm --gpus all nvidia/cuda:12.0.0-base-ubuntu22.04 nvidia-smi
```

### Network Setup

**Domain Configuration:**

For local development:
```bash
# Add to /etc/hosts
127.0.0.1 stack.local
127.0.0.1 auth.stack.local
127.0.0.1 open-webui.stack.local
127.0.0.1 grafana.stack.local
127.0.0.1 portainer.stack.local
127.0.0.1 agent-tool-server.stack.local
```

For production, configure DNS:
```
A     @                   -> SERVER_IP
A     *                   -> SERVER_IP
```

Or specific subdomains:
```
A     auth.your-domain.com      -> SERVER_IP
A     open-webui.your-domain.com -> SERVER_IP
A     grafana.your-domain.com    -> SERVER_IP
...
```

## Environment Configuration

### 1. Clone Repository

```bash
git clone <repository-url>
cd Datamancy
```

### 2. Create Environment File

```bash
cp .env.example .env
```

### 3. Configure .env

**Essential Variables:**

```bash
# Domain (use stack.local for dev, your-domain.com for prod)
DOMAIN=your-domain.com
MAIL_DOMAIN=your-domain.com

# Admin Credentials (CHANGE THESE!)
STACK_ADMIN_USER=admin
STACK_ADMIN_PASSWORD=$(openssl rand -base64 32)
STACK_ADMIN_EMAIL=admin@your-domain.com

# Volumes Location
VOLUMES_ROOT=./volumes

# LLM Configuration
LITELLM_MASTER_KEY=$(openssl rand -hex 32)
HUGGINGFACEHUB_API_TOKEN=<your-huggingface-token>

# LLM Model Selection
LLM_MODEL=hermes-2-pro-mistral-7b
OCR_MODEL=none  # Set to vision model if available

# Docker Socket (usually /var/run/docker.sock)
DOCKER_SOCKET=/var/run/docker.sock
```

**Security Secrets (Generate with openssl):**

```bash
# Generate all secrets at once
cat >> .env << 'EOF'
# Authelia Secrets
AUTHELIA_JWT_SECRET=$(openssl rand -hex 32)
AUTHELIA_SESSION_SECRET=$(openssl rand -hex 32)
AUTHELIA_STORAGE_ENCRYPTION_KEY=$(openssl rand -hex 32)
AUTHELIA_IDENTITY_PROVIDERS_OIDC_HMAC_SECRET=$(openssl rand -hex 32)

# Application OAuth Secrets
GRAFANA_OAUTH_SECRET=$(openssl rand -hex 16)
VAULTWARDEN_OAUTH_SECRET=$(openssl rand -hex 16)
VAULTWARDEN_ADMIN_TOKEN=$(openssl rand -hex 32)
PLANKA_OAUTH_SECRET=$(openssl rand -hex 16)
PLANKA_SECRET_KEY=$(openssl rand -hex 32)
JUPYTERHUB_OAUTH_SECRET=$(openssl rand -hex 16)
ONLYOFFICE_JWT_SECRET=$(openssl rand -hex 32)

# Email Configuration (Mailu/Vaultwarden)
VAULTWARDEN_SMTP_PASSWORD=$(openssl rand -hex 16)

# Database Passwords
MARIADB_SEAFILE_ROOT_PASSWORD=$(openssl rand -hex 16)
PLANKA_DB_PASSWORD=$(openssl rand -hex 16)
BOOKSTACK_DB_PASSWORD=$(openssl rand -hex 16)
SYNAPSE_DB_PASSWORD=$(openssl rand -hex 16)
MAILU_DB_PASSWORD=$(openssl rand -hex 16)
GRAFANA_DB_PASSWORD=$(openssl rand -hex 16)
VAULTWARDEN_DB_PASSWORD=$(openssl rand -hex 16)
OPENWEBUI_DB_PASSWORD=$(openssl rand -hex 16)
EOF
```

**Expand Environment Variables:**

```bash
# This expands $(openssl ...) commands
source .env
env | grep -E "(SECRET|PASSWORD|TOKEN)" > .env.expanded
mv .env.expanded .env
```

### 4. Create Volume Directories

```bash
# Create all volume mount points
mkdir -p volumes/{
  caddy_data,caddy_config,
  postgres_data,mariadb_data,redis_data,
  qdrant_data,clickhouse_data,
  grafana_data,open_webui_data,
  vaultwarden_data,planka_data,outline_data,
  jupyterhub_data,seafile_data,
  authelia,proofs/screenshots,
  secrets,embeddings/models,
  vllm/hf-cache,whisper/models,piper/voices
}

# Set permissions
sudo chown -R $(id -u):$(id -g) volumes/
chmod -R 755 volumes/
```

### 5. Generate OIDC Keys

```bash
# Generate RSA key pair for OIDC
mkdir -p .secrets
openssl genrsa -out .secrets/oidc-private.pem 4096
openssl rsa -in .secrets/oidc-private.pem -pubout -out .secrets/oidc-public.pem

# Set permissions
chmod 600 .secrets/oidc-private.pem
chmod 644 .secrets/oidc-public.pem
```

### 6. Generate SSH Key for Operations

```bash
# For agent-tool-server SSH operations
mkdir -p volumes/secrets
ssh-keygen -t ed25519 -f volumes/secrets/stackops_ed25519 -N "" -C "stackops@datamancy"

# Add public key to authorized_keys on host if using host SSH
# cat volumes/secrets/stackops_ed25519.pub >> ~/.ssh/authorized_keys

# Note: SSH host keys are automatically discovered and cached by the
# ssh-key-bootstrap init container, which runs before agent-tool-server starts.
# This enables strict host key checking (MITM protection).
```

### 7. Generate Configuration Files (**REQUIRED**)

⚠️ **CRITICAL**: The `configs/` directory is **generated from templates** and does NOT exist in git. You **MUST** run this step before starting services!

Datamancy uses a template system to support multiple environments without hardcoding values.

**Generate configs from templates:**
```bash
# Generate configs/ from configs.templates/ using .env values
kotlin scripts/process-config-templates.main.kts

# Verify generation succeeded
ls -la configs/
ls configs/infrastructure/caddy/Caddyfile
ls configs/applications/authelia/configuration.yml
```

**What this does:**
- Reads all files in `configs.templates/`
- Replaces `{{DOMAIN}}` with your actual domain from `.env`
- Replaces `{{STACK_ADMIN_EMAIL}}`, `{{VOLUMES_ROOT}}`, etc.
- Writes processed files to `configs/`
- Processes 28 template files, copies 24 additional files

**Preview before generating:**
```bash
# Dry-run mode (shows what would be generated)
kotlin scripts/process-config-templates.main.kts --dry-run --verbose
```

**Regenerate after changing .env:**
```bash
# Force overwrite existing configs/
kotlin scripts/process-config-templates.main.kts --force
```

**See full documentation:**
- [TEMPLATE_CONFIG.md](../TEMPLATE_CONFIG.md) - Complete reference
- [QUICKSTART_TEMPLATES.md](../QUICKSTART_TEMPLATES.md) - Step-by-step guide

## Development Deployment

### Quick Start (Bootstrap Profile)

```bash
# Build images
docker compose build

# Start core services
docker compose --profile bootstrap up -d

# Wait for services to be healthy
docker compose ps

# Check logs
docker compose logs -f
```

**Bootstrap profile includes:**
- Caddy, Authelia, OpenLDAP, Valkey, PostgreSQL
- Portainer, Portainer Agent
- vLLM, LiteLLM, vLLM Router, Embedding Service
- agent-tool-server, Probe Orchestrator, Playwright
- Open WebUI (PostgreSQL backend)
- SSH key bootstrap (init container)

### Access Services

```bash
# Open WebUI (LLM Chat)
open https://open-webui.stack.local

# Portainer (Docker Management)
open https://portainer.stack.local

# Grafana (Dashboards) - Requires applications profile
open https://grafana.stack.local

# Authelia (SSO)
open https://auth.stack.local
```

**Default Login:**
```
Username: admin
Password: <value from STACK_ADMIN_PASSWORD>
```

### Add More Services

```bash
# Start databases
docker compose --profile databases up -d

# Start all applications
docker compose --profile applications up -d

# Start vector databases and pipeline
docker compose --profile bootstrap_vector_dbs up -d

# Start everything
docker compose --profile bootstrap --profile databases --profile applications up -d
```

## Production Deployment

### Pre-Deployment Checklist

- [ ] DNS records configured
- [ ] Firewall rules set (ports 80, 443, 25, 587, 993, etc.)
- [ ] SSL certificate strategy decided (Let's Encrypt vs self-signed)
- [ ] Backup strategy planned
- [ ] Monitoring configured
- [ ] Strong passwords generated for all secrets
- [ ] `.env` file secured (chmod 600)
- [ ] Volume backups tested

### 1. Configure SSL/TLS

**Option A: Let's Encrypt (Recommended for Production)**

Edit `configs/infrastructure/caddy/Caddyfile`:

```caddyfile
{
    email admin@your-domain.com
}

https://open-webui.{$DOMAIN} {
    tls admin@your-domain.com  # Enables Let's Encrypt
    reverse_proxy open-webui:8080
}

https://grafana.{$DOMAIN} {
    tls admin@your-domain.com
    forward_auth authelia:9091 {
        uri /api/verify?rd=https://auth.{$DOMAIN}
        copy_headers Remote-User Remote-Groups Remote-Name Remote-Email
    }
    reverse_proxy grafana:3000
}
```

**Option B: Self-Signed (Development/Internal)**

Default configuration uses Caddy's internal CA (already configured).

### 2. Secure Environment

```bash
# Restrict .env permissions
chmod 600 .env

# Don't commit secrets
echo ".env" >> .gitignore
echo "volumes/" >> .gitignore
echo ".secrets/" >> .gitignore

# Disable SSH password auth (use keys only)
sudo sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo systemctl restart sshd
```

### 3. Configure Firewall

```bash
# UFW (Ubuntu)
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow ssh
sudo ufw allow 80/tcp    # HTTP
sudo ufw allow 443/tcp   # HTTPS
sudo ufw allow 25/tcp    # SMTP
sudo ufw allow 587/tcp   # SMTP Submission
sudo ufw allow 993/tcp   # IMAPS
sudo ufw enable

# Verify
sudo ufw status verbose
```

### 4. Deploy Services

```bash
# Pull latest images (if using pre-built)
docker compose pull

# Build local services
docker compose build

# Start in detached mode
docker compose --profile bootstrap --profile databases --profile applications up -d

# Monitor startup
docker compose logs -f
```

### 5. Initialize Services

**Create Portainer Admin:**
```bash
# First time only - Portainer will prompt for admin password creation
open https://portainer.your-domain.com
```

**Bootstrap LDAP Users:**
```bash
# Edit bootstrap_ldap.ldif if needed
# Service automatically loads on first start

# Access LDAP Account Manager
open https://lam.your-domain.com

# Login with:
# Password: ${STACK_ADMIN_PASSWORD}
```

**Configure Mailu (Email):**
```bash
# Access admin interface
open https://mail.your-domain.com/admin

# Login with admin@${MAIL_DOMAIN} / ${STACK_ADMIN_PASSWORD}

# Create email accounts for:
# - vaultwarden@${MAIL_DOMAIN}
# - Any user accounts
```

## Service Profiles

Docker Compose profiles for modular deployment:

| Profile | Services | Purpose | Dependencies |
|---------|----------|---------|--------------|
| `bootstrap` | Core + AI + Diagnostics | Minimal working stack | None |
| `databases` | PostgreSQL, MariaDB, Valkey, etc. | Data layer | None |
| `applications` | Grafana, Planka, BookStack, etc. | User apps | `databases` |
| `infrastructure` | Benthos, Dockge, Kopia, etc. | Ops tools | `bootstrap` |
| `bootstrap_vector_dbs` | Qdrant, ClickHouse, Benthos | RAG/Analytics | `bootstrap` |

**Examples:**

```bash
# Minimal AI stack
docker compose --profile bootstrap up -d

# Add databases
docker compose --profile bootstrap --profile databases up -d

# Full stack
docker compose \
  --profile bootstrap \
  --profile databases \
  --profile applications \
  --profile infrastructure \
  up -d
```

## Startup Sequence

Services start in dependency order:

```
Phase 1: Infrastructure Foundation
  1. OpenLDAP (directory), PostgreSQL (database)
  2. Valkey (cache)
  3. Authelia (waits for LDAP + Valkey + PostgreSQL)
  4. Caddy (waits for Authelia)
  5. SSH Key Bootstrap (one-time init - scans SSH host keys)

Phase 2: Additional Databases (optional)
  6. MariaDB
  7. Valkey (Synapse), Valkey (Mailu)
  8. CouchDB
  9. ClickHouse, Qdrant (vector DBs)

Phase 3: AI Services
  10. vLLM (GPU model serving)
  11. Embedding Service
  12. vLLM Router (waits for vLLM)
  13. LiteLLM (waits for vLLM Router, Embedding)

Phase 4: Diagnostics
  14. Playwright (browser automation)
  15. agent-tool-server (waits for Playwright + SSH bootstrap)
  16. Probe Orchestrator (waits for agent-tool-server, LiteLLM)

Phase 5: Applications
  17. Open WebUI (waits for LiteLLM + PostgreSQL)
  18. Portainer, Dockge
  19. Grafana, Vaultwarden, Planka, BookStack (all use PostgreSQL)
  20. Mailu stack, Seafile, Synapse, Mastodon, etc.
```

**Typical startup time:** 5-10 minutes (longer on first run due to image downloads)

## Health Verification

### Check All Services

```bash
# Show running containers
docker compose ps

# Check health status
docker compose ps --format json | jq -r '.[] | select(.Health != "healthy") | .Service'

# Run diagnostic probe
curl -X POST http://localhost:8089/start-stack-probe | jq
```

### Individual Service Health

```bash
# Core Services
curl http://localhost:9091/api/health  # Authelia
curl http://localhost:8081/tools       # agent-tool-server
curl http://localhost:8089/healthz     # Probe Orchestrator
curl http://localhost:4000/health      # LiteLLM
curl http://localhost:8000/health      # vLLM

# Databases
docker exec postgres pg_isready
docker exec postgres psql -U admin -d postgres -c '\l'  # List all databases
docker exec mariadb mariadb-admin ping
docker exec redis valkey-cli ping

# Applications
curl http://localhost:3000/api/health  # Grafana
curl http://localhost:8080/health      # Open WebUI
curl http://localhost:9000/api/system/status  # Portainer
```

### Automated Health Check

```bash
#!/bin/bash
# health-check.sh

SERVICES=(
  "authelia:9091/api/health"
  "agent-tool-server:8081/tools"
  "probe-orchestrator:8089/healthz"
  "litellm:4000/health"
  "open-webui:8080/health"
)

for service in "${SERVICES[@]}"; do
  name="${service%:*}"
  endpoint="${service#*:}"
  if docker exec "$name" wget -q -O- "http://localhost:$endpoint" &>/dev/null; then
    echo "✓ $name is healthy"
  else
    echo "✗ $name is unhealthy"
  fi
done
```

## Backup & Restore

### Backup Strategy

**1. Volume Backup (Recommended)**

```bash
#!/bin/bash
# backup-volumes.sh

BACKUP_DIR="/backup/datamancy-$(date +%Y%m%d)"
mkdir -p "$BACKUP_DIR"

# Stop services gracefully
docker compose stop

# Tar volumes
tar -czf "$BACKUP_DIR/volumes.tar.gz" volumes/

# Backup environment (without secrets)
grep -v -E "(PASSWORD|SECRET|TOKEN|KEY)" .env > "$BACKUP_DIR/env.template"

# Restart services
docker compose start

echo "Backup saved to $BACKUP_DIR"
```

**2. Database Dumps**

```bash
# PostgreSQL (primary database - serves 10 databases)
docker exec postgres pg_dumpall -U ${STACK_ADMIN_USER} | gzip > backup/postgres-$(date +%Y%m%d).sql.gz

# Individual database dumps (optional)
docker exec postgres pg_dump -U admin grafana | gzip > backup/grafana-$(date +%Y%m%d).sql.gz
docker exec postgres pg_dump -U admin vaultwarden | gzip > backup/vaultwarden-$(date +%Y%m%d).sql.gz
docker exec postgres pg_dump -U admin openwebui | gzip > backup/openwebui-$(date +%Y%m%d).sql.gz

# MariaDB
docker exec mariadb mariadb-dump -u root -p${STACK_ADMIN_PASSWORD} --all-databases | gzip > backup/mariadb-$(date +%Y%m%d).sql.gz

# Valkey
docker exec redis valkey-cli SAVE
cp volumes/redis_data/dump.rdb backup/valkey-$(date +%Y%m%d).rdb
```

**3. Kopia (Built-in Backup Service)**

Kopia service is configured in the `infrastructure` profile:

```bash
# Enable Kopia
docker compose --profile infrastructure up -d kopia

# Access Kopia UI
open https://kopia.your-domain.com
```

### Restore

**1. Restore Volumes**

```bash
# Stop services
docker compose down

# Restore volumes
tar -xzf backup/volumes.tar.gz

# Restore environment
cp backup/env.template .env
# Manually add secrets back

# Start services
docker compose --profile bootstrap up -d
```

**2. Restore Databases**

```bash
# PostgreSQL
gunzip < backup/postgres-20250101.sql.gz | docker exec -i postgres psql -U ${STACK_ADMIN_USER}

# MariaDB
gunzip < backup/mariadb-20250101.sql.gz | docker exec -i mariadb mariadb -u root -p${STACK_ADMIN_PASSWORD}
```

## Troubleshooting

### Service Won't Start

```bash
# Check logs
docker compose logs <service-name>

# Inspect container
docker inspect <container-name>

# Check dependencies
docker compose config --services

# Restart service
docker compose restart <service-name>

# Rebuild and restart
docker compose up -d --build --force-recreate <service-name>
```

### OIDC/SSO Issues

```bash
# Check Authelia logs
docker logs authelia | tail -100

# Test LDAP connection
docker exec authelia ldapsearch -x -H ldap://ldap:389 \
  -D "cn=admin,dc=stack,dc=local" \
  -w "${STACK_ADMIN_PASSWORD}" \
  -b "dc=stack,dc=local"

# Verify Valkey session store
docker exec redis valkey-cli KEYS 'authelia:*'

# Clear sessions
docker exec redis valkey-cli FLUSHALL
```

### GPU/vLLM Not Working

```bash
# Check GPU availability
nvidia-smi

# Test GPU in Docker
docker run --rm --gpus all nvidia/cuda:12.0.0-base-ubuntu22.04 nvidia-smi

# Check vLLM logs
docker logs vllm

# Reduce memory if OOM
# Edit docker-compose.yml:
#   command: ["--gpu-memory-utilization", "0.60", ...]
```

### Database Connection Errors

```bash
# Check network
docker network inspect datamancy_database

# Verify database is running
docker exec postgres pg_isready

# List PostgreSQL databases
docker exec postgres psql -U admin -d postgres -c '\l'

# Test from application container
docker exec grafana nc -zv postgres 5432

# Check credentials
docker exec -it postgres psql -U ${STACK_ADMIN_USER} -d postgres

# Verify specific application database access
docker exec -it postgres psql -U grafana -d grafana
docker exec -it postgres psql -U vaultwarden -d vaultwarden
docker exec -it postgres psql -U openwebui -d openwebui
```

### SSH Host Key Issues

```bash
# Symptom: agent-tool-server can't connect via SSH, "Host key verification failed"
# Cause: SSH host keys changed (e.g., after OS reinstall)

# Solution 1: Refresh known_hosts via API
curl -X POST http://localhost:8081/admin/refresh-ssh-keys

# Solution 2: Manually refresh
docker exec ssh-key-bootstrap sh /bootstrap_known_hosts.sh /app/known_hosts
docker compose restart agent-tool-server

# Verify known_hosts
docker exec agent-tool-server cat /app/known_hosts
```

## Monitoring

### Logs

```bash
# All services
docker compose logs -f

# Specific service
docker logs -f --tail 100 probe-orchestrator

# Grep logs
docker compose logs | grep ERROR

# Export logs
docker compose logs --no-color > datamancy-logs-$(date +%Y%m%d).log
```

### Resource Usage

```bash
# Container stats
docker stats

# Disk usage
docker system df
du -sh volumes/*

# GPU usage (real-time)
watch -n 1 nvidia-smi
```

### Grafana Dashboards

Access Grafana at `https://grafana.your-domain.com` and create dashboards for:

- Docker container metrics
- PostgreSQL query performance
- vLLM inference latency
- Probe orchestrator success rate
- System resources (CPU, RAM, disk, GPU)

---

**Next**: See [API.md](API.md) for service API documentation.
