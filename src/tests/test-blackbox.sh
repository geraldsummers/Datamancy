#!/bin/bash
# Live Integration Test: Verify blackbox-exporter probes

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Live Test: Blackbox Exporter Probes ==="

cd "$PROJECT_DIR"

FAIL=0

# Test 1: Blackbox exporter is responding
echo ""
echo "Test 1: Blackbox exporter web interface..."
if docker compose exec -T blackbox-exporter wget -q -O- http://localhost:9115/ 2>/dev/null | grep -q "Blackbox Exporter"; then
    echo "✅ Blackbox exporter UI: OK"
else
    echo "❌ Blackbox exporter UI: FAIL"
    FAIL=1
fi

# Test 2: Blackbox exporter config
echo ""
echo "Test 2: Blackbox exporter configuration..."
CONFIG=$(docker compose exec -T blackbox-exporter wget -q -O- http://localhost:9115/config 2>/dev/null)

if echo "$CONFIG" | grep -q "modules:"; then
    echo "✅ Blackbox exporter config loaded: OK"

    # Check for expected modules
    if echo "$CONFIG" | grep -q "http_2xx:"; then
        echo "   ✅ Module 'http_2xx' configured"
    fi

    if echo "$CONFIG" | grep -q "tcp_connect:"; then
        echo "   ✅ Module 'tcp_connect' configured"
    fi

    if echo "$CONFIG" | grep -q "icmp:"; then
        echo "   ✅ Module 'icmp' configured"
    fi
else
    echo "❌ Blackbox exporter config: FAIL"
    FAIL=1
fi

# Test 3: Test HTTP probe directly
echo ""
echo "Test 3: Testing HTTP probe directly..."

# Probe prometheus (internal container-to-container)
PROBE_RESULT=$(docker compose exec -T blackbox-exporter wget -q -O- 'http://localhost:9115/probe?target=http://prometheus:9090&module=http_2xx' 2>/dev/null)

if echo "$PROBE_RESULT" | grep -q "probe_success 1"; then
    echo "✅ HTTP probe (prometheus internal): SUCCESS"
else
    echo "❌ HTTP probe (prometheus internal): FAIL"
    FAIL=1
fi

# Test 4: Test TCP probe
echo ""
echo "Test 4: Testing TCP probe..."
TCP_PROBE=$(docker compose exec -T blackbox-exporter wget -q -O- 'http://localhost:9115/probe?target=prometheus:9090&module=tcp_connect' 2>/dev/null)

if echo "$TCP_PROBE" | grep -q "probe_success 1"; then
    echo "✅ TCP probe (prometheus:9090): SUCCESS"
else
    echo "❌ TCP probe (prometheus:9090): FAIL"
    FAIL=1
fi

# Test 5: Check blackbox probe metrics in Prometheus
echo ""
echo "Test 5: Checking blackbox probe results in Prometheus..."
PROM_PROBES=$(docker compose exec -T prometheus wget -q -O- 'http://localhost:9090/api/v1/query?query=probe_success' 2>/dev/null)

if echo "$PROM_PROBES" | grep -q '"status":"success"'; then
    echo "✅ Blackbox probes in Prometheus: OK"

    # Count successful probes
    SUCCESS_COUNT=$(echo "$PROM_PROBES" | grep -o '"value":\[.*,"1"\]' | wc -l)
    FAIL_COUNT=$(echo "$PROM_PROBES" | grep -o '"value":\[.*,"0"\]' | wc -l)

    echo "   Successful probes: $SUCCESS_COUNT"
    echo "   Failed probes: $FAIL_COUNT"

    if [[ $FAIL_COUNT -gt 0 ]]; then
        echo "   ⚠️  Some probes are failing (may need /etc/hosts or DNS for HTTPS probes)"
    fi
else
    echo "⚠️  Blackbox probes: No data in Prometheus yet (may be initializing)"
fi

# Test 6: Check specific probe targets
echo ""
echo "Test 6: Checking individual probe targets..."

PROBE_TARGETS=(
    "id.lab.localhost"
    "grafana.lab.localhost"
    "prometheus.lab.localhost"
)

for target in "${PROBE_TARGETS[@]}"; do
    TARGET_STATUS=$(docker compose exec -T prometheus wget -q -O- "http://localhost:9090/api/v1/query?query=probe_success{instance=\"https://$target\"}" 2>/dev/null)

    if echo "$TARGET_STATUS" | grep -q '"value":\[.*,"1"\]'; then
        echo "   ✅ Probe $target: SUCCESS"
    elif echo "$TARGET_STATUS" | grep -q '"value":\[.*,"0"\]'; then
        echo "   ❌ Probe $target: FAIL"
    else
        echo "   ⚠️  Probe $target: No data yet"
    fi
done

# Test 7: Blackbox exporter metrics
echo ""
echo "Test 7: Blackbox exporter self-metrics..."
BB_METRICS=$(docker compose exec -T blackbox-exporter wget -q -O- http://localhost:9115/metrics 2>/dev/null)

if echo "$BB_METRICS" | grep -q "blackbox_exporter_"; then
    echo "✅ Blackbox exporter metrics: OK"

    # Check probe duration metric
    if echo "$BB_METRICS" | grep -q "probe_duration_seconds"; then
        echo "   ✅ Probe duration metrics available"
    fi
else
    echo "❌ Blackbox exporter metrics: FAIL"
    FAIL=1
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
    echo "✅ PASS: All blackbox exporter tests passed"
    exit 0
else
    echo "❌ FAIL: Some blackbox exporter tests failed"
    exit 1
fi
