# Bootstrap Deployment and Readiness

This is the canonical bootstrap guide. It consolidates the prior BOOTSTRAP_READY.md (checklist) and BOOTSTRAP_DEPLOY.md (step‑by‑step) into a single source of truth.

Overview
--------
The bootstrap profile brings up a minimal, production‑lean core:
- Caddy (reverse proxy, TLS) + Authelia (SSO) + LDAP/Redis (directory/sessions)
- LocalAI (local models) + LiteLLM (OpenAI‑compatible gateway)
- Open WebUI (chat UI)
- KFuncDB (function/tool host)
- LAM (LDAP Account Manager) and Portainer (+ agent)
- Optional e2e test runner for basic smoke checks

Quickstart (local, HTTP)
------------------------
1) bash scripts/bootstrap-stack.sh init
2) bash scripts/bootstrap-stack.sh up-bootstrap
3) Open WebUI at http://localhost:8080

Production (TLS/SSO)
--------------------
Prereqs:
- DNS: A records for subdomains (e.g., auth.${DOMAIN}, open-webui.${DOMAIN}, litellm.${DOMAIN}, etc.)
- Open ports 80/443; Docker 24+; 16GB RAM recommended

Steps:
1) Create/verify .env.bootstrap (scripts/bootstrap-stack.sh init)
2) Verify DOMAIN and secrets in .env.bootstrap
3) Start: bash scripts/bootstrap-stack.sh up-bootstrap
4) Monitor: bash scripts/bootstrap-stack.sh status; docker logs caddy|authelia|localai -f
5) Wait 3–5 minutes for initial model downloads

Readiness Checklist
-------------------
- DNS resolves for required subdomains
- HTTPS is issued by Let’s Encrypt (check caddy logs for “certificate obtained”)
- Authelia health: https://auth.${DOMAIN}/api/health → 200
- Open WebUI responds behind SSO: https://open-webui.${DOMAIN}
- LiteLLM health: https://litellm.${DOMAIN}/health
- LocalAI readiness: http://localai:8080/readyz (internal) or via Caddy if exposed
- Portainer and LAM accessible behind SSO

Profiles and next steps
-----------------------
- Vectors/RAG: enable profile bootstrap_vector_dbs (Qdrant, ClickHouse, Benthos). See docs/DATA_AND_RAG.md
- Full stack: prepare a complete .env, then run scripts/bootstrap-stack.sh switch-to-full

Operations
----------
- CLI helper: scripts/bootstrap-stack.sh (status, up/down flows)
- Logs: docker logs <service> -f
- Health checks: most services have built‑in HEALTHCHECK or /health endpoints

Security Notes
--------------
- All UIs are fronted by Caddy with Authelia forward_auth
- Rotate secrets in .env.bootstrap/.env for production
- KFuncDB capabilities are explicitly gated via KFUNCDB_ALLOW_CAPS

References
----------
- docs/OPERATIONS.md for log/backup procedures
- docs/SECURITY.md for SSO/TLS/secrets guidance
- docs/APP_CATALOG.md for the full apps layer and URLs
