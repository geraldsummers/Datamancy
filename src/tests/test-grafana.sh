#!/bin/bash
# Live Integration Test: Verify Grafana and datasources

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Live Test: Grafana and Datasources ==="

cd "$PROJECT_DIR"

# Load credentials from .env
if [[ -f .env ]]; then
    set -a; source <(grep -v "^#" .env | grep -v "^$" | sed "s/\\s*#.*$//"); set +a
fi

GF_USER="${GF_SECURITY_ADMIN_USER:-admin}"
GF_PASS="${GF_SECURITY_ADMIN_PASSWORD:-admin}"

FAIL=0

# Test 1: Grafana health
echo ""
echo "Test 1: Grafana health endpoint..."
HEALTH=$(docker compose exec -T prometheus wget -q -O- http://grafana:3000/api/health 2>/dev/null)

if echo "$HEALTH" | grep -q '"database".*"ok"'; then
    echo "✅ Grafana health: OK"
else
    echo "❌ Grafana health: FAIL"
    echo "$HEALTH"
    FAIL=1
fi

# Test 2: Grafana API authentication
echo ""
echo "Test 2: Grafana API authentication..."
if docker compose exec -T prometheus wget -q --header="Authorization: Basic $(echo -n "$GF_USER:$GF_PASS" | base64)" -O- http://grafana:3000/api/org 2>/dev/null | grep -q '"name"'; then
    echo "✅ Grafana API auth: OK"
else
    echo "❌ Grafana API auth: FAIL"
    FAIL=1
fi

# Test 3: List datasources
echo ""
echo "Test 3: Checking provisioned datasources..."
DATASOURCES=$(docker compose exec -T prometheus wget -q --header="Authorization: Basic $(echo -n "$GF_USER:$GF_PASS" | base64)" -O- http://grafana:3000/api/datasources 2>/dev/null)

# Check Prometheus datasource
if echo "$DATASOURCES" | grep -q '"name":"Prometheus"'; then
    PROM_ID=$(echo "$DATASOURCES" | grep -A5 '"name":"Prometheus"' | grep '"id":' | head -1 | grep -o '[0-9]*')
    echo "✅ Prometheus datasource found (ID: $PROM_ID)"
else
    echo "❌ Prometheus datasource: NOT FOUND"
    FAIL=1
fi

# Check Loki datasource
if echo "$DATASOURCES" | grep -q '"name":"Loki"'; then
    LOKI_ID=$(echo "$DATASOURCES" | grep -A5 '"name":"Loki"' | grep '"id":' | head -1 | grep -o '[0-9]*')
    echo "✅ Loki datasource found (ID: $LOKI_ID)"
else
    echo "❌ Loki datasource: NOT FOUND"
    FAIL=1
fi

# Test 4: Test Prometheus datasource connectivity
echo ""
echo "Test 4: Testing Prometheus datasource connectivity..."
if [[ -n "$PROM_ID" ]]; then
    PROM_TEST=$(docker compose exec -T prometheus wget -q --header="Authorization: Basic $(echo -n "$GF_USER:$GF_PASS" | base64)" -O- "http://grafana:3000/api/datasources/proxy/$PROM_ID/api/v1/query?query=up" 2>/dev/null)

    if echo "$PROM_TEST" | grep -q '"status":"success"'; then
        echo "✅ Prometheus datasource query: OK"
    else
        echo "❌ Prometheus datasource query: FAIL"
        FAIL=1
    fi
else
    echo "⚠️  Skipping Prometheus datasource test (not found)"
fi

# Test 5: Test Loki datasource connectivity
echo ""
echo "Test 5: Testing Loki datasource connectivity..."
if [[ -n "$LOKI_ID" ]]; then
    LOKI_TEST=$(docker compose exec -T prometheus wget -q --header="Authorization: Basic $(echo -n "$GF_USER:$GF_PASS" | base64)" -O- "http://grafana:3000/api/datasources/proxy/$LOKI_ID/loki/api/v1/labels" 2>/dev/null)

    if echo "$LOKI_TEST" | grep -q '"status":"success"'; then
        echo "✅ Loki datasource query: OK"
    else
        echo "❌ Loki datasource query: FAIL"
        FAIL=1
    fi
else
    echo "⚠️  Skipping Loki datasource test (not found)"
fi

# Test 6: Check if Grafana can query metrics
echo ""
echo "Test 6: Querying Prometheus metrics via Grafana..."
QUERY_RESULT=$(docker compose exec -T prometheus wget -q --header="Authorization: Basic $(echo -n "$GF_USER:$GF_PASS" | base64)" --post-data='{"queries":[{"refId":"A","datasource":{"type":"prometheus","uid":"prometheus"},"expr":"up","range":true,"instant":false}],"from":"now-5m","to":"now"}' --header='Content-Type: application/json' -O- http://grafana:3000/api/ds/query 2>/dev/null || echo "{}")

if echo "$QUERY_RESULT" | grep -q '"frames"'; then
    echo "✅ Grafana Prometheus query: OK"
else
    echo "⚠️  Grafana Prometheus query: No results (may be initializing)"
fi

# Test 7: Check auth proxy headers (if set)
echo ""
echo "Test 7: Checking auth proxy configuration..."
AUTH_SETTINGS=$(docker compose exec -T prometheus wget -q --header="Authorization: Basic $(echo -n "$GF_USER:$GF_PASS" | base64)" -O- http://grafana:3000/api/admin/settings 2>/dev/null)

if echo "$AUTH_SETTINGS" | grep -q '"auth.proxy"'; then
    if echo "$AUTH_SETTINGS" | grep -q '"enabled":"true"'; then
        echo "✅ Auth proxy enabled: OK"
    else
        echo "⚠️  Auth proxy: Not enabled"
    fi
else
    echo "⚠️  Auth proxy settings not found"
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
    echo "✅ PASS: All Grafana tests passed"
    exit 0
else
    echo "❌ FAIL: Some Grafana tests failed"
    exit 1
fi
