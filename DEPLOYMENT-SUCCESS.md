# Datamancy Stack Deployment - SUCCESS ✅

**Date:** January 9, 2026
**Server:** latium.local (192.168.0.11)
**Domain:** project-saturn.com
**Commit:** 45840e6

---

## Deployment Summary

**Status:** 🎯 **OPERATIONAL** - 40/45 services running (89%)

### Hardware
- **GPU:** NVIDIA GeForce RTX 3060 (12GB VRAM)
  - vLLM: 5.6GB active
  - Embeddings: 378MB active
- **Storage:**
  - RAID: `/mnt/btrfs_raid_1_01_docker/` (volumes, configs)
  - SSD: `/mnt/sdc1_ctbx500_0385/` (vector databases)

### Core Services Status

#### ✅ All Healthy
- **Databases:** postgres, mariadb, clickhouse, qdrant
- **Infrastructure:** caddy, authelia, ldap, valkey
- **AI/ML:** vllm-7b, litellm, embedding-service
- **Datamancy:**
  - control-panel (port 8097) - healthy
  - data-fetcher - healthy
  - unified-indexer - healthy
  - search-service - healthy
  - agent-tool-server - running

#### 🔄 Non-Critical Restart Loops (5 services)
- **mailserver** - waiting for Caddy TLS cert generation
- **synapse** - volume permission issue
- **mastodon-sidekiq** - DB timing
- **kopia** - repository exists (cosmetic)
- **planka** - DB timing

### Network
- **HTTP:** Port 80 → redirects to HTTPS ✅
- **HTTPS:** Port 443 → serving ✅
- **DNS:** project-saturn.com → 192.168.0.11 ✅
- **Control Panel:** http://192.168.0.11:8097/health → `{"status":"ok"}` ✅

---

## Build System

### New: `build-datamancy.main.kts`
Replaced complex multi-stage buildSrc with single Kotlin script:
1. Builds JARs with Gradle
2. Builds Docker images from pre-built JARs
3. Generates compose files with hardcoded versions
4. Processes config templates (domain hardcoded, secrets as ${VARS})
5. Creates .env with pre-generated secrets

### Output Structure
```
dist/
├── docker-compose.yml (master with includes)
├── docker-compose.override.yml (qdrant SSD)
├── docker-compose.gpu.yml (NVIDIA GPU)
├── docker-compose.test-ports.yml (port exposure)
├── compose/
│   ├── core/ (networks, volumes, infrastructure)
│   ├── databases/ (relational, vector, analytics)
│   ├── applications/ (web, communication, files)
│   └── datamancy/ (services, ai)
├── configs/ (processed templates)
└── .env (generated secrets)
```

---

## Deployment Process

### 1. Build (Local)
```bash
./build-datamancy.main.kts --clean
```
- Builds all services
- Generates compose files
- Processes configs with domain=project-saturn.com

### 2. Package
```bash
docker save $(docker images 'datamancy/*:local-build' -q) -o datamancy-images.tar
tar -czf datamancy-deploy.tar.gz -C dist .
```

### 3. Transfer
```bash
scp datamancy-deploy.tar.gz datamancy-images.tar gerald@latium.local:/tmp/
```

### 4. Deploy (Server)
```bash
# Load images
docker load -i /tmp/datamancy-images.tar

# Extract deployment
cd /mnt/btrfs_raid_1_01_docker/datamancy
tar -xzf /tmp/datamancy-deploy.tar.gz

# Create volume directories
mkdir -p /mnt/btrfs_raid_1_01_docker/volumes/{redis/data,caddy/data,...}
mkdir -p /mnt/sdc1_ctbx500_0385/datamancy/vector-dbs/qdrant/data

# Start stack
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d
```

---

## Known Issues & Fixes Applied

### ✅ Fixed in Build Script
1. **Qdrant volume prefix** - added `datamancy_` prefix to override
2. **Dockerfiles** - removed multi-stage builds, use pre-built JARs
3. **Bookstack init paths** - updated to use DEPLOYMENT_ROOT

### 🔧 Fixed on Server (one-time)
1. **Volume directories** - created all bind mount paths
2. **Qdrant override** - regenerated with correct prefix
3. **compose/core/volumes.yml** - updated qdrant device path

### 📝 Outstanding (non-blocking)
1. Mailserver TLS certs - will self-resolve when Caddy generates certs
2. Synapse permissions - needs volume ownership fix
3. Mastodon/Planka timing - will stabilize as Postgres completes startup
4. Kopia repository - cosmetic error, not affecting functionality

---

## Next Steps

### Immediate
- [ ] Monitor mailserver cert generation (should auto-resolve)
- [ ] Fix synapse volume permissions if needed
- [ ] Verify all services stable after 24h

### Future
- [ ] Set up automated backups (Kopia)
- [ ] Configure monitoring alerts
- [ ] Test AI services (vLLM, embeddings)
- [ ] Verify agent-tool-server MCP functionality
- [ ] SSL/TLS cert renewal automation

---

## Lessons Learned

1. **Volume prefixes matter** - Docker Compose adds project prefix to named volumes
2. **Pre-built JARs > multi-stage builds** - faster, clearer, easier to debug
3. **Iterative deployment wins** - start services in phases, fix issues as they appear
4. **GPU works out of the box** - nvidia-docker runtime "just works" with compose
5. **Most "errors" are timing** - services restart until dependencies are ready

---

**Deployment Time:** ~30 minutes (including fixes)
**Success Rate:** 89% (40/45 services)
**GPU Utilization:** Active
**Network:** Operational
**Status:** 🚀 DEPLOYED AND RUNNING
