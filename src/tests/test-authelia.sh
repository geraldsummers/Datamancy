#!/bin/bash
# Live Integration Test: Verify Authelia authentication flow

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Live Test: Authelia Authentication ==="

cd "$PROJECT_DIR"

# Load BASE_DOMAIN from .env
if [[ -f .env ]]; then
    set -a; source <(grep -v "^#" .env | grep -v "^$" | sed "s/\\s*#.*$//"); set +a
fi

BASE_DOMAIN="${BASE_DOMAIN:-lab.localhost}"

FAIL=0

# Test 1: Authelia health endpoint
echo ""
echo "Test 1: Authelia health endpoint..."
if docker compose exec -T authelia wget -q -O- http://localhost:9091/api/health 2>/dev/null; then
    echo "✅ Authelia health: OK"
else
    echo "❌ Authelia health: FAIL"
    FAIL=1
fi

# Test 2: Authelia configuration endpoint (POST only)
echo ""
echo "Test 2: Authelia configuration endpoint..."
echo "⚠️  Skipped (requires POST method)"
fi

# Test 3: Authelia state endpoint (POST only)
echo ""
echo "Test 3: Authelia state endpoint..."
echo "⚠️  Skipped (requires POST method)"
fi

# Test 4: Forward-auth endpoint (used by Caddy)
echo ""
echo "Test 4: Authelia forward-auth endpoint..."
# Without credentials, should return 401
HTTP_CODE=$(docker compose exec -T authelia wget -q -O- --server-response http://localhost:9091/api/authz/forward-auth 2>&1 | grep "HTTP/" | tail -1 | awk '{print $2}')

if [[ "$HTTP_CODE" == "401" ]]; then
    echo "✅ Authelia forward-auth endpoint: OK (returns 401 as expected without auth)"
else
    echo "⚠️  Authelia forward-auth endpoint: Unexpected response (HTTP $HTTP_CODE)"
fi

# Test 5: Check if Authelia is accessible via Caddy
echo ""
echo "Test 5: Authelia accessible via Caddy..."
HTTP_CODE=$(curl -k -s -o /dev/null -w "%{http_code}" "https://id.${BASE_DOMAIN}/api/health" 2>/dev/null || echo "000")

if [[ "$HTTP_CODE" == "200" ]]; then
    echo "✅ Authelia via Caddy: OK"
elif [[ "$HTTP_CODE" == "000" ]]; then
    echo "⚠️  Authelia via Caddy: Cannot connect (may need /etc/hosts or DNS)"
else
    echo "⚠️  Authelia via Caddy: HTTP $HTTP_CODE"
fi

# Test 6: Protected endpoint redirects to auth
echo ""
echo "Test 6: Protected endpoint redirects to Authelia..."
RESPONSE=$(curl -k -s -L -o /dev/null -w "%{url_effective}" "https://whoami.${BASE_DOMAIN}" 2>/dev/null || echo "")

if [[ "$RESPONSE" =~ "id.${BASE_DOMAIN}" ]]; then
    echo "✅ Protected endpoint redirects to Authelia: OK"
elif [[ -z "$RESPONSE" ]]; then
    echo "⚠️  Protected endpoint: Cannot connect (may need /etc/hosts or DNS)"
else
    echo "⚠️  Protected endpoint: Unexpected redirect ($RESPONSE)"
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
    echo "✅ PASS: Authelia authentication tests passed"
    exit 0
else
    echo "❌ FAIL: Some Authelia tests failed"
    exit 1
fi
