#!/usr/bin/env bash
# Test 01: Verify kfuncdb tool inventory
set -euo pipefail

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "TEST 01: kfuncdb Tool Inventory"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check if kfuncdb container is running
echo ""
echo "Step 1: Checking kfuncdb container status..."
if ! docker ps --format '{{.Names}}' | grep -q '^kfuncdb$'; then
    echo "❌ FAIL: kfuncdb container is not running"
    echo "   Run: docker compose --profile bootstrap up -d kfuncdb"
    exit 1
fi
echo "✅ kfuncdb container is running"

# Check health
echo ""
echo "Step 2: Checking kfuncdb health endpoint..."
if docker exec kfuncdb wget -qO- --timeout=5 http://localhost:8081/healthz | grep -q '"status":"ok"'; then
    echo "✅ kfuncdb health check passed"
else
    echo "❌ FAIL: kfuncdb health check failed"
    exit 1
fi

# Check loaded plugins
echo ""
echo "Step 3: Checking loaded plugins..."
PLUGINS=$(docker logs kfuncdb 2>&1 | grep -E "Loaded plugin:" | tail -10)
echo "$PLUGINS"

PLUGIN_COUNT=$(echo "$PLUGINS" | wc -l)
echo ""
echo "Loaded plugins: $PLUGIN_COUNT"

if [ "$PLUGIN_COUNT" -lt 4 ]; then
    echo "⚠️  WARNING: Expected at least 4 plugins (core, hosttools, browser, llmcompletion, ops)"
    echo ""
    echo "Checking for skipped plugins:"
    docker logs kfuncdb 2>&1 | grep -E "\[WARN\].*requires capabilities" | tail -10
fi

# List all available tools
echo ""
echo "Step 4: Fetching tool inventory..."
TOOLS_JSON=$(docker exec kfuncdb wget -qO- http://localhost:8081/tools 2>/dev/null)
TOOL_COUNT=$(echo "$TOOLS_JSON" | jq '. | length' 2>/dev/null || echo "0")

echo "Total tools available: $TOOL_COUNT"

# Check for critical diagnostic tools
echo ""
echo "Step 5: Verifying critical diagnostic tools..."
CRITICAL_TOOLS=(
    "browser_screenshot"
    "http_get"
    "docker_logs"
    "docker_stats"
    "docker_inspect"
)

MISSING_TOOLS=()
for tool in "${CRITICAL_TOOLS[@]}"; do
    if echo "$TOOLS_JSON" | jq -e ".[] | select(.name == \"$tool\")" > /dev/null 2>&1; then
        echo "  ✅ $tool"
    else
        echo "  ❌ $tool (MISSING)"
        MISSING_TOOLS+=("$tool")
    fi
done

# Summary
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [ ${#MISSING_TOOLS[@]} -eq 0 ]; then
    echo "✅ TEST 01 PASSED: All critical tools available ($TOOL_COUNT total)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 0
else
    echo "❌ TEST 01 FAILED: Missing tools: ${MISSING_TOOLS[*]}"
    echo ""
    echo "Diagnosis:"
    echo "  Check docker-compose.yml for KFUNCDB_ALLOW_CAPS environment variable"
    echo "  Required capabilities: host.docker.write,host.docker.inspect,host.network.http,host.network.ssh"
    echo ""
    echo "  Current configuration:"
    docker exec kfuncdb env | grep KFUNCDB_ALLOW_CAPS || echo "  (not set)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 1
fi
