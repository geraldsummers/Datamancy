Datamancy
=========

Datamancy is a self‑hosted, Docker‑Compose based platform for running local AI (LLM, vision, speech), an OpenAI‑compatible API gateway, a secured chat UI, function/tool orchestration, and an optional data ingestion + vector search stack.

This repository uses a standardized documentation layout. Start here and follow links to focused guides.

- Bootstrap and production guide: docs/BOOTSTRAP.md
- App catalog (all services in docker‑compose): docs/APP_CATALOG.md
- Architecture overview: docs/ARCHITECTURE.md
- Data ingestion and RAG: docs/DATA_AND_RAG.md
- Operations (logs, backups, upgrades): docs/OPERATIONS.md
- Security (SSO/TLS/secrets/policies): docs/SECURITY.md

Quickstart (bootstrap)
----------------------

1) Ensure Docker (with compose plugin) is installed.
2) From repo root (first time):
   - bash scripts/bootstrap-stack.sh init
   - docker compose -f docker-compose.secrets.yml --profile bootstrap run --rm secrets-manager
   - docker compose -f docker-compose.secrets.yml -f docker-compose.yml --profile bootstrap up -d
3) Bootstrap access: all UIs are served via Caddy and gated by Authelia SSO. Use https://<service>.${DOMAIN} (e.g., https://open-webui.${DOMAIN}).
4) See docs/BOOTSTRAP.md for dev/test vs production TLS and readiness. Note: bootstrap is just the first slice of the same stack — there are no separate "bootstrap configs"; auth/TLS settings are shared across modes.

Profiles
--------

- bootstrap: Core AI and supporting services (Caddy, Authelia, LDAP/Redis, LocalAI, LiteLLM, Open WebUI, KFuncDB, Portainer, LAM, test runner).
- bootstrap_vector_dbs: Qdrant, ClickHouse, and Benthos for vector/RAG pipelines.
- full: Everything else as defined in docker-compose.yml (requires a complete .env). Same SSO/TLS configuration approach as bootstrap; bootstrap differs only by starting a smaller subset first.

See docs/APP_CATALOG.md for which services belong to which profile and how they’re exposed.
# Datamancy Stack

A secure, self-hosted infrastructure stack with integrated secrets management.

## 🔐 Security First

**All secrets are generated programmatically at runtime and never exposed to human eyes or logs.**

### First-Time Setup

1. **Initialize secrets** (one-time only):
   ```bash
   docker compose -f docker-compose.secrets.yml --profile bootstrap run --rm secrets-manager
   ```

   This will:
   - Generate cryptographically secure secrets using OpenSSL
   - Encrypt them with AES-256-CBC
   - Store them in an encrypted volume at `volumes/secrets/`
   - **Never display secrets in logs or terminal output**

2. **Start the stack**:
   ```bash
   docker compose -f docker-compose.secrets.yml -f docker-compose.yml --profile bootstrap up -d
   ```

   Services automatically load secrets from the encrypted store at runtime.

### Secret Management

#### View Available Commands
CLI helper
----------

Use scripts/bootstrap-stack.sh for common flows. The script is self‑documenting and referenced from docs/OPERATIONS.md.

Where to look next
------------------

- Components and topology: docs/ARCHITECTURE.md
- Ingest data and run vector search: docs/DATA_AND_RAG.md
- Production with TLS/SSO: docs/BOOTSTRAP.md
- All apps and URLs: docs/APP_CATALOG.md
