# Datamancy

Self-hosted data platform with AI agents, vector search, and knowledge management tools.

## Quick Start

```bash
# Build deployment-ready distribution
./build-datamancy.main.kts

# Deploy locally
cd dist
cp .env.example .env
vim .env  # Configure secrets and paths
docker compose up -d
```

## Architecture

**Single source of truth**: `services.registry.yaml` defines all 43 services

**Build system**: `build-datamancy.main.kts` generates deployment artifacts in `dist/`
- Docker Compose files (all versions hardcoded)
- Configuration templates (only secrets remain as `${VARS}`)
- Kotlin service JARs
- Runtime scripts

**Security**: Secrets never hardcoded - preserved as `${VARIABLES}` for runtime substitution

## Services

- **Infrastructure**: Postgres, MariaDB, ClickHouse, Redis, LDAP, Traefik
- **AI/ML**: vLLM, Embedding service, LiteLLM, Agent tool server
- **Search**: Qdrant (vectors), Typesense (full-text)
- **Apps**: Forgejo, Grafana, JupyterHub, BookStack, Element, Mastodon
- **Custom**: Control panel, data fetcher, unified indexer, search service

## Development

```bash
# Build Kotlin services
./gradlew build

# Run tests
./gradlew test

# Test with exposed ports
docker compose -f docker-compose.yml -f docker-compose.test-ports.yml up
```

## Deployment

```bash
# Package for server
tar -czf datamancy-$(git rev-parse --short HEAD).tar.gz -C dist .

# Deploy
scp datamancy-*.tar.gz server:/opt/datamancy/
ssh server
cd /opt/datamancy && tar -xzf datamancy-*.tar.gz
cp .env.example .env && vim .env
docker compose up -d
```

## Structure

```
services.registry.yaml     # Service definitions
build-datamancy.main.kts   # Build system
install-datamancy.main.kts # Deployment helper
configs.templates/         # App configuration templates
src/                       # Kotlin services
dist/                      # Generated (gitignored)
```
