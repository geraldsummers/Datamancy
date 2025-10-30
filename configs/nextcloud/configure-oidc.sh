#!/bin/bash
# Nextcloud OIDC Configuration Script
# Run this after Nextcloud container is up and installation is complete

set -e

echo "===== Configuring Nextcloud OIDC Integration ====="

# Wait for Nextcloud to be ready
echo "Waiting for Nextcloud to be ready..."
sleep 10

# Install and enable the user_oidc app
echo "Installing user_oidc app..."
docker exec -u www-data nextcloud php occ app:install user_oidc || true
docker exec -u www-data nextcloud php occ app:enable user_oidc

# Configure trusted domains
echo "Configuring trusted domains..."
docker exec -u www-data nextcloud php occ config:system:set trusted_domains 0 --value="nextcloud.stack.local"

# Configure overwrite settings for reverse proxy
echo "Configuring reverse proxy settings..."
docker exec -u www-data nextcloud php occ config:system:set overwriteprotocol --value="https"
docker exec -u www-data nextcloud php occ config:system:set overwritehost --value="nextcloud.stack.local"
docker exec -u www-data nextcloud php occ config:system:set overwrite.cli.url --value="https://nextcloud.stack.local"
docker exec -u www-data nextcloud php occ config:system:set trusted_proxies 0 --value="172.18.0.0/16"

# Configure OIDC provider
echo "Configuring OIDC provider..."

# Create OIDC provider (this returns a provider ID)
PROVIDER_ID=$(docker exec -u www-data nextcloud php occ user_oidc:provider Authelia \
  --clientid="nextcloud" \
  --clientsecret="f99eab7b5e43e7e0ac03f2cbdcc0a02b849a38fdfe9458097c4f1c1708df6399" \
  --discoveryuri="https://auth.stack.local/.well-known/openid-configuration" \
  --scope="openid profile email groups" \
  --unique-uid=1 \
  2>&1 | grep -oP 'Provider .* created with id \K\d+' || echo "1")

echo "OIDC Provider ID: $PROVIDER_ID"

# Configure OIDC attribute mapping
docker exec -u www-data nextcloud php occ config:app:set user_oidc provider-${PROVIDER_ID}-mappingDisplayName --value="name"
docker exec -u www-data nextcloud php occ config:app:set user_oidc provider-${PROVIDER_ID}-mappingEmail --value="email"
docker exec -u www-data nextcloud php occ config:app:set user_oidc provider-${PROVIDER_ID}-mappingQuota --value=""
docker exec -u www-data nextcloud php occ config:app:set user_oidc provider-${PROVIDER_ID}-mappingUid --value="preferred_username"

# Enable auto-provisioning
docker exec -u www-data nextcloud php occ config:app:set user_oidc auto_provision --value="1"

# Allow multiple OIDC providers (in case we add more later)
docker exec -u www-data nextcloud php occ config:app:set user_oidc provider-params --value='{"Authelia":{"allow_multiple_user_backends":true}}'

# Configure login button text
docker exec -u www-data nextcloud php occ config:app:set user_oidc provider-${PROVIDER_ID}-buttonLabel --value="Login with SSO"

# Set OIDC as default login
docker exec -u www-data nextcloud php occ config:system:set allow_user_to_change_display_name --value=false --type=boolean

# Disable local login (optional - comment out if you want to keep admin access)
# docker exec -u www-data nextcloud php occ config:app:set user_oidc hide_password_login --value="1"

# Enable Redis caching
echo "Configuring Redis cache..."
docker exec -u www-data nextcloud php occ config:system:set redis host --value="redis"
docker exec -u www-data nextcloud php occ config:system:set redis port --value="6379"
docker exec -u www-data nextcloud php occ config:system:set redis timeout --value="1.5"
docker exec -u www-data nextcloud php occ config:system:set memcache.local --value="\\OC\\Memcache\\Redis"
docker exec -u www-data nextcloud php occ config:system:set memcache.locking --value="\\OC\\Memcache\\Redis"

# Set file locking
docker exec -u www-data nextcloud php occ config:system:set filelocking.enabled --value=true --type=boolean

# Configure background jobs
docker exec -u www-data nextcloud php occ background:cron

echo "===== Nextcloud OIDC Configuration Complete ====="
echo ""
echo "Next steps:"
echo "1. Navigate to https://nextcloud.stack.local"
echo "2. Click 'Login with SSO' button"
echo "3. Authenticate with Authelia using:"
echo "   Username: admin"
echo "   Password: DatamancyTest2025!"
echo ""
