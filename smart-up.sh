#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY_JSON="${REGISTRY_JSON:-$ROOT_DIR/test-registry.json}"
STATUS_JSON="${STATUS_JSON:-$ROOT_DIR/build-status.json}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
DRY_RUN="${DRY_RUN:-0}"
FORCE_REFRESH_SERVICES="${FORCE_REFRESH_SERVICES:-ldap-ensure-suffixes,test-all,test-playwright-e2e}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${GREEN}[INFO]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

prepare_shadow_accounts_dir() {
    local env_file="${ROOT_DIR}/.env"
    [ -f "$env_file" ] || return 0

    local shadow_dir
    shadow_dir="$(python3 - "$env_file" <<'PY'
import os
import sys

env_file = sys.argv[1]
values = {}

with open(env_file, "r", encoding="utf-8") as handle:
    for raw in handle:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")

shadow_dir = values.get("SHADOW_ACCOUNTS_HOST_DIR", "").strip()
if shadow_dir == "~":
    shadow_dir = os.path.expanduser("~")
elif shadow_dir.startswith("~/"):
    shadow_dir = os.path.join(os.path.expanduser("~"), shadow_dir[2:])

if shadow_dir:
    print(shadow_dir)
PY
)"

    if [ -n "${shadow_dir:-}" ]; then
        mkdir -p "$shadow_dir"
        chmod 700 "$shadow_dir" 2>/dev/null || true
        info "Prepared shadow account secrets dir: $shadow_dir"
    fi
}

prepare_test_results_dir() {
    local service="$1"

    case "$service" in
        test-*)
            mkdir -p "$ROOT_DIR/test-results" "$ROOT_DIR/test-results/${service#test-}"
            ;;
    esac
}

if [ ! -f "$REGISTRY_JSON" ]; then
    error "Missing registry: $REGISTRY_JSON"
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    error "python3 is required for smart-up"
    exit 1
fi

prepare_shadow_accounts_dir

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
    service_def = compose_services.get(name) or {}
    needs_build = bool(service_def.get("build"))
    build_key = str(service_def.get("image") or name)
    if last_changed and last_changed != last_built:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}|{last_changed}|changed|{build_key}")
        planned.add(name)
    elif missing_container:
        lines.append(f"{name}|no-build|{last_changed}|missing|")
        planned.add(name)
    elif stopped_container:
        lines.append(f"{name}|no-build|{last_changed}|stopped|")
        planned.add(name)

for name in sorted(force_refresh_services):
    if name not in services or name in planned:
        continue
    service_def = compose_services.get(name) or {}
    restart_policy = str(service_def.get("restart") or "").strip().lower()
    one_shot_service = restart_policy == "no"
    needs_build = bool(service_def.get("build"))
    build_key = str(service_def.get("image") or name)
    if one_shot_service:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}||force-one-shot|{build_key}")
    elif name not in existing_services:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}||missing|{build_key}")
    elif name not in running_services:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}||stopped|{build_key}")
    else:
        lines.append(f"{name}|{'build' if needs_build else 'no-build'}||refresh|{build_key}")

with open(out_path, "w", encoding="utf-8") as f:
    if lines:
        f.write("\n".join(lines) + "\n")
PY

if [ ! -s "$tmp_plan" ]; then
    info "All services are up to date and tracked containers are already running"
    rm -f "$tmp_plan" "$tmp_updated" "$COMPOSE_CONFIG_JSON"
    exit 0
fi

declare -A built_keys=()

while IFS='|' read -r service build_flag last_changed reason build_key; do
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
    elif [ "$reason" = "force-one-shot" ]; then
        info "Refreshing one-shot reconciler $service"
    elif [ "$reason" = "refresh" ]; then
        info "Refreshing $service"
    else
        info "Updating $service (changed: $last_changed)"
    fi
    if [ "$build_flag" = "build" ]; then
        if [ -n "${build_key:-}" ] && [ -n "${built_keys[$build_key]+x}" ]; then
            info "Skipping duplicate build for $service (already built image key: $build_key)"
        else
            if [ "$DRY_RUN" = "1" ]; then
                echo "docker compose -f \"$COMPOSE_FILE\" build \"$service\""
            else
                docker compose -f "$COMPOSE_FILE" build "$service"
            fi
            if [ -n "${build_key:-}" ]; then
                built_keys["$build_key"]=1
            fi
        fi
    fi
    prepare_test_results_dir "$service"
    up_build_arg=""
    if [ "$build_flag" = "build" ]; then
        up_build_arg="--no-build"
    fi
    if [ "$reason" = "stopped" ]; then
        if [ "$DRY_RUN" = "1" ]; then
            echo "docker compose -f \"$COMPOSE_FILE\" up -d --no-deps ${up_build_arg} \"$service\""
        else
            docker compose -f "$COMPOSE_FILE" up -d --no-deps ${up_build_arg} "$service"
        fi
    else
        if [ "$DRY_RUN" = "1" ]; then
            echo "docker compose -f \"$COMPOSE_FILE\" up -d --no-deps --force-recreate ${up_build_arg} \"$service\""
        else
            docker compose -f "$COMPOSE_FILE" up -d --no-deps --force-recreate ${up_build_arg} "$service"
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
