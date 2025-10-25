#!/bin/bash
# Provenance: Phase 1 validation script
# Purpose: Validate complete Phase 1 bring-up with Freshness Rule
# Architecture: Single front door, rootless, autonomous testing

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."
cd "${PROJECT_ROOT}"

echo -e "${BLUE}==> Phase 1 Validation: Single Front Door + Freshness Rule${NC}"
echo ""

# Check 1: Certificates exist
echo -e "${BLUE}[1/8] Checking certificates...${NC}"
if [[ -f certs/ca.crt ]] && [[ -f certs/fullchain.pem ]] && [[ -f certs/privkey.pem ]]; then
    echo -e "${GREEN}✓ Certificates exist${NC}"
else
    echo -e "${RED}✗ Certificates missing. Run: docker compose --profile ca up ca-generator${NC}"
    exit 1
fi

# Check 2: /etc/hosts mapping
echo -e "${BLUE}[2/8] Checking /etc/hosts mapping...${NC}"
if grep -q "stack.local" /etc/hosts; then
    echo -e "${GREEN}✓ stack.local mapped in /etc/hosts${NC}"
else
    echo -e "${YELLOW}⚠ stack.local not in /etc/hosts. Add: echo '127.0.0.1 stack.local' | sudo tee -a /etc/hosts${NC}"
fi

# Check 3: Containers running
echo -e "${BLUE}[3/8] Checking containers...${NC}"
EXPECTED_CONTAINERS=("socket-proxy" "traefik" "grafana" "browserless" "homepage")
ALL_RUNNING=true

for container in "${EXPECTED_CONTAINERS[@]}"; do
    if docker ps --filter "name=${container}" --filter "status=running" | grep -q "${container}"; then
        echo -e "${GREEN}  ✓ ${container} running${NC}"
    else
        echo -e "${RED}  ✗ ${container} not running${NC}"
        ALL_RUNNING=false
    fi
done

if [[ "${ALL_RUNNING}" == "false" ]]; then
    echo -e "${RED}✗ Not all containers running. Run: docker compose --profile infra up -d${NC}"
    exit 1
fi

# Check 4: Healthchecks
echo -e "${BLUE}[4/8] Checking healthchecks...${NC}"
ALL_HEALTHY=true

for container in "${EXPECTED_CONTAINERS[@]}"; do
    HEALTH=$(docker inspect --format='{{.State.Health.Status}}' "${container}" 2>/dev/null || echo "no-health")
    if [[ "${HEALTH}" == "healthy" ]]; then
        echo -e "${GREEN}  ✓ ${container} healthy${NC}"
    else
        echo -e "${YELLOW}  ⚠ ${container} health: ${HEALTH}${NC}"
        if [[ "${HEALTH}" == "unhealthy" ]]; then
            ALL_HEALTHY=false
        fi
    fi
done

if [[ "${ALL_HEALTHY}" == "false" ]]; then
    echo -e "${RED}✗ Some containers unhealthy. Check logs: docker compose logs${NC}"
    exit 1
fi

# Check 5: HTTPS endpoints reachable
echo -e "${BLUE}[5/8] Checking HTTPS endpoints (may show cert warnings if CA not trusted)...${NC}"
ENDPOINTS=(
    "https://stack.local/"
    "https://stack.local/grafana/"
    "https://stack.local/dashboard/"
)

for endpoint in "${ENDPOINTS[@]}"; do
    if curl -skf -m 5 "${endpoint}" > /dev/null 2>&1; then
        echo -e "${GREEN}  ✓ ${endpoint} reachable${NC}"
    else
        echo -e "${RED}  ✗ ${endpoint} unreachable${NC}"
        ALL_RUNNING=false
    fi
done

# Check internal Browserless (not exposed via Traefik)
echo -e "${BLUE}  Checking internal Browserless...${NC}"
if docker exec browserless curl -sf "http://localhost:3000/?token=browserless-token-2024" > /dev/null 2>&1; then
    echo -e "${GREEN}  ✓ Browserless (internal) reachable${NC}"
else
    echo -e "${RED}  ✗ Browserless (internal) unreachable${NC}"
fi

# Check 6: Run tests
echo -e "${BLUE}[6/8] Running autonomous tests...${NC}"
if docker compose run --rm test-runner; then
    echo -e "${GREEN}✓ Tests passed${NC}"
else
    echo -e "${RED}✗ Tests failed. Check: data/tests/html/index.html${NC}"
    exit 1
fi

# Check 7: Freshness Rule status
echo -e "${BLUE}[7/8] Checking Freshness Rule status...${NC}"
if [[ -x scripts/freshness-check.sh ]]; then
    ./scripts/freshness-check.sh ./data/tests/freshness
else
    echo -e "${YELLOW}⚠ freshness-check.sh not executable${NC}"
fi

# Check 8: Test artifacts
echo -e "${BLUE}[8/8] Checking test artifacts...${NC}"
ARTIFACTS=(
    "data/tests/freshness/grafana.json"
    "data/tests/freshness/traefik.json"
    "data/tests/freshness/homepage.json"
    "data/tests/junit.xml"
)

for artifact in "${ARTIFACTS[@]}"; do
    if [[ -f "${artifact}" ]]; then
        echo -e "${GREEN}  ✓ ${artifact}${NC}"
    else
        echo -e "${YELLOW}  ⚠ ${artifact} not found${NC}"
    fi
done

echo ""
echo -e "${GREEN}==> Phase 1 Validation Complete!${NC}"
echo ""
echo "Next steps:"
echo "  1. Review test artifacts: open data/tests/html/index.html"
echo "  2. Review freshness status: ./scripts/freshness-check.sh ./data/tests/freshness"
echo "  3. Access services:"
echo "     - Landing:   https://stack.local/"
echo "     - Grafana:   https://stack.local/grafana/"
echo "     - Traefik:   https://stack.local/dashboard/"
echo "  4. Proceed to Phase 2 when all services show '✓ Functional'"
echo ""
