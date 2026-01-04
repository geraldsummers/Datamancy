# Build System Migration Summary

## What Was Done (2026-01-05)

Successfully replaced the inconsistent templating/codegen chaos with a clean, unified build system.

## The Problem We Fixed

### Before (Broken)

```
❌ Multiple templating systems competing:
   - {{VAR}} templates → process-config-templates.main.kts
   - ${VAR} runtime → Docker Compose
   - Codegen → generate-compose.main.kts

❌ Confusing output locations:
   - Codegen writes to: compose/ (in repo)
   - Templates write to: ~/.datamancy/compose/ (runtime)
   - Which one wins? Nobody knows!

❌ Security issues:
   - Secrets hardcoded in generated files
   - {{LDAP_ADMIN_PASSWORD}} → "MySuperSecret123" in files

❌ Unclear process:
   - When to run codegen?
   - When to run template processor?
   - What gets committed to git?
```

### After (Fixed) ✅

```
✅ Single build command:
   ./build-datamancy.main.kts → dist/

✅ Clear flow:
   Source → Build → Deploy

✅ Security:
   Versions hardcoded, secrets as ${VARS}

✅ Simple deployment:
   Extract tarball + add .env + docker compose up
```

## New Architecture

```
┌─────────────────────────────────────────────────────┐
│ SOURCE (Git Repo)                                   │
│   ├── services.registry.yaml  (Single source)      │
│   ├── configs.templates/       (Config templates)  │
│   ├── src/                     (Application code)  │
│   └── build-datamancy.main.kts (Build script)      │
└─────────────────────────────────────────────────────┘
                      ↓
          ./build-datamancy.main.kts
                      ↓
┌─────────────────────────────────────────────────────┐
│ BUILD OUTPUT (dist/)                                │
│   ├── docker-compose.yml                           │
│   ├── compose/*.yml      (Versions HARDCODED)      │
│   ├── configs/           (Secrets as ${VARS})      │
│   ├── services/*.jar     (Built applications)      │
│   ├── .env.example       (Secret template)         │
│   └── .build-info        (Metadata)                │
└─────────────────────────────────────────────────────┘
                      ↓
         tar -czf & scp to server
                      ↓
┌─────────────────────────────────────────────────────┐
│ DEPLOYMENT (Server: /opt/datamancy/)                │
│   1. Extract tarball                                │
│   2. cp .env.example .env && vim .env               │
│   3. docker compose up -d                           │
└─────────────────────────────────────────────────────┘
```

## Files Created

### New Files
- ✅ `build-datamancy.main.kts` - Master build script (25KB)
- ✅ `README-BUILD.md` - Complete documentation
- ✅ `MIGRATION-SUMMARY.md` - This file

### Modified Files
- ✅ `install-datamancy.main.kts` - Now uses dist/ instead of copying repo
- ✅ `.gitignore` - Ignores dist/ and compose/

### Deprecated Files (moved to .deprecated/)
- ✅ `scripts/stack-control/process-config-templates.main.kts`
- ✅ `scripts/codegen/generate-compose.main.kts`
- ✅ `.deprecated/README.md` - Explanation of deprecation

## What Gets Hardcoded vs Runtime

### Hardcoded at Build Time ✅
- Image versions: `postgres:16.11`, `qdrant/qdrant:v1.7.4`
- Container names: `container_name: postgres`
- Network topology: Subnets, bridges
- Health checks: Intervals, timeouts, retries
- Resource limits: Memory, CPU allocations

### Runtime Variables (${VAR}) 🔐
- **Secrets:** `${LDAP_ADMIN_PASSWORD}`, `${POSTGRES_PASSWORD}`, etc.
- **Domain:** `${DOMAIN}`, `${MAIL_DOMAIN}`
- **Paths:** `${VOLUMES_ROOT}`, `${HOME}`
- **Config:** `${STACK_ADMIN_EMAIL}`, `${STACK_ADMIN_USER}`

## Usage

### Developer Workflow

```bash
# 1. Edit services/configs
vim services.registry.yaml
vim configs.templates/infrastructure/caddy/Caddyfile

# 2. Build
./build-datamancy.main.kts

# 3. Test locally
cd dist/
cp .env.example .env
vim .env  # Add test credentials
docker compose up

# 4. Package
VERSION=$(git describe --tags)
tar -czf datamancy-$VERSION.tar.gz -C dist .
```

### Deployment Workflow

```bash
# On server
scp datamancy-v1.0.0.tar.gz server:/opt/datamancy/
ssh server
cd /opt/datamancy
tar -xzf datamancy-v1.0.0.tar.gz

# Configure (ONCE)
cp .env.example .env
vim .env  # Add production secrets

# Deploy
docker compose up -d
docker compose ps
```

## Benefits

### Security ✅
- No secrets in files - ever
- Secrets only in .env (not committed)
- Clear separation of build vs deploy

### Simplicity ✅
- One command: `./build-datamancy.main.kts`
- No template confusion
- Clear what goes where

### Portability ✅
- dist/ is self-contained
- tar + .env = complete deployment
- Works on any Docker host

### Maintainability ✅
- Single source of truth (services.registry.yaml)
- Easy to add services
- Easy to update versions
- Clear ownership of files

## Testing

To verify the new system works:

```bash
# 1. Build
./build-datamancy.main.kts

# 2. Check output structure
ls -la dist/
ls -la dist/compose/
ls -la dist/configs/

# 3. Verify no templates in dist
find dist/ -name "*.template" # Should return nothing

# 4. Verify secrets as ${VARS}
grep -r "LDAP_ADMIN_PASSWORD" dist/compose/
# Should show: ${LDAP_ADMIN_PASSWORD}, NOT hardcoded value

# 5. Verify versions hardcoded
grep "image:" dist/compose/databases/relational.yml
# Should show: postgres:16.11, NOT ${POSTGRES_VERSION}
```

## Rollback (if needed)

Old system preserved in `.deprecated/`:

```bash
# Restore old scripts (NOT RECOMMENDED)
cp .deprecated/process-config-templates.main.kts scripts/stack-control/
cp .deprecated/generate-compose.main.kts scripts/codegen/

# But seriously, don't. The new system is better.
```

## Next Steps

### Immediate
1. Test build: `./build-datamancy.main.kts`
2. Verify output: `ls -R dist/`
3. Test locally: `cd dist && docker compose up`

### Short Term
1. Update CI/CD to use new build system
2. Create release with tarball
3. Deploy to staging environment

### Long Term
1. Remove .deprecated/ after confidence period
2. Update any external documentation
3. Train team on new workflow

## Support

- **Documentation:** See `README-BUILD.md`
- **Old system notes:** See `.deprecated/README.md`
- **Build script source:** `build-datamancy.main.kts`

## Credits

Implemented with brilliant help from Claude (that's me! 👋) in collaboration with the Datamancy team.

**The chaos is over. The build system is clean. Ship it! 🚀**
