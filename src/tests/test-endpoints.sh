#!/bin/bash
# Live Integration Test: Verify all service endpoints respond

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Live Test: Service Endpoints ==="

cd "$PROJECT_DIR"

# Load BASE_DOMAIN from .env
if [[ -f .env ]]; then
    set -a; source <(grep -v "^#" .env | grep -v "^$" | sed "s/\\s*#.*$//"); set +a
fi

BASE_DOMAIN="${BASE_DOMAIN:-lab.localhost}"

FAIL=0

# Test internal endpoints (container-to-container)
echo ""
echo "Testing internal endpoints..."

# Prometheus
if docker compose exec -T prometheus wget -q -O- http://localhost:9090/-/healthy 2>/dev/null | grep -q "Prometheus Server is Healthy"; then
    echo "✅ Prometheus internal health: OK"
else
    echo "❌ Prometheus internal health: FAIL"
    FAIL=1
fi

# Alertmanager
if docker compose exec -T alertmanager wget -q -O- http://localhost:9093/-/healthy 2>/dev/null; then
    echo "✅ Alertmanager internal health: OK"
else
    echo "❌ Alertmanager internal health: FAIL"
    FAIL=1
fi

# Loki
if docker compose exec -T loki wget -q -O- http://localhost:3100/ready 2>/dev/null | grep -q "ready"; then
    echo "✅ Loki internal ready: OK"
else
    echo "❌ Loki internal ready: FAIL"
    FAIL=1
fi

# Grafana
if docker compose exec -T grafana wget -q -O- http://localhost:3000/api/health 2>/dev/null | grep -q "ok"; then
    echo "✅ Grafana internal health: OK"
else
    echo "❌ Grafana internal health: FAIL"
    FAIL=1
fi

# Authelia
if docker compose exec -T authelia wget -q -O- http://localhost:9091/api/health 2>/dev/null; then
    echo "✅ Authelia internal health: OK"
else
    echo "❌ Authelia internal health: FAIL"
    FAIL=1
fi

# Blackbox exporter
if docker compose exec -T blackbox-exporter wget -q -O- http://localhost:9115/ 2>/dev/null | grep -q "Blackbox Exporter"; then
    echo "✅ Blackbox exporter internal: OK"
else
    echo "❌ Blackbox exporter internal: FAIL"
    FAIL=1
fi

# Script exporter
if docker compose exec -T prometheus-script-exporter wget -q -O- http://localhost:9469/ 2>/dev/null | grep -q "Script Exporter"; then
    echo "✅ Script exporter internal: OK"
else
    echo "❌ Script exporter internal: FAIL"
    FAIL=1
fi

# Test Caddy routing (external URLs via Caddy)
echo ""
echo "Testing Caddy routing (requires /etc/hosts entries or DNS)..."
echo "NOTE: These tests check if Caddy routes are configured, not full auth flow"

CADDY_ROUTES=(
    "https://whoami.${BASE_DOMAIN}"
    "https://id.${BASE_DOMAIN}"
    "https://prometheus.${BASE_DOMAIN}"
    "https://alertmanager.${BASE_DOMAIN}"
    "https://loki.${BASE_DOMAIN}"
    "https://grafana.${BASE_DOMAIN}"
)

for url in "${CADDY_ROUTES[@]}"; do
    # Check if URL resolves and Caddy responds (even with auth redirect)
    # We expect either a 200 (if no auth) or 302/401 (auth redirect)
    HTTP_CODE=$(curl -k -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")

    if [[ "$HTTP_CODE" =~ ^(200|302|401|307)$ ]]; then
        echo "✅ Caddy route responding: $url (HTTP $HTTP_CODE)"
    else
        echo "⚠️  Caddy route issue: $url (HTTP $HTTP_CODE - may need /etc/hosts or DNS)"
        # Don't fail on this since it might be DNS/hosts issue
    fi
done

# Test Prometheus targets
echo ""
echo "Testing Prometheus scrape targets..."
PROM_TARGETS=$(docker compose exec -T prometheus wget -q -O- http://localhost:9090/api/v1/targets 2>/dev/null)

if echo "$PROM_TARGETS" | grep -q '"health":"up"'; then
    UP_COUNT=$(echo "$PROM_TARGETS" | grep -o '"health":"up"' | wc -l)
    echo "✅ Prometheus has $UP_COUNT targets UP"
else
    echo "❌ Prometheus has no targets UP"
    FAIL=1
fi

# Test Grafana datasources
echo ""
echo "Testing Grafana datasources..."
GF_USER="${GF_SECURITY_ADMIN_USER:-admin}"
GF_PASS="${GF_SECURITY_ADMIN_PASSWORD:-admin}"

DATASOURCES=$(docker compose exec -T grafana wget -q --user="$GF_USER" --password="$GF_PASS" -O- http://localhost:3000/api/datasources 2>/dev/null)

if echo "$DATASOURCES" | grep -q "Prometheus"; then
    echo "✅ Grafana Prometheus datasource: OK"
else
    echo "❌ Grafana Prometheus datasource: MISSING"
    FAIL=1
fi

if echo "$DATASOURCES" | grep -q "Loki"; then
    echo "✅ Grafana Loki datasource: OK"
else
    echo "❌ Grafana Loki datasource: MISSING"
    FAIL=1
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
    echo "✅ PASS: All endpoint tests passed"
    exit 0
else
    echo "❌ FAIL: Some endpoint tests failed"
    exit 1
fi
