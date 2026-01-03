# Compose File Code Generation System

## Overview

The Datamancy stack uses a **single source of truth** pattern for managing 50+ services across the stack. All service metadata is defined in `services.registry.yaml`, and Docker Compose files are automatically generated from this registry.

## Architecture

```
services.registry.yaml (Single Source of Truth)
          ↓
scripts/codegen/generate-compose.main.kts (Code Generator)
          ↓
compose/*.yml files (Generated Compose Files)
          ↓
docker-compose.modular.yml (Orchestrator)
          ↓
datamancy-controller.main.kts (Runtime Controller)
```

## Files

### 1. `services.registry.yaml` - Single Source of Truth

Defines all service metadata:
- **Image names and versions** - `caddy:2.8.4`, `postgres:16.11`, etc.
- **Container names** - Consistent naming across stack
- **Network configuration** - Which networks each service connects to
- **Subdomain mappings** - `grafana.${DOMAIN}`, `open-webui.${DOMAIN}`
- **Health checks** - Type, interval, timeout, retries
- **Phase assignments** - Which deployment phase (core, databases, auth, applications, ai, datamancy)
- **Dependencies** - `depends_on` relationships
- **Resource limits** - Memory, CPU requirements

Example:
```yaml
core:
  caddy:
    image: caddy
    version: 2.8.4
    container_name: caddy
    subdomain: www
    additional_aliases: [grafana, open-webui, vaultwarden, ...]
    networks: [frontend, backend]
    health_check:
      type: docker
      interval: 10s
    phase: core
    phase_order: 1

databases:
  postgres:
    image: postgres
    version: "16.11"
    container_name: postgres
    networks: [database, backend]
    health_check:
      type: docker
      interval: 30s
    phase: databases
    phase_order: 2

phases:
  core:
    order: 1
    description: Core infrastructure (caddy, ldap, valkey)
    timeout_seconds: 90
  databases:
    order: 2
    description: Database services
    timeout_seconds: 120
```

### 2. `scripts/codegen/generate-compose.main.kts` - Code Generator

Kotlin script that reads `services.registry.yaml` and generates compose files:

**What it generates:**
- `compose/core/infrastructure.yml` - Core services (caddy, ldap, authelia, valkey, etc.)
- `compose/databases/relational.yml` - PostgreSQL, MariaDB
- `compose/databases/vector.yml` - Qdrant vector database
- `compose/databases/analytics.yml` - ClickHouse
- `compose/applications/web.yml` - Web apps (grafana, bookstack, etc.)
- `compose/applications/communication.yml` - Matrix, Mastodon, email
- `compose/applications/files.yml` - Seafile, OnlyOffice
- `compose/datamancy/services.yml` - Datamancy custom services
- `compose/datamancy/ai.yml` - AI/ML services (vLLM, LiteLLM, embeddings)
- `docker-compose.modular.yml` - Orchestrator with include directives

**Features:**
- Generates image tags from registry versions
- Creates network aliases from subdomain metadata
- Generates healthcheck configurations
- Handles depends_on relationships
- Adds auto-generated headers warning not to edit manually

**Run manually:**
```bash
./scripts/codegen/generate-compose.main.kts
```

**Auto-generated files include:**
```yaml
# Auto-generated from services.registry.yaml
# DO NOT EDIT MANUALLY - run scripts/codegen/generate-compose.main.kts

services:
  caddy:
    image: caddy:2.8.4
    container_name: caddy
    networks:
      frontend:
        aliases:
          - www.${DOMAIN}
          - grafana.${DOMAIN}
    # ... rest of config
```

### 3. `scripts/stack-control/datamancy-controller.main.kts` - Runtime Controller

The controller now:
1. **Auto-regenerates** compose files from registry on every `up` command
2. **Generates phases dynamically** from registry metadata
3. **Loads service health checks** from registry definitions
4. **Validates** that registry exists and is parseable

**New Commands:**
```bash
# Regenerate compose files manually
datamancy-controller codegen

# Start stack (auto-regenerates compose files first)
datamancy-controller up [profile]
```

**Phase Generation:**
The controller reads `services.registry.yaml` and automatically:
- Groups services by `phase` field
- Sorts phases by `phase_order`
- Creates health checks from service metadata
- Uses timeout values from phase metadata

Before:
```kotlin
// Hardcoded phases in controller
phases.add(ComposePhase(
    name = "databases",
    healthChecks = listOf(
        ServiceHealthCheck("postgres", HealthCheckType.DOCKER_HEALTH),
        ServiceHealthCheck("mariadb", HealthCheckType.DOCKER_HEALTH)
    ),
    timeoutSeconds = 120
))
```

After:
```kotlin
// Generated from registry
val registry = loadServiceRegistry(root)
val phases = getPhasesFromRegistry(registry, targetProfile)
// Automatically includes all services in 'databases' phase
```

## Workflow

### Making Changes to Services

**Before (Old Way):**
1. Edit version in `docker-compose.yml` line 453
2. Edit same version in controller health check
3. Edit version in documentation
4. Hope you didn't miss any instances

**After (New Way):**
1. Edit version in `services.registry.yaml`:
   ```yaml
   postgres:
     version: "16.12"  # Update here only
   ```
2. Run: `datamancy-controller up`
3. Controller auto-regenerates all compose files with new version
4. Single source of truth ensures consistency

### Adding a New Service

1. Add to `services.registry.yaml`:
```yaml
applications:
  mynewservice:
    image: mynewservice/app
    version: "1.0.0"
    container_name: mynewservice
    subdomain: mynewservice
    networks: [backend]
    health_check:
      type: docker
      interval: 30s
    phase: applications
    phase_order: 4
```

2. Run: `datamancy-controller codegen` (or just `up`)
3. Service automatically added to:
   - Generated compose file
   - Phase 4 deployment
   - Health check list
   - Caddy network aliases (if subdomain specified)

### Changing Deployment Phases

Modify phase metadata:
```yaml
phases:
  databases:
    order: 2
    description: Database services (postgres, mariadb, clickhouse, qdrant)
    timeout_seconds: 180  # Increase timeout
```

Controller automatically picks up new timeout on next `up`.

## Benefits

### 1. **Single Source of Truth**
- One place to update versions: `services.registry.yaml`
- No duplicate definitions across files
- Consistent metadata everywhere

### 2. **Maintainability**
- 50+ services managed in one YAML file
- Easy to see all versions at a glance
- Bulk updates possible with search/replace

### 3. **Automation**
- Compose files regenerated automatically
- No manual sync needed
- Reduces human error

### 4. **Documentation**
- Registry serves as live documentation
- Every service's metadata in one place
- Easy to audit what's deployed

### 5. **Flexibility**
- Change phases dynamically
- Adjust timeouts per environment
- Easy to create new profiles

## File Structure

```
datamancy/
├── services.registry.yaml           # SOURCE OF TRUTH
├── docker-compose.modular.yml       # Generated orchestrator
├── compose/                         # Generated compose files
│   ├── core/
│   │   ├── networks.yml            # Manual (network definitions)
│   │   ├── volumes.yml             # Manual (complex env vars)
│   │   └── infrastructure.yml      # GENERATED
│   ├── databases/
│   │   ├── relational.yml          # GENERATED
│   │   ├── vector.yml              # GENERATED
│   │   └── analytics.yml           # GENERATED
│   ├── applications/
│   │   ├── web.yml                 # GENERATED
│   │   ├── communication.yml       # GENERATED
│   │   └── files.yml               # GENERATED
│   └── datamancy/
│       ├── services.yml            # GENERATED
│       └── ai.yml                  # GENERATED
└── scripts/
    ├── codegen/
    │   └── generate-compose.main.kts  # Code generator
    └── stack-control/
        └── datamancy-controller.main.kts  # Runtime controller
```

## Implementation Details

### Codegen Script
- **Language**: Kotlin script (kotlin.main.kts)
- **Dependencies**: Jackson for YAML parsing
- **Input**: `services.registry.yaml`
- **Output**: Multiple compose files organized by category
- **Runtime**: ~2-3 seconds on typical hardware

### Controller Integration
- **Loads registry** on startup via Jackson
- **Validates** registry syntax and structure
- **Falls back** to hardcoded values if registry missing
- **Regenerates** compose files before every `up` command
- **Generates phases** dynamically from registry metadata

### Phase Generation Algorithm
1. Load `services.registry.yaml`
2. Parse all service categories (core, databases, applications, ai, datamancy)
3. Group services by `phase` field
4. Sort groups by `phase_order`
5. For each phase:
   - Map to compose file paths
   - Extract health checks from service definitions
   - Use timeout from phase metadata
   - Create `ComposePhase` object
6. Return sorted list of phases

### Health Check Translation
```yaml
# Registry format
health_check:
  type: docker  # or http, tcp, exec
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 30s

# Translates to controller enum
ServiceHealthCheck(
    serviceName = "postgres",
    checkType = HealthCheckType.DOCKER_HEALTH,
    timeoutSeconds = 30
)
```

## Future Enhancements

### Possible improvements:
1. **Environment variables** - Generate .env templates from registry
2. **Volume definitions** - Generate volumes.yml from registry metadata
3. **Profiles** - Generate profile definitions from registry tags
4. **Documentation** - Auto-generate service inventory markdown
5. **Validation** - Schema validation for registry file
6. **Versioning** - Track registry changes over time
7. **Diffing** - Show what changed between codegen runs
8. **Testing** - Validate generated compose files with docker compose config

## Troubleshooting

### Compose files not regenerating
```bash
# Manually trigger regeneration
datamancy-controller codegen

# Check if registry exists
ls -la services.registry.yaml

# Validate registry syntax
docker compose -f docker-compose.modular.yml config
```

### Registry parse errors
```bash
# Check YAML syntax
yamllint services.registry.yaml

# View parse errors
datamancy-controller up  # Will show error if registry is invalid
```

### Generated files look wrong
```bash
# Backup current generated files
cp -r compose compose.backup

# Regenerate
datamancy-controller codegen

# Compare
diff -r compose.backup compose
```

### Want to skip auto-regeneration
Edit controller to comment out:
```kotlin
// regenerateComposeFiles(root)  # Disable auto-regen
```

## Best Practices

1. **Never edit generated files** - Changes will be overwritten
2. **Always edit registry** - Make changes in `services.registry.yaml`
3. **Run codegen after edits** - Or just run `up` which does it automatically
4. **Commit registry** - Version control the source of truth, not generated files
5. **Review diffs** - Check what codegen changed before deploying
6. **Test phases** - Ensure phase assignments make sense for dependencies
7. **Validate health checks** - Make sure services actually support the check type

## Migration Notes

The system currently generates **most** compose configuration but **not all**:

**Generated:**
- Service definitions
- Image tags
- Container names
- Network membership
- Network aliases (subdomains)
- Health check types
- Depends_on relationships
- Resource limits

**Still Manual (TODO):**
- Volume definitions (in `compose/core/volumes.yml`)
- Environment variables (complex substitution)
- Port mappings (some services)
- Custom command overrides
- Specific mount paths

These will be migrated to registry-driven generation in future iterations.

## Summary

The Compose Codegen system transforms Datamancy infrastructure management from a **manual, error-prone process** into an **automated, consistent workflow** driven by a single source of truth. Version updates, service additions, and infrastructure changes now happen in one place (`services.registry.yaml`), with automatic propagation to all deployment artifacts.
