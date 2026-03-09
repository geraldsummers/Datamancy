#!/bin/bash

# Datamancy Test Runner Script
# This script provides a convenient interface for running tests inside the test-runner container

set -e

PLAYWRIGHT_DIR="/app/playwright-tests"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_CADDY_CONTAINER="caddy"

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
    echo "  start [suite]       Start a test runner container (default: all)"
    echo "  stop [suite]        Stop a test runner container (default: all)"
    echo "  restart [suite]     Restart a test runner container (default: all)"
    echo "  status [suite]      Check if a test runner container is running (default: all)"
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
    echo -e "${GREEN}Smart Selection:${NC}"
    echo "  smart              Run suites needing retest based on registry/status"
    echo ""
    echo -e "${GREEN}Utility Commands:${NC}"
    echo "  shell [suite]      Open a shell inside a test runner container (default: all)"
    echo "  logs [suite]       Show container logs (default: all)"
    echo "  help               Show this help message"
    echo ""
    echo -e "${YELLOW}Examples:${NC}"
    echo "  $0 start                    # Start the all-tests container"
    echo "  $0 start foundation         # Start the foundation test container"
    echo "  $0 kt foundation            # Run foundation test suite"
    echo "  $0 kt all                   # Run all Kotlin integration tests"
    echo "  $0 ts-unit                  # Run Jest unit tests"
    echo "  $0 ts-e2e                   # Run Playwright E2E tests"
    echo "  $0 shell playwright-e2e     # Open shell in Playwright container"
    echo "  $0 smart                    # Run only suites needing retest"
    echo ""
}

suite_container() {
    case "$1" in
        foundation|llm|knowledge-base|data-pipeline|microservices|search-service|infrastructure|databases|user-interface|communication|collaboration|productivity|file-management|security|monitoring|backup|authentication|enhanced-auth|authenticated-ops|utility|homeassistant|stack-deployment|bookstack|cicd|isolated-docker-vm|stack-replication|agent-capability|agent-security|agent-llm-quality|agent-orchestration|stack-llm-capability|trading|trading-dsl|trading-advanced|web3-wallet|email-stack|caching-layer|extended-communication|extended-productivity|playwright-e2e|all)
            echo "test-$1"
            ;;
        *)
            return 1
            ;;
    esac
}

ensure_caddy_ca_trusted() {
    local container_name="$1"
    local caddy_container="${CADDY_CONTAINER:-$DEFAULT_CADDY_CONTAINER}"
    local caddy_cert_path="${CADDY_CERT_PATH:-/certs/pki/authorities/local/root.crt}"
    local tmp_cert

    if ! docker ps --format '{{.Names}}' | grep -q "^${caddy_container}$"; then
        return 0
    fi

    tmp_cert="$(mktemp)"
    if ! docker cp "${caddy_container}:${caddy_cert_path}" "$tmp_cert" >/dev/null 2>&1; then
        rm -f "$tmp_cert"
        return 0
    fi

    docker cp "$tmp_cert" "${container_name}:/usr/local/share/ca-certificates/caddy-local-root.crt" >/dev/null 2>&1 || true
    rm -f "$tmp_cert"

    docker compose exec --user root "$container_name" bash -lc "
        update-ca-certificates >/dev/null 2>&1 || true
        if command -v keytool >/dev/null 2>&1; then
            JAVA_CACERTS=\"\${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/lib/security/cacerts\"
            if [ -f \"\$JAVA_CACERTS\" ]; then
                if ! keytool -list -keystore \"\$JAVA_CACERTS\" -storepass changeit -alias caddy-local-root >/dev/null 2>&1; then
                    keytool -importcert -noprompt -trustcacerts -alias caddy-local-root -file /usr/local/share/ca-certificates/caddy-local-root.crt -keystore \"\$JAVA_CACERTS\" -storepass changeit >/dev/null 2>&1 || true
                fi
            fi
        fi
    " >/dev/null 2>&1 || true
}

check_container_running() {
    local container_name="$1"
    if ! docker compose ps "$container_name" | grep -q "Up"; then
        echo -e "${RED}✗${NC} Container is not running. Start it with: $0 start $container_name"
        exit 1
    fi
}

smart_plan_from_registry() {
    local registry_json="${TEST_REGISTRY_JSON:-$ROOT_DIR/test-registry.json}"
    local status_json="${TEST_STATUS_JSON:-$ROOT_DIR/test-status.json}"

    if [ ! -f "$registry_json" ]; then
        echo -e "${RED}Error:${NC} Registry not found: $registry_json"
        exit 1
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        echo -e "${RED}Error:${NC} python3 is required for smart mode without build script"
        exit 1
    fi

    python3 - <<'PY' "$registry_json" "$status_json"
import hashlib
import json
import sys

registry_path, status_path = sys.argv[1:3]
with open(registry_path, "r", encoding="utf-8") as f:
    registry = json.load(f)

status = {"suites": {}}
try:
    with open(status_path, "r", encoding="utf-8") as f:
        status = json.load(f)
except FileNotFoundError:
    pass

components = registry.get("components", {})

def suite_signature(targets):
    commits = [
        (components.get(target) or {}).get("last_changed_commit") or ""
        for target in targets
    ]
    if not commits:
        return ""
    payload = "|".join(sorted(commits)).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()

for suite in registry.get("suites", {}).values():
    name = suite.get("name")
    suite_type = suite.get("type", "kotlin")
    if not name:
        continue
    targets = suite.get("targets") or []
    expected = suite_signature(targets)
    last_tested = (status.get("suites", {}).get(name) or {}).get("last_tested_commit") or ""
    if not last_tested or expected != last_tested:
        print(f"{name}|{suite_type}")
PY
}

record_test_status() {
    local suite="$1"
    local registry_json="${TEST_REGISTRY_JSON:-$ROOT_DIR/test-registry.json}"
    local status_json="${TEST_STATUS_JSON:-$ROOT_DIR/test-status.json}"

    if ! command -v python3 >/dev/null 2>&1; then
        echo -e "${RED}Error:${NC} python3 is required to record smart status"
        exit 1
    fi

    python3 - <<'PY' "$suite" "$registry_json" "$status_json"
import hashlib
import json
import sys
from datetime import datetime, timezone

suite, registry_path, status_path = sys.argv[1:4]
with open(registry_path, "r", encoding="utf-8") as f:
    registry = json.load(f)

status = {"suites": {}}
try:
    with open(status_path, "r", encoding="utf-8") as f:
        status = json.load(f)
except FileNotFoundError:
    pass

components = registry.get("components", {})
suite_def = (registry.get("suites", {}) or {}).get(suite, {})
targets = suite_def.get("targets") or []
commits = [
    (components.get(target) or {}).get("last_changed_commit") or ""
    for target in targets
]
signature = ""
if commits:
    signature = hashlib.sha256("|".join(sorted(commits)).encode("utf-8")).hexdigest()

status.setdefault("suites", {})
status["suites"][suite] = {
    "last_tested_commit": signature,
    "last_tested_at": datetime.now(timezone.utc).isoformat(),
}

with open(status_path, "w", encoding="utf-8") as f:
    json.dump(status, f, indent=2, sort_keys=True)
PY
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
    echo "  agent-orchestration, stack-llm-capability"
    echo "  trading, trading-dsl, trading-advanced, web3-wallet"
    echo ""
    echo -e "${BLUE}Extended Tests:${NC}"
    echo "  extended-communication, extended-productivity, playwright-e2e"
    echo ""
    echo -e "${YELLOW}Special:${NC}"
    echo "  all                         Run ALL test suites"
    echo ""
}

case "${1:-help}" in
    smart)
        cd "$ROOT_DIR"
        use_build_script=0
        if [ -x "./build-datamancy-v3.main.kts" ]; then
            use_build_script=1
            plan_output=$(./build-datamancy-v3.main.kts --test-plan)
        else
            plan_output=$(smart_plan_from_registry)
        fi
        if [ -z "$plan_output" ]; then
            echo -e "${GREEN}✓${NC} No suites require retest"
            exit 0
        fi

        while IFS='|' read -r suite suite_type; do
            if [ -z "$suite" ]; then
                continue
            fi
            container_name=$(suite_container "$suite") || { echo -e "${RED}Error:${NC} Unknown suite: $suite"; exit 1; }
            echo -e "${BLUE}Starting test runner container: ${container_name}...${NC}"
            docker compose up -d "$container_name"
            ensure_caddy_ca_trusted "$container_name"

            case "$suite_type" in
                playwright)
                    echo -e "${BLUE}Running Playwright E2E tests...${NC}"
                    docker compose exec "$container_name" npm run --prefix $PLAYWRIGHT_DIR test:e2e
                    ;;
                kotlin|*)
                    echo -e "${BLUE}Running Kotlin test suite: $suite${NC}"
                    docker compose exec "$container_name" java -jar /app/test-runner.jar --env container --suite "$suite"
                    ;;
            esac

            if [ "$use_build_script" -eq 1 ]; then
                ./build-datamancy-v3.main.kts --record-test "$suite"
            else
                record_test_status "$suite"
            fi
        done <<< "$plan_output"
        ;;
    start)
        suite="${2:-all}"
        container_name=$(suite_container "$suite") || { echo -e "${RED}Error:${NC} Unknown suite: $suite"; exit 1; }
        echo -e "${BLUE}Starting test runner container: ${container_name}...${NC}"
        docker compose up -d "$container_name"
        echo -e "${GREEN}✓${NC} Container started"
        ;;

    stop)
        suite="${2:-all}"
        container_name=$(suite_container "$suite") || { echo -e "${RED}Error:${NC} Unknown suite: $suite"; exit 1; }
        echo -e "${BLUE}Stopping test runner container: ${container_name}...${NC}"
        docker compose stop "$container_name"
        echo -e "${GREEN}✓${NC} Container stopped"
        ;;

    restart)
        suite="${2:-all}"
        container_name=$(suite_container "$suite") || { echo -e "${RED}Error:${NC} Unknown suite: $suite"; exit 1; }
        echo -e "${BLUE}Restarting test runner container: ${container_name}...${NC}"
        docker compose restart "$container_name"
        echo -e "${GREEN}✓${NC} Container restarted"
        ;;

    status)
        suite="${2:-all}"
        container_name=$(suite_container "$suite") || { echo -e "${RED}Error:${NC} Unknown suite: $suite"; exit 1; }
        if docker compose ps "$container_name" | grep -q "Up"; then
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
        container_name=$(suite_container "$2") || { echo -e "${RED}Error:${NC} Unknown suite: $2"; exit 1; }
        check_container_running "$container_name"
        ensure_caddy_ca_trusted "$container_name"
        echo -e "${BLUE}Running Kotlin test suite: $2${NC}"
        docker compose exec "$container_name" java -jar /app/test-runner.jar --env container --suite "$2"
        ;;

    kt-list)
        list_kt_suites
        ;;

    ts)
        container_name=$(suite_container "playwright-e2e")
        check_container_running "$container_name"
        ensure_caddy_ca_trusted "$container_name"
        echo -e "${BLUE}Running all TypeScript tests...${NC}"
        docker compose exec "$container_name" npm run --prefix $PLAYWRIGHT_DIR test
        ;;

    ts-unit)
        container_name=$(suite_container "playwright-e2e")
        check_container_running "$container_name"
        ensure_caddy_ca_trusted "$container_name"
        echo -e "${BLUE}Running Jest unit tests...${NC}"
        docker compose exec "$container_name" npm run --prefix $PLAYWRIGHT_DIR test:unit
        ;;

    ts-e2e)
        container_name=$(suite_container "playwright-e2e")
        check_container_running "$container_name"
        ensure_caddy_ca_trusted "$container_name"
        echo -e "${BLUE}Running Playwright E2E tests...${NC}"
        docker compose exec "$container_name" npm run --prefix $PLAYWRIGHT_DIR test:e2e
        ;;

    ts-ui)
        container_name=$(suite_container "playwright-e2e")
        check_container_running "$container_name"
        ensure_caddy_ca_trusted "$container_name"
        echo -e "${BLUE}Running Playwright in UI mode...${NC}"
        docker compose exec "$container_name" npm run --prefix $PLAYWRIGHT_DIR test:ui
        ;;

    ts-headed)
        container_name=$(suite_container "playwright-e2e")
        check_container_running "$container_name"
        ensure_caddy_ca_trusted "$container_name"
        echo -e "${BLUE}Running Playwright in headed mode...${NC}"
        docker compose exec "$container_name" npm run --prefix $PLAYWRIGHT_DIR test:headed
        ;;

    ts-debug)
        container_name=$(suite_container "playwright-e2e")
        check_container_running "$container_name"
        ensure_caddy_ca_trusted "$container_name"
        echo -e "${BLUE}Running Playwright in debug mode...${NC}"
        docker compose exec "$container_name" npm run --prefix $PLAYWRIGHT_DIR test:debug
        ;;

    ts-report)
        container_name=$(suite_container "playwright-e2e")
        check_container_running "$container_name"
        ensure_caddy_ca_trusted "$container_name"
        echo -e "${BLUE}Showing Playwright test report...${NC}"
        docker compose exec "$container_name" npm run --prefix $PLAYWRIGHT_DIR test:report
        ;;

    shell)
        suite="${2:-all}"
        container_name=$(suite_container "$suite") || { echo -e "${RED}Error:${NC} Unknown suite: $suite"; exit 1; }
        check_container_running "$container_name"
        echo -e "${BLUE}Opening shell in container...${NC}"
        docker compose exec "$container_name" bash
        ;;

    logs)
        suite="${2:-all}"
        container_name=$(suite_container "$suite") || { echo -e "${RED}Error:${NC} Unknown suite: $suite"; exit 1; }
        echo -e "${BLUE}Showing container logs...${NC}"
        docker compose logs -f "$container_name"
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
