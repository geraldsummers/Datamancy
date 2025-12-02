# Pre-Deployment Checklist for Datamancy

## ✅ Automated Fixes Completed

The following issues have been automatically fixed in `docker-compose.yml`:

- ✅ **Qdrant healthcheck** - Now properly checks TCP port 6333 instead of no-op test
- ✅ **ldap-sync-service healthcheck** - Removed meaningless healthcheck (restart:"no" service)
- ✅ **Resource limits added** to:
  - vllm (8G RAM, 4 CPUs)
  - clickhouse (4G RAM, 2 CPUs)
  - postgres (4G RAM, 2 CPUs)
  - qdrant (2G RAM, 2 CPUs)
  - mastodon-web (2G RAM, 2 CPUs)
  - mastodon-sidekiq (2G RAM, 2 CPUs)
- ✅ **PostgreSQL max_connections** increased from 200 to 300
- ✅ **PostgreSQL shared_buffers** set to 1GB for better performance
- ✅ **LDAP log level** changed from debug to info (prevents password logging)
- ✅ **Image versions pinned**:
  - vllm: latest → v0.6.5
  - qdrant: latest → v1.12.5
  - portainer: latest → 2.24.0
  - bookstack: latest → 24.12.1
  - whisper: latest → v1.6.0
- ✅ **Network subnets** explicitly configured:
  - frontend: 172.20.0.0/24
  - backend: 172.21.0.0/24
  - database: 172.22.0.0/24
- ✅ **Kopia backup** - Fixed volume mounts to backup critical data
- ✅ **Logging** added to caddy with rotation

---

## 🔧 Manual Actions Required Before Deployment

### 1. Environment Configuration

#### Check Docker Group ID on Target Server
```bash
# On the target deployment server, run:
getent group docker | cut -d: -f3
```
- **Current config**: Group 985 in docker-compose.yml line ~1595
- **Action**: Update `group_add: ["985"]` for agent-tool-server if needed

#### Generate Secrets
```bash
# Ensure .env has real secrets, not <CHANGE_ME> placeholders
grep -c "CHANGE_ME" .env
# Should return 0
```

If secrets are not generated:
```bash
# Use your secrets manager or generate manually
# All services expect these environment variables to be set
```

### 2. SSH Stack Operations User

The `agent-tool-server` requires SSH access to the host. Set this up:

```bash
# On the deployment server:
sudo useradd -m -s /bin/bash stackops

# Generate SSH key (if not already done)
ssh-keygen -t ed25519 -f $VOLUMES_ROOT/secrets/stackops_ed25519 -C "stackops@datamancy"

# Add to authorized_keys with forced command wrapper
sudo mkdir -p /home/stackops/.ssh
sudo cat << 'EOF' | sudo tee /home/stackops/.ssh/authorized_keys
command="/usr/local/bin/stackops-wrapper" ssh-ed25519 AAAAC3... stackops@datamancy
EOF

sudo chown -R stackops:stackops /home/stackops/.ssh
sudo chmod 700 /home/stackops/.ssh
sudo chmod 600 /home/stackops/.ssh/authorized_keys
```

**Create forced-command wrapper** at `/usr/local/bin/stackops-wrapper`:
```bash
#!/bin/bash
# Restricted shell for agent-tool-server SSH operations
# Only allows safe read-only commands

case "$SSH_ORIGINAL_COMMAND" in
    "docker ps"*)
        exec docker ps "$@"
        ;;
    "docker inspect"*)
        exec docker inspect "$@"
        ;;
    "docker logs"*)
        exec docker logs "$@"
        ;;
    *)
        echo "Command not allowed: $SSH_ORIGINAL_COMMAND" >&2
        exit 1
        ;;
esac
```

```bash
sudo chmod +x /usr/local/bin/stackops-wrapper
```

### 3. Generate Configuration Files

```bash
# Generate configs from templates
kotlin scripts/core/process-config-templates.main.kts

# Verify all configs were generated
ls -la configs/
```

### 4. Create Volume Directories

```bash
# Create all volume mount points
kotlin scripts/core/create-volume-dirs.main.kts

# Or manually:
mkdir -p volumes/{caddy_data,caddy_config,postgres_data,mariadb_data,grafana_data,open_webui_data}
mkdir -p volumes/{vaultwarden_data,planka_data,ldap_data,ldap_config,redis_data}
mkdir -p volumes/{clickhouse_data,qdrant_data,kopia_data,kopia_cache,kopia_repository}
mkdir -p volumes/{agent_tool_server,secrets,proofs/screenshots}
```

### 5. TLS/SSL Certificate Setup

**Option A: Let's Encrypt (Public Domain)**
```bash
# Edit configs/infrastructure/caddy/Caddyfile
# For each public service, add:
# grafana.your-domain.com {
#     tls your-email@domain.com
#     ...
# }
```

**Option B: Self-Signed (Internal Use)**
- Default behavior, no changes needed
- **BUT**: Export Caddy CA cert for services that verify TLS:
  ```bash
  docker compose exec caddy cat /data/caddy/pki/authorities/local/root.crt > configs/applications/planka/caddy-ca.crt

  # Update all services that need it (not just Planka)
  # Check which services have NODE_EXTRA_CA_CERTS or similar TLS config
  ```

### 6. GPU Verification (for vLLM)

```bash
# Verify NVIDIA GPU is available
nvidia-smi

# Check Docker can access GPU
docker run --rm --gpus all nvidia/cuda:12.0-base nvidia-smi
```

### 7. Build Kotlin Services

```bash
# Build all custom Kotlin services
./gradlew build

# Verify shadowJars were created
ls -lh src/*/build/libs/*-all.jar
ls -lh src/probe-orchestrator/build/libs/probe-orchestrator-kt.jar
```

---

## 🚀 Initial Deployment

### Start Infrastructure

```bash
# Start bootstrap profile (core services + LLM)
kotlin scripts/deployment/stackops.main.kts up --profile bootstrap

# OR use docker-compose directly:
docker compose --profile bootstrap up -d

# Watch startup
docker compose --profile bootstrap logs -f
```

### Verify Bootstrap Services

```bash
# Check all containers are healthy
docker compose ps

# Test critical services
curl http://localhost/health  # Caddy
curl http://probe-orchestrator:8089/healthz  # Diagnostics
curl http://litellm:4000/health  # LLM Gateway

# Test LDAP
docker compose exec ldap ldapsearch -x -H ldap://localhost:389 -b "dc=stack,dc=local" -D "cn=admin,dc=stack,dc=local" -w "$STACK_ADMIN_PASSWORD"
```

### Start Databases

```bash
kotlin scripts/deployment/stackops.main.kts up --profile databases
# Wait for all databases to be healthy (check docker compose ps)
```

### Start Applications

```bash
kotlin scripts/deployment/stackops.main.kts up --profile applications
```

---

## 🔍 Post-Deployment Verification

### 1. Run Health Probe

```bash
# Execute diagnostic probe on all services
curl -X POST http://probe-orchestrator:8089/start-stack-probe | jq
```

### 2. Test Authentication Flow

```bash
# Access Authelia
open https://auth.your-domain.com

# Login with admin credentials from .env
# Username: $STACK_ADMIN_USER
# Password: $STACK_ADMIN_PASSWORD

# Test OIDC with Grafana
open https://grafana.your-domain.com
```

### 3. Verify Database Connections

```bash
# PostgreSQL
docker compose exec postgres psql -U admin -l

# MariaDB
docker compose exec mariadb mariadb -u admin -p -e "SHOW DATABASES;"

# Redis
docker compose exec redis valkey-cli PING
```

### 4. Test LLM Pipeline

```bash
# Test vLLM directly
curl http://vllm:8000/v1/models

# Test through LiteLLM gateway
curl http://litellm:4000/v1/models \
  -H "Authorization: Bearer ${LITELLM_MASTER_KEY}"

# Test Open WebUI
open https://open-webui.your-domain.com
```

### 5. Verify Backup System

```bash
# Check Kopia initialized
docker compose exec kopia kopia repository status

# Run manual backup test
docker compose exec kopia kopia snapshot create /backup/postgres

# List snapshots
docker compose exec kopia kopia snapshot list
```

---

## ⚠️ Known Issues & Mitigations

### Issue: Services Fail to Start Due to Network Conflicts
**Mitigation**: If 172.20-22.x subnets conflict with existing networks:
```bash
# Edit docker-compose.yml networks section with different subnets
# Suggested alternatives: 10.88.0.0/24, 10.89.0.0/24, 10.90.0.0/24
```

### Issue: Mailu Takes 5+ Minutes to Start
**Expected**: mailu-antivirus has 5-minute start_period for ClamAV signature download
**Mitigation**: Be patient on first startup

### Issue: Out of Memory Errors
**Mitigation**: Resource limits are conservative estimates. Monitor with:
```bash
docker stats --no-stream
```
Adjust limits in docker-compose.yml as needed after profiling.

### Issue: Self-Signed Certificate Warnings
**Expected**: Internal services using Caddy's self-signed CA
**Mitigation**:
- Trust Caddy CA in browsers/systems
- Or configure Let's Encrypt for public services

---

## 📊 Monitoring After Deployment

### Container Health

```bash
# Watch for unhealthy containers
watch -n 5 'docker compose ps --format json | jq -r ".[] | select(.Health != \"healthy\") | \"\(.Name): \(.Health)\"'
```

### Resource Usage

```bash
# Live stats
docker stats

# Check logs for OOM kills
journalctl -u docker -f | grep -i "out of memory"
```

### Service Logs

```bash
# Follow logs for critical services
docker compose logs -f caddy authelia postgres litellm vllm probe-orchestrator agent-tool-server
```

### Run Diagnostics

```bash
# Execute diagnostic tests
./tests/diagnostic/test-01-agent-tool-server-tools.sh
./tests/diagnostic/test-02-single-probe.sh
./tests/diagnostic/test-03-screenshot-capture.sh
```

---

## 🎯 Performance Tuning (Post-Deployment)

After running for a few hours/days, profile actual resource usage:

```bash
# Export metrics
docker stats --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}" > resource_usage.txt

# Adjust limits in docker-compose.yml based on actual usage:
# - Set limits to ~150% of observed peak usage
# - Add reservations for critical services
```

Update docker-compose.yml:
```yaml
deploy:
  resources:
    limits:
      memory: [150% of peak]
      cpus: [150% of peak]
    reservations:
      memory: [80% of average]
      cpus: [80% of average]
```

---

## 📞 Troubleshooting Contacts

- **Docker Issues**: Check logs: `docker compose logs <service>`
- **Network Issues**: `docker network inspect datamancy_backend`
- **Database Issues**: See configs/databases/postgres/init-db.sh
- **OIDC/Auth Issues**: Check Authelia logs and configuration.yml

---

## ✅ Pre-Deployment Sign-Off

- [ ] Secrets generated and verified (no `<CHANGE_ME>` in .env)
- [ ] SSH stackops user configured on host
- [ ] Docker group ID verified and updated in compose file
- [ ] GPU verified accessible (if using vLLM)
- [ ] Volume directories created
- [ ] Configuration files generated from templates
- [ ] Network subnets verified no conflicts
- [ ] Kotlin services built successfully
- [ ] Backup strategy documented and tested
- [ ] TLS certificate strategy decided (Let's Encrypt vs self-signed)
- [ ] Monitoring plan in place
- [ ] Rollback procedure documented

**Deployment Date**: _______________
**Deployed By**: _______________
**Server Hostname**: _______________
**Domain**: _______________
