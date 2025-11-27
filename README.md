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
2) From repo root:
   - bash scripts/bootstrap-stack.sh init
   - bash scripts/bootstrap-stack.sh up-bootstrap
3) Open WebUI: http://localhost:8080 (bootstrap)
4) See docs/BOOTSTRAP.md for production/TLS and readiness.

Profiles
--------

- bootstrap: Core AI and supporting services (Caddy, Authelia, LDAP/Redis, LocalAI, LiteLLM, Open WebUI, KFuncDB, Portainer, LAM, test runner).
- bootstrap_vector_dbs: Qdrant, ClickHouse, and Benthos for vector/RAG pipelines.
- full: Everything else as defined in docker-compose.yml (requires a complete .env).

See docs/APP_CATALOG.md for which services belong to which profile and how they’re exposed.

CLI helper
----------

Use scripts/bootstrap-stack.sh for common flows. The script is self‑documenting and referenced from docs/OPERATIONS.md.

Where to look next
------------------

- Components and topology: docs/ARCHITECTURE.md
- Ingest data and run vector search: docs/DATA_AND_RAG.md
- Production with TLS/SSO: docs/BOOTSTRAP.md
- All apps and URLs: docs/APP_CATALOG.md
