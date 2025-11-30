# App Catalog

This catalog summarizes the services defined in docker-compose.yml. For each app we list purpose, profiles, URL pattern, SSO, key dependencies, persistent volumes, and healthcheck notes.

Core (bootstrap profile)
------------------------

- caddy
  - Purpose: Reverse proxy and TLS via Caddy (configured via a static Caddyfile)
  - Profile: bootstrap
  - URL: https://<service>.${DOMAIN}
  - SSO: Enforces Authelia forward_auth via per‑vhost directives in the Caddyfile
  - Volumes: caddy_data, caddy_config
  - Health: GET http://localhost:80/

- authelia
  - Purpose: SSO provider and access policy engine
  - Profile: bootstrap
  - URL: https://auth.${DOMAIN}
  - SSO: N/A (it is the SSO service)
  - Deps: ldap, redis
  - Volumes: authelia configuration/DB (see volumes/authelia)
  - Health: /api/health

- ldap (OpenLDAP)
  - Purpose: Directory for users and groups
  - Profile: bootstrap
  - Deps: volumes ldap_data, ldap_config

- redis
  - Purpose: Session store for Authelia
  - Profile: bootstrap
  - Volumes: redis_data

- vllm
  - Purpose: Primary LLM server (chat/completions)
  - Profile: bootstrap
  - URL: API base http://vllm:8000/v1 (internal)
  - Volumes: hf-cache under ${VOLUMES_ROOT}/vllm
  - Health: GET http://vllm:8000/health

- litellm
  - Purpose: OpenAI‑compatible gateway proxying to vLLM (and others if configured)
  - Profile: bootstrap
  - URL: https://litellm.${DOMAIN}
  - Env: LITELLM_MASTER_KEY
  - Notes: Machine‑to‑machine access is available at https://api.litellm.${DOMAIN} (no SSO; IP allowlisted in Caddy via API_LITELLM_ALLOWLIST)

- open-webui
  - Purpose: Chat UI
  - Profile: bootstrap
  - URL: https://open-webui.${DOMAIN}
  - SSO: via Caddy forward_auth (Authelia)
  - Volumes: open_webui_data

Note: Embeddings and Chat API are provided via LiteLLM (OpenAI-compatible) backed by vLLM by default.

- kfuncdb
  - Purpose: Function/tool host with capability policy
  - Profile: bootstrap
  - URL: https://kfuncdb.${DOMAIN}
  - Env: KFUNCDB_ALLOW_CAPS
  - Health: /healthz

- ldap-account-manager (LAM)
  - Purpose: Web UI for LDAP
  - Profile: bootstrap
  - URL: https://lam.${DOMAIN}

- portainer (+ portainer-agent)
  - Purpose: Container management
  - Profile: bootstrap
  - URL: https://portainer.${DOMAIN}
  - Volumes: portainer_data

- test-runner
  - Purpose: Smoke tests (e.g., screenshot loop and vision checks)
  - Profile: bootstrap

Vector/RAG (bootstrap_vector_dbs profile)
-----------------------------------------

- qdrant
  - Purpose: Vector database
  - Profile: bootstrap_vector_dbs
  - URL: http://qdrant:6333 (internal), optional https://qdrant.${DOMAIN}
  - Volumes: qdrant_data
  - Health: /readyz

- clickhouse
  - Purpose: Columnar database for series/analytics
  - Profile: bootstrap_vector_dbs
  - URL: https://clickhouse.${DOMAIN} (HTTP API), native TCP 9000
  - Volumes: clickhouse_data
  - Health: HTTP /ping

- benthos (and specialized pipelines)
  - Purpose: Ingestion/ETL and embedding pipelines
  - Profile: bootstrap_vector_dbs
  - URL: http://benthos:4195/benthos (internal)

- vector-bootstrap
  - Purpose: One‑shot collection initialization from configs/vectors/collections.yaml
  - Profile: bootstrap_vector_dbs

Additional applications (full stack)
-----------------------------------

These services are present in docker-compose.yml outside the bootstrap profiles. See the Caddyfile vhosts for URL patterns and SSO status. Many depend on Postgres, MariaDB/MySQL, or Redis and mount their own persistent volumes.

- grafana — dashboards/observability; SSO via Authelia; volume: grafana_data
- homepage — service dashboard/launcher; config in configs/homepage/*
- vaultwarden — password manager; SSO fronted; volume: vaultwarden_data
- planka — kanban/project management; depends on Postgres; volume: planka_data
- outline — team wiki; depends on Postgres; volume: outline_data
- jupyterhub — multi‑user notebooks; optional Docker proxy; volume: jupyterhub_data
- seafile — file sync/storage; depends on MySQL; volume: seafile_data (+ seafile_mysql_data)
- onlyoffice — document editing; volume: onlyoffice_data
- synapse (matrix) — chat server; depends on Postgres/Redis; volume: synapse_data
- mastodon — social server; depends on Postgres/Redis; volume: mastodon_data
- homeassistant — home automation; depends on Postgres; volume: homeassistant_config
- sogo — groupware; mail integration; volume: sogo_data
- adminer / pgadmin — DB admin UIs
- mailu — mail stack; volumes: mailu_*
- browserless — headless browser API
- kopia — backup client/server; volumes: kopia_data, kopia_cache, kopia_repository
- postgres, mariadb — databases; volumes: postgres_data, mariadb_data
- couchdb — DB for specific apps; volume: couchdb_data

Notes
-----
- All UIs are exposed via Caddy and gated by Authelia SSO in both bootstrap and full modes; see configs/infrastructure/caddy/Caddyfile for per‑service vhost/SSO settings
- URL pattern is usually https://<service>.${DOMAIN}
- Health checks are defined per service via HEALTHCHECK or simple HTTP GETs
- For exact environment variables and dependencies, consult docker-compose.yml and the configs/* directory

Security-specific notes
-----------------------
- Seafile, OnlyOffice, and SOGo are now explicitly protected via Authelia forward_auth.
- Vector DB services (Qdrant 6333/6334 and ClickHouse TCP 9000) are not published to the host; access them via internal service DNS from other containers.
