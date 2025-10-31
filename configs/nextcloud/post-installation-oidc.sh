#!/bin/bash
# Nextcloud OIDC Post-Installation Hook
# This script runs automatically after Nextcloud installation completes

set -e

echo "===== Nextcloud OIDC Post-Installation Hook ====="

# Give Nextcloud a moment to fully initialize
sleep 5

# Install and enable the user_oidc app
echo "Installing user_oidc app..."

# Check if already installed
if php occ app:list 2>/dev/null | grep -q "user_oidc"; then
    echo "user_oidc app already installed"
    php occ app:enable user_oidc 2>&1 || echo "user_oidc already enabled"
else
    # Retry logic for app installation (may fail due to app store connectivity)
    INSTALLED=false
    for i in 1 2 3 4 5; do
        echo "Attempt $i: Installing user_oidc app..."
        if php occ app:install user_oidc 2>&1; then
            echo "✓ user_oidc app installed successfully"
            INSTALLED=true
            break
        else
            echo "⚠ Installation attempt $i failed, waiting 10 seconds before retry..."
            sleep 10
        fi
    done

    # If all attempts failed, check if app is actually available despite errors
    if [ "$INSTALLED" = false ]; then
        echo "⚠ App store download failed after 5 attempts"
        echo "⚠ Checking if user_oidc is available anyway..."
        if php occ app:list 2>/dev/null | grep -q "user_oidc"; then
            echo "✓ user_oidc app found in app list, proceeding..."
        else
            echo "❌ CRITICAL: user_oidc app not available!"
            echo "❌ OIDC login will not work. Manual installation required:"
            echo "   docker exec -u www-data nextcloud php occ app:install user_oidc"
            echo "   docker exec -u www-data nextcloud php occ app:enable user_oidc"
        fi
    fi

    php occ app:enable user_oidc 2>&1 || true
fi

# Configure reverse proxy settings (already done by env vars, but ensure they're set)
echo "Configuring reverse proxy settings..."
php occ config:system:set overwriteprotocol --value="https"
php occ config:system:set overwritehost --value="nextcloud.project-saturn.com"
php occ config:system:set overwrite.cli.url --value="https://nextcloud.project-saturn.com"
php occ config:system:set trusted_proxies 0 --value="172.18.0.0/16"

# Allow Nextcloud to connect to internal IPs (needed because external domain resolves to internal Caddy IP)
echo "Allowing connections to local servers for OIDC discovery..."
php occ config:system:set allow_local_remote_servers --value=true --type=boolean

# Configure OIDC provider
echo "Configuring OIDC provider..."

# Check if provider already exists with correct URL
EXISTING_PROVIDER=$(php occ user_oidc:provider 2>&1 || true)
CORRECT_URL="https://auth.project-saturn.com/.well-known/openid-configuration"

if echo "$EXISTING_PROVIDER" | grep -q "$CORRECT_URL"; then
    echo "OIDC provider already configured with correct URL, skipping..."
    PROVIDER_ID=$(echo "$EXISTING_PROVIDER" | grep -oP '^\|\s+\K\d+' | head -1)
else
    # Delete old provider if it exists with wrong URL
    if echo "$EXISTING_PROVIDER" | grep -q "Authelia"; then
        echo "Removing old OIDC provider with incorrect URL..."
        OLD_ID=$(echo "$EXISTING_PROVIDER" | grep -oP '^\|\s+\K\d+' | head -1)
        if [ -n "$OLD_ID" ]; then
            # Direct database deletion since CLI doesn't have delete command
            PGPASSWORD="${NEXTCLOUD_DB_PASSWORD}" psql -h postgres -U nextcloud -d nextcloud -c "DELETE FROM oc_user_oidc_providers WHERE id = $OLD_ID;" 2>/dev/null || true
        fi
    fi

    # Create OIDC provider (use external authelia URL for proper session handling)
    PROVIDER_ID=$(php occ user_oidc:provider Authelia \
      --clientid="nextcloud" \
      --clientsecret="f99eab7b5e43e7e0ac03f2cbdcc0a02b849a38fdfe9458097c4f1c1708df6399" \
      --discoveryuri="$CORRECT_URL" \
      --scope="openid profile email groups" \
      --unique-uid=1 \
      2>&1 | grep -oP 'Provider .* created with id \K\d+' || echo "1")

    echo "OIDC Provider ID: $PROVIDER_ID"

    # Configure OIDC attribute mapping
    php occ config:app:set user_oidc provider-${PROVIDER_ID}-mappingDisplayName --value="name"
    php occ config:app:set user_oidc provider-${PROVIDER_ID}-mappingEmail --value="email"
    php occ config:app:set user_oidc provider-${PROVIDER_ID}-mappingQuota --value=""
    php occ config:app:set user_oidc provider-${PROVIDER_ID}-mappingUid --value="preferred_username"

    # Configure login button text
    php occ config:app:set user_oidc provider-${PROVIDER_ID}-buttonLabel --value="Login with SSO"

    # Enable auto-provisioning
    php occ config:app:set user_oidc auto_provision --value="1"

    # Allow multiple OIDC providers
    php occ config:app:set user_oidc allow_multiple_user_backends --value="1"
fi

# Enable Redis caching
echo "Configuring Redis cache..."
php occ config:system:set redis host --value="redis"
php occ config:system:set redis port --value="6379"
php occ config:system:set redis timeout --value="1.5"
php occ config:system:set memcache.local --value="\\OC\\Memcache\\Redis"
php occ config:system:set memcache.locking --value="\\OC\\Memcache\\Redis"

# Set file locking
php occ config:system:set filelocking.enabled --value=true --type=boolean

# Configure background jobs
php occ background:cron

# Maintenance mode off (just in case)
php occ maintenance:mode --off

echo "===== Nextcloud OIDC Post-Installation Hook Complete ====="
