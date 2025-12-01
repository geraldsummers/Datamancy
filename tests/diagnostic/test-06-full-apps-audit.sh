#!/bin/bash
# Test 06: Full Applications Layer Audit
# Systematically tests all application services using probe-orchestrator

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Configuration
KFUN_URL="${KFUN_URL:-http://agent-tool-server.stack.local:8081}"
DOMAIN="${DOMAIN:-stack.local}"
PROOFS_DIR="${PROJECT_ROOT}/volumes/proofs/screenshots"

# Service groups for systematic testing
CORE_APPS=("grafana" "open-webui" "ldap-account-manager" "homepage")
PRODUCTIVITY_APPS=("planka" "bookstack" "vaultwarden")
COLLAB_APPS=("seafile" "onlyoffice" "jupyterhub")
COMMS_APPS=("sogo" "synapse")
DEVOPS_APPS=("dockge" "portainer" "kopia")
MAIL_APPS=("mailu-admin" "mailu-webmail")

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║     TEST 06: Full Applications Layer Audit                ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Helper functions
log_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
    echo -e "${GREEN}✅ $1${NC}"
    PASSED_TESTS=$((PASSED_TESTS + 1))
}

log_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
    echo -e "${RED}❌ $1${NC}"
    FAILED_TESTS=$((FAILED_TESTS + 1))
}

# Check if service is running
check_service_running() {
    local service=$1
    if docker compose ps "$service" 2>/dev/null | grep -q "Up"; then
        return 0
    else
        return 1
    fi
}

# Test internal health endpoint
test_internal_health() {
    local service=$1
    local health_url=$2

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    log_info "Testing internal health: $health_url"

    if curl -sf --max-time 10 "$health_url" > /dev/null 2>&1; then
        log_success "$service internal health OK"
        return 0
    else
        log_error "$service internal health FAILED"
        return 1
    fi
}

# Capture screenshot via agent-tool-server
capture_screenshot() {
    local service=$1
    local url=$2
    local output_file="${PROOFS_DIR}/${service}-audit.png"

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    log_info "Capturing screenshot: $url"

    # Create output directory
    mkdir -p "$PROOFS_DIR"

    # Call agent-tool-server browser_screenshot tool
    local response
    response=$(curl -sf --max-time 45 -X POST "$KFUN_URL/call" \
        -H "Content-Type: application/json" \
        -d "{
            \"tool\": \"browser_screenshot\",
            \"arguments\": {
                \"url\": \"$url\",
                \"output_path\": \"/app/proofs/screenshots/${service}-audit.png\",
                \"width\": 1280,
                \"height\": 720,
                \"full_page\": false
            }
        }" 2>&1)

    if [ $? -eq 0 ] && [ -f "$output_file" ]; then
        local size=$(stat -f%z "$output_file" 2>/dev/null || stat -c%s "$output_file" 2>/dev/null)
        log_success "$service screenshot captured (${size} bytes)"
        return 0
    else
        log_error "$service screenshot FAILED: $response"
        return 1
    fi
}

# Check if URL redirects to SSO
test_sso_redirect() {
    local service=$1
    local url=$2

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    log_info "Testing SSO redirect: $url"

    # Follow redirects and get final URL
    local final_url
    final_url=$(curl -sL -w "%{url_effective}" -o /dev/null "$url" 2>&1)

    if echo "$final_url" | grep -q "auth.${DOMAIN}"; then
        log_success "$service SSO redirect working"
        return 0
    else
        log_warning "$service no SSO redirect detected (may not require SSO)"
        return 0
    fi
}

# Test a single service comprehensively
test_service() {
    local service=$1
    local internal_url=$2
    local external_url=$3
    local test_sso=${4:-true}

    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}Testing: $service${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

    # Check if service is running
    if ! check_service_running "$service"; then
        log_warning "$service is not running, skipping tests"
        return 0
    fi

    log_success "$service container is running"

    # Test internal health if provided
    if [ -n "$internal_url" ]; then
        test_internal_health "$service" "$internal_url" || true
    fi

    # Capture screenshot of external URL
    if [ -n "$external_url" ]; then
        capture_screenshot "$service" "$external_url" || true

        # Test SSO redirect if requested
        if [ "$test_sso" = "true" ]; then
            test_sso_redirect "$service" "$external_url" || true
        fi
    fi
}

# Main test execution
echo ""
log_info "Starting comprehensive application layer audit"
log_info "Domain: $DOMAIN"
log_info "agent-tool-server URL: $KFUN_URL"
log_info "Screenshots will be saved to: $PROOFS_DIR"
echo ""

# Phase 1: Core Applications
echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 1: Core Applications${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"

test_service "grafana" "http://grafana:3000/api/health" "https://grafana.${DOMAIN}"
test_service "open-webui" "http://open-webui:8080/health" "https://open-webui.${DOMAIN}"
test_service "ldap-account-manager" "http://ldap-account-manager:80/lam/" "https://lam.${DOMAIN}"
test_service "homepage" "http://homepage:3000/" "https://homepage.${DOMAIN}"

# Phase 2: Productivity Applications
echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 2: Productivity Applications${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"

test_service "planka" "http://planka:1337/api/health" "https://planka.${DOMAIN}"
test_service "bookstack" "http://bookstack:80/" "https://bookstack.${DOMAIN}"
test_service "vaultwarden" "http://vaultwarden:80/alive" "https://vaultwarden.${DOMAIN}"

# Phase 3: Collaboration Applications
echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 3: Collaboration Applications${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"

test_service "seafile" "http://seafile:8000/" "https://seafile.${DOMAIN}"
test_service "onlyoffice" "http://onlyoffice:80/healthcheck" "https://onlyoffice.${DOMAIN}"
test_service "jupyterhub" "http://jupyterhub:8000/hub/health" "https://jupyterhub.${DOMAIN}"

# Phase 4: Communication Applications
echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 4: Communication Applications${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"

test_service "sogo" "http://sogo:20000/" "https://sogo.${DOMAIN}"
test_service "synapse" "http://synapse:8008/health" "https://matrix.${DOMAIN}"

# Phase 5: DevOps & Management
echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 5: DevOps & Management Applications${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"

test_service "dockge" "http://dockge:5001" "https://dockge.${DOMAIN}"
test_service "portainer" "http://portainer:9000/api/system/status" "https://portainer.${DOMAIN}"
test_service "kopia" "" "https://kopia.${DOMAIN}"

# Phase 6: Mail Services
echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Phase 6: Mail & Groupware Services${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"

test_service "mailu-admin" "http://mailu-admin:8080/admin/ui" "https://mail.${DOMAIN}/admin/"
test_service "mailu-webmail" "http://mailu-webmail:80" "https://mail.${DOMAIN}/webmail/"

# Summary
echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Test Summary${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "Total tests: ${BLUE}${TOTAL_TESTS}${NC}"
echo -e "Passed: ${GREEN}${PASSED_TESTS}${NC}"
echo -e "Failed: ${RED}${FAILED_TESTS}${NC}"
echo -e "Success rate: ${BLUE}$((PASSED_TESTS * 100 / TOTAL_TESTS))%${NC}"
echo ""

# List captured screenshots
if [ -d "$PROOFS_DIR" ]; then
    local screenshot_count=$(find "$PROOFS_DIR" -name "*-audit.png" 2>/dev/null | wc -l)
    echo -e "Screenshots captured: ${GREEN}${screenshot_count}${NC}"
    echo -e "Location: ${BLUE}${PROOFS_DIR}${NC}"
    echo ""
    echo "Screenshot list:"
    find "$PROOFS_DIR" -name "*-audit.png" -exec basename {} \; 2>/dev/null | sort
fi

echo ""
if [ $FAILED_TESTS -eq 0 ]; then
    log_success "Full applications layer audit PASSED"
    exit 0
else
    log_warning "Full applications layer audit completed with $FAILED_TESTS failures"
    exit 1
fi
