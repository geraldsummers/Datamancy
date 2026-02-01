#!/usr/bin/env bash
set -e

# Auto-create Home Assistant admin account from LDAP credentials
# This script runs on first boot to create the initial admin user

CONFIG_DIR="/config"
AUTH_FILE="$CONFIG_DIR/.storage/auth"
ONBOARDING_FILE="$CONFIG_DIR/.storage/onboarding"

echo "[Auto-Create Admin] Starting..."

# Wait for Home Assistant to initialize
sleep 5

# Check if onboarding is already complete
if [ -f "$ONBOARDING_FILE" ]; then
    echo "[Auto-Create Admin] Onboarding already completed, skipping"
    exit 0
fi

# Wait for auth storage file to be created
TIMEOUT=60
ELAPSED=0
while [ ! -f "$AUTH_FILE" ] && [ $ELAPSED -lt $TIMEOUT ]; do
    echo "[Auto-Create Admin] Waiting for Home Assistant to initialize... ($ELAPSED/$TIMEOUT)"
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

if [ ! -f "$AUTH_FILE" ]; then
    echo "[Auto-Create Admin] ERROR: Home Assistant auth file not found after $TIMEOUT seconds"
    exit 1
fi

# Check if we already have users
USER_COUNT=$(python3 -c "import json; data=json.load(open('$AUTH_FILE')); print(len(data.get('data', {}).get('users', [])))" 2>/dev/null || echo "0")

if [ "$USER_COUNT" -gt 0 ]; then
    echo "[Auto-Create Admin] Users already exist, skipping auto-create"
    exit 0
fi

echo "[Auto-Create Admin] Creating admin user: $STACK_ADMIN_USER"

# Use Home Assistant CLI to create user (if available)
# Otherwise, we'll use the REST API
if command -v ha &> /dev/null; then
    echo "[Auto-Create Admin] Using Home Assistant CLI"
    ha auth reset --username "$STACK_ADMIN_USER" --password "$STACK_ADMIN_PASSWORD"
else
    echo "[Auto-Create Admin] Using REST API"

    # Wait for Home Assistant API to be ready
    TIMEOUT=120
    ELAPSED=0
    while ! curl -s http://localhost:8123/api/ > /dev/null 2>&1 && [ $ELAPSED -lt $TIMEOUT ]; do
        echo "[Auto-Create Admin] Waiting for Home Assistant API... ($ELAPSED/$TIMEOUT)"
        sleep 5
        ELAPSED=$((ELAPSED + 5))
    done

    if [ $ELAPSED -ge $TIMEOUT ]; then
        echo "[Auto-Create Admin] ERROR: Home Assistant API not ready after $TIMEOUT seconds"
        exit 1
    fi

    # Create user via onboarding API
    # Note: This only works during initial setup before onboarding is complete
    RESPONSE=$(curl -s -X POST http://localhost:8123/api/onboarding/users \
        -H "Content-Type: application/json" \
        -d "{
            \"name\": \"$STACK_ADMIN_USER\",
            \"username\": \"$STACK_ADMIN_USER\",
            \"password\": \"$STACK_ADMIN_PASSWORD\",
            \"language\": \"en\"
        }" 2>&1)

    if echo "$RESPONSE" | grep -q "error"; then
        echo "[Auto-Create Admin] WARNING: Could not create user via API: $RESPONSE"
        echo "[Auto-Create Admin] User may need to be created manually or via LDAP first login"
    else
        echo "[Auto-Create Admin] Admin user created successfully"

        # Mark onboarding as complete
        curl -s -X POST http://localhost:8123/api/onboarding/core_config \
            -H "Content-Type: application/json" \
            -d "{}" > /dev/null 2>&1

        curl -s -X POST http://localhost:8123/api/onboarding/integration \
            -H "Content-Type: application/json" \
            -d "{}" > /dev/null 2>&1
    fi
fi

echo "[Auto-Create Admin] Complete"
