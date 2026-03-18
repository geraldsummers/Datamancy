#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY_JSON="${REGISTRY_JSON:-$ROOT_DIR/test-registry.json}"
STATUS_JSON="${STATUS_JSON:-$ROOT_DIR/build-status.json}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
DRY_RUN="${DRY_RUN:-0}"
FORCE_REFRESH_SERVICES="${FORCE_REFRESH_SERVICES:-test-all,test-playwright-e2e}"

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
EXISTING_SERVICES="$(docker compose -f "$COMPOSE_FILE" ps -a --services 2>/dev/null || true)"
RUNNING_SERVICES="$(docker compose -f "$COMPOSE_FILE" ps --services --status running 2>/dev/null || true)"
COMPOSE_CONFIG_JSON="$(mktemp)"
docker compose -f "$COMPOSE_FILE" config --format json > "$COMPOSE_CONFIG_JSON"

tmp_plan="$(mktemp)"
tmp_updated="$(mktemp)"

python3 - <<'PY' "$REGISTRY_JSON" "$STATUS_JSON" "$SERVICES_LIST" "$EXISTING_SERVICES" "$RUNNING_SERVICES" "$COMPOSE_CONFIG_JSON" "$FORCE_REFRESH_SERVICES" "$tmp_plan"
import json
import sys

(
    reg_path,
    status_path,
    services_text,
    existing_services_text,
    running_services_text,
    compose_json_path,
    force_refresh_services_text,
    out_path,
) = sys.argv[1:9]
services = {s.strip() for s in services_text.splitlines() if s.strip()}
existing_services = {s.strip() for s in existing_services_text.splitlines() if s.strip()}
running_services = {s.strip() for s in running_services_text.splitlines() if s.strip()}
force_refresh_services = {
    s.strip()
    for s in force_refresh_services_text.split(",")
    if s.strip()
}

with open(reg_path, "r", encoding="utf-8") as f:
    registry = json.load(f)
with open(compose_json_path, "r", encoding="utf-8") as f:
    compose_config = json.load(f)

status = {"components": {}}
try:
    with open(status_path, "r", encoding="utf-8") as f:
        status = json.load(f)
except FileNotFoundError:
    pass

status_components = status.get("components", {})
compose_services = compose_config.get("services", {})

lines = []
planned = set()
for name, comp in registry.get("components", {}).items():
    if name not in services:
        continue
    last_changed = comp.get("last_changed_commit") or ""
    last_built = (status_components.get(name) or {}).get("last_built_commit") or ""
    missing_container = name not in existing_services
    restart_policy = str((compose_services.get(name) or {}).get("restart") or "").strip().lower()
    one_shot_service = restart_policy == "no"
    stopped_container = (
        name in existing_services and
        name not in running_services and
        not one_shot_service
    )
    needs_build = bool((compose_services.get(name) or {}).get("build"))
    if last_changed and last_changed != last_built:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}|{last_changed}|changed")
        planned.add(name)
    elif missing_container:
        lines.append(f"{name}|no-build|{last_changed}|missing")
        planned.add(name)
    elif stopped_container:
        lines.append(f"{name}|no-build|{last_changed}|stopped")
        planned.add(name)

for name in sorted(force_refresh_services):
    if name not in services or name in planned:
        continue
    service_def = compose_services.get(name) or {}
    restart_policy = str(service_def.get("restart") or "").strip().lower()
    if restart_policy == "no":
        continue
    needs_build = bool(service_def.get("build"))
    if name not in existing_services:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}||missing")
    elif name not in running_services:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}||stopped")
    else:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}||refresh")

with open(out_path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
PY

if [ ! -s "$tmp_plan" ]; then
    info "All services are up to date and tracked containers are already running"
    rm -f "$tmp_plan" "$tmp_updated" "$COMPOSE_CONFIG_JSON"
    exit 0
fi

while IFS='|' read -r service build_flag last_changed reason; do
    if [ -z "$service" ]; then
        continue
    fi
    if ! echo "$SERVICES_LIST" | grep -qx "$service"; then
        warn "Service '$service' not in compose config, skipping"
        continue
    fi
    if [ "$reason" = "missing" ]; then
        info "Deploying missing container for $service"
    elif [ "$reason" = "stopped" ]; then
        info "Recovering stopped container for $service"
    elif [ "$reason" = "refresh" ]; then
        info "Refreshing $service"
    else
        info "Updating $service (changed: $last_changed)"
    fi
    if [ "$build_flag" = "build" ]; then
        if [ "$DRY_RUN" = "1" ]; then
            echo "docker compose -f \"$COMPOSE_FILE\" build \"$service\""
        else
            docker compose -f "$COMPOSE_FILE" build "$service"
        fi
    fi
    if [ "$reason" = "stopped" ]; then
        if [ "$DRY_RUN" = "1" ]; then
            echo "docker compose -f \"$COMPOSE_FILE\" up -d --no-deps \"$service\""
        else
            docker compose -f "$COMPOSE_FILE" up -d --no-deps "$service"
        fi
    else
        if [ "$DRY_RUN" = "1" ]; then
            echo "docker compose -f \"$COMPOSE_FILE\" up -d --no-deps --force-recreate \"$service\""
        else
            docker compose -f "$COMPOSE_FILE" up -d --no-deps --force-recreate "$service"
        fi
    fi
    if [ "$reason" = "changed" ]; then
        echo "$service" >> "$tmp_updated"
    fi
done < "$tmp_plan"

if [ -s "$tmp_updated" ] && [ "$DRY_RUN" != "1" ]; then
    python3 - <<'PY' "$REGISTRY_JSON" "$STATUS_JSON" "$tmp_updated"
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

rm -f "$tmp_plan" "$tmp_updated" "$COMPOSE_CONFIG_JSON"
