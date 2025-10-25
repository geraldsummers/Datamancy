#!/bin/bash
# Provenance: Grafana CA certificate trust setup
# Purpose: Trust self-signed CA before starting Grafana
# Architecture: Debian-based Grafana image uses update-ca-certificates

set -e

# Install CA certificate if present (running as root temporarily)
if [ -f "/usr/local/share/ca-certificates/datamancy-ca.crt" ]; then
    update-ca-certificates 2>/dev/null || true
    echo "✓ Grafana: CA certificates updated"
fi

# Change ownership of grafana data directory
chown -R grafana:grafana /var/lib/grafana 2>/dev/null || true

# Drop privileges to grafana user and start Grafana
exec gosu grafana /run.sh "$@"
