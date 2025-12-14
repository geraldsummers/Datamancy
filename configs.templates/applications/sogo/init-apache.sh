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
# Apache mod_rewrite is required to capture HTTP headers as environment variables
if ! grep -q 'RewriteEngine On' "$SOGO_CONF"; then
    sed -i '/## When using proxy-side autentication/a\    RewriteEngine On\n    RewriteCond %{HTTP:Remote-User} (.+)\n    RewriteRule .* - [E=REMOTE_USER:%1]' "$SOGO_CONF"
fi

# Change the RequestHeader line to use REMOTE_USER environment variable instead of Remote-User input header
sed -i 's|RequestHeader set "x-webobjects-remote-user" "%{Remote-User}i"|RequestHeader set "x-webobjects-remote-user" "%{REMOTE_USER}e"|' "$SOGO_CONF"

echo "[sogo-init] Apache configuration updated successfully"

# Reload Apache gracefully
apache2ctl graceful 2>&1 || true

echo "[sogo-init] Apache reloaded"
