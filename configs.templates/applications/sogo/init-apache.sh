#!/bin/bash
# Configure SOGo Apache for auto-login via Authelia forward-auth headers
# This script runs inside the SOGo container on startup

set -e

SOGO_CONF="/etc/apache2/conf-enabled/SOGo.conf"

echo "[sogo-init] Configuring Apache for proxy authentication..."

# Enable redirect from / to /SOGo
sed -i 's|^#RedirectMatch \^/\$ .*|RedirectMatch ^/$ /SOGo|' "$SOGO_CONF"

# Comment out the line that unsets the remote user header
sed -i 's|^    RequestHeader unset "x-webobjects-remote-user"|#    RequestHeader unset "x-webobjects-remote-user"|' "$SOGO_CONF"

# Enable reading Remote-User header from Caddy and pass to SOGo as x-webobjects-remote-user
# Apache must map the incoming Remote-User header to x-webobjects-remote-user
if ! grep -q 'RequestHeader set "x-webobjects-remote-user" "%{Remote-User}i"' "$SOGO_CONF"; then
    # Insert after the comment about proxy-side authentication
    sed -i '/## When using proxy-side autentication/a\    RequestHeader set "x-webobjects-remote-user" "%{Remote-User}i"' "$SOGO_CONF"
else
    # Already exists, ensure it's uncommented and correct
    sed -i 's|^#*    RequestHeader set "x-webobjects-remote-user".*|    RequestHeader set "x-webobjects-remote-user" "%{Remote-User}i"|' "$SOGO_CONF"
fi

echo "[sogo-init] Apache configuration updated successfully"

# Reload Apache gracefully
apache2ctl graceful 2>&1 || true

echo "[sogo-init] Apache reloaded"
