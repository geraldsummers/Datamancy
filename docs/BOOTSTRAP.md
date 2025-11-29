# Bootstrap Deployment and Readiness

This is the canonical bootstrap guide. It consolidates the prior BOOTSTRAP_READY.md (checklist) and BOOTSTRAP_DEPLOY.md (step‑by‑step) into a single source of truth.

Overview
--------
The bootstrap profile brings up a minimal, production‑lean core. It is the first slice of the same production stack and uses the same configuration patterns (auth, TLS, policies). There are no separate or special “bootstrap configs” — bootstrap simply starts a smaller subset of services first:
- Caddy (reverse proxy, TLS) + Authelia (SSO) + LDAP/Redis (directory/sessions)
- LocalAI (local models) + LiteLLM (OpenAI‑compatible gateway)
- Open WebUI (chat UI)
- KFuncDB (function/tool host)
- LAM (LDAP Account Manager) and Portainer (+ agent)
- Optional e2e test runner for basic smoke checks

Quickstart (with SSO)
---------------------
Important: Bootstrap mode enforces complete Authelia coverage. All UIs are served via Caddy and gated by Authelia SSO. Direct container ports are not published. In dev/test, Caddy uses local certificates (local_certs). For production, configure public DNS and ACME.

Prereqs (dev/test):
- Docker 24+
- No public DNS is required for dev/test (Caddy uses local certificates)

Prereqs (production):
- A real DOMAIN with DNS A records for required subdomains (e.g., auth.${DOMAIN}, open-webui.${DOMAIN}, litellm.${DOMAIN}, etc.)
- Ports 80/443 reachable from the internet for certificate issuance (Let’s Encrypt)

Steps (first time):
1) Initialize non‑sensitive config: bash scripts/bootstrap-stack.sh init
2) Initialize secrets store (one‑time):
   docker compose -f docker-compose.secrets.yml --profile bootstrap run --rm secrets-manager
3) Start bootstrap stack (uses secrets automatically):
   docker compose -f docker-compose.secrets.yml -f docker-compose.yml --profile bootstrap up -d
4) Sign in at: https://open-webui.${DOMAIN} (you will be redirected to https://auth.${DOMAIN} for SSO)

Production (TLS/SSO)
--------------------
Prereqs:
- DNS: A records for subdomains (e.g., auth.${DOMAIN}, open-webui.${DOMAIN}, litellm.${DOMAIN}, etc.)
- Open ports 80/443; Docker 24+; 16GB RAM recommended

Steps:
1) Create/verify .env.bootstrap (scripts/bootstrap-stack.sh init)
2) Initialize secrets (one‑time):
   docker compose -f docker-compose.secrets.yml --profile bootstrap run --rm secrets-manager
3) Configure Caddy for public ACME (remove local_certs from the global block in configs/infrastructure/caddy/Caddyfile and set email if desired)
4) Start:
   docker compose -f docker-compose.secrets.yml -f docker-compose.yml --profile bootstrap up -d
5) Monitor: bash scripts/bootstrap-stack.sh status; docker logs caddy|authelia|localai -f
6) Wait 3–5 minutes for initial model downloads

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
- Full stack: prepare a complete .env, then run scripts/bootstrap-stack.sh switch-to-full. This command starts additional applications, but continues to use the same production SSO/TLS approach — there is no distinct “bootstrap Authelia config.” Apps like Grafana/Outline/Planka/JupyterHub authenticate via the same OIDC provider configuration already present.

Operations
----------
- CLI helper: scripts/bootstrap-stack.sh (status, up/down flows)
- Logs: docker logs <service> -f
- Health checks: most services have built‑in HEALTHCHECK or /health endpoints

Security Notes
--------------
- Bootstrap and full modes both require SSO coverage for every UI. All UIs are fronted by Caddy with Authelia forward_auth; direct ports are not exposed.
- There is a single configuration approach for auth/TLS across modes. Avoid creating mode‑specific config files; bootstrap is only about startup order/minimal footprint, not different settings.
- Rotate secrets in .env.bootstrap/.env before production.
- KFuncDB capabilities are explicitly gated via KFUNCDB_ALLOW_CAPS.
- If your DOMAIN is not project-saturn.com, update the Authelia configuration for cookie domains, ACLs, and redirect URIs to match your domain, or set the equivalent AUTHELIA_* environment overrides. This applies equally in bootstrap and full modes (there isn’t a separate bootstrap config).
 - In dev/test, Caddy uses local certificates via local_certs; for production, configure ACME/Let’s Encrypt in the Caddyfile.

References
----------
- docs/OPERATIONS.md for log/backup procedures
- docs/SECURITY.md for SSO/TLS/secrets guidance
- docs/APP_CATALOG.md for the full apps layer and URLs
