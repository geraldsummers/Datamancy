#!/bin/bash
# Phase 1 Simple Smoke Test - HTTP-based validation
# Provenance: Single front door validation
# Architecture: Test path routing and service availability

set -e

echo "==> Phase 1 Smoke Test Starting..."

# Test 1: Homepage loads
echo "Test 1: Homepage loads"
STATUS=$(curl -k -s -o /dev/null -w "%{http_code}" https://stack.local/)
if [ "$STATUS" = "200" ]; then
    echo "✓ Homepage returned 200 OK"
else
    echo "✗ Homepage returned $STATUS"
    exit 1
fi

# Test 2: Grafana loads
echo "Test 2: Grafana loads"
STATUS=$(curl -k -s -o /dev/null -w "%{http_code}" https://stack.local/grafana/)
if [ "$STATUS" = "200" ]; then
    echo "✓ Grafana returned 200 OK"
else
    echo "✗ Grafana returned $STATUS"
    exit 1
fi

# Test 3: Traefik dashboard loads
echo "Test 3: Traefik dashboard loads"
STATUS=$(curl -k -s -o /dev/null -w "%{http_code}" https://stack.local/dashboard/)
if [ "$STATUS" = "200" ]; then
    echo "✓ Traefik dashboard returned 200 OK"
else
    echo "✗ Traefik dashboard returned $STATUS"
    exit 1
fi

# Test 4: Verify TLS is in use
echo "Test 4: Verify TLS is in use"
PROTOCOL=$(curl -k -s -o /dev/null -w "%{http_version}" https://stack.local/)
if [[ "$PROTOCOL" =~ ^[23] ]]; then
    echo "✓ TLS verified (HTTP/$PROTOCOL)"
else
    echo "✗ TLS verification failed"
    exit 1
fi

echo ""
echo "==> All Phase 1 tests passed!"
echo "✓ Single front door operational"
echo "✓ Path-based routing functional"
echo "✓ TLS termination working"
