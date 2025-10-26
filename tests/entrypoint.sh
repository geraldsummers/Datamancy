#!/usr/bin/env bash
# Test Runner Entrypoint
# Provenance: Phase 1 agent autonomy - dynamic hostname resolution
set -euo pipefail

# Trust custom CA if present
if [ -f /usr/local/share/ca-certificates/datamancy-ca.crt ]; then
  echo "==> Trusting custom CA"
  update-ca-certificates
fi

# Resolve Caddy IP dynamically
CADDY_IP=$(getent hosts caddy | awk '{ print $1 }')
echo "==> Caddy IP: $CADDY_IP"

# Add hostname mappings
echo "$CADDY_IP grafana.stack.local" >> /etc/hosts
echo "$CADDY_IP stack.local" >> /etc/hosts

echo "==> Hostname resolution configured"
cat /etc/hosts | tail -3

# Run tests
exec npx playwright test "$@"
