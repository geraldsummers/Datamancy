#!/bin/bash
# Structured screenshot capture with service-name/timestamp organization
# Wraps agent-tool-server browser_screenshot tool with proper file saving

set -euo pipefail

# Configuration
SCREENSHOTS_DIR="${SCREENSHOTS_DIR:-/var/lib/docker/volumes/datamancy_proofs/_data/screenshots}"
KFUN_URL="${KFUN_URL:-http://agent-tool-server:8081}"
DOMAIN="${DOMAIN:-project-saturn.com}"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

capture_screenshot() {
    local service_name=$1
    local url=$2

    echo -e "${BLUE}📸 Capturing $service_name...${NC}"

    # Generate timestamp
    local timestamp=$(date -u +"%Y-%m-%d_%H-%M-%S")

    # Create service directory
    local service_dir="$SCREENSHOTS_DIR/$service_name"
    docker run --rm -v datamancy_proofs:/proofs alpine mkdir -p "/proofs/screenshots/$service_name"

    # Capture screenshot
    local result=$(docker exec agent-tool-server wget -qO- --timeout=60 \
        --post-data="{\"name\":\"browser_screenshot\",\"args\":{\"url\":\"$url\"}}" \
        --header='Content-Type: application/json' \
        http://localhost:8081/call-tool 2>&1)

    # Extract status and image
    local status=$(echo "$result" | grep -o '"status":[0-9]*' | cut -d: -f2)

    if [ "$status" = "200" ]; then
        # Extract base64, decode, and save
        local filename="$service_name/${timestamp}.png"
        echo "$result" | grep -o '"imageBase64":"[^"]*"' | cut -d'"' -f4 | \
            docker run --rm -i -v datamancy_proofs:/proofs alpine sh -c "base64 -d > /proofs/screenshots/$filename"

        # Check file size
        local size=$(docker run --rm -v datamancy_proofs:/proofs alpine stat -c%s "/proofs/screenshots/$filename" 2>/dev/null)

        if [ "$size" -gt 10000 ]; then
            echo -e "  ${GREEN}✅ Saved: $filename ($size bytes)${NC}"
            return 0
        else
            echo -e "  ${RED}❌ Failed: Screenshot too small ($size bytes)${NC}"
            return 1
        fi
    else
        echo -e "  ${RED}❌ Failed: HTTP $status${NC}"
        return 1
    fi
}

# Main function
main() {
    echo "🎯 Structured Screenshot Capture"
    echo "=================================="
    echo "Target dir: $SCREENSHOTS_DIR"
    echo ""

    local success=0
    local total=0

    # Services to capture
    declare -A services
    services[grafana]="https://grafana.$DOMAIN"
    services[homepage]="https://homepage.$DOMAIN"
    services[dockge]="https://dockge.$DOMAIN"
    services[portainer]="https://portainer.$DOMAIN"
    services[open-webui]="https://open-webui.$DOMAIN"
    services[litellm]="https://litellm.$DOMAIN"
    services[ldap-account-manager]="https://lam.$DOMAIN"

    for service in "${!services[@]}"; do
        total=$((total + 1))
        if capture_screenshot "$service" "${services[$service]}"; then
            success=$((success + 1))
        fi
        echo ""
    done

    echo "========================================="
    echo -e "${GREEN}✅ Results: $success/$total services captured${NC}"
    echo ""
    echo "View screenshots:"
    echo "  docker run --rm -v datamancy_proofs:/proofs alpine find /proofs/screenshots -name '*.png' -type f"
    echo ""
    echo "Or from host (requires root):"
    echo "  sudo ls -R $SCREENSHOTS_DIR"
}

# Run if executed directly
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    main "$@"
fi
