#!/bin/bash

# Datamancy Test Runner Script
# This script provides a convenient interface for running tests inside the test-runner container

set -e

CONTAINER_NAME="integration-test-runner"
PLAYWRIGHT_DIR="/app/playwright-tests"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_header() {
    echo -e "${BLUE}╔═══════════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║  Datamancy Test Runner                                                   ║${NC}"
    echo -e "${BLUE}╚═══════════════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_usage() {
    print_header
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo ""
    echo -e "${GREEN}Container Management:${NC}"
    echo "  start              Start the test runner container"
    echo "  stop               Stop the test runner container"
    echo "  restart            Restart the test runner container"
    echo "  status             Check if container is running"
    echo ""
    echo -e "${GREEN}Kotlin Integration Tests:${NC}"
    echo "  kt <suite>         Run a Kotlin test suite"
    echo "  kt-list            List all available Kotlin test suites"
    echo ""
    echo -e "${GREEN}TypeScript Tests:${NC}"
    echo "  ts                 Run all TypeScript tests (unit + e2e)"
    echo "  ts-unit            Run Jest unit tests only"
    echo "  ts-e2e             Run Playwright E2E tests only"
    echo "  ts-ui              Run Playwright in UI mode"
    echo "  ts-headed          Run Playwright in headed mode (browser visible)"
    echo "  ts-debug           Run Playwright in debug mode"
    echo "  ts-report          Show Playwright test report"
    echo ""
    echo -e "${GREEN}Utility Commands:${NC}"
    echo "  shell              Open a shell inside the container"
    echo "  logs               Show container logs"
    echo "  help               Show this help message"
    echo ""
    echo -e "${YELLOW}Examples:${NC}"
    echo "  $0 start                    # Start the container"
    echo "  $0 kt foundation            # Run foundation test suite"
    echo "  $0 kt all                   # Run all Kotlin integration tests"
    echo "  $0 ts-unit                  # Run Jest unit tests"
    echo "  $0 ts-e2e                   # Run Playwright E2E tests"
    echo "  $0 shell                    # Open shell in container"
    echo ""
}

check_container_running() {
    if ! docker compose ps $CONTAINER_NAME | grep -q "Up"; then
        echo -e "${RED}✗${NC} Container is not running. Start it with: $0 start"
        exit 1
    fi
}

list_kt_suites() {
    echo -e "${GREEN}Available Kotlin Test Suites:${NC}"
    echo ""
    echo -e "${BLUE}Core Services:${NC}"
    echo "  foundation, llm, knowledge-base, data-pipeline, microservices, search-service"
    echo ""
    echo -e "${BLUE}Infrastructure:${NC}"
    echo "  infrastructure, databases, user-interface"
    echo ""
    echo -e "${BLUE}Features:${NC}"
    echo "  communication, collaboration, productivity, file-management"
    echo "  security, monitoring, backup"
    echo ""
    echo -e "${BLUE}Authentication:${NC}"
    echo "  authentication, enhanced-auth, authenticated-ops"
    echo ""
    echo -e "${BLUE}Services:${NC}"
    echo "  utility, homeassistant, bookstack, email-stack, caching-layer"
    echo ""
    echo -e "${BLUE}Deployment & CI/CD:${NC}"
    echo "  stack-deployment, cicd, isolated-docker-vm, stack-replication"
    echo ""
    echo -e "${BLUE}Advanced:${NC}"
    echo "  agent-capability, agent-security, agent-llm-quality"
    echo "  trading, web3-wallet"
    echo ""
    echo -e "${BLUE}Extended Tests:${NC}"
    echo "  extended-communication, extended-productivity, playwright-e2e"
    echo ""
    echo -e "${YELLOW}Special:${NC}"
    echo "  all                         Run ALL test suites"
    echo ""
}

case "${1:-help}" in
    start)
        echo -e "${BLUE}Starting test runner container...${NC}"
        docker compose up -d $CONTAINER_NAME
        echo -e "${GREEN}✓${NC} Container started"
        ;;

    stop)
        echo -e "${BLUE}Stopping test runner container...${NC}"
        docker compose stop $CONTAINER_NAME
        echo -e "${GREEN}✓${NC} Container stopped"
        ;;

    restart)
        echo -e "${BLUE}Restarting test runner container...${NC}"
        docker compose restart $CONTAINER_NAME
        echo -e "${GREEN}✓${NC} Container restarted"
        ;;

    status)
        if docker compose ps $CONTAINER_NAME | grep -q "Up"; then
            echo -e "${GREEN}✓${NC} Container is running"
        else
            echo -e "${RED}✗${NC} Container is not running"
            exit 1
        fi
        ;;

    kt)
        if [ -z "$2" ]; then
            echo -e "${RED}Error:${NC} Test suite name required"
            echo "Usage: $0 kt <suite-name>"
            echo ""
            list_kt_suites
            exit 1
        fi
        check_container_running
        echo -e "${BLUE}Running Kotlin test suite: $2${NC}"
        docker compose exec $CONTAINER_NAME java -jar /app/test-runner.jar --env container --suite "$2"
        ;;

    kt-list)
        list_kt_suites
        ;;

    ts)
        check_container_running
        echo -e "${BLUE}Running all TypeScript tests...${NC}"
        docker compose exec $CONTAINER_NAME npm run --prefix $PLAYWRIGHT_DIR test
        ;;

    ts-unit)
        check_container_running
        echo -e "${BLUE}Running Jest unit tests...${NC}"
        docker compose exec $CONTAINER_NAME npm run --prefix $PLAYWRIGHT_DIR test:unit
        ;;

    ts-e2e)
        check_container_running
        echo -e "${BLUE}Running Playwright E2E tests...${NC}"
        docker compose exec $CONTAINER_NAME npm run --prefix $PLAYWRIGHT_DIR test:e2e
        ;;

    ts-ui)
        check_container_running
        echo -e "${BLUE}Running Playwright in UI mode...${NC}"
        docker compose exec $CONTAINER_NAME npm run --prefix $PLAYWRIGHT_DIR test:ui
        ;;

    ts-headed)
        check_container_running
        echo -e "${BLUE}Running Playwright in headed mode...${NC}"
        docker compose exec $CONTAINER_NAME npm run --prefix $PLAYWRIGHT_DIR test:headed
        ;;

    ts-debug)
        check_container_running
        echo -e "${BLUE}Running Playwright in debug mode...${NC}"
        docker compose exec $CONTAINER_NAME npm run --prefix $PLAYWRIGHT_DIR test:debug
        ;;

    ts-report)
        check_container_running
        echo -e "${BLUE}Showing Playwright test report...${NC}"
        docker compose exec $CONTAINER_NAME npm run --prefix $PLAYWRIGHT_DIR test:report
        ;;

    shell)
        check_container_running
        echo -e "${BLUE}Opening shell in container...${NC}"
        docker compose exec $CONTAINER_NAME bash
        ;;

    logs)
        echo -e "${BLUE}Showing container logs...${NC}"
        docker compose logs -f $CONTAINER_NAME
        ;;

    help|--help|-h)
        print_usage
        ;;

    *)
        echo -e "${RED}Error:${NC} Unknown command: $1"
        echo ""
        print_usage
        exit 1
        ;;
esac
