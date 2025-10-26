#!/bin/bash
# Test runner for Caddy Security authentication and authorization
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "==> Caddy Security Test Suite"
echo ""

# Check if services are running
echo "==> Checking required services..."
if ! docker compose ps caddy | grep -q "Up"; then
    echo "❌ Caddy is not running. Start services first:"
    echo "   docker compose --profile infra --profile phase2 --profile phase3 up -d"
    exit 1
fi

if ! docker compose ps openldap | grep -q "Up"; then
    echo "❌ OpenLDAP is not running. Start services first:"
    echo "   docker compose --profile infra --profile phase2 --profile phase3 up -d"
    exit 1
fi

echo "✓ Caddy is running"
echo "✓ OpenLDAP is running"
echo ""

# Test LDAP connection
echo "==> Testing LDAP connection..."
docker compose exec openldap ldapsearch -x -H ldap://localhost \
    -b "dc=datamancy,dc=local" \
    -D "cn=admin,dc=datamancy,dc=local" \
    -w admin_password \
    "(uid=admin)" uid cn mail >/dev/null 2>&1

if [ $? -eq 0 ]; then
    echo "✓ LDAP connection successful"
else
    echo "❌ LDAP connection failed"
    exit 1
fi
echo ""

# Test Caddy configuration
echo "==> Validating Caddy configuration..."
docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile
if [ $? -eq 0 ]; then
    echo "✓ Caddy configuration is valid"
else
    echo "❌ Caddy configuration has errors"
    exit 1
fi
echo ""

# Test basic endpoints
echo "==> Testing unauthenticated endpoints..."

# Homepage (should work)
if curl -k -s -o /dev/null -w "%{http_code}" https://stack.local/ | grep -q "200"; then
    echo "✓ Homepage accessible (200)"
else
    echo "⚠ Homepage returned non-200"
fi

# Metrics endpoints (should bypass auth)
if curl -k -s https://stack.local/prometheus/metrics | grep -q "# HELP"; then
    echo "✓ Prometheus metrics accessible without auth"
else
    echo "⚠ Prometheus metrics may require auth"
fi

if curl -k -s https://stack.local/alertmanager/metrics | grep -q "# HELP"; then
    echo "✓ Alertmanager metrics accessible without auth"
else
    echo "⚠ Alertmanager metrics may require auth"
fi

if curl -k -s https://stack.local/grafana/api/health | grep -q "ok"; then
    echo "✓ Grafana health endpoint accessible without auth"
else
    echo "⚠ Grafana health endpoint may require auth"
fi

echo ""

# Run Playwright tests
echo "==> Running Playwright test suite..."
echo ""

cd "$PROJECT_ROOT/tests"

if [ "$1" == "rbac" ]; then
    echo "Running RBAC tests only..."
    npx playwright test caddy-security-rbac-test.spec.js --config playwright.config.caddy-security.js
elif [ "$1" == "oidc-grafana" ]; then
    echo "Running Grafana OIDC tests only..."
    npx playwright test caddy-security-oidc-grafana-test.spec.js --config playwright.config.caddy-security.js
elif [ "$1" == "oidc-librechat" ]; then
    echo "Running LibreChat OIDC tests only..."
    npx playwright test caddy-security-oidc-librechat-test.spec.js --config playwright.config.caddy-security.js
else
    echo "Running all Caddy Security tests..."
    npx playwright test --config playwright.config.caddy-security.js
fi

TEST_EXIT_CODE=$?

echo ""
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "========================================="
    echo "✓ All Caddy Security tests passed!"
    echo "========================================="
else
    echo "========================================="
    echo "❌ Some tests failed (exit code: $TEST_EXIT_CODE)"
    echo "========================================="
    echo ""
    echo "Check screenshots in: data/tests/test-results/"
    echo "View HTML report: npm run report"
fi

exit $TEST_EXIT_CODE
