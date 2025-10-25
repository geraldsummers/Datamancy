#!/bin/bash
# Provenance: Freshness Rule integration for test runner
# Purpose: Wrap Playwright test execution with freshness status updates
# Architecture: Parse test results, update per-service freshness JSON, aggregate into status file

set -e

RESULTS_DIR="/results"
FRESHNESS_DIR="$RESULTS_DIR/freshness"

mkdir -p "$FRESHNESS_DIR"

echo "==> Running Playwright tests with Freshness Rule tracking"
echo ""

# Run Playwright tests (allow failures to capture results)
EXIT_CODE=0
npx playwright test "$@" || EXIT_CODE=$?

echo ""
echo "==> Updating freshness status from test results"

# Parse JUnit XML results if available
JUNIT_FILE="$RESULTS_DIR/junit.xml"

if [ -f "$JUNIT_FILE" ]; then
    # Extract test results and map to services
    # This is a simplified parser - proper XML parsing would be better

    # Check Grafana test
    if grep -q 'name=".*Grafana.*"' "$JUNIT_FILE" 2>/dev/null; then
        if grep -q 'name=".*Grafana.*".*failures="0"' "$JUNIT_FILE" 2>/dev/null || \
           grep -A1 'name=".*Grafana.*"' "$JUNIT_FILE" | grep -q '<testcase.*time=' | grep -v 'failure'; then
            GRAFANA_STATUS="pass"
        else
            GRAFANA_STATUS="fail"
        fi

        cat > "$FRESHNESS_DIR/grafana.json" <<EOF
{
  "service": "grafana",
  "status": "$GRAFANA_STATUS",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")",
  "epochMs": $(date +%s%3N)
}
EOF
        echo "  ✓ Recorded grafana test: $GRAFANA_STATUS"
    fi

    # Check Traefik test
    if grep -q 'name=".*Traefik.*"' "$JUNIT_FILE" 2>/dev/null; then
        if grep -q 'name=".*Traefik.*".*failures="0"' "$JUNIT_FILE" 2>/dev/null || \
           grep -A1 'name=".*Traefik.*"' "$JUNIT_FILE" | grep -q '<testcase.*time=' | grep -v 'failure'; then
            TRAEFIK_STATUS="pass"
        else
            TRAEFIK_STATUS="fail"
        fi

        cat > "$FRESHNESS_DIR/traefik.json" <<EOF
{
  "service": "traefik",
  "status": "$TRAEFIK_STATUS",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")",
  "epochMs": $(date +%s%3N)
}
EOF
        echo "  ✓ Recorded traefik test: $TRAEFIK_STATUS"
    fi

    # Check Landing page test
    if grep -q 'name=".*Landing.*"' "$JUNIT_FILE" 2>/dev/null; then
        if grep -q 'name=".*Landing.*".*failures="0"' "$JUNIT_FILE" 2>/dev/null || \
           grep -A1 'name=".*Landing.*"' "$JUNIT_FILE" | grep -q '<testcase.*time=' | grep -v 'failure'; then
            HOMEPAGE_STATUS="pass"
        else
            HOMEPAGE_STATUS="fail"
        fi

        cat > "$FRESHNESS_DIR/homepage.json" <<EOF
{
  "service": "homepage",
  "status": "$HOMEPAGE_STATUS",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")",
  "epochMs": $(date +%s%3N)
}
EOF
        echo "  ✓ Recorded homepage test: $HOMEPAGE_STATUS"
    fi
fi

# Parse JSON results if available
JSON_FILE="$RESULTS_DIR/results.json"

if [ -f "$JSON_FILE" ] && command -v jq &> /dev/null; then
    # Extract per-test results using jq
    jq -r '.suites[]?.specs[]? | select(.title | test("Grafana|Traefik|Landing"; "i")) |
           "\(.title)|\(.ok)"' "$JSON_FILE" 2>/dev/null | while IFS='|' read -r title status; do

        service_name=$(echo "$title" | tr '[:upper:]' '[:lower:]' | sed -E 's/.*(grafana|traefik|landing).*/\1/')

        if [ "$status" = "true" ]; then
            test_status="pass"
        else
            test_status="fail"
        fi

        # Normalize service name
        if echo "$title" | grep -qi "landing"; then
            service_name="homepage"
        fi

        if [ -n "$service_name" ]; then
            cat > "$FRESHNESS_DIR/${service_name}.json" <<EOF
{
  "service": "$service_name",
  "status": "$test_status",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")",
  "epochMs": $(date +%s%3N)
}
EOF
            echo "  ✓ Recorded $service_name test: $test_status"
        fi
    done
fi

echo ""
echo "==> Freshness tracking complete"
echo "  Results written to: $FRESHNESS_DIR/"

exit $EXIT_CODE
