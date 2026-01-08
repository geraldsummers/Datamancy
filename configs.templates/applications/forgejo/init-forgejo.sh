#!/bin/bash
# Forgejo OIDC Configuration Script - IDEMPOTENT (safe to run on every container start)
# This script configures Authelia as an OIDC authentication source in Forgejo
# NOTE: This script must run as the git user (UID 1000), not root

set -e

# Wait for Forgejo to be fully ready
echo "Waiting for Forgejo to be ready..."
sleep 10

# Set the config file path for Forgejo
export FORGEJO_WORK_DIR=/data/gitea
export FORGEJO_CUSTOM=/data/gitea

# Check if OIDC auth source already exists
if forgejo --config /data/gitea/conf/app.ini admin auth list 2>/dev/null | grep -q "Authelia"; then
    echo "Authelia OIDC authentication source already exists, skipping setup."
    exit 0
fi

# Add Authelia as an OIDC authentication source
echo "Adding Authelia OIDC authentication source to Forgejo..."

forgejo --config /data/gitea/conf/app.ini admin auth add-oauth \
    --name 'Authelia' \
    --provider 'openidConnect' \
    --key 'forgejo' \
    --secret "${FORGEJO_OAUTH_SECRET}" \
    --auto-discover-url "https://auth.${DOMAIN}/.well-known/openid-configuration" \
    --scopes 'openid profile email groups' \
    --group-claim-name 'groups' \
    --admin-group 'admins' \
    --restricted-group '' \
    --skip-local-2fa

echo "Forgejo OIDC configuration completed successfully!"
echo "Users can now sign in with the 'Sign in with Authelia' button."
