# Operations Guide

This page captures routine operational tasks: start/stop flows, logs, health checks, backups, and updates.

CLI helper
---------

Use scripts/bootstrap-stack.sh for common flows:

- init: Create .env.bootstrap if missing
- up-bootstrap: Start the bootstrap profile
- down-bootstrap: Stop and remove only bootstrap services (volumes preserved)
- bootstrap-vectors: Bring up Qdrant and initialize collections
- up-benthos: Start Qdrant + ClickHouse + Benthos
- switch-to-full: Stop bootstrap and start the full stack (requires .env)
- status: Show container status summary

Secrets manager
---------------

- Initialize secrets (one-time):
  docker compose -f docker-compose.secrets.yml --profile bootstrap run --rm secrets-manager

- Start stack with secrets loaded:
  docker compose -f docker-compose.secrets.yml -f docker-compose.yml --profile bootstrap up -d

- Export runtime env (only when necessary; avoid storing secrets in plaintext):
  docker compose -f docker-compose.secrets.yml run --rm secrets-exporter
  # Output will be written to ./.env.runtime (load with: export $(cat .env.runtime | xargs))

Logs and health
---------------

- Status: bash scripts/bootstrap-stack.sh status
- Tail logs: docker logs <service> -f
- Health endpoints: many services expose /health or have Docker HEALTHCHECKs

Backups (examples)
------------------

- Authelia DB: cp volumes/authelia/db.sqlite3 backup-authelia.db
- Open WebUI data:
  docker run --rm -v datamancy_open_webui_data:/data -v $(pwd):/backup \
    alpine tar czf /backup/openwebui-backup.tar.gz /data
- Qdrant storage: snapshot qdrant_data volume
- ClickHouse data: snapshot clickhouse_data volume

Updates
-------

1) Pull images and restart relevant services:
   docker compose --env-file .env.bootstrap --profile bootstrap pull
   docker compose -f docker-compose.secrets.yml -f docker-compose.yml --env-file .env.bootstrap --profile bootstrap up -d

2) For the vector profile:
   docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs pull
   docker compose -f docker-compose.secrets.yml -f docker-compose.yml --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d

Troubleshooting
---------------

- Verify DNS and TLS: check Caddy logs for certificate messages
- Verify SSO: https://auth.${DOMAIN}/api/health should return 200
- vLLM health: curl http://vllm:8000/health from a container on the backend network
- Dev/test TLS: Caddy uses local certificates (local_certs). For production, ensure ACME/Let’s Encrypt is configured in the Caddyfile and ports 80/443 are reachable.

See also
--------

- docs/BOOTSTRAP.md for deployment and readiness
- docs/SECURITY.md for SSO/TLS/secrets guidance
- docs/DATA_AND_RAG.md for vector pipeline operations
