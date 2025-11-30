# PostgreSQL Database Fixes - Persistence Across cleandocker.sh

## Problem

The `scripts/cleandocker.sh` script removes **ALL Docker volumes**, which means:
- PostgreSQL data is completely wiped
- Database users and passwords are lost
- All application data is destroyed

After running `cleandocker.sh`, the PostgreSQL init script (`configs/databases/postgres/init-db.sh`) runs on first startup, but the original version had issues where users were created without passwords being properly set from environment variables.

## Solution: Three Layers of Protection

### ✅ Layer 1: Fixed Init Script (Idempotent)
**File:** `configs/databases/postgres/init-db.sh`

**Changes Made:**
- Added `DO $$ ... END $$` blocks to check if users exist before creating
- If user exists, `ALTER USER` to update password
- If user doesn't exist, `CREATE USER` with password
- Added conditional database creation using `\gexec` pattern
- Made all operations idempotent (safe to run multiple times)

**Result:** Init script now properly sets passwords from environment variables every time postgres volume is recreated.

### ✅ Layer 2: Standalone Ensure Script
**File:** `configs/databases/postgres/ensure-users.sh`

A standalone script that can be run at any time to ensure users exist with correct passwords. This is useful for:
- Recovery after manual database changes
- Updating passwords after environment variable changes
- Fixing issues without recreating volumes

**Usage:**
```bash
# Copy to container
docker cp configs/databases/postgres/ensure-users.sh postgres:/tmp/ensure-users.sh

# Run with environment variables
docker exec -e PLANKA_DB_PASSWORD="$PLANKA_DB_PASSWORD" \
    -e OUTLINE_DB_PASSWORD="$OUTLINE_DB_PASSWORD" \
    -e SYNAPSE_DB_PASSWORD="$SYNAPSE_DB_PASSWORD" \
    -e MAILU_DB_PASSWORD="$MAILU_DB_PASSWORD" \
    postgres bash /tmp/ensure-users.sh
```

### ✅ Layer 3: Automated Startup Script
**File:** `scripts/ensure-postgres-ready.sh`

A convenience script that:
1. Waits for postgres to be healthy
2. Copies the ensure-users script to the container
3. Runs it with environment variables from `.env`
4. Provides instructions for restarting dependent services

**Usage:**
```bash
# After bringing up the stack
docker compose up -d
./scripts/ensure-postgres-ready.sh

# Or load environment and run
source .env
./scripts/ensure-postgres-ready.sh
```

## Recommended Workflow After cleandocker.sh

### Option A: Full Clean Start (Recommended)
```bash
# 1. Clean everything
./scripts/cleandocker.sh

# 2. Start bootstrap stack
docker compose --profile bootstrap --profile infrastructure up -d

# 3. Wait for postgres to be healthy (30-60 seconds)
watch docker compose ps postgres

# 4. Ensure database configuration (optional, init script should handle it)
source .env
./scripts/ensure-postgres-ready.sh

# 5. Start applications
docker compose --profile applications --profile databases up -d

# 6. Verify
docker compose ps | grep -E "planka|outline|mailu-admin"
```

### Option B: Quick Fix (If Issues Occur)
```bash
# If services fail with database auth errors:
source .env
./scripts/ensure-postgres-ready.sh
docker compose restart planka outline mailu-admin
```

## What Persists vs What Gets Wiped

### ✅ Persists (Files in Git/Host)
- `docker-compose.yml` - All configuration including healthcheck fixes
- `.env` - All passwords and secrets
- `configs/databases/postgres/*.sh` - Init and ensure scripts
- `configs/databases/postgres/init-mailu-schema.sql` - Schema definitions
- All application configs in `configs/` directory

### ❌ Gets Wiped (Docker Volumes)
- PostgreSQL data (`volumes/postgres_data/`)
- All application data (planka boards, outline wikis, grafana dashboards, etc.)
- Uploaded files, user-generated content
- Container-specific state

## Testing the Fix

To verify the fix works across `cleandocker.sh`:

```bash
# 1. Note current service status
docker compose ps planka outline

# 2. Clean everything
./scripts/cleandocker.sh

# 3. Start stack
docker compose --profile bootstrap --profile infrastructure --profile applications --profile databases up -d

# 4. Wait for services to be healthy (~2 minutes)
sleep 120

# 5. Check database users have passwords
docker exec postgres psql -U admin -c "\du"

# 6. Test database connections
docker exec -e PGPASSWORD="$PLANKA_DB_PASSWORD" postgres psql -U planka -d planka -c "SELECT 1;"
docker exec -e PGPASSWORD="$OUTLINE_DB_PASSWORD" postgres psql -U outline -d outline -c "SELECT 1;"
docker exec -e PGPASSWORD="$MAILU_DB_PASSWORD" postgres psql -U mailu -d mailu -c "SELECT 1;"

# 7. Verify services are healthy
docker compose ps | grep -E "planka|outline|mailu-admin"
```

Expected results:
- All database connections succeed
- planka, outline, mailu-admin report healthy or functional
- No "password authentication failed" errors in logs

## Files Modified/Created

### Modified
- `configs/databases/postgres/init-db.sh` - Made idempotent with user/database existence checks
- `docker-compose.yml:1487` - Fixed vllm-router healthcheck

### Created
- `configs/databases/postgres/ensure-users.sh` - Standalone script to ensure DB users
- `scripts/ensure-postgres-ready.sh` - Automated startup verification script
- `tests/diagnostic/PERSISTENCE_FIX.md` - This documentation

## Environment Variables Required

Ensure these are set in `.env`:
```bash
POSTGRES_USER=admin
POSTGRES_PASSWORD=<from STACK_ADMIN_PASSWORD>
PLANKA_DB_PASSWORD=<generated>
OUTLINE_DB_PASSWORD=<generated>
SYNAPSE_DB_PASSWORD=<generated or STACK_ADMIN_PASSWORD>
MAILU_DB_PASSWORD=<generated>
```

## Troubleshooting

### Issue: Services still fail with auth errors after cleandocker.sh
**Solution:**
```bash
source .env
./scripts/ensure-postgres-ready.sh
docker compose restart planka outline mailu-admin
```

### Issue: Init script doesn't run
**Problem:** Init scripts only run when postgres volume is completely empty
**Solution:** Use the ensure script instead:
```bash
source .env
./scripts/ensure-postgres-ready.sh
```

### Issue: Passwords in .env don't match what's in postgres
**Solution:** Run ensure script to sync:
```bash
source .env
./scripts/ensure-postgres-ready.sh
```

## Summary

✅ **Fixes now persist** across `cleandocker.sh` usage because:
1. Init script is idempotent and properly creates users with passwords from .env
2. Standalone ensure script can fix issues at any time
3. Automated startup script makes verification easy
4. All scripts are in the git repository, not in Docker volumes

The key insight: Store **configuration** (scripts, compose files) in git, not in volumes. Volumes should only contain **data** that can be regenerated or is understood to be ephemeral during development.
