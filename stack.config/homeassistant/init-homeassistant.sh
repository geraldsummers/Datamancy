#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR="/config"
ONBOARDING_FILE="${CONFIG_DIR}/.storage/onboarding"
CORE_FILE="${CONFIG_DIR}/.storage/core.config"

echo "Starting Home Assistant pre-init..."
mkdir -p "${CONFIG_DIR}/.storage"

count_users() {
    local output
    output="$(python3 -m homeassistant --script auth -c "${CONFIG_DIR}" list 2>/dev/null || true)"
    if [ -z "${output}" ]; then
        echo 0
        return
    fi
    local count
    count="$(printf '%s\n' "${output}" | awk '/^Total users:/ {print $3}' | tail -n1)"
    if [ -z "${count}" ]; then
        echo 0
        return
    fi
    echo "${count}"
}

create_admin_user() {
    local admin_username="${STACK_ADMIN_USER:-}"
    local admin_password="${STACK_ADMIN_PASSWORD:-}"

    if [ -z "${admin_username}" ] || [ -z "${admin_password}" ]; then
        echo "STACK_ADMIN_USER/STACK_ADMIN_PASSWORD missing; skipping user bootstrap"
        return
    fi

    local existing_users
    existing_users="$(count_users)"
    if [ "${existing_users:-0}" -gt 0 ]; then
        echo "Home Assistant user already exists (${existing_users}), skipping bootstrap user creation"
        return
    fi

    echo "Creating Home Assistant bootstrap admin user via supported auth script..."
    if python3 -m homeassistant --script auth -c "${CONFIG_DIR}" add "${admin_username}" "${admin_password}"; then
        echo "Bootstrap admin user created: ${admin_username}"
    else
        echo "Auth script add failed, checking if user now exists before failing..."
    fi

    existing_users="$(count_users)"
    if [ "${existing_users:-0}" -le 0 ]; then
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
