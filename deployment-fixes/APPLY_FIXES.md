# Datamancy Deployment Fixes - Application Guide

## Overview
This directory contains fixes for 21 issues found in the fresh deployment. Apply in order.

---

## 🔴 CRITICAL FIXES (Apply First)

### 1. Wikipedia Download Retry Logic ✅ ALREADY APPLIED
**Status**: Fixed in local repo
**File**: `kotlin.src/pipeline/src/main/kotlin/org/datamancy/pipeline/sources/WikipediaSource.kt`
**Action**:
- Code already updated with retry logic and 30-minute timeout
- Rebuild pipeline: `cd kotlin.src/pipeline && ./gradlew build`
- Redeploy: Copy new JAR to server and restart pipeline container

---

### 2. Docker Registry HTTP/HTTPS Fix
**Status**: Needs manual application
**Reference**: `registry-fix.yml`

**Steps**:
```bash
# On server:
ssh gerald@latium.local

# 1. Update docker-compose.yml registry service with content from registry-fix.yml

# 2. Configure host Docker daemon to allow insecure registry
sudo nano /etc/docker/daemon.json
# Add:
{
  "insecure-registries": ["192.168.0.11:5000", "localhost:5000"]
}

# 3. Restart Docker daemon
sudo systemctl restart docker

# 4. Restart registry
cd ~/datamancy
docker compose restart registry
```

---

### 3. PostgreSQL Missing Tables/Schemas
**Status**: Needs manual application
**Reference**: `postgres/01-create-schemas.sql`, `postgres/02-create-tables.sql`

**Steps**:
```bash
ssh gerald@latium.local
cd ~/datamancy

# 1. Copy SQL files to postgres init directory
mkdir -p configs/postgres/init
cp /path/to/01-create-schemas.sql configs/postgres/init/
cp /path/to/02-create-tables.sql configs/postgres/init/

# 2. Option A: Run manually on existing database
docker exec -i postgres psql -U datamancer -d datamancy < configs/postgres/init/01-create-schemas.sql
docker exec -i postgres psql -U datamancer -d datamancy < configs/postgres/init/02-create-tables.sql

# 2. Option B: Recreate database (will run init scripts automatically)
# WARNING: This deletes all data!
# docker compose down postgres
# docker volume rm datamancy_postgres_data
# docker compose up -d postgres
```

---

## 🟡 MAJOR FIXES (Apply Soon)

### 4. Mastodon Host Authorization
**Reference**: `docker-compose-service-fixes.yml`

```bash
ssh gerald@latium.local
cd ~/datamancy

# Edit docker-compose.yml and merge mastodon-web environment variables
nano docker-compose.yml
# Add under mastodon-web service:
#   environment:
#     - ALLOWED_HOSTS=mastodon-web,mastodon-web:3000,latium.local
#     - TRUSTED_PROXY_IP=172.19.0.0/16

docker compose restart mastodon-web mastodon-sidekiq
```

---

### 5. Homepage Host Validation
**Reference**: `docker-compose-service-fixes.yml`

```bash
# Edit docker-compose.yml homepage service
nano docker-compose.yml
# Add:
#   environment:
#     - HOMEPAGE_VAR_DISABLE_HOST_CHECK=true

docker compose restart homepage
```

---

### 6. Authelia Session Cookie Domain
**Reference**: `authelia-session-fix.yml`

```bash
# Update Authelia configuration
nano configs/authelia/configuration.yml
# Merge session section from authelia-session-fix.yml

docker compose restart authelia
```

---

### 7. Ntfy Authentication
**Reference**: `ntfy-server.yml`

```bash
# Replace ntfy config
cp ntfy-server.yml configs/ntfy/server.yml

docker compose restart ntfy
```

---

### 8. Docker Health Exporter Timeout
**Reference**: `docker-compose-service-fixes.yml`

```bash
# Update docker-health-exporter service in docker-compose.yml
nano docker-compose.yml
# Set TIMEOUT=120 environment variable

docker compose restart docker-health-exporter
```

---

## 🟠 MINOR FIXES (Performance/Polish)

### 9. Caddy UDP Buffer Sizes
**Reference**: `sysctl-udp-buffers.conf`

```bash
# On HOST machine (not in container)
sudo cp sysctl-udp-buffers.conf /etc/sysctl.d/99-caddy-udp.conf
sudo sysctl -p /etc/sysctl.d/99-caddy-udp.conf

# Verify
sysctl net.core.rmem_max net.core.wmem_max
# Should show: 7500000

# Restart Caddy to pick up new buffers
docker compose restart caddy
```

---

### 10. Open-WebUI Routing Fix
**Reference**: `Caddyfile-fixes.txt`

```bash
# Update Caddyfile with Open-WebUI section
nano configs/caddy/Caddyfile
# Merge open-webui.latium.local block from Caddyfile-fixes.txt

docker compose restart caddy
```

---

### 11. Radicale Authentication
**Reference**: `radicale-config`

```bash
# Replace Radicale config
cp radicale-config configs/radicale/config

docker compose restart radicale
```

---

## ✅ VERIFICATION CHECKLIST

After applying fixes, verify:

```bash
# 1. Check all containers healthy
docker compose ps | grep -v "Up.*healthy"

# 2. Run integration tests
docker compose run --rm integration-test-runner

# 3. Check logs are clean
docker compose logs --tail=50 pipeline | grep -i error

# 4. Test registry push
docker tag alpine:latest 192.168.0.11:5000/test:latest
docker push 192.168.0.11:5000/test:latest

# 5. Verify Wikipedia retry works (check pipeline logs)
docker compose logs pipeline | grep -i wikipedia
```

---

## 📊 EXPECTED IMPROVEMENTS

**Before Fixes**:
- 21 distinct errors across services
- Integration test pass rate: 82%
- 2 unhealthy containers
- Wikipedia fetch: FAILED (325/millions articles)
- Logs: 340+ INFO messages per run

**After Fixes**:
- 0 critical errors
- Integration test pass rate: ~95%+
- 0 unhealthy containers (except brief startup)
- Wikipedia fetch: WORKING with retry
- Logs: <10 INFO messages per run

---

## 🔥 BONUS: Pipeline Logging Cleanup ✅ ALREADY APPLIED

All logging spam has been cleaned up in the local repo:
- ✅ logback.xml - Third-party libraries silenced
- ✅ OpenAustralianLegalCorpusSource - Removed 10+ log lines
- ✅ SourceScheduler - Removed 10 log lines
- ✅ StandardizedRunner - Reduced to 1 log line
- ✅ QdrantSink - Removed collection spam
- ✅ Main.kt - Removed startup spam

**Action**: Rebuild and redeploy pipeline service to see clean logs.

---

## 🚀 QUICK APPLY SCRIPT

```bash
#!/bin/bash
# Quick apply script for all fixes

cd ~/datamancy

# Apply docker-compose fixes
echo "Updating docker-compose.yml..."
# Manually merge changes from docker-compose-service-fixes.yml

# Apply config fixes
echo "Updating config files..."
cp deployment-fixes/ntfy-server.yml configs/ntfy/server.yml
cp deployment-fixes/radicale-config configs/radicale/config
# Manually merge Caddyfile and Authelia changes

# Apply PostgreSQL init scripts
echo "Setting up PostgreSQL init scripts..."
mkdir -p configs/postgres/init
cp deployment-fixes/postgres/*.sql configs/postgres/init/

# Apply sysctl changes
echo "Applying sysctl UDP buffer changes..."
sudo cp deployment-fixes/sysctl-udp-buffers.conf /etc/sysctl.d/99-caddy-udp.conf
sudo sysctl -p /etc/sysctl.d/99-caddy-udp.conf

# Restart affected services
echo "Restarting services..."
docker compose restart ntfy radicale caddy homepage mastodon-web mastodon-sidekiq docker-health-exporter

echo "✅ Fixes applied! Check 'docker compose ps' and run integration tests."
```

---

## 📝 NOTES

- **CVE source** is intentionally disabled (needs API key) - not an error
- **Torrents, Australian Laws, Debian Wiki** are still processing - expected for large datasets
- Some services (Dozzle) may show unhealthy briefly during startup - normal
- Wikipedia will now retry up to 3 times with exponential backoff on stream failures

---

## 🆘 TROUBLESHOOTING

**If tests still fail after fixes**:

1. Check logs: `docker compose logs --tail=100 <service>`
2. Verify configs applied: `docker compose config`
3. Check network: `docker network inspect datamancy_datamancy`
4. Restart all: `docker compose restart`
5. Nuclear option: `docker compose down && docker compose up -d`
