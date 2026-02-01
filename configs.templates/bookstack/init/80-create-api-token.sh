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

# Check if token credentials are provided
if [ -z "$BOOKSTACK_TOKEN_ID" ] || [ -z "$BOOKSTACK_TOKEN_SECRET" ]; then
    echo "[BookStack API Token] WARNING: BOOKSTACK_TOKEN_ID or BOOKSTACK_TOKEN_SECRET not set"
    echo "[BookStack API Token] Generating new token credentials..."

    # Generate token credentials
    BOOKSTACK_TOKEN_ID="datamancy-automation-$(date +%s)"
    BOOKSTACK_TOKEN_SECRET="$(openssl rand -hex 32)"

    echo "=========================================="
    echo "GENERATED BOOKSTACK API TOKEN"
    echo "=========================================="
    echo "Add these to your .env file:"
    echo "BOOKSTACK_API_TOKEN_ID=$BOOKSTACK_TOKEN_ID"
    echo "BOOKSTACK_API_TOKEN_SECRET=$BOOKSTACK_TOKEN_SECRET"
    echo "=========================================="
    echo ""
    echo "These credentials have been set for this container session."
    echo "Pipeline will work until container restart."
    echo "To persist across restarts, add to .env file."
    echo "=========================================="
fi

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

    # Write credentials to shared volume for pipeline container
    CREDS_DIR="/shared-credentials"
    if [ -d "$CREDS_DIR" ]; then
        echo "[BookStack API Token] Writing credentials to shared volume..."
        cat > "$CREDS_DIR/bookstack-api-token.env" <<EOF
# Auto-generated BookStack API Token
# Created: $(date -Iseconds)
export BOOKSTACK_API_TOKEN_ID="$BOOKSTACK_TOKEN_ID"
export BOOKSTACK_API_TOKEN_SECRET="$BOOKSTACK_TOKEN_SECRET"
export BOOKSTACK_TOKEN_ID="$BOOKSTACK_TOKEN_ID"
export BOOKSTACK_TOKEN_SECRET="$BOOKSTACK_TOKEN_SECRET"
EOF
        chmod 600 "$CREDS_DIR/bookstack-api-token.env"
        echo "[BookStack API Token] ✓ Credentials written to $CREDS_DIR/bookstack-api-token.env"
    else
        echo "[BookStack API Token] WARNING: Shared credentials directory not mounted"
        echo "[BookStack API Token] Pipeline will need credentials from .env file"
    fi
else
    echo "[BookStack API Token] ERROR: Failed to create token"
    echo "$RESULT"
fi
