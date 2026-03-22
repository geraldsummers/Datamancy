#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR_DEFAULT=""
if [ -f "$SCRIPT_DIR/docker-compose.yml" ] && [ -f "$SCRIPT_DIR/tests.compose/test-runners.yml" ]; then
    PROJECT_ROOT="$SCRIPT_DIR"
    DIST_DIR_DEFAULT="$PROJECT_ROOT"
elif [ -d "$SCRIPT_DIR/../global.settings" ]; then
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
    DIST_DIR_DEFAULT="$PROJECT_ROOT/dist"
elif [ -d "$SCRIPT_DIR/../../global.settings" ]; then
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
    DIST_DIR_DEFAULT="$PROJECT_ROOT/dist"
else
    echo "Error: could not locate the Datamancy project root" >&2
    exit 1
fi

DIST_DIR="${DIST_DIR:-$DIST_DIR_DEFAULT}"
PRIMARY_COMPOSE_FILE="${COMPOSE_FILE_PATH:-$DIST_DIR/docker-compose.yml}"
TEST_RUNNERS_COMPOSE_FILE="${TEST_RUNNERS_COMPOSE_FILE_PATH:-$DIST_DIR/tests.compose/test-runners.yml}"
TEST_REGISTRY_JSON="${TEST_REGISTRY_JSON:-$DIST_DIR/test-registry.json}"
TEST_STATUS_JSON="${TEST_STATUS_JSON:-$DIST_DIR/test-status.json}"
PLAYWRIGHT_SERVICE="test-playwright-e2e"
BUILD_SCRIPT="$PROJECT_ROOT/build-datamancy-v4.main.kts"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_header() {
    echo -e "${BLUE}╔═══════════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║  Datamancy Test Runner                                                   ║${NC}"
    echo -e "${BLUE}╚═══════════════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}Artifacts:${NC}"
    echo "  Compose stack: $PRIMARY_COMPOSE_FILE"
    echo "  Test override: $TEST_RUNNERS_COMPOSE_FILE"
    echo "  Results root:  $DIST_DIR/test-results"
    echo ""
}

print_usage() {
    print_header
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo ""
    echo -e "${GREEN}One-Shot Suite Runs:${NC}"
    echo "  kt <suite>         Run a Kotlin integration suite as the container process"
    echo "  run <suite>        Alias for kt"
    echo "  kt-list            List all available Kotlin test suites"
    echo "  smart              Run suites needing retest based on registry/status"
    echo ""
    echo -e "${GREEN}TypeScript / Playwright:${NC}"
    echo "  ts                 Run all TypeScript tests (unit + e2e)"
    echo "  ts-unit            Run Jest unit tests only"
    echo "  ts-unit-one <path> Run a single Jest test file"
    echo "  ts-unit-name <pat> Run Jest tests matching a name"
    echo "  ts-e2e             Run Playwright E2E tests only"
    echo "  ts-e2e-one <path>  Run a single Playwright test file"
    echo "  ts-e2e-name <pat>  Run Playwright tests matching a name"
    echo "  ts-ui              Run Playwright in UI mode"
    echo "  ts-headed          Run Playwright in headed mode"
    echo "  ts-debug           Run Playwright in debug mode"
    echo "  ts-report          Print the persisted Playwright report location"
    echo ""
    echo -e "${GREEN}Background / Debug:${NC}"
    echo "  start [suite]      Run a suite service in detached mode"
    echo "  stop [suite]       Stop a detached suite service"
    echo "  restart [suite]    Recreate and rerun a detached suite service"
    echo "  status [suite]     Show detached suite service status"
    echo "  logs [suite]       Follow detached suite service logs"
    echo "  shell [suite]      Open a shell in a one-shot suite container"
    echo ""
    echo -e "${YELLOW}Examples:${NC}"
    echo "  $0 kt foundation"
    echo "  $0 smart"
    echo "  $0 ts-e2e"
    echo "  $0 shell trading"
    echo "  $0 start playwright-e2e"
    echo ""
}

ensure_compose_artifacts() {
    if [ ! -f "$PRIMARY_COMPOSE_FILE" ]; then
        echo -e "${RED}Error:${NC} Compose file not found: $PRIMARY_COMPOSE_FILE" >&2
        echo "Run ./build-datamancy-v4.main.kts first to generate dist artifacts." >&2
        exit 1
    fi

    if [ ! -f "$TEST_RUNNERS_COMPOSE_FILE" ]; then
        echo -e "${RED}Error:${NC} Test runner override not found: $TEST_RUNNERS_COMPOSE_FILE" >&2
        echo "Run ./build-datamancy-v4.main.kts --test-plan to refresh test-runner artifacts." >&2
        exit 1
    fi
}

docker_compose() {
    ensure_compose_artifacts
    docker compose \
        --project-directory "$DIST_DIR" \
        -f "$PRIMARY_COMPOSE_FILE" \
        -f "$TEST_RUNNERS_COMPOSE_FILE" \
        "$@"
}

repair_dir_ownership() {
    local target="$1"
    local uid gid

    uid="$(id -u 2>/dev/null || true)"
    gid="$(id -g 2>/dev/null || true)"
    if [ -z "$uid" ] || [ -z "$gid" ] || ! command -v docker >/dev/null 2>&1; then
        return 1
    fi

    docker run --rm -v "$target:/target" alpine sh -lc "chown -R $uid:$gid /target && chmod -R u+rwX /target" >/dev/null 2>&1
}

ensure_writable_dir() {
    local dir="$1"

    if mkdir -p "$dir" 2>/dev/null && [ -d "$dir" ] && [ -w "$dir" ]; then
        return 0
    fi

    if [ -e "$dir" ]; then
        chmod u+rwx "$dir" 2>/dev/null || true
    fi
    if [ -d "$dir" ] && [ -w "$dir" ]; then
        return 0
    fi

    if [ -e "$dir" ]; then
        repair_dir_ownership "$dir" || true
        chmod u+rwx "$dir" 2>/dev/null || true
    fi

    if mkdir -p "$dir" 2>/dev/null && [ -d "$dir" ] && [ -w "$dir" ]; then
        return 0
    fi

    echo -e "${RED}Error:${NC} Unable to prepare writable test results directory: $dir" >&2
    exit 1
}

prepare_service_results_dir() {
    local service="$1"

    ensure_writable_dir "$DIST_DIR/test-results"
    case "$service" in
        test-*)
            ensure_writable_dir "$DIST_DIR/test-results/${service#test-}"
            ;;
    esac
}

suite_service() {
    case "$1" in
        foundation|llm|knowledge-base|data-pipeline|microservices|search-service|infrastructure|databases|user-interface|communication|collaboration|productivity|file-management|security|monitoring|backup|authentication|enhanced-auth|authenticated-ops|utility|homeassistant|stack-deployment|bookstack|cicd|isolated-docker-vm|stack-replication|agent-capability|agent-security|agent-llm-quality|agent-orchestration|stack-llm-capability|trading|trading-staged|trading-dsl|trading-advanced|web3-wallet|email-stack|caching-layer|extended-communication|extended-productivity|playwright-e2e|all)
            echo "test-$1"
            ;;
        *)
            return 1
            ;;
    esac
}

build_exec_env_args() {
    EXEC_ENV_ARGS=()
    local passthrough_vars=(
        TRADING_E2E_MODE
        TRADING_E2E_TARGET_HOST
        TRADING_E2E_TX_GATEWAY_URL
        TRADING_E2E_AUTHELIA_URL
        TRADING_E2E_HYPERLIQUID_WORKER_URL
        TRADING_E2E_HYPERLIQUID_KEY
        TRADING_E2E_HYPERLIQUID_STRICT_CREDENTIALS
        HYPERLIQUID_TESTNET_KEY
        TRADING_E2E_PREP_RISK_STATE
        TRADING_E2E_RECORD
        TRADING_E2E_RECORD_DIR
        TRADING_E2E_BEARER_TOKEN
        TRADING_E2E_OIDC_CLIENT_ID
        TRADING_E2E_OIDC_CLIENT_SECRET
        TRADING_E2E_OIDC_REDIRECT_URI
        TRADING_E2E_OIDC_SCOPE
        TRADING_E2E_USERNAME
        TRADING_E2E_PASSWORD
        MODEL_CONTEXT_OIDC_CLIENT_ID
        MODEL_CONTEXT_OIDC_CLIENT_SECRET
        MODEL_CONTEXT_OIDC_REDIRECT_URI
        MODEL_CONTEXT_OIDC_SCOPE
        TEST_RUNNER_OAUTH_SECRET
        STACK_ADMIN_USER
        STACK_ADMIN_PASSWORD
        STACK_ADMIN_EMAIL
        LDAP_ADMIN_PASSWORD
    )

    if [ -n "${RUN_TESTS_EXEC_ENV_PASSTHROUGH:-}" ]; then
        local extra_csv="${RUN_TESTS_EXEC_ENV_PASSTHROUGH//,/ }"
        local extra_var
        for extra_var in $extra_csv; do
            [ -n "$extra_var" ] && passthrough_vars+=("$extra_var")
        done
    fi

    local var_name
    for var_name in "${passthrough_vars[@]}"; do
        if [ -n "${!var_name:-}" ]; then
            EXEC_ENV_ARGS+=("-e" "${var_name}=${!var_name}")
        fi
    done
}

run_service_once() {
    local service="$1"
    shift || true

    prepare_service_results_dir "$service"
    build_exec_env_args
    docker_compose run --rm "${EXEC_ENV_ARGS[@]}" "$service" "$@"
}

smart_plan_from_registry() {
    if [ ! -f "$TEST_REGISTRY_JSON" ]; then
        echo -e "${RED}Error:${NC} Registry not found: $TEST_REGISTRY_JSON" >&2
        exit 1
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        echo -e "${RED}Error:${NC} python3 is required for smart mode without build script" >&2
        exit 1
    fi

    python3 - <<'PY' "$TEST_REGISTRY_JSON" "$TEST_STATUS_JSON"
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
    if not name:
        continue
    targets = suite.get("targets") or []
    expected = suite_signature(targets)
    last_tested = (status.get("suites", {}).get(name) or {}).get("last_tested_commit") or ""
    if not last_tested or expected != last_tested:
        print(name)
PY
}

record_test_status() {
    local suite="$1"

    if ! command -v python3 >/dev/null 2>&1; then
        echo -e "${RED}Error:${NC} python3 is required to record smart status" >&2
        exit 1
    fi

    python3 - <<'PY' "$suite" "$TEST_REGISTRY_JSON" "$TEST_STATUS_JSON"
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

def suite_signature(targets):
    commits = [
        (components.get(target) or {}).get("last_changed_commit") or ""
        for target in targets
    ]
    if not commits:
        return ""
    payload = "|".join(sorted(commits)).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()

suite_def = (registry.get("suites") or {}).get(suite) or {}
expected = suite_signature(suite_def.get("targets") or [])
status.setdefault("suites", {})[suite] = {
    "last_tested_commit": expected,
    "last_tested_at": datetime.now(timezone.utc).isoformat(),
}

with open(status_path, "w", encoding="utf-8") as f:
    json.dump(status, f, indent=2, sort_keys=True)
    f.write("\n")
PY
}

show_status() {
    local service="$1"
    local container_id

    container_id="$(docker_compose ps -q "$service")"
    if [ -z "$container_id" ]; then
        echo -e "${RED}✗${NC} No container exists for $service"
        return 1
    fi

    local state
    state="$(docker inspect -f '{{.State.Status}}' "$container_id")"
    echo -e "${GREEN}✓${NC} $service: $state"
}

list_kt_suites() {
    echo -e "${BLUE}Available Kotlin test suites:${NC}"
    echo ""
    echo -e "${BLUE}Core:${NC}"
    echo "  foundation, llm, knowledge-base, data-pipeline, microservices, search-service"
    echo ""
    echo -e "${BLUE}Platform:${NC}"
    echo "  infrastructure, databases, user-interface, monitoring, backup"
    echo ""
    echo -e "${BLUE}Applications:${NC}"
    echo "  communication, collaboration, productivity, file-management, homeassistant"
    echo ""
    echo -e "${BLUE}Security:${NC}"
    echo "  security, authentication, enhanced-auth, authenticated-ops"
    echo ""
    echo -e "${BLUE}Deployment:${NC}"
    echo "  stack-deployment, bookstack, cicd, isolated-docker-vm, stack-replication"
    echo ""
    echo -e "${BLUE}Agents:${NC}"
    echo "  agent-capability, agent-security, agent-llm-quality, agent-orchestration, stack-llm-capability"
    echo ""
    echo -e "${BLUE}Trading:${NC}"
    echo "  trading, trading-staged, trading-dsl, trading-advanced, web3-wallet"
    echo ""
    echo -e "${BLUE}Extended:${NC}"
    echo "  email-stack, caching-layer, extended-communication, extended-productivity, playwright-e2e"
    echo ""
    echo -e "${YELLOW}Special:${NC}"
    echo "  utility, all"
}

case "${1:-help}" in
    smart)
        ensure_compose_artifacts
        if [ -x "$BUILD_SCRIPT" ]; then
            plan_output="$(cd "$PROJECT_ROOT" && "$BUILD_SCRIPT" --test-plan)"
        else
            plan_output="$(smart_plan_from_registry)"
        fi

        if [ -z "$plan_output" ]; then
            echo -e "${GREEN}✓${NC} No suites require retest"
            exit 0
        fi

        while IFS= read -r suite; do
            [ -z "$suite" ] && continue
            service="$(suite_service "$suite")" || {
                echo -e "${RED}Error:${NC} Unknown suite: $suite" >&2
                exit 1
            }
            echo -e "${BLUE}Running suite: $suite${NC}"
            run_service_once "$service"
            if [ -x "$BUILD_SCRIPT" ]; then
                (cd "$PROJECT_ROOT" && "$BUILD_SCRIPT" --record-test "$suite")
            else
                record_test_status "$suite"
            fi
        done <<< "$plan_output"
        ;;
    start)
        suite="${2:-all}"
        service="$(suite_service "$suite")" || {
            echo -e "${RED}Error:${NC} Unknown suite: $suite" >&2
            exit 1
        }
        prepare_service_results_dir "$service"
        echo -e "${BLUE}Starting detached suite service: ${service}${NC}"
        docker_compose up -d --force-recreate "$service"
        ;;
    stop)
        suite="${2:-all}"
        service="$(suite_service "$suite")" || {
            echo -e "${RED}Error:${NC} Unknown suite: $suite" >&2
            exit 1
        }
        docker_compose stop "$service"
        ;;
    restart)
        suite="${2:-all}"
        service="$(suite_service "$suite")" || {
            echo -e "${RED}Error:${NC} Unknown suite: $suite" >&2
            exit 1
        }
        prepare_service_results_dir "$service"
        docker_compose rm -f -s "$service" >/dev/null 2>&1 || true
        docker_compose up -d --force-recreate "$service"
        ;;
    status)
        suite="${2:-all}"
        service="$(suite_service "$suite")" || {
            echo -e "${RED}Error:${NC} Unknown suite: $suite" >&2
            exit 1
        }
        show_status "$service"
        ;;
    run|kt)
        if [ -z "${2:-}" ]; then
            echo -e "${RED}Error:${NC} Test suite name required" >&2
            echo "Usage: $0 kt <suite-name>" >&2
            echo ""
            list_kt_suites
            exit 1
        fi
        service="$(suite_service "$2")" || {
            echo -e "${RED}Error:${NC} Unknown suite: $2" >&2
            exit 1
        }
        run_service_once "$service"
        ;;
    kt-list)
        list_kt_suites
        ;;
    ts)
        run_service_once "$PLAYWRIGHT_SERVICE" ts
        ;;
    ts-unit)
        run_service_once "$PLAYWRIGHT_SERVICE" ts-unit
        ;;
    ts-unit-one)
        if [ -z "${2:-}" ]; then
            echo "Usage: $0 ts-unit-one <path>" >&2
            exit 1
        fi
        run_service_once "$PLAYWRIGHT_SERVICE" ts-unit-one "$2"
        ;;
    ts-unit-name)
        if [ -z "${2:-}" ]; then
            echo "Usage: $0 ts-unit-name <pattern>" >&2
            exit 1
        fi
        run_service_once "$PLAYWRIGHT_SERVICE" ts-unit-name "$2"
        ;;
    ts-e2e)
        run_service_once "$PLAYWRIGHT_SERVICE" ts-e2e
        ;;
    ts-e2e-one)
        if [ -z "${2:-}" ]; then
            echo "Usage: $0 ts-e2e-one <path>" >&2
            exit 1
        fi
        run_service_once "$PLAYWRIGHT_SERVICE" ts-e2e-one "$2"
        ;;
    ts-e2e-name)
        if [ -z "${2:-}" ]; then
            echo "Usage: $0 ts-e2e-name <pattern>" >&2
            exit 1
        fi
        run_service_once "$PLAYWRIGHT_SERVICE" ts-e2e-name "$2"
        ;;
    ts-ui)
        run_service_once "$PLAYWRIGHT_SERVICE" ts-ui
        ;;
    ts-headed)
        run_service_once "$PLAYWRIGHT_SERVICE" ts-headed
        ;;
    ts-debug)
        run_service_once "$PLAYWRIGHT_SERVICE" ts-debug
        ;;
    ts-report)
        report_path="$DIST_DIR/test-results/playwright-e2e/playwright/report/index.html"
        if [ -f "$report_path" ]; then
            echo "$report_path"
        else
            echo -e "${YELLOW}No persisted Playwright report found at:${NC} $report_path" >&2
            exit 1
        fi
        ;;
    shell)
        suite="${2:-all}"
        service="$(suite_service "$suite")" || {
            echo -e "${RED}Error:${NC} Unknown suite: $suite" >&2
            exit 1
        }
        run_service_once "$service" shell
        ;;
    logs)
        suite="${2:-all}"
        service="$(suite_service "$suite")" || {
            echo -e "${RED}Error:${NC} Unknown suite: $suite" >&2
            exit 1
        }
        docker_compose logs -f "$service"
        ;;
    help|--help|-h)
        print_usage
        ;;
    *)
        echo -e "${RED}Error:${NC} Unknown command: $1" >&2
        echo ""
        print_usage
        exit 1
        ;;
esac
