#!/bin/bash
# Provenance: Docker DNS resolution for single front door testing
# Purpose: Resolve traefik service to stack.local hostname and configure TLS trust

set -e

# Resolve traefik container IP and add to /etc/hosts
TRAEFIK_IP=$(getent hosts traefik | awk '{ print $1 }')

if [ -n "$TRAEFIK_IP" ]; then
    echo "$TRAEFIK_IP stack.local" >> /etc/hosts
    echo "✓ Mapped stack.local -> $TRAEFIK_IP"
else
    echo "⚠ Warning: Could not resolve traefik service"
fi

# Trust CA certificate in system store (for Node.js and curl)
if [ -f "/usr/local/share/ca-certificates/datamancy-ca.crt" ]; then
    update-ca-certificates 2>/dev/null || true
    echo "✓ System CA certificate store updated"
fi

# Create NSS database for Firefox certificate trust
# Firefox uses NSS (Network Security Services) for certificate validation
if [ -f "/usr/local/share/ca-certificates/datamancy-ca.crt" ]; then
    # Install NSS tools if not present
    if ! command -v certutil &> /dev/null; then
        apt-get update -qq && apt-get install -y -qq libnss3-tools > /dev/null 2>&1
        echo "✓ Installed NSS tools"
    fi

    # Use persistent Firefox profile directory (bind-mounted)
    PROFILE_DIR="/firefox-profile"
    mkdir -p "$PROFILE_DIR"

    # Initialize NSS database for Firefox if not exists
    if [ ! -f "$PROFILE_DIR/cert9.db" ]; then
        certutil -d "sql:$PROFILE_DIR" -N --empty-password 2>/dev/null || true
        echo "✓ Initialized Firefox NSS certificate database"
    fi

    # Import CA certificate into Firefox NSS database with trust flags
    # Trust flags: C = trusted CA, T = trusted for SSL, p = trusted peer
    # Check if cert already exists to avoid duplicate errors
    if ! certutil -d "sql:$PROFILE_DIR" -L -n "Datamancy CA" 2>/dev/null; then
        certutil -d "sql:$PROFILE_DIR" -A -t "CT,," -n "Datamancy CA" -i /usr/local/share/ca-certificates/datamancy-ca.crt 2>/dev/null || true
        echo "✓ CA certificate imported into Firefox trust store"
    else
        echo "✓ CA certificate already in Firefox trust store"
    fi
fi

# Execute the original command
# Note: Running as root due to complexity of privilege-dropping in entrypoint scripts
# TODO: Build custom image with proper USER directive and gosu/su-exec
# If running tests, use freshness tracking wrapper
if [[ "$1" == "npx" ]] && [[ "$2" == "playwright" ]] && [[ "$3" == "test" ]]; then
    shift 3  # Remove 'npx playwright test'
    exec /tests/run-with-freshness.sh "$@"
else
    exec "$@"
fi
