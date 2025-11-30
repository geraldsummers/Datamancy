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

- bootstrap: Core AI and supporting services (Caddy, Authelia, LDAP/Redis, vLLM, LiteLLM, Open WebUI, KFuncDB, Portainer, LAM, test runner).
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

Autonomous diagnostics & self-healing
--------------------------------------

**NEW**: Enhanced AI-powered diagnostics with automated fix proposals!

This stack includes an autonomous diagnostic system that uses local AI to analyze failures and propose fixes—keeping expensive cloud LLM costs minimal.

### Quick Start

```bash
# Run enhanced diagnostics (AI analysis + fix proposals)
./scripts/supervisor-session.sh diagnose-enhanced

# Review issues and approve fixes interactively
./scripts/supervisor-session.sh review
```

### What It Does

1. **Probes services** - Screenshots, DOM inspection, HTTP checks
2. **Gathers evidence** - Container logs, resource metrics, health status
3. **AI Analysis** - Local LLM analyzes root causes using all evidence
4. **Fix Proposals** - Generates actionable fixes with confidence ratings
5. **Human Review** - You approve/reject fixes via interactive CLI
6. **Reports** - Saves structured diagnostics to `volumes/proofs/`

### Cost Efficiency

- **Local LLM (free)**: Does heavy lifting—log analysis, root cause detection, fix generation
- **You (expensive)**: Only reviews summaries and approves fixes (5-10 min per session)
- **Savings**: 90-95% reduction in expensive cloud LLM costs

### Basic Diagnostics (Original)

```bash
# Simple probe without AI analysis
./scripts/supervisor-session.sh diagnose

# View summary report
./scripts/supervisor-session.sh report
```

For complete documentation, see: **docs/AUTONOMOUS_DIAGNOSTICS.md**

**Key Features:**
- 🤖 Local AI analysis (Hermes-2-Pro-Mistral-7B via vLLM)
- 📊 Structured diagnostic reports with evidence
- 🔍 Root cause hypothesis generation
- 🛠️ Confidence-rated fix proposals
- ✅ Interactive approval workflow
- 🔒 Read-only tools (safe by default)
- 💰 Minimal cloud API costs

Custom programs in this repository
---------------------------------

The stack includes several custom-built services that glue the platform together and provide functionality not available off-the-shelf. This section documents the purpose of each program so you can quickly understand where it fits and when to use or troubleshoot it.

- agent-tool-server (Kotlin/Gradle)
  - Purpose: A lightweight tool execution host that exposes curated “tools” to agents/LLMs with strict capability policies. It provides a registry of tools, parameter specs, and HTTP endpoints to invoke them safely from other services.
  - Role in stack: Backing service for function/tool orchestration used by diagnostics and future agent workflows. It centralizes tool metadata and execution so policies and observability are consistent.
  - Highlights: Explicit tool registration (no reflection), capability policy model, plugins for core host tools and LLM completions.

- vllm-router (Kotlin/Gradle)
  - Purpose: An OpenAI-compatible proxy in front of vLLM that normalizes routes and responses, provides health endpoints, and exposes a stable API base to the rest of the stack.
  - Role in stack: Default upstream for LiteLLM and any client expecting OpenAI-like endpoints. It helps decouple application clients from raw vLLM specifics.
  - Highlights: Healthcheck at /health, OpenAI-style endpoints under /v1, used by Caddy for external access and by internal services (e.g., LiteLLM) via http://vllm-router:8010.

- probe-orchestrator (Kotlin/Gradle)
  - Purpose: Coordinates active probes across the stack (HTTP checks, UI screenshots, DOM inspection, log scraping), aggregates evidence, and drives AI-based analysis for the autonomous diagnostics feature.
  - Role in stack: Orchestrates diagnostic sessions, stores structured results under volumes/proofs, and triggers suggested fixes for operator review.
  - Highlights: Works with the Playwright controller and service manifests to build a full-surface health picture of running services.

- playwright-controller (Kotlin/Gradle)
  - Purpose: A controller service that manages browser automation tasks used in diagnostics (page navigation, screenshots, DOM capture) via Playwright-capable executors.
  - Role in stack: Supplies the visual and structural evidence (PNG/HTML) referenced by diagnostic reports and used by the local LLM during analysis.
  - Highlights: Designed to run headless in containers and integrate with orchestrated probe runs.

- speech-gateway (Kotlin/Gradle)
  - Purpose: A gateway for speech capabilities (STT/TTS) that presents a simple API to the stack while brokering requests to local engines or model backends.
  - Role in stack: Enables voice features in UI and agents without coupling them to any single speech implementation.
  - Highlights: Consistent, secure interface; intended to be extended with different speech providers.

- stack-discovery (Kotlin/Gradle)
  - Purpose: Discovers services and their endpoints from the running stack (compose config, Caddy routes, health URLs) and produces a manifest consumed by diagnostics and tooling.
  - Role in stack: Source of truth for “what should be running where,” powering checks and dashboards.
  - Highlights: Helps keep probes, routers, and UIs configuration-light by generating up-to-date service metadata.

- JupyterHub customizations (Python config)
  - Purpose: Repository-local configuration for JupyterHub to integrate with the platform’s SSO/TLS and routing conventions.
  - Role in stack: Provides a secured, SSO-gated notebook environment aligned with the same Caddy/Authelia front door as other apps.
  - Highlights: See src/jupyterhub/jupyterhub_config.py for details.

Where to learn more about each program
-------------------------------------

- vllm-router has a focused README with usage examples: src/vllm-router/README.md
- The docker-compose.yml and docs/APP_CATALOG.md show how each service is built, wired, and exposed.
- The autonomous diagnostics guide (docs/AUTONOMOUS_DIAGNOSTICS.md) explains how probe-orchestrator, Playwright, and local LLMs work together.
