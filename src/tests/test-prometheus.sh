#!/bin/bash
# Live Integration Test: Verify Prometheus scraping and targets

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Live Test: Prometheus Scraping ==="

cd "$PROJECT_DIR"

FAIL=0

# Test 1: Prometheus API is responding
echo ""
echo "Test 1: Prometheus API responding..."
if docker compose exec -T prometheus wget -q -O- http://localhost:9090/api/v1/status/config 2>/dev/null | grep -q "prometheus"; then
    echo "✅ Prometheus API: OK"
else
    echo "❌ Prometheus API: FAIL"
    FAIL=1
fi

# Test 2: Check all configured targets
echo ""
echo "Test 2: Checking Prometheus targets..."
TARGETS=$(docker compose exec -T prometheus wget -q -O- http://localhost:9090/api/v1/targets 2>/dev/null)

EXPECTED_JOBS=(
    "prometheus"
    "alertmanager"
    "blackbox-exporter"
    "script-exporter"
    "blackbox-http"
)

for job in "${EXPECTED_JOBS[@]}"; do
    if echo "$TARGETS" | grep -q "\"job\":\"$job\""; then
        # Check if any target in this job is UP
        if echo "$TARGETS" | grep "\"job\":\"$job\"" | grep -q '"health":"up"'; then
            echo "✅ Job '$job': Targets UP"
        else
            echo "❌ Job '$job': Targets DOWN"
            FAIL=1
        fi
    else
        echo "⚠️  Job '$job': Not found in targets"
        FAIL=1
    fi
done

# Test 3: Query metrics from each scrape target
echo ""
echo "Test 3: Querying metrics from scraped targets..."

# Prometheus self-metrics
if docker compose exec -T prometheus wget -q -O- 'http://localhost:9090/api/v1/query?query=up{job="prometheus"}' 2>/dev/null | grep -q '"value":\[.*,"1"\]'; then
    echo "✅ Prometheus self-scrape metrics: OK"
else
    echo "❌ Prometheus self-scrape metrics: FAIL"
    FAIL=1
fi

# Alertmanager metrics
if docker compose exec -T prometheus wget -q -O- 'http://localhost:9090/api/v1/query?query=up{job="alertmanager"}' 2>/dev/null | grep -q '"value":\[.*,"1"\]'; then
    echo "✅ Alertmanager metrics: OK"
else
    echo "❌ Alertmanager metrics: FAIL"
    FAIL=1
fi

# Test 4: Check alertmanager connectivity
echo ""
echo "Test 4: Alertmanager connectivity from Prometheus..."
ALERTMANAGERS=$(docker compose exec -T prometheus wget -q -O- http://localhost:9090/api/v1/alertmanagers 2>/dev/null)

if echo "$ALERTMANAGERS" | grep -q "alertmanager:9093"; then
    echo "✅ Alertmanager registered in Prometheus: OK"
else
    echo "❌ Alertmanager not found in Prometheus: FAIL"
    FAIL=1
fi

# Test 5: Blackbox exporter probes
echo ""
echo "Test 5: Blackbox exporter HTTP probes..."
PROBE_RESULT=$(docker compose exec -T prometheus wget -q -O- 'http://localhost:9090/api/v1/query?query=probe_success{job="blackbox-http"}' 2>/dev/null)

if echo "$PROBE_RESULT" | grep -q '"metric"'; then
    SUCCESS_COUNT=$(echo "$PROBE_RESULT" | grep -o '"value":\[.*,"1"\]' | wc -l)
    TOTAL_COUNT=$(echo "$PROBE_RESULT" | grep -o '"metric":{' | wc -l)
    echo "✅ Blackbox probes: $SUCCESS_COUNT/$TOTAL_COUNT successful"

    if [[ $SUCCESS_COUNT -eq 0 ]]; then
        echo "⚠️  Warning: No successful probes (may need /etc/hosts or DNS)"
    fi
else
    echo "⚠️  Blackbox probes: No probe results yet (may still be initializing)"
fi

# Test 6: Script exporter metrics
echo ""
echo "Test 6: Script exporter custom metrics..."
if docker compose exec -T prometheus wget -q -O- 'http://localhost:9090/api/v1/query?query=up{job="script-exporter"}' 2>/dev/null | grep -q '"value":\[.*,"1"\]'; then
    echo "✅ Script exporter metrics: OK"
else
    echo "❌ Script exporter metrics: FAIL"
    FAIL=1
fi

# Test 7: Check time series database
echo ""
echo "Test 7: Checking TSDB status..."
TSDB_STATUS=$(docker compose exec -T prometheus wget -q -O- http://localhost:9090/api/v1/status/tsdb 2>/dev/null)

if echo "$TSDB_STATUS" | grep -q "seriesCountByMetricName"; then
    SERIES_COUNT=$(echo "$TSDB_STATUS" | grep -o '"numSeries":[0-9]*' | head -1 | cut -d: -f2)
    echo "✅ Prometheus TSDB: OK ($SERIES_COUNT series)"
else
    echo "❌ Prometheus TSDB: FAIL"
    FAIL=1
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
    echo "✅ PASS: All Prometheus tests passed"
    exit 0
else
    echo "❌ FAIL: Some Prometheus tests failed"
    exit 1
fi
