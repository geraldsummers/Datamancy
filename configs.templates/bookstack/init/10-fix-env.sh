#!/usr/bin/with-contenv bash
ENV_FILE_APP="/app/www/.env"
ENV_FILE_CONFIG="/config/www/.env"
echo "Waiting for BookStack persistent .env file to be created..."
for i in {1..60}; do
    if [ -f "$ENV_FILE_CONFIG" ]; then
        echo "Found persistent .env file at $ENV_FILE_CONFIG"
        ENV_FILE="$ENV_FILE_CONFIG"
        break
    fi
    sleep 1
done
if [ ! -f "$ENV_FILE" ]; then
    if [ -f "$ENV_FILE_APP" ]; then
        echo "Using app .env file at $ENV_FILE_APP (will be copied to config)"
        ENV_FILE="$ENV_FILE_APP"
    else
        echo "ERROR: No BookStack .env file found after 60 seconds"
        echo "Container may need manual initialization"
        exit 0
    fi
fi
if grep -q "^DB_HOST=mariadb$" "$ENV_FILE" 2>/dev/null && \
   grep -q "^DB_USERNAME=$DB_USER$" "$ENV_FILE" 2>/dev/null; then
    echo "BookStack .env already configured correctly, skipping update"
    exit 0
fi
echo "Updating BookStack .env file at $ENV_FILE..."
if [ -n "$APP_URL" ]; then
    sed -i "s|^APP_URL=.*|APP_URL=$APP_URL|" "$ENV_FILE"
fi
if [ -n "$APP_KEY" ]; then
    sed -i "s|^APP_KEY=.*|APP_KEY=$APP_KEY|" "$ENV_FILE"
fi
if [ -n "$DB_HOST" ]; then
    sed -i "s|^DB_HOST=.*|DB_HOST=$DB_HOST|" "$ENV_FILE"
fi
if [ -n "$DB_USERNAME" ]; then
    sed -i "s|^DB_USERNAME=.*|DB_USERNAME=$DB_USERNAME|" "$ENV_FILE"
fi
if [ -n "$DB_PASSWORD" ]; then
    sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$DB_PASSWORD|" "$ENV_FILE"
fi
if [ -n "$DB_DATABASE" ]; then
    sed -i "s|^DB_DATABASE=.*|DB_DATABASE=$DB_DATABASE|" "$ENV_FILE"
fi
sed -i "s|^AUTH_METHOD=.*|AUTH_METHOD=oidc|" "$ENV_FILE"
grep -q "^AUTH_METHOD=" "$ENV_FILE" || echo "AUTH_METHOD=oidc" >> "$ENV_FILE"
sed -i "s|^OIDC_NAME=.*|OIDC_NAME=Authelia|" "$ENV_FILE"
grep -q "^OIDC_NAME=" "$ENV_FILE" || echo "OIDC_NAME=Authelia" >> "$ENV_FILE"
sed -i "s|^OIDC_DISPLAY_NAME_CLAIMS=.*|OIDC_DISPLAY_NAME_CLAIMS=name|" "$ENV_FILE"
grep -q "^OIDC_DISPLAY_NAME_CLAIMS=" "$ENV_FILE" || echo "OIDC_DISPLAY_NAME_CLAIMS=name" >> "$ENV_FILE"
sed -i "s|^OIDC_CLIENT_ID=.*|OIDC_CLIENT_ID=bookstack|" "$ENV_FILE"
grep -q "^OIDC_CLIENT_ID=" "$ENV_FILE" || echo "OIDC_CLIENT_ID=bookstack" >> "$ENV_FILE"
if [ -n "$BOOKSTACK_OAUTH_SECRET" ]; then
    sed -i "s|^OIDC_CLIENT_SECRET=.*|OIDC_CLIENT_SECRET=$BOOKSTACK_OAUTH_SECRET|" "$ENV_FILE"
    grep -q "^OIDC_CLIENT_SECRET=" "$ENV_FILE" || echo "OIDC_CLIENT_SECRET=$BOOKSTACK_OAUTH_SECRET" >> "$ENV_FILE"
fi
sed -i "s|^OIDC_ISSUER=.*|OIDC_ISSUER=https://auth.\${APP_URL#https://bookstack.}|" "$ENV_FILE"
grep -q "^OIDC_ISSUER=" "$ENV_FILE" || echo 'OIDC_ISSUER=https://auth.${APP_URL#https://bookstack.}' >> "$ENV_FILE"
if [ -n "$APP_URL" ]; then
    DOMAIN="${APP_URL#https://bookstack.}"
    OIDC_ISSUER="https://auth.${DOMAIN}"
    sed -i "s|^OIDC_ISSUER=.*|OIDC_ISSUER=$OIDC_ISSUER|" "$ENV_FILE"
fi
sed -i "s|^OIDC_PUBLIC_KEY=.*|OIDC_PUBLIC_KEY=|" "$ENV_FILE"
grep -q "^OIDC_PUBLIC_KEY=" "$ENV_FILE" || echo "OIDC_PUBLIC_KEY=" >> "$ENV_FILE"
sed -i "s|^OIDC_AUTH_ENDPOINT=.*|OIDC_AUTH_ENDPOINT=|" "$ENV_FILE"
grep -q "^OIDC_AUTH_ENDPOINT=" "$ENV_FILE" || echo "OIDC_AUTH_ENDPOINT=" >> "$ENV_FILE"
sed -i "s|^OIDC_TOKEN_ENDPOINT=.*|OIDC_TOKEN_ENDPOINT=|" "$ENV_FILE"
grep -q "^OIDC_TOKEN_ENDPOINT=" "$ENV_FILE" || echo "OIDC_TOKEN_ENDPOINT=" >> "$ENV_FILE"
sed -i "s|^OIDC_ISSUER_DISCOVER=.*|OIDC_ISSUER_DISCOVER=true|" "$ENV_FILE"
grep -q "^OIDC_ISSUER_DISCOVER=" "$ENV_FILE" || echo "OIDC_ISSUER_DISCOVER=true" >> "$ENV_FILE"
sed -i "s|^OIDC_DUMP_USER_DETAILS=.*|OIDC_DUMP_USER_DETAILS=false|" "$ENV_FILE"
grep -q "^OIDC_DUMP_USER_DETAILS=" "$ENV_FILE" || echo "OIDC_DUMP_USER_DETAILS=false" >> "$ENV_FILE"
sed -i "s|^OIDC_ADDITIONAL_SCOPES=.*|OIDC_ADDITIONAL_SCOPES=profile,email|" "$ENV_FILE"
grep -q "^OIDC_ADDITIONAL_SCOPES=" "$ENV_FILE" || echo "OIDC_ADDITIONAL_SCOPES=profile,email" >> "$ENV_FILE"
sed -i "s|^OIDC_USER_TO_GROUPS=.*|OIDC_USER_TO_GROUPS=true|" "$ENV_FILE"
grep -q "^OIDC_USER_TO_GROUPS=" "$ENV_FILE" || echo "OIDC_USER_TO_GROUPS=true" >> "$ENV_FILE"
sed -i "s|^OIDC_GROUPS_CLAIM=.*|OIDC_GROUPS_CLAIM=groups|" "$ENV_FILE"
grep -q "^OIDC_GROUPS_CLAIM=" "$ENV_FILE" || echo "OIDC_GROUPS_CLAIM=groups" >> "$ENV_FILE"
sed -i "s|^OIDC_REMOVE_FROM_GROUPS=.*|OIDC_REMOVE_FROM_GROUPS=false|" "$ENV_FILE"
grep -q "^OIDC_REMOVE_FROM_GROUPS=" "$ENV_FILE" || echo "OIDC_REMOVE_FROM_GROUPS=false" >> "$ENV_FILE"
sed -i "s|^OIDC_TOKEN_ENDPOINT_AUTH_METHOD=.*|OIDC_TOKEN_ENDPOINT_AUTH_METHOD=client_secret_post|" "$ENV_FILE"
grep -q "^OIDC_TOKEN_ENDPOINT_AUTH_METHOD=" "$ENV_FILE" || echo "OIDC_TOKEN_ENDPOINT_AUTH_METHOD=client_secret_post" >> "$ENV_FILE"
echo "BookStack .env file updated successfully with OIDC authentication"
if [ -f "/app/www/artisan" ]; then
    echo "Clearing Laravel configuration cache..."
    cd /app/www && php artisan config:clear 2>/dev/null || true
    cd /app/www && php artisan cache:clear 2>/dev/null || true
    echo "Laravel cache cleared"
fi
if [ -f "/app/www/artisan" ]; then
    echo "Checking if database migrations are needed..."
    cd /app/www
    if ! php artisan migrate:status >/dev/null 2>&1; then
        echo "Running database migrations..."
        php artisan migrate --force
        echo "Migrations completed"
    else
        echo "Database already migrated"
    fi
fi
if grep -q "database_username" /config/www/.env.backup 2>/dev/null || [ ! -f /config/www/.env.backup ]; then
    echo "First boot detected - credentials were updated."
    cp /config/www/.env /config/www/.env.backup
fi
