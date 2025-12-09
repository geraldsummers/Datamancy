#!/usr/bin/with-contenv bash
# Fix BookStack .env file with correct database credentials
# Note: LinuxServer BookStack uses /config/www/.env as the persistent config
# This runs as part of the container's init system and waits for the .env to be created

ENV_FILE="/config/www/.env"

# Wait up to 30 seconds for the .env file to be created by the container
echo "Waiting for BookStack .env file to be created..."
for i in {1..30}; do
    if [ -f "$ENV_FILE" ]; then
        echo "Found .env file, proceeding with updates..."
        break
    fi
    sleep 1
done

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: BookStack .env file not found at $ENV_FILE after 30 seconds"
    echo "Container may need manual initialization"
    exit 0  # Don't fail the container startup
fi

echo "Updating BookStack .env file at $ENV_FILE..."

# Update APP_URL
if [ -n "$APP_URL" ]; then
    sed -i "s|^APP_URL=.*|APP_URL=$APP_URL|" "$ENV_FILE"
fi

# Update APP_KEY
if [ -n "$APP_KEY" ]; then
    sed -i "s|^APP_KEY=.*|APP_KEY=$APP_KEY|" "$ENV_FILE"
fi

# Update DB_HOST
if [ -n "$DB_HOST" ]; then
    sed -i "s|^DB_HOST=.*|DB_HOST=$DB_HOST|" "$ENV_FILE"
fi

# Update DB_USERNAME
if [ -n "$DB_USER" ]; then
    sed -i "s|^DB_USERNAME=.*|DB_USERNAME=$DB_USER|" "$ENV_FILE"
fi

# Update DB_PASSWORD
if [ -n "$DB_PASS" ]; then
    sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$DB_PASS|" "$ENV_FILE"
fi

# Update DB_DATABASE
if [ -n "$DB_DATABASE" ]; then
    sed -i "s|^DB_DATABASE=.*|DB_DATABASE=$DB_DATABASE|" "$ENV_FILE"
fi

# Use authentication via reverse proxy headers (from Authelia)
# This enables seamless SSO - users authenticated by Authelia are auto-logged in
sed -i "s|^AUTH_METHOD=.*|AUTH_METHOD=oidc|" "$ENV_FILE"
grep -q "^AUTH_METHOD=" "$ENV_FILE" || echo "AUTH_METHOD=oidc" >> "$ENV_FILE"

# Configure OIDC for Authelia
sed -i "s|^OIDC_NAME=.*|OIDC_NAME=Authelia|" "$ENV_FILE"
grep -q "^OIDC_NAME=" "$ENV_FILE" || echo "OIDC_NAME=Authelia" >> "$ENV_FILE"

sed -i "s|^OIDC_DISPLAY_NAME_CLAIMS=.*|OIDC_DISPLAY_NAME_CLAIMS=name|" "$ENV_FILE"
grep -q "^OIDC_DISPLAY_NAME_CLAIMS=" "$ENV_FILE" || echo "OIDC_DISPLAY_NAME_CLAIMS=name" >> "$ENV_FILE"

sed -i "s|^OIDC_CLIENT_ID=.*|OIDC_CLIENT_ID=bookstack|" "$ENV_FILE"
grep -q "^OIDC_CLIENT_ID=" "$ENV_FILE" || echo "OIDC_CLIENT_ID=bookstack" >> "$ENV_FILE"

# Get OIDC secret from container environment (passed from docker-compose)
if [ -n "$BOOKSTACK_OAUTH_SECRET" ]; then
    sed -i "s|^OIDC_CLIENT_SECRET=.*|OIDC_CLIENT_SECRET=$BOOKSTACK_OAUTH_SECRET|" "$ENV_FILE"
    grep -q "^OIDC_CLIENT_SECRET=" "$ENV_FILE" || echo "OIDC_CLIENT_SECRET=$BOOKSTACK_OAUTH_SECRET" >> "$ENV_FILE"
fi

# Get domain from APP_URL environment variable
DOMAIN_FROM_URL=$(echo "$APP_URL" | sed -E 's|https?://[^.]+\.||' | sed 's|/.*||')
if [ -n "$DOMAIN_FROM_URL" ]; then
    sed -i "s|^OIDC_ISSUER=.*|OIDC_ISSUER=https://auth.$DOMAIN_FROM_URL|" "$ENV_FILE"
    grep -q "^OIDC_ISSUER=" "$ENV_FILE" || echo "OIDC_ISSUER=https://auth.$DOMAIN_FROM_URL" >> "$ENV_FILE"
fi

# Auto-register users from OIDC (seamless SSO)
sed -i "s|^OIDC_AUTO_DISCOVER=.*|OIDC_AUTO_DISCOVER=true|" "$ENV_FILE"
grep -q "^OIDC_AUTO_DISCOVER=" "$ENV_FILE" || echo "OIDC_AUTO_DISCOVER=true" >> "$ENV_FILE"

sed -i "s|^OIDC_AUTO_CONFIRM_EMAIL=.*|OIDC_AUTO_CONFIRM_EMAIL=true|" "$ENV_FILE"
grep -q "^OIDC_AUTO_CONFIRM_EMAIL=" "$ENV_FILE" || echo "OIDC_AUTO_CONFIRM_EMAIL=true" >> "$ENV_FILE"

# Auto-initiate OIDC login on login page
sed -i "s|^OIDC_AUTO_INITIATE=.*|OIDC_AUTO_INITIATE=true|" "$ENV_FILE"
grep -q "^OIDC_AUTO_INITIATE=" "$ENV_FILE" || echo "OIDC_AUTO_INITIATE=true" >> "$ENV_FILE"

# LDAP server configuration
sed -i "s|^LDAP_SERVER=.*|LDAP_SERVER=ldap://ldap:389|" "$ENV_FILE"
grep -q "^LDAP_SERVER=" "$ENV_FILE" || echo "LDAP_SERVER=ldap://ldap:389" >> "$ENV_FILE"

# LDAP base DN
sed -i "s|^LDAP_BASE_DN=.*|LDAP_BASE_DN=dc=stack,dc=local|" "$ENV_FILE"
grep -q "^LDAP_BASE_DN=" "$ENV_FILE" || echo "LDAP_BASE_DN=dc=stack,dc=local" >> "$ENV_FILE"

# LDAP bind user (for searching)
sed -i "s|^LDAP_DN=.*|LDAP_DN=cn=admin,dc=stack,dc=local|" "$ENV_FILE"
grep -q "^LDAP_DN=" "$ENV_FILE" || echo "LDAP_DN=cn=admin,dc=stack,dc=local" >> "$ENV_FILE"

# LDAP bind password
if [ -n "$LDAP_ADMIN_PASSWORD" ]; then
    sed -i "s|^LDAP_PASS=.*|LDAP_PASS=$LDAP_ADMIN_PASSWORD|" "$ENV_FILE"
    grep -q "^LDAP_PASS=" "$ENV_FILE" || echo "LDAP_PASS=$LDAP_ADMIN_PASSWORD" >> "$ENV_FILE"
fi

# LDAP user filter
sed -i "s|^LDAP_USER_FILTER=.*|LDAP_USER_FILTER=(&(uid=\${user}))|" "$ENV_FILE"
grep -q "^LDAP_USER_FILTER=" "$ENV_FILE" || echo 'LDAP_USER_FILTER=(&(uid=${user}))' >> "$ENV_FILE"

# LDAP version
sed -i "s|^LDAP_VERSION=.*|LDAP_VERSION=3|" "$ENV_FILE"
grep -q "^LDAP_VERSION=" "$ENV_FILE" || echo "LDAP_VERSION=3" >> "$ENV_FILE"

# LDAP user to DN
sed -i "s|^LDAP_USER_TO_GROUPS=.*|LDAP_USER_TO_GROUPS=false|" "$ENV_FILE"
grep -q "^LDAP_USER_TO_GROUPS=" "$ENV_FILE" || echo "LDAP_USER_TO_GROUPS=false" >> "$ENV_FILE"

# LDAP ID attribute
sed -i "s|^LDAP_ID_ATTRIBUTE=.*|LDAP_ID_ATTRIBUTE=uid|" "$ENV_FILE"
grep -q "^LDAP_ID_ATTRIBUTE=" "$ENV_FILE" || echo "LDAP_ID_ATTRIBUTE=uid" >> "$ENV_FILE"

# LDAP email attribute
sed -i "s|^LDAP_EMAIL_ATTRIBUTE=.*|LDAP_EMAIL_ATTRIBUTE=mail|" "$ENV_FILE"
grep -q "^LDAP_EMAIL_ATTRIBUTE=" "$ENV_FILE" || echo "LDAP_EMAIL_ATTRIBUTE=mail" >> "$ENV_FILE"

# LDAP display name attribute
sed -i "s|^LDAP_DISPLAY_NAME_ATTRIBUTE=.*|LDAP_DISPLAY_NAME_ATTRIBUTE=cn|" "$ENV_FILE"
grep -q "^LDAP_DISPLAY_NAME_ATTRIBUTE=" "$ENV_FILE" || echo "LDAP_DISPLAY_NAME_ATTRIBUTE=cn" >> "$ENV_FILE"

echo "BookStack .env file updated successfully with LDAP authentication"

# Clear Laravel cache to ensure new credentials are used
# This is critical because Laravel may have cached the old database_username placeholder
if [ -f "/app/www/artisan" ]; then
    echo "Clearing Laravel configuration cache..."
    cd /app/www && php artisan config:clear 2>/dev/null || true
    cd /app/www && php artisan cache:clear 2>/dev/null || true
    echo "Laravel cache cleared"
fi
