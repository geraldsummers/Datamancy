#!/bin/sh
set -e

echo "Setting up test user for authentication testing..."

# Wait for Authentik to be ready
echo "Waiting for Authentik to be ready..."
MAX_RETRIES=60
RETRY_COUNT=0

until curl -sf http://authentik-server:9000/-/health/ready/ > /dev/null 2>&1; do
  RETRY_COUNT=$((RETRY_COUNT+1))
  if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
    echo "Timeout waiting for Authentik"
    exit 1
  fi
  echo "Waiting for Authentik... (attempt $RETRY_COUNT/$MAX_RETRIES)"
  sleep 5
done

echo "Authentik is ready. Creating test user..."

AUTHENTIK_URL=${AUTHENTIK_URL:-http://authentik-server:9000}
AUTHENTIK_TOKEN=${AUTHENTIK_BOOTSTRAP_TOKEN}

# Create test user via API
echo "Creating user 'authtest'..."
USER_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${AUTHENTIK_URL}/api/v3/core/users/" \
  -H "Authorization: Bearer ${AUTHENTIK_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "authtest",
    "name": "Auth Test User",
    "email": "authtest@lab.localhost",
    "is_active": true,
    "path": "users",
    "attributes": {
      "purpose": "automated-authentication-testing"
    }
  }')

HTTP_CODE=$(echo "$USER_RESPONSE" | tail -n1)
RESPONSE_BODY=$(echo "$USER_RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "201" ] || [ "$HTTP_CODE" = "200" ]; then
  echo "User created successfully"
elif [ "$HTTP_CODE" = "400" ]; then
  echo "User may already exist, continuing..."
else
  echo "Failed to create user (HTTP $HTTP_CODE)"
  echo "Response: $RESPONSE_BODY"
fi

# Get user ID
echo "Fetching user ID..."
USER_DATA=$(curl -s "${AUTHENTIK_URL}/api/v3/core/users/?username=authtest" \
  -H "Authorization: Bearer ${AUTHENTIK_TOKEN}")

# Extract pk using grep and sed (since jq may not be available)
USER_ID=$(echo "$USER_DATA" | grep -o '"pk":[0-9]*' | head -1 | sed 's/"pk"://')

if [ -n "$USER_ID" ] && [ "$USER_ID" != "null" ]; then
  echo "Setting password for test user (ID: ${USER_ID})..."
  PASSWORD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${AUTHENTIK_URL}/api/v3/core/users/${USER_ID}/set_password/" \
    -H "Authorization: Bearer ${AUTHENTIK_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"password\": \"${TEST_PASSWORD:-TestAuth123!}\"}")

  PASSWORD_HTTP_CODE=$(echo "$PASSWORD_RESPONSE" | tail -n1)

  if [ "$PASSWORD_HTTP_CODE" = "200" ] || [ "$PASSWORD_HTTP_CODE" = "204" ]; then
    echo "✓ Test user 'authtest' configured successfully!"
    echo "  Username: authtest"
    echo "  Email: authtest@lab.localhost"
    echo "  Password: ${TEST_PASSWORD:-TestAuth123!}"
  else
    echo "Warning: Failed to set password (HTTP $PASSWORD_HTTP_CODE)"
  fi
else
  echo "Failed to find test user"
  exit 1
fi
