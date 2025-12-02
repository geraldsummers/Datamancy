# Stackops Up Command

The `stackops.main.kts up` command is an **intelligent wrapper** around `docker compose up` that performs pre-flight checks and automatic fixes before starting your stack.

## What It Does

### 5-Step Pre-Flight Process

1. **✓ .env Validation**
   - Checks `.env` file exists
   - Validates required variables: `DOMAIN`, `STACK_ADMIN_PASSWORD`, `STACK_ADMIN_EMAIL`, `VOLUMES_ROOT`
   - **Fails fast** if missing → prompts you to configure

2. **✓ Config Generation**
   - Checks if `configs/` directory exists
   - **Auto-generates** from `configs.templates/` if missing
   - Warns if templates are newer than configs (drift detection)
   - Processes 28 template files + copies 24 additional files

3. **✓ Volume Creation**
   - Reads `VOLUMES_ROOT` from `.env`
   - Creates missing critical directories:
     - `caddy_data`, `caddy_config`
     - `postgres_data`, `redis_data`
     - `authelia`, `proofs/screenshots`

4. **✓ Profile Selection**
   - Defaults to `--profile bootstrap`
   - Supports `--all`, `--bootstrap`, `--profile <name>`
   - Smart profile resolution

5. **✓ Stack Start**
   - Runs `docker compose up -d` with selected profiles
   - Shows startup output
   - Reports status

## Usage

### Available Profiles

Datamancy has 5 Docker Compose profiles:

| Profile | Services | Purpose |
|---------|----------|---------|
| **infrastructure** | Caddy, core network services | Reverse proxy, networking |
| **bootstrap** | Core stack | Authelia, LDAP, Redis, LLM services, Open WebUI |
| **databases** | Postgres, MariaDB, Redis, etc. | Data storage layer |
| **bootstrap_vector_dbs** | Qdrant, ClickHouse | Vector and analytics databases |
| **applications** | Full app suite | All 40+ applications |

### Basic Usage

```bash
# Start with bootstrap profile (default)
kotlin scripts/stackops.main.kts up

# Start all 5 profiles
kotlin scripts/stackops.main.kts up --all

# Start specific profile (convenient flags)
kotlin scripts/stackops.main.kts up --databases
kotlin scripts/stackops.main.kts up --applications
kotlin scripts/stackops.main.kts up --infrastructure
kotlin scripts/stackops.main.kts up --vector-dbs

# Start multiple profiles (explicit)
kotlin scripts/stackops.main.kts up --profile bootstrap --profile databases

# Mix convenient flags and explicit profiles
kotlin scripts/stackops.main.kts up --databases --applications
```

### Example Output

**Starting with all profiles:**

```bash
$ kotlin scripts/stackops.main.kts up --all
```

```
[INFO] === Datamancy Stack Pre-Flight Checks ===

[INFO] 1/5 Checking .env file...
[INFO]     ✓ .env exists
[INFO] 2/5 Validating environment variables...
[INFO]     ✓ Required variables present: DOMAIN, STACK_ADMIN_PASSWORD, STACK_ADMIN_EMAIL, VOLUMES_ROOT
[INFO] 3/5 Checking configuration files...
[WARN]     configs/ directory missing or empty - generating from templates...
[INFO]     Running: kotlin scripts/process-config-templates.main.kts
[INFO]     ✓ Generated configs/ from templates (52 files)
[INFO] 4/5 Checking volume directories...
[INFO]     Creating 1 missing volume directories...
[INFO]     ✓ Volume directories ready
[INFO] 5/5 Starting Docker Compose stack...

[INFO] Running: docker compose --profile infrastructure --profile bootstrap --profile databases --profile bootstrap_vector_dbs --profile applications up -d
[+] Running 94/94
 ✔ Container caddy              Started
 ✔ Container ldap               Started
 ✔ Container redis              Started
 ✔ Container postgres           Started
 ✔ Container authelia           Started
 ✔ Container grafana            Started
 ...

[INFO] === Pre-Flight Complete ===
[INFO] Stack is starting. Check status with: docker compose ps
[INFO] Started profiles: infrastructure, bootstrap, databases, bootstrap_vector_dbs, applications
```

## Error Handling

### Missing .env

```
[ERROR] .env file not found! Run: cp .env.example .env && nano .env
```

**Fix:** Create and configure your environment file.

### Missing Environment Variables

```
[ERROR] Missing required environment variables: DOMAIN, STACK_ADMIN_PASSWORD
Edit .env and set these values
```

**Fix:** Add the missing variables to `.env`.

### Template Generation Failure

```
[ERROR] Failed to generate configs/
[template processor output]
```

**Fix:** Check that `configs.templates/` exists and `process-config-templates.main.kts` is present.

## Advanced Usage

### First-Time Setup (Clean Slate)

```bash
# 1. Clone repo
git clone <repo-url>
cd Datamancy

# 2. Configure environment
cp .env.example .env
nano .env  # Set DOMAIN, passwords, etc.

# 3. One command to start everything!
kotlin scripts/stackops.main.kts up --all
```

The `up` command will:
- Validate your `.env`
- Generate all configs from templates
- Create all volume directories
- Start all services

### Regenerate Configs

If you change `.env` or update templates:

```bash
# Regenerate manually
kotlin scripts/process-config-templates.main.kts --force

# Or delete configs/ and let stackops regenerate
rm -rf configs
kotlin scripts/stackops.main.kts up
```

### Check for Config Drift

The script warns if templates are newer than configs:

```
[WARN]     ⚠ configs.templates/ has changes newer than configs/
[WARN]     Consider regenerating: kotlin scripts/process-config-templates.main.kts --force
```

## Integration with Deployment

Add to your deployment scripts:

```bash
#!/bin/bash
set -euo pipefail

# Pull latest code
git pull

# Update environment (from secrets manager)
cat > .env << EOF
DOMAIN=production.example.com
STACK_ADMIN_PASSWORD=${SECRET_ADMIN_PASS}
STACK_ADMIN_EMAIL=ops@example.com
# ... more vars
EOF

# Intelligent startup (checks + fixes + starts)
kotlin scripts/stackops.main.kts up --all

# Wait for health
sleep 30
docker compose ps
```

## Comparison

### Without stackops up

```bash
# Manual steps - easy to forget!
cp .env.example .env
nano .env
kotlin scripts/process-config-templates.main.kts
mkdir -p volumes/{caddy_data,postgres_data,redis_data,...}
docker compose --profile bootstrap up -d
# Did I miss anything? 🤔
```

### With stackops up

```bash
# One command - all checks automatic!
kotlin scripts/stackops.main.kts up
# Everything handled ✓
```

## See Also

- **[TEMPLATE_CONFIG.md](TEMPLATE_CONFIG.md)** - Template system reference
- **[QUICKSTART_TEMPLATES.md](QUICKSTART_TEMPLATES.md)** - Template quickstart
- **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** - Full deployment guide
- **[scripts/stackops.main.kts](scripts/stackops.main.kts)** - Source code
