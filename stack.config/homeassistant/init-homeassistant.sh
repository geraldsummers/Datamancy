#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR="/config"
AUTH_FILE="${CONFIG_DIR}/.storage/auth"
ONBOARDING_FILE="${CONFIG_DIR}/.storage/onboarding"
CORE_FILE="${CONFIG_DIR}/.storage/core.config"

echo "Starting Home Assistant pre-init..."
mkdir -p "${CONFIG_DIR}/.storage"

count_real_users() {
    if [ ! -f "${AUTH_FILE}" ]; then
        echo 0
        return
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

ensure_admin_user_linked() {
    local admin_username="$1"
    local admin_password="$2"

    python3 - "$CONFIG_DIR" "$admin_username" "$admin_password" <<'PY'
import asyncio
import os
import sys

from homeassistant import runner
from homeassistant.auth import auth_manager_from_config
from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr, entity_registry as er

config_dir, username, password = sys.argv[1], sys.argv[2], sys.argv[3]

asyncio.set_event_loop_policy(runner.HassEventLoopPolicy(False))

async def main() -> None:
    hass = HomeAssistant(os.path.join(os.getcwd(), config_dir))
    await asyncio.gather(dr.async_load(hass), er.async_load(hass))
    hass.auth = await auth_manager_from_config(hass, [{"type": "homeassistant"}], [])

    provider = hass.auth.auth_providers[0]
    await provider.async_initialize()

    # Keep provider auth data in sync; ignore "already exists" errors.
    try:
        await provider.async_add_auth(username, password)
    except Exception:
        pass

    credentials = await provider.async_get_or_create_credentials({"username": username})
    user = await hass.auth.async_get_user_by_credentials(credentials)
    if user is None:
        user = await hass.auth.async_create_user(username, group_ids=["system-admin"])
        await hass.auth.async_link_user(user, credentials)
        print(f"Linked auth credentials for {username}")
    else:
        print(f"Auth credentials already linked for {username}")

    # Ensure auth storage has flushed before exit.
    await asyncio.sleep(2)
    await hass.async_stop()

asyncio.run(main())
PY
}

create_admin_user() {
    local admin_username="${STACK_ADMIN_USER:-}"
    local admin_password="${STACK_ADMIN_PASSWORD:-}"

    if [ -z "${admin_username}" ] || [ -z "${admin_password}" ]; then
        echo "STACK_ADMIN_USER/STACK_ADMIN_PASSWORD missing; skipping user bootstrap"
        return
    fi

    local existing_users
    existing_users="$(count_real_users)"
    if [ "${existing_users:-0}" -gt 0 ]; then
        echo "Home Assistant user already exists (${existing_users}), skipping bootstrap user creation"
        return
    fi

    echo "Creating Home Assistant bootstrap admin user and linking auth credentials..."
    if ensure_admin_user_linked "${admin_username}" "${admin_password}"; then
        echo "Bootstrap admin user created: ${admin_username}"
    else
        echo "Bootstrap admin creation/linking failed, checking if user now exists before failing..."
    fi

    existing_users="$(count_real_users)"
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

echo "Skipping local Home Assistant user bootstrap; LDAP is the identity source."
mark_onboarding_complete
ensure_core_config

echo "Home Assistant pre-init complete"
