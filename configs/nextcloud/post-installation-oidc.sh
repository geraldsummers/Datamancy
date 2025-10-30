#!/bin/bash
# Nextcloud OIDC Post-Installation Hook
# This script runs automatically after Nextcloud installation completes

set -e

echo "===== Nextcloud OIDC Post-Installation Hook ====="

# Give Nextcloud a moment to fully initialize
sleep 5

# Install and enable the user_oidc app
echo "Installing user_oidc app..."
php occ app:install user_oidc 2>/dev/null || true
php occ app:enable user_oidc

# Configure reverse proxy settings (already done by env vars, but ensure they're set)
echo "Configuring reverse proxy settings..."
php occ config:system:set overwriteprotocol --value="https"
php occ config:system:set overwritehost --value="nextcloud.stack.local"
php occ config:system:set overwrite.cli.url --value="https://nextcloud.stack.local"
php occ config:system:set trusted_proxies 0 --value="172.18.0.0/16"

# Configure OIDC provider
echo "Configuring OIDC provider..."

# Check if provider already exists
EXISTING_PROVIDER=$(php occ user_oidc:provider 2>&1 || true)

if echo "$EXISTING_PROVIDER" | grep -q "Authelia"; then
    echo "OIDC provider already configured, skipping..."
else
    # Create OIDC provider
    PROVIDER_ID=$(php occ user_oidc:provider Authelia \
      --clientid="nextcloud" \
      --clientsecret="f99eab7b5e43e7e0ac03f2cbdcc0a02b849a38fdfe9458097c4f1c1708df6399" \
      --discoveryuri="https://auth.stack.local/.well-known/openid-configuration" \
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
