#!/usr/bin/env bash
set -euo pipefail
CONFIG_DIR="/config"
AUTH_FILE="${CONFIG_DIR}/.storage/auth"
ONBOARDING_FILE="${CONFIG_DIR}/.storage/onboarding"
CORE_FILE="${CONFIG_DIR}/.storage/core.config"
echo "Waiting for Home Assistant storage directory..."
for i in {1..30}; do
    if [ -d "${CONFIG_DIR}/.storage" ]; then
        echo "Storage directory found."
        break
    fi
    sleep 2
done
if [ ! -d "${CONFIG_DIR}/.storage" ]; then
    echo "Error: Storage directory not created after waiting."
    exit 1
fi
create_admin_user() {
    if [ -f "${AUTH_FILE}" ]; then
        USER_COUNT=$(python3 -c "import json; print(len(json.load(open('${AUTH_FILE}'))['data']['users']))" 2>/dev/null || echo "0")
        if [ "${USER_COUNT}" -gt "0" ]; then
            echo "Users already exist, skipping admin creation."
            return
        fi
    fi
    echo "Creating admin user..."
    python3 << 'PYEOF'
import json
import os
import hashlib
import secrets
from pathlib import Path
CONFIG_DIR = Path("/config")
AUTH_FILE = CONFIG_DIR / ".storage" / "auth"
PERSON_FILE = CONFIG_DIR / ".storage" / "person"
admin_username = os.environ.get("STACK_ADMIN_USER")
admin_password = os.environ.get("STACK_ADMIN_PASSWORD")
admin_email = os.environ.get("STACK_ADMIN_EMAIL", "{{STACK_ADMIN_EMAIL}}")
if not admin_username or not admin_password:
    print("ERROR: STACK_ADMIN_USER and STACK_ADMIN_PASSWORD must be set")
    exit(1)
user_id = secrets.token_hex(16)
person_id = secrets.token_hex(16)
import bcrypt
password_hash = bcrypt.hashpw(admin_password.encode('utf-8'), bcrypt.gensalt()).decode('utf-8')
auth_data = {
    "version": 1,
    "minor_version": 3,
    "key": "auth",
    "data": {
        "users": [
            {
                "id": user_id,
                "group_ids": ["system-admin"],
                "is_owner": True,
                "is_active": True,
                "name": admin_username,
                "system_generated": False,
                "local_only": False
            }
        ],
        "credentials": [
            {
                "id": secrets.token_hex(16),
                "user_id": user_id,
                "auth_provider_type": "homeassistant",
                "auth_provider_id": None,
                "data": {
                    "username": admin_username,
                    "password": password_hash
                }
            }
        ],
        "refresh_tokens": [],
        "groups": [
            {
                "id": "system-admin",
                "name": "Administrators"
            },
            {
                "id": "system-users",
                "name": "Users"
            },
            {
                "id": "system-read-only",
                "name": "Read Only"
            }
        ]
    }
}
AUTH_FILE.parent.mkdir(parents=True, exist_ok=True)
with open(AUTH_FILE, 'w') as f:
    json.dump(auth_data, f, indent=2)
print(f"Admin user '{admin_username}' created successfully.")
person_data = {
    "version": 1,
    "minor_version": 2,
    "key": "person",
    "data": {
        "persons": [
            {
                "id": person_id,
                "name": admin_username,
                "user_id": user_id,
                "device_trackers": []
            }
        ]
    }
}
with open(PERSON_FILE, 'w') as f:
    json.dump(person_data, f, indent=2)
print(f"Person '{admin_username}' created successfully.")
PYEOF
    echo "Admin user created."
}
mark_onboarding_user_complete() {
    if [ -f "${ONBOARDING_FILE}" ]; then
        echo "Onboarding already configured."
        return
    fi
    echo "Marking user creation step as complete..."
    cat > "${ONBOARDING_FILE}" << 'EOF'
{
  "version": 3,
  "minor_version": 1,
  "key": "onboarding",
  "data": {
    "done": [
      "user",
      "analytics"
    ]
  }
}
EOF
    echo "User creation step marked complete. Home configuration will be shown on first login."
}
echo "Starting Home Assistant initialization..."
create_admin_user
mark_onboarding_user_complete
echo "Home Assistant initialization complete."
if [ -n "${STACK_ADMIN_USER:-}" ]; then
    echo "Admin user: ${STACK_ADMIN_USER}"
fi
echo "User will be prompted to create or restore home on first login."
echo "LDAP users can login via the mobile app or API using their LDAP credentials."
