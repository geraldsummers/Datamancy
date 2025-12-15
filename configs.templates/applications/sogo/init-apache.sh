#!/bin/bash
# Configure SOGo Apache for auto-login via Authelia forward-auth headers
# This script runs inside the SOGo container on startup

set -e

SOGO_CONF="/etc/apache2/conf-enabled/SOGo.conf"

echo "[sogo-init] Configuring Apache for proxy authentication..."

# Enable necessary Apache modules
a2enmod rewrite headers 2>&1 || true

# Enable redirect from / to /SOGo
sed -i 's|^#RedirectMatch \^/\$ .*|RedirectMatch ^/$ /SOGo|' "$SOGO_CONF"

# Remove any existing RewriteEngine rules from wrong locations
sed -i "/  <IfModule headers_module>/,/<\/IfModule>/ {
  /RewriteEngine On/d
  /RewriteCond %{HTTP:Remote-User}/d
  /RewriteRule .* - \[E=REMOTE_USER:/d
}" "$SOGO_CONF"

# Add RewriteEngine rules in correct location (right after <Proxy> line, in rewrite_module block)
if ! grep -A5 "<Proxy http://127.0.0.1:20000/SOGo>" "$SOGO_CONF" | grep -q "RewriteEngine On"; then
    sed -i "/<Proxy http:\/\/127.0.0.1:20000\/SOGo>/a\  <IfModule rewrite_module>\n    RewriteEngine On\n    RewriteCond %{HTTP:Remote-User} (.+)\n    RewriteRule .* - [E=REMOTE_USER:%1]\n  </IfModule>" "$SOGO_CONF"
    echo "[sogo-init] Added RewriteEngine rules in rewrite_module block"
fi

# Comment out the RequestHeader unset line
sed -i 's|^    RequestHeader unset "x-webobjects-remote-user"|#    RequestHeader unset "x-webobjects-remote-user"|' "$SOGO_CONF"

# Uncomment the RequestHeader line for x-webobjects-remote-user
sed -i 's|^#    RequestHeader set "x-webobjects-remote-user"|    RequestHeader set "x-webobjects-remote-user"|' "$SOGO_CONF"

# Ensure it uses REMOTE_USER environment variable
sed -i 's|RequestHeader set "x-webobjects-remote-user" "%{Remote-User}i"|RequestHeader set "x-webobjects-remote-user" "%{REMOTE_USER}e"|' "$SOGO_CONF"

echo "[sogo-init] Apache configuration updated successfully"

# Reload Apache gracefully
apache2ctl graceful 2>&1 || true

echo "[sogo-init] Apache reloaded"
