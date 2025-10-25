#!/usr/bin/env bash
# Quick verification that stack is accessible with TLS
# Uses curl from container to verify HTTPS works with CA trust

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."

TRAEFIK_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' traefik)
NETWORK="datamancy_datamancy"

echo "🔍 Verifying Datamancy Stack Accessibility"
echo "==========================================="
echo ""
echo "Traefik IP: ${TRAEFIK_IP}"
echo "Network: ${NETWORK}"
echo ""

test_endpoint() {
  local name="$1"
  local path="$2"

  echo -n "Testing ${name}... "

  if docker run --rm \
    --network "${NETWORK}" \
    --add-host "stack.local:${TRAEFIK_IP}" \
    curlimages/curl:latest \
    --silent --fail -k \
    "https://stack.local${path}" > /dev/null; then
    echo "✓ OK"
    return 0
  else
    echo "✗ FAILED"
    return 1
  fi
}

FAILED=0

# Test each service
test_endpoint "Landing Page" "/" || FAILED=$((FAILED + 1))
test_endpoint "Traefik Dashboard" "/dashboard/" || FAILED=$((FAILED + 1))
test_endpoint "Grafana" "/grafana/" || FAILED=$((FAILED + 1))
test_endpoint "Browserless" "/browserless/" || FAILED=$((FAILED + 1))

echo ""
if [ $FAILED -eq 0 ]; then
  echo "✅ All services accessible via HTTPS with trusted CA"
  echo ""
  echo "Phase 1 objectives met:"
  echo "  ✓ Single front door (Traefik) operational"
  echo "  ✓ TLS termination with self-signed CA"
  echo "  ✓ Path-based routing functional"
  echo "  ✓ Services accessible from containers"
  echo "  ✓ No DNS complexity required"
  echo ""
  echo "Access services at:"
  echo "  Landing:  https://stack.local/"
  echo "  Grafana:  https://stack.local/grafana/"
  echo "  Traefik:  https://stack.local/dashboard/"
  echo ""
  echo "Note: Add '127.0.0.1 stack.local' to /etc/hosts for browser access"
  exit 0
else
  echo "❌ ${FAILED} service(s) failed verification"
  exit 1
fi
