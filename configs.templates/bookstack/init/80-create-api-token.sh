#!/usr/bin/with-contenv bash
# Create BookStack API token for pipeline automation
# This runs during container init and creates/updates the API token in the database
# Token credentials come from environment variables: BOOKSTACK_TOKEN_ID and BOOKSTACK_TOKEN_SECRET

# Wait for BookStack to be fully initialized
echo "[BookStack API Token] Waiting for BookStack initialization..."
for i in {1..60}; do
    if [ -f "/app/www/artisan" ] && [ -f "/config/www/.env" ]; then
        echo "[BookStack API Token] BookStack is ready"
        break
    fi
    sleep 2
done

if [ ! -f "/app/www/artisan" ]; then
    echo "[BookStack API Token] ERROR: artisan not found, skipping token creation"
    exit 0
fi

# Check if token credentials are provided from build-time generation
if [ -z "$BOOKSTACK_API_TOKEN_ID" ] || [ -z "$BOOKSTACK_API_TOKEN_SECRET" ]; then
    echo "[BookStack API Token] ERROR: BOOKSTACK_API_TOKEN_ID or BOOKSTACK_API_TOKEN_SECRET not set"
    echo "[BookStack API Token] These should be generated at build time by build-datamancy-v2.main.kts"
    echo "[BookStack API Token] Please rebuild your stack to generate API tokens"
    exit 1
fi

# Use the build-time generated credentials
BOOKSTACK_TOKEN_ID="$BOOKSTACK_API_TOKEN_ID"
BOOKSTACK_TOKEN_SECRET="$BOOKSTACK_API_TOKEN_SECRET"

echo "[BookStack API Token] Using build-time generated API token"
echo "[BookStack API Token] Token ID: $BOOKSTACK_TOKEN_ID"

cd /app/www

# Get admin user ID
echo "[BookStack API Token] Finding admin user..."
USER_ID=$(php artisan tinker --execute="echo BookStack\Users\Models\User::where('email', 'admin@admin.com')->first()->id ?? 1;" 2>/dev/null | tail -1)

if [ -z "$USER_ID" ] || [ "$USER_ID" = "0" ]; then
    echo "[BookStack API Token] ERROR: Could not find admin user, using ID 1"
    USER_ID=1
fi

echo "[BookStack API Token] Using user ID: $USER_ID"

# Delete existing token if present (idempotency)
echo "[BookStack API Token] Removing old token if exists..."
php artisan tinker --execute="BookStack\Api\ApiToken::where('name', 'Datamancy Automation')->delete();" 2>/dev/null

# Create token via Tinker
echo "[BookStack API Token] Creating API token..."
# Use addslashes to escape special characters in token values for PHP
ESCAPED_TOKEN_ID=$(php -r "echo addslashes('$BOOKSTACK_TOKEN_ID');")
ESCAPED_TOKEN_SECRET=$(php -r "echo addslashes('$BOOKSTACK_TOKEN_SECRET');")

CREATE_CMD="\$user = BookStack\Users\Models\User::find($USER_ID); "
CREATE_CMD+="\$token = new BookStack\Api\ApiToken(); "
CREATE_CMD+="\$token->user_id = \$user->id; "
CREATE_CMD+="\$token->name = 'Datamancy Automation'; "
CREATE_CMD+="\$token->token_id = '$ESCAPED_TOKEN_ID'; "
CREATE_CMD+="\$token->secret = '$ESCAPED_TOKEN_SECRET'; "
CREATE_CMD+="\$token->expires_at = '2099-12-31 23:59:59'; "
CREATE_CMD+="\$token->save(); "
CREATE_CMD+="echo 'SUCCESS';"

RESULT=$(php artisan tinker --execute="$CREATE_CMD" 2>&1 | tail -1)

if echo "$RESULT" | grep -q "SUCCESS"; then
    echo "[BookStack API Token] ✓ API token created successfully"
    echo "[BookStack API Token] Token ID: $BOOKSTACK_TOKEN_ID"
    echo "[BookStack API Token] Pipeline will use this token from environment variables"
else
    echo "[BookStack API Token] ERROR: Failed to create token"
    echo "$RESULT"
    exit 1
fi
