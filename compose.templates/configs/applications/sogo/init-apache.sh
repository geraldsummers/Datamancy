#!/bin/bash
# Configure SOGo Apache for auto-login via Authelia forward-auth headers
# This script runs inside the SOGo container on startup (IDEMPOTENT - safe to run multiple times)

set -e

SOGO_CONF="/etc/apache2/conf-enabled/SOGo.conf"

echo "[sogo-init] Configuring Apache for proxy authentication..."

# Enable necessary Apache modules (idempotent)
a2enmod rewrite headers 2>&1 || true

# Check if already configured by looking for our marker comment
if grep -q "# DATAMANCY_CONFIGURED" "$SOGO_CONF"; then
    echo "[sogo-init] Apache already configured, verifying config is correct..."

    # Verify critical settings are still present
    if grep -q "RewriteEngine On" "$SOGO_CONF" && \
       grep -q "REMOTE_USER" "$SOGO_CONF" && \
       grep -q "RedirectMatch" "$SOGO_CONF"; then
        echo "[sogo-init] Configuration verified, reloading Apache..."
        apache2ctl graceful 2>&1 || true
        exit 0
    else
        echo "[sogo-init] Configuration incomplete, reconfiguring..."
        # Remove marker to force reconfiguration
        sed -i '/# DATAMANCY_CONFIGURED/d' "$SOGO_CONF"
    fi
fi

# Create a backup of original config if not already backed up
if [ ! -f "$SOGO_CONF.original" ]; then
    cp "$SOGO_CONF" "$SOGO_CONF.original"
fi

# Start with original config for full idempotency
cp "$SOGO_CONF.original" "$SOGO_CONF"

# Enable redirect from / to /SOGo
sed -i 's|^#RedirectMatch \^/\$ .*|RedirectMatch ^/$ /SOGo|' "$SOGO_CONF"

# Add RewriteEngine rules in correct location (right after <Proxy> line, in rewrite_module block)
sed -i "/<Proxy http:\/\/127.0.0.1:20000\/SOGo>/a\  <IfModule rewrite_module>\n    RewriteEngine On\n    RewriteCond %{HTTP:Remote-User} (.+)\n    RewriteRule .* - [E=REMOTE_USER:%1]\n  </IfModule>" "$SOGO_CONF"

# Comment out the RequestHeader unset line
sed -i 's|^[[:space:]]*RequestHeader unset "x-webobjects-remote-user"|#    RequestHeader unset "x-webobjects-remote-user"|' "$SOGO_CONF"

# Uncomment and ensure RequestHeader line for x-webobjects-remote-user uses REMOTE_USER
sed -i 's|^#[[:space:]]*RequestHeader set "x-webobjects-remote-user".*|    RequestHeader set "x-webobjects-remote-user" "%{REMOTE_USER}e"|' "$SOGO_CONF"
sed -i 's|RequestHeader set "x-webobjects-remote-user" "%{Remote-User}i"|RequestHeader set "x-webobjects-remote-user" "%{REMOTE_USER}e"|' "$SOGO_CONF"

# Add marker comment at the top
sed -i "1i# DATAMANCY_CONFIGURED - Managed by init-apache.sh (idempotent)" "$SOGO_CONF"

echo "[sogo-init] Apache configuration updated successfully"

# Reload Apache gracefully
apache2ctl graceful 2>&1 || true

echo "[sogo-init] Apache reloaded"
