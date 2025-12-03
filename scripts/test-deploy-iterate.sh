#!/usr/bin/env bash
set -euo pipefail

###############################################################################
# Test-Deploy-Iterate Script
#
# Iteratively:
# 1. Generate configuration
# 2. Bring up stack
# 3. Collect logs
# 4. Identify issues
# 5. Clean volumes/configs
# 6. Repeat with fixes
###############################################################################

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ITERATION=0
MAX_ITERATIONS="${MAX_ITERATIONS:-5}"
PROFILE="${PROFILE:-bootstrap}"
LOGS_DIR="${PROJECT_ROOT}/logs"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*"
}

log_section() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$*${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

create_logs_dir() {
    mkdir -p "${LOGS_DIR}/iteration_${ITERATION}_${TIMESTAMP}"
    CURRENT_LOG_DIR="${LOGS_DIR}/iteration_${ITERATION}_${TIMESTAMP}"
    log_info "Logs directory: ${CURRENT_LOG_DIR}"
}

cleanup_stack() {
    log_section "ITERATION ${ITERATION}: Cleaning Stack"

    log_info "Stopping all containers..."
    cd "${PROJECT_ROOT}"
    ./stack-controller.main.kts down || true

    log_info "Removing volumes..."
    docker volume prune -f || true

    log_info "Removing .env file..."
    rm -f "${PROJECT_ROOT}/.env"

    log_info "Cleaning runtime configs..."
    rm -rf "${HOME}/.config/datamancy/configs"
    rm -rf "${HOME}/.config/datamancy/.secrets"

    log_info "Cleaning volume directories..."
    if [ -d "${VOLUMES_ROOT:-${PROJECT_ROOT}/volumes}" ]; then
        sudo rm -rf "${VOLUMES_ROOT:-${PROJECT_ROOT}/volumes}"/*
    fi
}

generate_configuration() {
    log_section "ITERATION ${ITERATION}: Generating Configuration"

    cd "${PROJECT_ROOT}"

    log_info "Step 1: Generate .env..."
    ./stack-controller.main.kts config generate

    log_info "Step 2: Generate LDAP bootstrap..."
    ./stack-controller.main.kts ldap bootstrap --force

    log_info "Step 3: Process config templates..."
    ./stack-controller.main.kts config process

    log_info "Step 4: Create volume directories..."
    ./stack-controller.main.kts volumes create

    log_info "Configuration generation complete"
}

deploy_stack() {
    log_section "ITERATION ${ITERATION}: Deploying Stack"

    cd "${PROJECT_ROOT}"

    log_info "Starting stack with profile: ${PROFILE}"
    ./stack-controller.main.kts up --profile="${PROFILE}" 2>&1 | tee "${CURRENT_LOG_DIR}/deploy.log"

    log_info "Waiting 30 seconds for services to initialize..."
    sleep 30
}

collect_logs() {
    log_section "ITERATION ${ITERATION}: Collecting Logs"

    cd "${PROJECT_ROOT}"

    log_info "Getting container status..."
    docker compose ps > "${CURRENT_LOG_DIR}/containers_status.txt" 2>&1 || true

    log_info "Collecting logs from all services..."
    docker compose logs --no-color > "${CURRENT_LOG_DIR}/all_services.log" 2>&1 || true

    # Collect individual service logs for key services
    local key_services=(
        "caddy"
        "ldap"
        "authelia"
        "postgres"
        "redis"
        "open-webui"
        "litellm"
        "vllm"
        "vllm-router"
        "embedding-service"
        "docker-proxy"
        "portainer"
        "agent-tool-server"
        "playwright"
    )

    for service in "${key_services[@]}"; do
        if docker compose ps | grep -q "${service}"; then
            log_info "Collecting logs for ${service}..."
            docker compose logs --no-color "${service}" > "${CURRENT_LOG_DIR}/${service}.log" 2>&1 || true
        fi
    done

    log_info "Checking health status..."
    ./stack-controller.main.kts health > "${CURRENT_LOG_DIR}/health_check.txt" 2>&1 || {
        log_warn "Health check failed (expected on first run)"
    }
}

analyze_logs() {
    log_section "ITERATION ${ITERATION}: Analyzing Logs"

    local issues_file="${CURRENT_LOG_DIR}/identified_issues.txt"
    : > "${issues_file}"  # Clear file

    log_info "Scanning for common issues..."

    # Check for container failures
    if grep -qi "exited\|error\|failed" "${CURRENT_LOG_DIR}/containers_status.txt" 2>/dev/null; then
        echo "ISSUE: Container failures detected" >> "${issues_file}"
        grep -i "exited\|error\|failed" "${CURRENT_LOG_DIR}/containers_status.txt" >> "${issues_file}" 2>&1 || true
    fi

    # Check for database connection errors
    if grep -qi "connection refused\|could not connect" "${CURRENT_LOG_DIR}/all_services.log" 2>/dev/null; then
        echo "ISSUE: Database connection errors" >> "${issues_file}"
        grep -i "connection refused\|could not connect" "${CURRENT_LOG_DIR}/all_services.log" | head -20 >> "${issues_file}" 2>&1 || true
    fi

    # Check for authentication errors
    if grep -qi "authentication failed\|unauthorized\|403\|401" "${CURRENT_LOG_DIR}/all_services.log" 2>/dev/null; then
        echo "ISSUE: Authentication errors" >> "${issues_file}"
        grep -i "authentication failed\|unauthorized" "${CURRENT_LOG_DIR}/all_services.log" | head -20 >> "${issues_file}" 2>&1 || true
    fi

    # Check for missing files/configs
    if grep -qi "no such file\|not found\|does not exist" "${CURRENT_LOG_DIR}/all_services.log" 2>/dev/null; then
        echo "ISSUE: Missing files or configurations" >> "${issues_file}"
        grep -i "no such file\|not found\|does not exist" "${CURRENT_LOG_DIR}/all_services.log" | head -20 >> "${issues_file}" 2>&1 || true
    fi

    # Check for permission errors
    if grep -qi "permission denied\|access denied\|forbidden" "${CURRENT_LOG_DIR}/all_services.log" 2>/dev/null; then
        echo "ISSUE: Permission errors" >> "${issues_file}"
        grep -i "permission denied\|access denied" "${CURRENT_LOG_DIR}/all_services.log" | head -20 >> "${issues_file}" 2>&1 || true
    fi

    # Check for port conflicts
    if grep -qi "address already in use\|port.*already allocated" "${CURRENT_LOG_DIR}/all_services.log" 2>/dev/null; then
        echo "ISSUE: Port conflicts" >> "${issues_file}"
        grep -i "address already in use\|port.*already allocated" "${CURRENT_LOG_DIR}/all_services.log" >> "${issues_file}" 2>&1 || true
    fi

    # Check for volume mount issues
    if grep -qi "no such file or directory.*mount" "${CURRENT_LOG_DIR}/all_services.log" 2>/dev/null; then
        echo "ISSUE: Volume mount failures" >> "${issues_file}"
        grep -i "no such file or directory.*mount" "${CURRENT_LOG_DIR}/all_services.log" >> "${issues_file}" 2>&1 || true
    fi

    # Display results
    if [ -s "${issues_file}" ]; then
        log_error "Issues found:"
        cat "${issues_file}"
        return 1
    else
        log_info "No critical issues detected!"
        return 0
    fi
}

interactive_review() {
    log_section "ITERATION ${ITERATION}: Review"

    echo ""
    log_info "Log files saved to: ${CURRENT_LOG_DIR}"
    echo ""

    if [ -f "${CURRENT_LOG_DIR}/identified_issues.txt" ]; then
        echo "Identified issues:"
        cat "${CURRENT_LOG_DIR}/identified_issues.txt"
        echo ""
    fi

    read -p "Do you want to continue to next iteration? (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log_info "Stopping iterations. Review logs in: ${LOGS_DIR}"
        exit 0
    fi
}

main() {
    log_section "Starting Test-Deploy-Iterate Process"

    log_info "Project root: ${PROJECT_ROOT}"
    log_info "Profile: ${PROFILE}"
    log_info "Max iterations: ${MAX_ITERATIONS}"

    # Create main logs directory
    mkdir -p "${LOGS_DIR}"

    while [ ${ITERATION} -lt ${MAX_ITERATIONS} ]; do
        ITERATION=$((ITERATION + 1))

        log_section "ITERATION ${ITERATION}/${MAX_ITERATIONS}"

        # Create iteration-specific log directory
        create_logs_dir

        # Full cleanup
        cleanup_stack

        # Generate fresh configuration
        generate_configuration

        # Deploy stack
        deploy_stack

        # Wait for stabilization
        log_info "Waiting 60 seconds for full stack stabilization..."
        sleep 60

        # Collect logs
        collect_logs

        # Analyze for issues
        if analyze_logs; then
            log_section "SUCCESS: Stack deployed successfully!"
            log_info "All services healthy on iteration ${ITERATION}"
            exit 0
        fi

        # Interactive review before next iteration
        if [ ${ITERATION} -lt ${MAX_ITERATIONS} ]; then
            interactive_review
        fi
    done

    log_warn "Reached maximum iterations (${MAX_ITERATIONS})"
    log_info "Review logs in: ${LOGS_DIR}"
}

# Run main function
main "$@"
