# Fixes Applied & Remaining Issues

## ✅ Fixed in Repository (Persists on Rebuild)

### 1. Mastodon - Missing Secrets
**Status:** FIXED
**What:** Added Mastodon OTP_SECRET and related secrets to build script
**File:** `build-datamancy.main.kts` lines 763-767
**Action Required:** Rebuild and redeploy

### 2. vLLM - No GPU Available
**Status:** DISABLED
**What:** Commented out vLLM service (requires CUDA/GPU)
**File:** `services.registry.yaml` lines 1197-1238
**Action Required:** Rebuild to remove from compose files

### 3. JupyterHub - Permission Issues
**Status:** FIXED
**What:** Added proper user/group config and volume mount
**File:** `services.registry.yaml` lines 862-868
**Action Required:** Rebuild and redeploy

---

## ❌ Hard Fixes Needed (Config/Environment Issues)

### 4. open-webui - Database Authentication Failure
**Issue:** `FATAL: password authentication failed for user "openwebui"`
**Root Cause:** Database user `openwebui` either doesn't exist or password mismatch
**Location:** Check postgres init scripts and environment variables

**Solution:**
```bash
# Option 1: Create the user in postgres
docker exec postgres psql -U postgres -c "CREATE USER openwebui WITH PASSWORD 'password_from_env';"
docker exec postgres psql -U postgres -c "CREATE DATABASE openwebui OWNER openwebui;"

# Option 2: Add to postgres init script
# Add to configs.templates/databases/postgres/init-scripts/01-create-databases.sql:
CREATE USER openwebui WITH PASSWORD '${OPENWEBUI_DB_PASSWORD}';
CREATE DATABASE openwebui OWNER openwebui;
```

**Files to Check:**
- `services.registry.yaml` - open-webui environment section (line ~800)
- `configs.templates/databases/postgres/init-scripts/` - database init
- Ensure `OPENWEBUI_DB_PASSWORD` matches between compose and postgres init

---

### 5. bookstack - Database Authentication Failure
**Issue:** `Access denied for user 'database_username'@'172.21.0.3'`
**Root Cause:** Environment variable `DB_USERNAME` not being substituted (literal string "database_username")

**Solution:**
```bash
# Check bookstack service definition
grep -A50 "^  bookstack:" services.registry.yaml

# Ensure environment has:
environment:
  DB_HOST: "mariadb"
  DB_DATABASE: "bookstack"
  DB_USERNAME: "bookstack"
  DB_PASSWORD: "${BOOKSTACK_DB_PASSWORD}"
  APP_KEY: "${BOOKSTACK_APP_KEY}"
  APP_URL: "https://bookstack.${DOMAIN}"
```

**Files to Fix:**
- `services.registry.yaml` - bookstack environment section (add DB_ variables)
- Add bookstack user to MariaDB init script

---

### 6. synapse (Matrix) - Permission & Variable Substitution
**Issue:** `Permission denied: '/data/matrix.${DOMAIN}.signing.key'`
**Root Causes:**
1. Volume permissions - synapse user can't write to /data
2. `${DOMAIN}` variable not being substituted in config path

**Solution:**
```bash
# Fix 1: Adjust volume ownership
# In init script or entrypoint:
chown -R 991:991 /data  # synapse runs as UID 991

# Fix 2: Update homeserver.yaml template
# In configs.templates/applications/synapse/homeserver.yaml
# Change signing_key_path to use proper variable substitution
signing_key_path: "/data/matrix.project-saturn.com.signing.key"
# Or use environment variable properly
```

**Files to Fix:**
- `configs.templates/applications/synapse/homeserver.yaml` - fix signing_key_path
- Add init container or script to fix permissions
- Verify DOMAIN variable is passed to synapse container

---

### 7. homeassistant - Entrypoint Permission Error
**Issue:** `/entrypoint-wrapper.sh: permission denied`
**Root Cause:** Dockerfile doesn't set execute bit on entrypoint, or volume mount issue

**Solution:**
```bash
# Check if custom entrypoint exists
ls -la configs.templates/applications/homeassistant/

# If using custom entrypoint, ensure it's executable:
chmod +x configs.templates/applications/homeassistant/entrypoint-wrapper.sh

# Or switch to official image without custom entrypoint
# In services.registry.yaml:
homeassistant:
  image: ghcr.io/home-assistant/home-assistant
  # Remove any custom entrypoint mounts
```

**Files to Check:**
- `services.registry.yaml` - homeassistant volumes (check for entrypoint mount)
- `configs.templates/applications/homeassistant/` - custom scripts
- Consider using vanilla Home Assistant image

---

### 8. search-service - Unhealthy (Minor)
**Issue:** Can't connect to Qdrant for version check
**Impact:** Service works, just healthcheck fails
**Root Cause:** Healthcheck is too strict or Qdrant network connectivity

**Solution:**
```bash
# Option 1: Relax healthcheck
# In services.registry.yaml search-service section:
health_check:
  type: http
  interval: 60s  # Increase interval
  retries: 10    # More retries

# Option 2: Disable healthcheck entirely if service works
# Remove health_check section or set to docker (no test)
```

**Files to Fix:**
- `services.registry.yaml` - search-service health_check section

---

## 🔄 Rebuild & Redeploy Process

After making fixes:

```bash
# 1. Rebuild from source
./build-datamancy.main.kts --clean

# 2. Package for deployment
cd dist
tar -czf ../datamancy-fixed.tar.gz .
cd ..
docker save $(docker images 'datamancy/*:*' -q) -o datamancy-images-fixed.tar

# 3. Transfer to server
scp datamancy-fixed.tar.gz datamancy-images-fixed.tar gerald@latium.local:/tmp/

# 4. On server - stop services, load new images, deploy
ssh gerald@latium.local
cd /mnt/btrfs_raid_1_01_docker
docker compose down
docker load -i /tmp/datamancy-images-fixed.tar
tar -xzf /tmp/datamancy-fixed.tar.gz
docker compose up -d

# 5. Fix any volume permissions
chown -R 1000:1000 /mnt/btrfs_raid_1_01_docker/volumes/jupyterhub
```

---

## Priority Order for Fixes

1. **HIGH**: bookstack, open-webui (common apps, DB auth issues)
2. **MEDIUM**: synapse (Matrix communication platform)
3. **LOW**: homeassistant (optional smart home), search-service (already works)

---

## Testing Checklist

After rebuild:
- [ ] Mastodon web/sidekiq start successfully
- [ ] JupyterHub starts and notebooks work
- [ ] Bookstack accessible and can create pages
- [ ] Open-WebUI loads and connects to LiteLLM
- [ ] Synapse/Element for Matrix chat works
- [ ] All services show healthy status
