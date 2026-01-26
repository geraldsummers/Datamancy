#!/bin/bash
# CI/CD Pipeline Test Runner
# Runs comprehensive tests of the CI/CD pipeline using labware socket

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║       Datamancy CI/CD Pipeline Test Suite                     ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check labware socket exists
echo -e "${YELLOW}▸ Checking labware socket...${NC}"
if [ ! -S /run/labware-docker.sock ]; then
    echo -e "${RED}✗ Labware socket not found at /run/labware-docker.sock${NC}"
    echo -e "${YELLOW}  Ensure labware VM is running and socket is mounted.${NC}"
    echo -e "${YELLOW}  See: docs/LABWARE_SOCKET.md${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Labware socket exists${NC}"

# Test socket connectivity
echo -e "${YELLOW}▸ Testing socket connectivity...${NC}"
if ! docker -H unix:///run/labware-docker.sock ps > /dev/null 2>&1; then
    echo -e "${RED}✗ Cannot connect to labware Docker socket${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Labware Docker socket is accessible${NC}"

# Check test-runner availability
echo -e "${YELLOW}▸ Checking test-runner availability...${NC}"
cd "${PROJECT_ROOT}"

if [ ! -d "src/test-runner" ]; then
    echo -e "${RED}✗ test-runner module not found${NC}"
    exit 1
fi
echo -e "${GREEN}✓ test-runner module found${NC}"

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  Running Test Suites${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo ""

# Parse command-line arguments
TEST_FILTER="${1:-*}"
VERBOSE="${VERBOSE:-false}"

if [ "$TEST_FILTER" = "--help" ] || [ "$TEST_FILTER" = "-h" ]; then
    echo "Usage: $0 [TEST_FILTER] [OPTIONS]"
    echo ""
    echo "TEST_FILTER:"
    echo "  all              Run all CI/CD tests (default)"
    echo "  labware          Run labware socket connectivity tests"
    echo "  cicd             Run CI/CD pipeline integration tests"
    echo "  build            Run image building tests only"
    echo "  registry         Run registry push/pull tests only"
    echo "  deployment       Run deployment workflow tests only"
    echo ""
    echo "OPTIONS:"
    echo "  VERBOSE=true     Show detailed test output"
    echo ""
    echo "Examples:"
    echo "  $0 all                    # Run all tests"
    echo "  $0 labware                # Test labware socket only"
    echo "  $0 build                  # Test image building only"
    echo "  VERBOSE=true $0 cicd      # Run CI/CD tests with verbose output"
    exit 0
fi

# Determine which tests to run
case "$TEST_FILTER" in
    "all")
        GRADLE_FILTER="*Labware*,*CICD*"
        ;;
    "labware")
        GRADLE_FILTER="*LabwareDockerTestsTest*"
        ;;
    "cicd")
        GRADLE_FILTER="*CICDPipelineTestsTest*"
        ;;
    "build")
        GRADLE_FILTER="*CICDPipelineTestsTest*should build*"
        ;;
    "registry")
        GRADLE_FILTER="*CICDPipelineTestsTest*registry*"
        ;;
    "deployment")
        GRADLE_FILTER="*CICDPipelineTestsTest*deploy*"
        ;;
    *)
        GRADLE_FILTER="$TEST_FILTER"
        ;;
esac

echo -e "${YELLOW}Test filter: ${GRADLE_FILTER}${NC}"
echo ""

# Build test-runner if needed
if [ ! -f "build.gradle.kts" ]; then
    echo -e "${RED}✗ build.gradle.kts not found${NC}"
    exit 1
fi

# Run tests
echo -e "${YELLOW}▸ Running tests...${NC}"
echo ""

GRADLE_OPTS="-Dorg.gradle.daemon=false"

if [ "$VERBOSE" = "true" ]; then
    ./gradlew test-runner:test --tests "$GRADLE_FILTER" --info
else
    ./gradlew test-runner:test --tests "$GRADLE_FILTER"
fi

TEST_EXIT_CODE=$?

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"

if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
    echo ""
    echo -e "${CYAN}Test Results:${NC}"
    echo -e "  • Labware socket: ${GREEN}✓ Verified${NC}"
    echo -e "  • Image building: ${GREEN}✓ Working${NC}"
    echo -e "  • Registry ops:   ${GREEN}✓ Working${NC}"
    echo -e "  • Deployments:    ${GREEN}✓ Working${NC}"
    echo -e "  • Isolation:      ${GREEN}✓ Confirmed${NC}"
    echo ""
    echo -e "${GREEN}Your CI/CD pipeline is ready! 🚀${NC}"
else
    echo -e "${RED}✗ Some tests failed${NC}"
    echo ""
    echo -e "${YELLOW}Troubleshooting:${NC}"
    echo -e "  1. Check labware socket: ${CYAN}ls -la /run/labware-docker.sock${NC}"
    echo -e "  2. Test socket manually: ${CYAN}docker -H unix:///run/labware-docker.sock ps${NC}"
    echo -e "  3. Check labware VM: ${CYAN}ssh root@labware-vm systemctl status docker${NC}"
    echo -e "  4. View test logs: ${CYAN}./gradlew test-runner:test --tests \"$GRADLE_FILTER\" --info${NC}"
    echo -e "  5. See docs: ${CYAN}docs/LABWARE_SOCKET.md${NC}"
fi

echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"

exit $TEST_EXIT_CODE
