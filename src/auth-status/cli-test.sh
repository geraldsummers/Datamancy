#!/bin/bash
# CLI authentication test script
# Usage: ./cli-test.sh [service_name]
# Exit codes: 0=all tests passed, 1=some tests failed

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

API_URL="${API_URL:-http://localhost:3000}"
SERVICE_NAME="$1"

echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  Authentication Status Test Runner    ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════╝${NC}"
echo ""

# Function to print status
print_status() {
    local status=$1
    local message=$2

    if [ "$status" == "healthy" ]; then
        echo -e "${GREEN}✓${NC} $message"
        return 0
    elif [ "$status" == "unhealthy" ]; then
        echo -e "${RED}✗${NC} $message"
        return 1
    else
        echo -e "${YELLOW}?${NC} $message"
        return 1
    fi
}

# Test specific service
test_service() {
    local service=$1
    echo -e "\n${BLUE}Testing: ${service}${NC}"

    response=$(curl -s "$API_URL/api/status/$service")
    status=$(echo "$response" | jq -r '.status')
    message=$(echo "$response" | jq -r '.message')
    auth_type=$(echo "$response" | jq -r '.authType // "N/A"')
    duration=$(echo "$response" | jq -r '.duration // "N/A"')

    echo "  Auth Type: $auth_type"
    echo "  Duration: ${duration}ms"
    print_status "$status" "$message"
    return $?
}

# Main logic
main() {
    # Check if API is available
    if ! curl -s -f "$API_URL/api/health" > /dev/null; then
        echo -e "${RED}✗ API not available at $API_URL${NC}"
        exit 1
    fi

    echo -e "${GREEN}✓ API available${NC}\n"

    # Test specific service or all
    if [ -n "$SERVICE_NAME" ]; then
        test_service "$SERVICE_NAME"
        exit $?
    else
        # Test Authentik
        echo -e "${BLUE}Testing Authentik (OIDC Provider)${NC}"
        response=$(curl -s "$API_URL/api/status/authentik")
        status=$(echo "$response" | jq -r '.status')
        message=$(echo "$response" | jq -r '.message')
        print_status "$status" "$message"
        authentik_result=$?

        # Get all services
        echo -e "\n${BLUE}Testing Services:${NC}"
        services=$(curl -s "$API_URL/api/status" | jq -r '.services | keys[]')

        failed_count=0
        passed_count=0

        for service in $services; do
            if test_service "$service"; then
                ((passed_count++))
            else
                ((failed_count++))
            fi
        done

        # Summary
        echo -e "\n${BLUE}════════════════════════════════════════${NC}"
        echo -e "${BLUE}Test Summary${NC}"
        echo -e "${BLUE}════════════════════════════════════════${NC}"
        echo -e "Authentik: $([ $authentik_result -eq 0 ] && echo -e \"${GREEN}PASS${NC}\" || echo -e \"${RED}FAIL${NC}\")"
        echo -e "Services Passed: ${GREEN}$passed_count${NC}"
        echo -e "Services Failed: ${RED}$failed_count${NC}"
        echo -e "${BLUE}════════════════════════════════════════${NC}"

        if [ $failed_count -gt 0 ] || [ $authentik_result -ne 0 ]; then
            exit 1
        else
            exit 0
        fi
    fi
}

# Run main
main
