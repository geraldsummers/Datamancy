#!/usr/bin/env bash
set -euo pipefail

# Bring the stack up in rootless/dev HTTP-only mode and collect quick diagnostics
# Usage: ./scripts/dev-rootless-up.sh

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

OVERRIDE="docker-compose.rootless.override.yml"
ENV_FILE=".env.rootless"

if [[ ! -f "$OVERRIDE" ]]; then
  echo "Missing $OVERRIDE. Aborting." >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Aborting." >&2
  exit 1
fi

# Load env so DOMAIN and others are available to this script
set -a
source "$ENV_FILE"
set +a

# Compose project name to avoid clashing with prod
export COMPOSE_PROJECT_NAME="datamancy_dev"

# Pull images (best-effort)
if command -v docker >/dev/null 2>&1; then
  echo "Pulling images (best effort)..."
  docker compose -f docker-compose.yml -f "$OVERRIDE" --env-file "$ENV_FILE" pull || true
else
  echo "Docker CLI not found. Please install Docker (rootless) first." >&2
  exit 1
fi

# Bring up core services first to surface fewer cascading errors
# You can adjust this list as needed
CORE_SERVICES=(caddy authelia postgres redis grafana open-webui planka outline litellm homepage)

echo "Starting core services: ${CORE_SERVICES[*]}"
docker compose -f docker-compose.yml -f "$OVERRIDE" --env-file "$ENV_FILE" up -d "${CORE_SERVICES[@]}" || true

# Then try the rest (best-effort)
echo "Starting remaining services (best-effort)..."
docker compose -f docker-compose.yml -f "$OVERRIDE" --env-file "$ENV_FILE" up -d || true

# Show status
echo
echo "==== docker compose ps ===="
docker compose -f docker-compose.yml -f "$OVERRIDE" --env-file "$ENV_FILE" ps

# Collect short logs for failing containers without relying on json output
echo
echo "==== collecting first 100 log lines for unhealthy/exited containers ===="
mapfile -t ALL_IDS < <(docker compose -f docker-compose.yml -f "$OVERRIDE" --env-file "$ENV_FILE" ps -q || true)
BAD_IDS=()
if [[ ${#ALL_IDS[@]} -gt 0 ]]; then
  for id in "${ALL_IDS[@]}"; do
    st=$(docker inspect -f '{{.State.Status}}' "$id" 2>/dev/null || echo "unknown")
    if [[ "$st" != "running" ]]; then
      BAD_IDS+=("$id")
    fi
  done
fi

if [[ ${#BAD_IDS[@]} -eq 0 ]]; then
  echo "All containers are running."
else
  for id in "${BAD_IDS[@]}"; do
    name=$(docker inspect -f '{{.Name}}' "$id" | sed 's#^/##')
    echo
    echo "---- logs: $name ----"
    docker logs --tail 100 "$id" || true
  done
fi

echo
DOM=${DOMAIN:-project-saturn.com}
echo "Done. Visit http://homepage.${DOM}:8080 (add to /etc/hosts if needed)."
