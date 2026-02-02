#!/bin/sh
# Ntfy user provisioning script
# Creates alertmanager user and grants topic permissions
# Run once on first boot or when user doesn't exist

set -e

NTFY_DATA_DIR="/var/lib/ntfy"
AUTH_DB="${NTFY_DATA_DIR}/auth.db"
USER_PROVISIONED_FLAG="${NTFY_DATA_DIR}/.users_provisioned"

echo "[ntfy-init] Checking if users need provisioning..."

# Wait for ntfy to start and create auth.db
for i in $(seq 1 30); do
    if [ -f "$AUTH_DB" ]; then
        echo "[ntfy-init] Auth database found"
        break
    fi
    echo "[ntfy-init] Waiting for ntfy to initialize... ($i/30)"
    sleep 2
done

if [ ! -f "$AUTH_DB" ]; then
    echo "[ntfy-init] ERROR: Auth database not found after 60 seconds"
    exit 1
fi

# Check if users already provisioned
if [ -f "$USER_PROVISIONED_FLAG" ]; then
    echo "[ntfy-init] Users already provisioned, skipping"
    exit 0
fi

echo "[ntfy-init] Provisioning users..."

# Validate required environment variables
if [ -z "$NTFY_USERNAME" ] || [ -z "$NTFY_PASSWORD" ]; then
    echo "[ntfy-init] ERROR: NTFY_USERNAME and NTFY_PASSWORD must be set"
    exit 1
fi

# Check if user already exists
if ntfy user list | grep -q "^user ${NTFY_USERNAME}"; then
    echo "[ntfy-init] User ${NTFY_USERNAME} already exists"
else
    echo "[ntfy-init] Creating user: ${NTFY_USERNAME}"
    # Use printf to pass password via stdin
    printf "%s\n%s\n" "$NTFY_PASSWORD" "$NTFY_PASSWORD" | ntfy user add "$NTFY_USERNAME"
fi

# Grant write access to alertmanager topics
echo "[ntfy-init] Granting access to datamancy-* topics"
ntfy access "$NTFY_USERNAME" "datamancy-alerts" write-only || true
ntfy access "$NTFY_USERNAME" "datamancy-critical" write-only || true
ntfy access "$NTFY_USERNAME" "datamancy-warnings" write-only || true

# Mark provisioning as complete
touch "$USER_PROVISIONED_FLAG"

echo "[ntfy-init] User provisioning complete!"
ntfy user list

exit 0
