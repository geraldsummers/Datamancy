#!/bin/bash

set -euo pipefail

TEST_USER="testing_container_user"
TEST_USER_HOME="/home/${TEST_USER}"
PLAYWRIGHT_DIR="/app/playwright-tests"
RESULTS_DIR="/app/test-results"
PLAYWRIGHT_RESULTS_DIR="${RESULTS_DIR}/playwright"
DEFAULT_CADDY_CONTAINER="caddy"
DEFAULT_CADDY_CERT_PATH="/certs/pki/authorities/local/root.crt"
CADDY_CA_ALIAS="caddy-local-root"
CADDY_CA_TARGET="/usr/local/share/ca-certificates/${CADDY_CA_ALIAS}.crt"

log() {
    printf '[test-runner] %s\n' "$*"
}

prepare_results_dir() {
    mkdir -p "$RESULTS_DIR"
    chown -R "${TEST_USER}:${TEST_USER}" "$RESULTS_DIR"
}

copy_tree() {
    local source_dir="$1"
    local target_dir="$2"

    rm -rf "$target_dir"
    mkdir -p "$(dirname "$target_dir")"
    cp -a "$source_dir" "$target_dir"
}

bootstrap_caddy_ca() {
    local caddy_container="${CADDY_CONTAINER:-$DEFAULT_CADDY_CONTAINER}"
    local caddy_cert_path="${CADDY_CERT_PATH:-$DEFAULT_CADDY_CERT_PATH}"
    local docker_names
    local tmp_cert

    if ! command -v docker >/dev/null 2>&1; then
        log "Docker CLI is unavailable; skipping Caddy CA bootstrap"
        return 0
    fi

    docker_names="$(docker ps --format '{{.Names}}' 2>/dev/null || true)"
    if ! printf '%s\n' "$docker_names" | grep -qx "$caddy_container"; then
        log "Caddy container '$caddy_container' is not running; skipping CA bootstrap"
        return 0
    fi

    tmp_cert="$(mktemp)"
    if ! docker cp "${caddy_container}:${caddy_cert_path}" "$tmp_cert" >/dev/null 2>&1; then
        rm -f "$tmp_cert"
        log "Unable to copy Caddy CA from ${caddy_container}:${caddy_cert_path}; continuing without bootstrap"
        return 0
    fi

    install -m 0644 "$tmp_cert" "$CADDY_CA_TARGET"
    rm -f "$tmp_cert"

    update-ca-certificates >/dev/null 2>&1 || true

    if command -v keytool >/dev/null 2>&1; then
        if ! keytool -list -cacerts -storepass changeit -alias "$CADDY_CA_ALIAS" >/dev/null 2>&1; then
            keytool -importcert -noprompt -trustcacerts \
                -alias "$CADDY_CA_ALIAS" \
                -file "$CADDY_CA_TARGET" \
                -cacerts \
                -storepass changeit >/dev/null 2>&1 || true
        fi
    fi
}

run_as_test_user() {
    gosu "$TEST_USER" env \
        HOME="$TEST_USER_HOME" \
        USER="$TEST_USER" \
        LOGNAME="$TEST_USER" \
        DOCKER_CONFIG="$TEST_USER_HOME/.docker" \
        "$@"
}

exec_as_test_user() {
    exec gosu "$TEST_USER" env \
        HOME="$TEST_USER_HOME" \
        USER="$TEST_USER" \
        LOGNAME="$TEST_USER" \
        DOCKER_CONFIG="$TEST_USER_HOME/.docker" \
        "$@"
}

clear_playwright_outputs() {
    rm -rf "$PLAYWRIGHT_DIR/playwright-report" "$PLAYWRIGHT_DIR/test-results"
    rm -rf "$RESULTS_DIR/screenshots"
}

sync_playwright_artifacts() {
    mkdir -p "$PLAYWRIGHT_RESULTS_DIR"

    if [ -d "$PLAYWRIGHT_DIR/playwright-report" ]; then
        copy_tree "$PLAYWRIGHT_DIR/playwright-report" "$PLAYWRIGHT_RESULTS_DIR/report"
    fi

    if [ -d "$PLAYWRIGHT_DIR/test-results" ]; then
        copy_tree "$PLAYWRIGHT_DIR/test-results" "$PLAYWRIGHT_RESULTS_DIR/test-results"
    fi

    if [ -d "$RESULTS_DIR/screenshots" ]; then
        copy_tree "$RESULTS_DIR/screenshots" "$PLAYWRIGHT_RESULTS_DIR/screenshots"
    fi

    chown -R "${TEST_USER}:${TEST_USER}" "$PLAYWRIGHT_RESULTS_DIR"
}

restore_playwright_artifacts() {
    if [ -d "$PLAYWRIGHT_RESULTS_DIR/report" ]; then
        copy_tree "$PLAYWRIGHT_RESULTS_DIR/report" "$PLAYWRIGHT_DIR/playwright-report"
        chown -R "${TEST_USER}:${TEST_USER}" "$PLAYWRIGHT_DIR/playwright-report"
    fi

    if [ -d "$PLAYWRIGHT_RESULTS_DIR/test-results" ]; then
        copy_tree "$PLAYWRIGHT_RESULTS_DIR/test-results" "$PLAYWRIGHT_DIR/test-results"
        chown -R "${TEST_USER}:${TEST_USER}" "$PLAYWRIGHT_DIR/test-results"
    fi

    if [ -d "$PLAYWRIGHT_RESULTS_DIR/screenshots" ]; then
        copy_tree "$PLAYWRIGHT_RESULTS_DIR/screenshots" "$RESULTS_DIR/screenshots"
        chown -R "${TEST_USER}:${TEST_USER}" "$RESULTS_DIR/screenshots"
    fi
}

run_playwright_npm() {
    local exit_code=0

    clear_playwright_outputs
    if run_as_test_user npm run --prefix "$PLAYWRIGHT_DIR" "$@"; then
        exit_code=0
    else
        exit_code=$?
    fi

    sync_playwright_artifacts
    return "$exit_code"
}

print_usage() {
    cat <<'USAGE'
Usage: /container-entrypoint.sh [COMMAND] [ARGS]

Commands:
  suite [name]         Run a Kotlin suite through test-runner.jar
  ts                   Run all TypeScript tests
  ts-unit              Run Jest unit tests
  ts-unit-one <path>   Run one Jest file
  ts-unit-name <pat>   Run one Jest test by name
  ts-e2e               Run Playwright E2E tests
  ts-e2e-one <path>    Run one Playwright file
  ts-e2e-name <pat>    Run one Playwright test by name
  ts-ui                Run Playwright UI mode
  ts-headed            Run Playwright headed mode
  ts-debug             Run Playwright debug mode
  ts-report            Serve the latest Playwright report
  shell [cmd...]       Open a shell or run a command as the test user
USAGE
}

prepare_results_dir
bootstrap_caddy_ca

case "${1:-suite}" in
    suite)
        suite_name="${2:-${TEST_SUITE_NAME:-all}}"
        log "Running suite '${suite_name}'"
        exec_as_test_user java -jar /app/test-runner.jar --env container --suite "$suite_name"
        ;;
    ts)
        log "Running all TypeScript tests"
        run_playwright_npm test
        ;;
    ts-unit)
        log "Running Jest unit tests"
        run_playwright_npm test:unit
        ;;
    ts-unit-one)
        if [ -z "${2:-}" ]; then
            echo "Usage: $0 ts-unit-one <path>" >&2
            exit 1
        fi
        log "Running Jest test file '${2}'"
        run_playwright_npm test:unit:one -- "$2"
        ;;
    ts-unit-name)
        if [ -z "${2:-}" ]; then
            echo "Usage: $0 ts-unit-name <pattern>" >&2
            exit 1
        fi
        log "Running Jest tests matching '${2}'"
        run_playwright_npm test:unit:name -- "$2"
        ;;
    ts-e2e)
        log "Running Playwright E2E tests"
        run_playwright_npm test:e2e
        ;;
    ts-e2e-one)
        if [ -z "${2:-}" ]; then
            echo "Usage: $0 ts-e2e-one <path>" >&2
            exit 1
        fi
        log "Running Playwright test file '${2}'"
        run_playwright_npm test:e2e:one -- "$2"
        ;;
    ts-e2e-name)
        if [ -z "${2:-}" ]; then
            echo "Usage: $0 ts-e2e-name <pattern>" >&2
            exit 1
        fi
        log "Running Playwright tests matching '${2}'"
        run_playwright_npm test:e2e:one -- --grep "$2"
        ;;
    ts-ui)
        log "Running Playwright UI mode"
        exec_as_test_user npm run --prefix "$PLAYWRIGHT_DIR" test:ui
        ;;
    ts-headed)
        log "Running Playwright headed mode"
        run_playwright_npm test:headed
        ;;
    ts-debug)
        log "Running Playwright debug mode"
        run_playwright_npm test:debug
        ;;
    ts-report)
        log "Serving the latest Playwright report"
        restore_playwright_artifacts
        exec_as_test_user npm run --prefix "$PLAYWRIGHT_DIR" test:report
        ;;
    shell)
        shift || true
        if [ "$#" -eq 0 ]; then
            exec_as_test_user bash
        fi
        exec_as_test_user "$@"
        ;;
    help|--help|-h)
        print_usage
        ;;
    *)
        print_usage >&2
        exit 1
        ;;
esac
