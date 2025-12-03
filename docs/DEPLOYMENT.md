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

### Overview: Runtime Config Architecture

Datamancy uses a **runtime config directory** at `~/.config/datamancy/` to store all generated configurations and secrets **outside the git repository**. This ensures:

- ✅ Secrets never accidentally committed
- ✅ Each developer/server has isolated configuration
- ✅ No encryption complexity (SOPS/Age removed)
- ✅ Clean separation between templates (in git) and runtime configs (outside git)

### 1. Clone Repository

```bash
git clone <repository-url>
cd Datamancy
```

### 2. Generate Runtime Configuration

**All configuration is generated to `~/.config/datamancy/` automatically:**

```bash
# Generate secrets (random passwords, keys, etc.)
./stack-controller.main.kts config generate
# Creates: ~/.config/datamancy/.env.runtime

# Generate LDAP bootstrap with SSHA password hashes
./stack-controller.main.kts ldap bootstrap
# Creates: ~/.config/datamancy/bootstrap_ldap.ldif

# Process templates with your environment values
./stack-controller.main.kts config process
# Creates: ~/.config/datamancy/configs/
```

### 3. Customize Configuration (Optional)

**The `config generate` command creates ALL secrets automatically. Only customize if needed:**

```bash
# View generated secrets
cat ~/.config/datamancy/.env.runtime | head -20

# Optional: Edit DOMAIN or other non-secret values
nano ~/.config/datamancy/.env.runtime
```

**Key Variables** (most are auto-generated):

| Variable | Auto-Generated | Description |
|----------|----------------|-------------|
| `DOMAIN` | ✅ (stack.local) | Change for production |
| `MAIL_DOMAIN` | ✅ (stack.local) | Change for production |
| `STACK_ADMIN_PASSWORD` | ✅ | Keep safe! |
| `AUTHELIA_JWT_SECRET` | ✅ | Auto-generated 64-char hex |
| `LITELLM_MASTER_KEY` | ✅ | Auto-generated 64-char hex |
| `HUGGINGFACEHUB_API_TOKEN` | ❌ | Set manually if needed |

**After editing, re-process templates:**

```bash
./stack-controller.main.kts config process
./stack-controller.main.kts ldap bootstrap
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

### 5. Verify Runtime Configuration

**All configuration is in `~/.config/datamancy/` - verify it exists:**

```bash
# Check runtime config directory
ls -la ~/.config/datamancy/

# Should show:
# .env.runtime              - Environment variables with secrets
# bootstrap_ldap.ldif       - LDAP bootstrap with SSHA hashes
# configs/                  - All processed configuration files

# Verify key files
ls ~/.config/datamancy/configs/infrastructure/caddy/Caddyfile
ls ~/.config/datamancy/configs/applications/authelia/configuration.yml
```

**What each command created:**
- `config generate` → `~/.config/datamancy/.env.runtime` (secrets)
- `ldap bootstrap` → `~/.config/datamancy/bootstrap_ldap.ldif` (LDAP users)
- `config process` → `~/.config/datamancy/configs/` (all service configs)

**Regenerate after editing .env.runtime:**
```bash
nano ~/.config/datamancy/.env.runtime  # Edit DOMAIN, etc
./stack-controller.main.kts config process
./stack-controller.main.kts ldap bootstrap
```

**See full documentation:**
- [STACK_CONTROLLER_GUIDE.md](STACK_CONTROLLER_GUIDE.md) - All commands
- [LDAP_BOOTSTRAP.md](LDAP_BOOTSTRAP.md) - LDAP password security

## Development Deployment

### Quick Start (Bootstrap Profile)

```bash
# Build Kotlin services
./gradlew build

# Build Docker images
docker compose build

# Start core services
./stack-controller.main.kts up --profile=bootstrap

# Check stack status
./stack-controller.main.kts status

# Follow logs
./stack-controller.main.kts logs caddy -f
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

- [ ] DNS records configured (A records for domain and subdomains)
- [ ] Firewall rules set (ports 80, 443, 25, 587, 993, etc.)
- [ ] SSL certificate strategy decided (Let's Encrypt vs self-signed)
- [ ] Backup strategy for `~/.config/datamancy/` directory
- [ ] Monitoring configured (Grafana dashboards)
- [ ] Runtime config secured (`chmod 700 ~/.config/datamancy`)
- [ ] Volume backup location decided (`./volumes/`)
- [ ] DOMAIN variable set correctly in `~/.config/datamancy/.env.runtime`

### 1. Configure Production Domain

**Edit runtime configuration:**

```bash
nano ~/.config/datamancy/.env.runtime
```

**Change these values:**
```bash
DOMAIN=your-domain.com
MAIL_DOMAIN=your-domain.com
STACK_ADMIN_EMAIL=admin@your-domain.com
```

**Regenerate configs with new domain:**
```bash
./stack-controller.main.kts config process
./stack-controller.main.kts ldap bootstrap
```

### 2. Configure SSL/TLS

**Option A: Let's Encrypt (Recommended for Production)**

Edit `~/.config/datamancy/configs/infrastructure/caddy/Caddyfile`:

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

### 3.1. Configure Fail2ban for Email Protection

**CRITICAL**: Email ports (25, 587, 993) are constantly attacked. Fail2ban blocks brute-force attempts.

**Install Fail2ban:**

```bash
# Install on host server
sudo apt-get update
sudo apt-get install -y fail2ban

# Create Mailu-specific filters
sudo mkdir -p /etc/fail2ban/filter.d
sudo mkdir -p /etc/fail2ban/jail.d
```

**Create Mailu filter** (`/etc/fail2ban/filter.d/mailu-postfix.conf`):

```ini
[Definition]
# Matches Postfix authentication failures in Mailu logs
failregex = ^.* warning: .*\[<HOST>\]: SASL .* authentication failed.*$
            ^.* lost connection after AUTH from .*\[<HOST>\].*$
            ^.* client=.*\[<HOST>\], sasl_method=.*, sasl_username=.*$

ignoreregex =
```

**Create Mailu filter** (`/etc/fail2ban/filter.d/mailu-dovecot.conf`):

```ini
[Definition]
# Matches Dovecot authentication failures in Mailu logs
failregex = ^.* auth-worker.* pam\(.*,<HOST>,.*\): pam_authenticate\(\) failed: .*$
            ^.* auth-worker.* pam\(.*,<HOST>\): unknown user$
            ^.* imap-login: Disconnected \(auth failed, .*\): user=<.*>, method=.*, rip=<HOST>.*$

ignoreregex =
```

**Configure Fail2ban jail** (`/etc/fail2ban/jail.d/mailu.conf`):

```ini
[mailu-postfix]
enabled = true
backend = systemd
filter = mailu-postfix
journalmatch = CONTAINER_NAME=mailu-smtp
maxretry = 5
findtime = 600
bantime = 3600
action = iptables-allports[name=mailu-smtp]

[mailu-dovecot]
enabled = true
backend = systemd
filter = mailu-dovecot
journalmatch = CONTAINER_NAME=mailu-imap
maxretry = 5
findtime = 600
bantime = 3600
action = iptables-allports[name=mailu-imap]

[mailu-admin]
enabled = true
backend = systemd
filter = mailu-admin
journalmatch = CONTAINER_NAME=mailu-admin
maxretry = 3
findtime = 600
bantime = 7200
action = iptables-allports[name=mailu-admin]
```

**Create admin filter** (`/etc/fail2ban/filter.d/mailu-admin.conf`):

```ini
[Definition]
# Matches admin UI login failures
failregex = ^.* Failed login attempt from <HOST>.*$
            ^.* Invalid credentials for .* from <HOST>.*$

ignoreregex =
```

**Enable and test:**

```bash
# Restart fail2ban
sudo systemctl restart fail2ban

# Check status
sudo fail2ban-client status

# Check Mailu jails
sudo fail2ban-client status mailu-postfix
sudo fail2ban-client status mailu-dovecot

# View banned IPs
sudo fail2ban-client banned

# Manually test ban (replace with test IP)
# sudo fail2ban-client set mailu-postfix banip 192.0.2.1

# Unban IP
# sudo fail2ban-client set mailu-postfix unbanip 192.0.2.1
```

**Monitor logs:**

```bash
# Watch fail2ban activity
sudo tail -f /var/log/fail2ban.log

# Check Mailu logs for auth failures
docker logs mailu-smtp --tail 100 | grep -i "authentication failed"
docker logs mailu-imap --tail 100 | grep -i "auth failed"
```

**Whitelist trusted IPs** (optional):

Add to `/etc/fail2ban/jail.d/mailu.conf`:

```ini
[DEFAULT]
ignoreip = 127.0.0.1/8 ::1
           10.0.0.0/8       # Internal network
           YOUR_IP_HERE     # Your static IP
```

**Note**: Fail2ban requires Docker containers to use `journald` logging driver. Verify with:

```bash
docker inspect mailu-smtp | grep LoggingDriver
# Should show: "LoggingDriver": "journald" or "json-file"
```

If using `json-file` driver (default), use `backend = docker` instead of `systemd` and adjust paths.

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

**Critical: Backup Runtime Config Directory**

All secrets and configurations are in `~/.config/datamancy/` - **this MUST be backed up!**

**1. Runtime Config Backup (CRITICAL)**

```bash
#!/bin/bash
# backup-runtime-config.sh

BACKUP_DIR="/backup/datamancy-$(date +%Y%m%d)"
mkdir -p "$BACKUP_DIR"

# Backup runtime config (includes secrets!)
tar -czf "$BACKUP_DIR/runtime-config.tar.gz" ~/.config/datamancy/
chmod 600 "$BACKUP_DIR/runtime-config.tar.gz"  # Secure it!

echo "✓ Runtime config backed up (includes secrets)"
```

**2. Volume Backup (Data)**

```bash
#!/bin/bash
# backup-volumes.sh

BACKUP_DIR="/backup/datamancy-$(date +%Y%m%d)"
mkdir -p "$BACKUP_DIR"

# Stop services gracefully
docker compose stop

# Tar volumes
tar -czf "$BACKUP_DIR/volumes.tar.gz" volumes/

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
