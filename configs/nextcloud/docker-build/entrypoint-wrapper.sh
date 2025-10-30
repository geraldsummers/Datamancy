#!/bin/bash
set -e

# Add Caddy CA to runtime certificate bundle
/usr/local/bin/add-cert-runtime.sh

# Run original Nextcloud entrypoint
exec /entrypoint-original.sh "$@"
