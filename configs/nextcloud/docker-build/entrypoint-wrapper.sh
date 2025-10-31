#!/bin/bash
set -e

# Add Caddy CA to runtime certificate bundle
/usr/local/bin/add-cert-runtime.sh

# Start delayed OIDC installation check in background
if [ -f /usr/local/bin/delayed-oidc-install.sh ]; then
    /usr/local/bin/delayed-oidc-install.sh &
fi

# Run original Nextcloud entrypoint
exec /entrypoint-original.sh "$@"
