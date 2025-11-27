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
   docker compose --env-file .env.bootstrap --profile bootstrap up -d

2) For the vector profile:
   docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs pull
   docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d

Troubleshooting
---------------

- Verify DNS and TLS: check Caddy logs for certificate messages
- Verify SSO: https://auth.${DOMAIN}/api/health should return 200
- LocalAI readiness: curl http://localai:8080/readyz from a container on backend network

See also
--------

- docs/BOOTSTRAP.md for deployment and readiness
- docs/SECURITY.md for SSO/TLS/secrets guidance
- docs/DATA_AND_RAG.md for vector pipeline operations
