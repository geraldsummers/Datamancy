# Architecture Overview

This page summarizes the core components, networks, and request paths in Datamancy.

Networks
--------
- frontend: Public‑facing ingress and any UIs that must be reachable via Caddy.
- backend: Internal service‑to‑service network (AI stack, app backends).
- database: Data services network (Qdrant, ClickHouse, Postgres, MariaDB, etc.).

Ingress and SSO
---------------
- Caddy watches Docker labels, provisions TLS (Let’s Encrypt), and reverse proxies
  requests like https://<service>.${DOMAIN} to the target container.
- Authelia adds SSO via Caddy forward_auth. Most UIs are gated behind Authelia.

AI Core
-------
- LocalAI: Runs local models (LLM, embeddings, vision, whisper) and exposes OpenAI‑style routes at http://localai:8080/v1.
- LiteLLM: Exposes an OpenAI‑compatible API and routes requests to LocalAI per configs/litellm/config*.yaml.
- Open WebUI: Chat interface that talks to LiteLLM (OpenAI API compatible).
- KFuncDB: Function/tool host with an explicit capability policy.

Data & Vector Stack (optional)
------------------------------
- Qdrant (vectors) and ClickHouse (series/analytics) are enabled via profile bootstrap_vector_dbs.
- Benthos runs ingestion and embedding pipelines to populate Qdrant and ClickHouse.

Apps Layer (full stack)
-----------------------
Multiple user‑facing and admin apps are defined in docker-compose.yml (Grafana, Vaultwarden, Outline, Planka, JupyterHub, Seafile, OnlyOffice, Synapse, Mastodon, Home Assistant, SOGo, Adminer/PGAdmin, Portainer, Mailu, Browserless, etc.).
See docs/APP_CATALOG.md for purposes, URLs, profiles, and dependencies.

Request flows (examples)
------------------------
- Chat: Browser → Caddy (TLS, SSO) → Open WebUI → LiteLLM → LocalAI → response
- Embedding ingest: Client → Benthos /ingest/text → LocalAI /v1/embeddings → Qdrant upsert
- Series ingest: Client → Benthos /ingest/series → ClickHouse insert

Next steps
----------
- For service details and URLs, see docs/APP_CATALOG.md
- For RAG pipelines, see docs/DATA_AND_RAG.md
- For operations, see docs/OPERATIONS.md
