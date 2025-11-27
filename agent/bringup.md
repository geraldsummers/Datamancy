# Datamancy Bring-up Plan (Bootstrap Mode)

Use this outline inside Open WebUI (http://localhost:8080) to guide the initial configuration, then pivot to the full stack.

Objectives:
- Collect required config values from the user
- Write .env for the full docker-compose.yml
- Validate prerequisites (DNS/TLS reachability)
- Pivot to the full stack and verify health

Tools available:
- Ask the user questions directly in chat for missing values
- For privileged host actions, output exact sudo commands for the user to run
- LiteLLM endpoint is at http://localhost:4000 with OPENAI_API_KEY = LITELLM_MASTER_KEY from .env.bootstrap

Steps:
1) Collect base variables:
   - DOMAIN (e.g., example.com)
   - MAIL_DOMAIN (e.g., mail.example.com or example.com)
   - Admin emails and passwords
   - OAuth client secrets for services: Authelia, Open WebUI, Outline, Grafana, Vaultwarden, Planka, JupyterHub, etc.
2) Prepare a proposed /opt/datamancy/.env (or repo root .env) content and show it to the user for confirmation. Warn not to paste secrets back publicly.
3) Create the .env file on disk with 0600 permissions, owned by the operator. Include randomly generated secrets if missing.
4) DNS checks:
   - Ensure grafana.${DOMAIN}, open-webui.${DOMAIN}, auth.${DOMAIN}, etc. resolve to this host
   - If not, instruct the user to update DNS and wait for propagation
5) Pivot:
   - Ask the user to run: ./scripts/bootstrap-stack.sh switch-to-full
6) Health validation:
   - Caddy: https://<host>/ → 200 or redirect to a site
   - Authelia: https://auth.${DOMAIN}/api/health → 200
   - Grafana: https://grafana.${DOMAIN} responds
   - Open WebUI: https://open-webui.${DOMAIN} responds
   - LiteLLM: https://litellm.${DOMAIN}/health responds
7) Summarize results and next steps.

Notes:
- Bootstrap is HTTP-only and local; do not expose it to the internet.
- Only pivot once DNS/TLS are ready.
