#!/bin/bash
# Process template and substitute environment variables in Jellyfin SSO config
set -e

export BASE_DOMAIN=${BASE_DOMAIN:-lab.localhost}
export OIDC_JELLYFIN_CLIENT_SECRET=${OIDC_JELLYFIN_CLIENT_SECRET:-jellyfin_oidc_secret_change_me}

# Create config directory if it doesn't exist
mkdir -p /config/plugins/configurations

# Substitute environment variables in the template
envsubst < /tmp/sso-config.xml > /config/plugins/configurations/SSO-Auth.xml

# Call the original entrypoint
exec /init
