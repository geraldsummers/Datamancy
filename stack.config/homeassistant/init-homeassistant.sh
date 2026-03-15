#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR="/config"
AUTH_FILE="${CONFIG_DIR}/.storage/auth"
ONBOARDING_FILE="${CONFIG_DIR}/.storage/onboarding"
CORE_FILE="${CONFIG_DIR}/.storage/core.config"

echo "Starting Home Assistant pre-init..."
mkdir -p "${CONFIG_DIR}/.storage"

has_real_user() {
    if [ ! -f "${AUTH_FILE}" ]; then
        return 1
    fi
    python3 - "$AUTH_FILE" <<'PY'
import json
import sys
path = sys.argv[1]
try:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
except Exception:
    print(0)
    raise SystemExit(0)

users = data.get("data", {}).get("users", [])
real_users = sum(1 for u in users if u.get("is_active") and not u.get("system_generated"))
print(real_users)
PY
}

create_admin_user() {
    local admin_username="${STACK_ADMIN_USER:-}"
    local admin_password="${STACK_ADMIN_PASSWORD:-}"

    if [ -z "${admin_username}" ] || [ -z "${admin_password}" ]; then
        echo "STACK_ADMIN_USER/STACK_ADMIN_PASSWORD missing; skipping user bootstrap"
        return
    fi

    local real_users
    real_users="$(has_real_user || true)"
    if [ "${real_users:-0}" -gt 0 ]; then
        echo "Real Home Assistant user already exists (${real_users}), skipping bootstrap user creation"
        return
    fi

    echo "Creating Home Assistant bootstrap admin user via supported auth script..."
    if python3 -m homeassistant --script auth -c "${CONFIG_DIR}" add "${admin_username}" "${admin_password}"; then
        echo "Bootstrap admin user created: ${admin_username}"
    else
        echo "Auth script add failed, checking if user now exists before failing..."
    fi

    real_users="$(has_real_user || true)"
    if [ "${real_users:-0}" -le 0 ]; then
        echo "Failed to create a real Home Assistant user"
        exit 1
    fi
}

mark_onboarding_complete() {
    cat > "${ONBOARDING_FILE}" <<'EOF'
{
  "version": 3,
  "minor_version": 1,
  "key": "onboarding",
  "data": {
    "done": [
      "user",
      "core_config",
      "integration",
      "analytics"
    ]
  }
}
EOF
    echo "Onboarding state set to complete"
}

ensure_core_config() {
    if [ -f "${CORE_FILE}" ]; then
        return
    fi
    cat > "${CORE_FILE}" <<'EOF'
{
  "version": 1,
  "minor_version": 1,
  "key": "core.config",
  "data": {
    "latitude": -42.8821,
    "longitude": 147.3272,
    "elevation": 30,
    "unit_system": "metric",
    "location_name": "Datamancy",
    "time_zone": "Australia/Hobart",
    "currency": "USD",
    "language": "en"
  }
}
EOF
    echo "Core config written"
}

create_admin_user
mark_onboarding_complete
ensure_core_config

echo "Home Assistant pre-init complete"
