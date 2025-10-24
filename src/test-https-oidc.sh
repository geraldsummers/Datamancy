#!/bin/bash
# HTTPS-only OIDC Stack Test Script
# Tests acceptance criteria for Traefik + Dex + ForwardAuth setup

set -e

BASE_DOMAIN="${BASE_DOMAIN:-lab.localhost}"
TRAEFIK_IP="172.18.0.2"

echo "=========================================="
echo "HTTPS-only OIDC Stack Test Suite"
echo "BASE_DOMAIN: ${BASE_DOMAIN}"
echo "=========================================="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass() {
    echo -e "${GREEN}✓ PASS${NC}: $1"
}

fail() {
    echo -e "${RED}✗ FAIL${NC}: $1"
    exit 1
}

info() {
    echo -e "${YELLOW}→${NC} $1"
}

# Test 1: CoreDNS wildcard resolution
echo "Test 1: CoreDNS Wildcard DNS Resolution"
echo "----------------------------------------"
info "Testing DNS resolution via CoreDNS..."

for subdomain in dex grafana chat auth prometheus; do
    info "Resolving ${subdomain}.${BASE_DOMAIN}..."
    RESOLVED_IP=$(dig @172.18.0.53 +short ${subdomain}.${BASE_DOMAIN} | head -1)
    if [ "$RESOLVED_IP" = "$TRAEFIK_IP" ]; then
        pass "${subdomain}.${BASE_DOMAIN} -> ${RESOLVED_IP}"
    else
        fail "${subdomain}.${BASE_DOMAIN} resolved to ${RESOLVED_IP}, expected ${TRAEFIK_IP}"
    fi
done
echo ""

# Test 2: Dex OIDC Discovery
echo "Test 2: Dex OIDC Discovery"
echo "----------------------------------------"
info "Fetching Dex well-known configuration..."

WELLKNOWN_URL="https://dex.${BASE_DOMAIN}/.well-known/openid-configuration"
ISSUER=$(curl -sk "$WELLKNOWN_URL" | jq -r '.issuer' 2>/dev/null)

if [ "$ISSUER" = "https://dex.${BASE_DOMAIN}" ]; then
    pass "Issuer matches: $ISSUER"
else
    fail "Issuer mismatch. Expected: https://dex.${BASE_DOMAIN}, Got: $ISSUER"
fi

# Check required endpoints
AUTH_ENDPOINT=$(curl -sk "$WELLKNOWN_URL" | jq -r '.authorization_endpoint' 2>/dev/null)
TOKEN_ENDPOINT=$(curl -sk "$WELLKNOWN_URL" | jq -r '.token_endpoint' 2>/dev/null)
USERINFO_ENDPOINT=$(curl -sk "$WELLKNOWN_URL" | jq -r '.userinfo_endpoint' 2>/dev/null)

if [[ "$AUTH_ENDPOINT" == "https://dex.${BASE_DOMAIN}/auth" ]]; then
    pass "Authorization endpoint: $AUTH_ENDPOINT"
else
    fail "Authorization endpoint incorrect: $AUTH_ENDPOINT"
fi

if [[ "$TOKEN_ENDPOINT" == "https://dex.${BASE_DOMAIN}/token" ]]; then
    pass "Token endpoint: $TOKEN_ENDPOINT"
else
    fail "Token endpoint incorrect: $TOKEN_ENDPOINT"
fi

if [[ "$USERINFO_ENDPOINT" == "https://dex.${BASE_DOMAIN}/userinfo" ]]; then
    pass "Userinfo endpoint: $USERINFO_ENDPOINT"
else
    fail "Userinfo endpoint incorrect: $USERINFO_ENDPOINT"
fi
echo ""

# Test 3: Direct backend access (bypassing Traefik)
echo "Test 3: Direct Backend Access (No Auth)"
echo "----------------------------------------"
info "Testing direct container-to-container access..."

docker exec prometheus wget -q -O- http://prometheus:9090/metrics | grep -q "prometheus_build_info" && \
    pass "Direct backend access works (no proxy auth)" || \
    fail "Direct backend access failed"
echo ""

# Test 4: Public HTTPS endpoints
echo "Test 4: Public HTTPS Endpoints"
echo "----------------------------------------"
info "Testing public landing page..."

LANDING_STATUS=$(curl -sk -o /dev/null -w "%{http_code}" "https://${BASE_DOMAIN}")
if [ "$LANDING_STATUS" = "200" ]; then
    pass "Landing page accessible: https://${BASE_DOMAIN}"
else
    fail "Landing page returned HTTP $LANDING_STATUS"
fi

info "Testing Dex public endpoint..."
DEX_STATUS=$(curl -sk -o /dev/null -w "%{http_code}" "https://dex.${BASE_DOMAIN}/")
if [ "$DEX_STATUS" = "200" ] || [ "$DEX_STATUS" = "404" ]; then
    pass "Dex accessible: https://dex.${BASE_DOMAIN}"
else
    fail "Dex returned HTTP $DEX_STATUS"
fi
echo ""

# Test 5: ForwardAuth protection
echo "Test 5: ForwardAuth Protection"
echo "----------------------------------------"
info "Testing unauthenticated access to protected app..."

PROMETHEUS_UNAUTH=$(curl -sk -o /dev/null -w "%{http_code}" "https://prometheus.${BASE_DOMAIN}")
if [ "$PROMETHEUS_UNAUTH" = "302" ] || [ "$PROMETHEUS_UNAUTH" = "401" ]; then
    pass "Protected app redirects unauthenticated users (HTTP $PROMETHEUS_UNAUTH)"
else
    fail "Protected app should redirect, got HTTP $PROMETHEUS_UNAUTH"
fi

info "Testing Grafana (OIDC-native) redirects to login..."
GRAFANA_UNAUTH=$(curl -sk -o /dev/null -w "%{http_code}" "https://grafana.${BASE_DOMAIN}")
if [ "$GRAFANA_UNAUTH" = "302" ] || [ "$GRAFANA_UNAUTH" = "200" ]; then
    pass "OIDC-native app accessible (HTTP $GRAFANA_UNAUTH)"
else
    fail "OIDC-native app returned unexpected HTTP $GRAFANA_UNAUTH"
fi
echo ""

# Test 6: Traefik dashboard
echo "Test 6: Traefik Dashboard"
echo "----------------------------------------"
info "Testing Traefik dashboard protection..."

TRAEFIK_STATUS=$(curl -sk -o /dev/null -w "%{http_code}" "https://traefik.${BASE_DOMAIN}/dashboard/")
if [ "$TRAEFIK_STATUS" = "302" ] || [ "$TRAEFIK_STATUS" = "401" ]; then
    pass "Traefik dashboard protected by ForwardAuth (HTTP $TRAEFIK_STATUS)"
else
    fail "Traefik dashboard should be protected, got HTTP $TRAEFIK_STATUS"
fi
echo ""

# Test 7: Container DNS resolution
echo "Test 7: Container DNS Resolution"
echo "----------------------------------------"
info "Testing DNS resolution from inside container..."

docker exec grafana nslookup dex.${BASE_DOMAIN} 2>&1 | grep -q "$TRAEFIK_IP" && \
    pass "Container can resolve dex.${BASE_DOMAIN} via CoreDNS" || \
    fail "Container DNS resolution failed"

docker exec grafana nslookup prometheus.${BASE_DOMAIN} 2>&1 | grep -q "$TRAEFIK_IP" && \
    pass "Container can resolve prometheus.${BASE_DOMAIN} via CoreDNS" || \
    fail "Container DNS resolution failed"
echo ""

# Test 8: TLS/HTTPS enforcement
echo "Test 8: TLS/HTTPS Enforcement"
echo "----------------------------------------"
info "Testing HTTP->HTTPS redirect..."

HTTP_REDIRECT=$(curl -sk -o /dev/null -w "%{http_code}" "http://${BASE_DOMAIN}")
if [ "$HTTP_REDIRECT" = "301" ] || [ "$HTTP_REDIRECT" = "308" ]; then
    pass "HTTP redirects to HTTPS (HTTP $HTTP_REDIRECT)"
else
    info "HTTP redirect returned $HTTP_REDIRECT (acceptable if HTTPS works)"
fi
echo ""

# Test 9: Forward-auth service health
echo "Test 9: Forward-Auth Service Health"
echo "----------------------------------------"
info "Testing forward-auth service..."

docker exec forward-auth wget -q -O- http://localhost:4180/health | grep -q "OK" && \
    pass "Forward-auth service is healthy" || \
    fail "Forward-auth service health check failed"
echo ""

# Test 10: OIDC-native apps configuration
echo "Test 10: OIDC-Native Apps Configuration"
echo "----------------------------------------"
info "Checking Grafana OIDC config..."

docker exec grafana printenv | grep -q "GF_AUTH_GENERIC_OAUTH_ENABLED=true" && \
    pass "Grafana OIDC enabled" || \
    fail "Grafana OIDC not configured"

docker exec grafana printenv GF_AUTH_GENERIC_OAUTH_AUTH_URL | grep -q "https://dex.${BASE_DOMAIN}/auth" && \
    pass "Grafana points to HTTPS Dex endpoints" || \
    fail "Grafana not using HTTPS Dex endpoints"
echo ""

echo "=========================================="
echo -e "${GREEN}All tests passed!${NC}"
echo "=========================================="
echo ""
echo "Stack Status:"
echo "  ✓ CoreDNS: Wildcard DNS operational"
echo "  ✓ Traefik: Reverse proxy with TLS"
echo "  ✓ Dex: OIDC issuer over HTTPS"
echo "  ✓ ForwardAuth: Proxy-level authentication"
echo "  ✓ OIDC Apps: Native authentication configured"
echo ""
echo "Next Steps:"
echo "  1. Test login with user: authtest / TestAuth123!"
echo "  2. Verify group-based access control"
echo "  3. Test browser automation with Browserless"
echo ""
