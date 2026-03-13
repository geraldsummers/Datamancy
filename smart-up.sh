#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY_JSON="${REGISTRY_JSON:-$ROOT_DIR/test-registry.json}"
STATUS_JSON="${STATUS_JSON:-$ROOT_DIR/build-status.json}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
DRY_RUN="${DRY_RUN:-0}"
READY_CHECK_PATTERN="${READY_CHECK_PATTERN:-(postgres|caddy|vllm-7b|litellm|planka|mailserver)}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${GREEN}[INFO]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

if [ ! -f "$REGISTRY_JSON" ]; then
    error "Missing registry: $REGISTRY_JSON"
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    error "python3 is required for smart-up"
    exit 1
fi

if ! docker compose -f "$COMPOSE_FILE" config --services >/dev/null 2>&1; then
    error "Failed to load docker compose file: $COMPOSE_FILE"
    exit 1
fi

SERVICES_LIST="$(docker compose -f "$COMPOSE_FILE" config --services)"

tmp_plan="$(mktemp)"
tmp_updated="$(mktemp)"
tmp_ready="$(mktemp)"

wait_for_service_ready() {
    local service="$1"
    local timeout="${2:-300}"
    local elapsed=0
    local interval=5

    while [ "$elapsed" -lt "$timeout" ]; do
        local state
        local health

        state="$(docker inspect -f '{{.State.Status}}' "$service" 2>/dev/null || true)"
        health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$service" 2>/dev/null || true)"

        if [ "$state" = "running" ]; then
            if [ -z "$health" ] || [ "$health" = "healthy" ]; then
                return 0
            fi
        fi

        sleep "$interval"
        elapsed=$((elapsed + interval))
    done

    return 1
}

python3 - <<'PY' "$REGISTRY_JSON" "$STATUS_JSON" "$SERVICES_LIST" "$tmp_plan"
import json
import sys

reg_path, status_path, services_text, out_path = sys.argv[1:5]
services = {s.strip() for s in services_text.splitlines() if s.strip()}

with open(reg_path, "r", encoding="utf-8") as f:
    registry = json.load(f)

status = {"components": {}}
try:
    with open(status_path, "r", encoding="utf-8") as f:
        status = json.load(f)
except FileNotFoundError:
    pass

status_components = status.get("components", {})

lines = []
for name, comp in registry.get("components", {}).items():
    if name not in services:
        continue
    last_changed = comp.get("last_changed_commit") or ""
    last_built = (status_components.get(name) or {}).get("last_built_commit") or ""
    if last_changed and last_changed != last_built:
        source_paths = comp.get("source_paths") or []
        needs_build = any(path.startswith("stack.containers/") for path in source_paths)
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}|{last_changed}")

with open(out_path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
PY

if [ ! -s "$tmp_plan" ]; then
    info "All services are up to date"
    rm -f "$tmp_plan" "$tmp_updated"
    exit 0
fi

while IFS='|' read -r service build_flag last_changed; do
    if [ -z "$service" ]; then
        continue
    fi
    if ! echo "$SERVICES_LIST" | grep -qx "$service"; then
        warn "Service '$service' not in compose config, skipping"
        continue
    fi
    info "Updating $service (changed: $last_changed)"
    if [ "$build_flag" = "build" ]; then
        if [ "$DRY_RUN" = "1" ]; then
            echo "docker compose -f \"$COMPOSE_FILE\" build \"$service\""
        else
            docker compose -f "$COMPOSE_FILE" build "$service"
        fi
    fi
    if [ "$DRY_RUN" = "1" ]; then
        echo "docker compose -f \"$COMPOSE_FILE\" up -d --no-deps --force-recreate \"$service\""
    else
        docker compose -f "$COMPOSE_FILE" up -d --no-deps --force-recreate "$service"
    fi
    echo "$service" >> "$tmp_updated"
done < "$tmp_plan"

failed_services=()
if [ -s "$tmp_updated" ] && [ "$DRY_RUN" != "1" ]; then
    while IFS= read -r service; do
        [ -z "$service" ] && continue
        if ! echo "$service" | grep -Eq "$READY_CHECK_PATTERN"; then
            continue
        fi

        if wait_for_service_ready "$service" 180; then
            info "Service ready: $service"
            echo "$service" >> "$tmp_ready"
            continue
        fi

        warn "Service not ready after initial wait: $service (retrying once)"
        docker compose -f "$COMPOSE_FILE" restart "$service" >/dev/null

        if wait_for_service_ready "$service" 180; then
            info "Service recovered after restart: $service"
            echo "$service" >> "$tmp_ready"
        else
            error "Service failed readiness check: $service"
            failed_services+=("$service")
        fi
    done < "$tmp_updated"
fi

if [ -s "$tmp_ready" ] && [ "$DRY_RUN" != "1" ]; then
    python3 - <<'PY' "$REGISTRY_JSON" "$STATUS_JSON" "$tmp_ready"
import json
import sys
from datetime import datetime, timezone

reg_path, status_path, updated_path = sys.argv[1:4]
with open(reg_path, "r", encoding="utf-8") as f:
    registry = json.load(f)

try:
    with open(status_path, "r", encoding="utf-8") as f:
        status = json.load(f)
except FileNotFoundError:
    status = {"components": {}}

status.setdefault("components", {})

with open(updated_path, "r", encoding="utf-8") as f:
    updated = {line.strip() for line in f if line.strip()}

now = datetime.now(timezone.utc).isoformat()
for name in updated:
    comp = registry.get("components", {}).get(name, {})
    last_changed = comp.get("last_changed_commit") or ""
    status["components"][name] = {
        "last_built_commit": last_changed,
        "last_built_at": now,
    }

with open(status_path, "w", encoding="utf-8") as f:
    json.dump(status, f, indent=2, sort_keys=True)
PY
    info "Updated build status: $STATUS_JSON"
fi

rm -f "$tmp_plan" "$tmp_updated" "$tmp_ready"

if [ "${#failed_services[@]}" -gt 0 ]; then
    error "Unhealthy services after update: ${failed_services[*]}"
    exit 1
fi
