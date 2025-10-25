#!/bin/bash
# Provenance: Browserless CA certificate trust setup
# Purpose: Trust self-signed CA before starting Browserless

set -e

# Update CA certificates as root
if [ -f "/usr/local/share/ca-certificates/datamancy-ca.crt" ]; then
    update-ca-certificates 2>/dev/null || true
    echo "✓ Browserless: CA certificates updated"
fi

# Note: Running as root due to lack of privilege-dropping tools in base image
# TODO: Build custom image with gosu/su-exec for proper privilege dropping
exec /usr/src/app/start.sh
