#!/bin/bash

#
# Credential Rotation Cron Wrapper
# Handles logging, notifications, and error recovery
# Can be used with cron or called manually
#

set -euo pipefail

# Configuration
PROJECT_ROOT="/home/gerald/IdeaProjects/Datamancy"
LOG_DIR="/home/gerald/logs"
LOG_FILE="$LOG_DIR/credential-rotation.log"
ERROR_LOG="$LOG_DIR/credential-rotation-errors.log"
NTFY_URL="http://localhost:5555/datamancy-security"
KOTLIN_BIN="/usr/bin/kotlin"

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Ensure log directory exists
mkdir -p "$LOG_DIR"

# Function to send ntfy notification
send_notification() {
    local title="$1"
    local message="$2"
    local priority="${3:-default}"

    if command -v curl &> /dev/null; then
        curl -H "Title: $title" \
             -H "Priority: $priority" \
             -d "$message" \
             "$NTFY_URL" 2>/dev/null || true
    fi
}

# Function to log with timestamp
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"
}

# Function to log error
log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $*" | tee -a "$LOG_FILE" >> "$ERROR_LOG"
}

# Trap errors
trap 'log_error "Script failed at line $LINENO"' ERR

# Main execution
main() {
    log "=========================================="
    log "Credential rotation started"
    log "=========================================="

    # Check prerequisites
    if [ ! -d "$PROJECT_ROOT" ]; then
        log_error "Project directory not found: $PROJECT_ROOT"
        send_notification "Credential Rotation FAILED" \
                         "Project directory not found" \
                         "urgent"
        exit 1
    fi

    if [ ! -x "$KOTLIN_BIN" ]; then
        log_error "Kotlin not found or not executable: $KOTLIN_BIN"
        send_notification "Credential Rotation FAILED" \
                         "Kotlin not found" \
                         "urgent"
        exit 1
    fi

    # Check Docker is running
    if ! docker ps &> /dev/null; then
        log_error "Docker is not running or not accessible"
        send_notification "Credential Rotation FAILED" \
                         "Docker is not running" \
                         "urgent"
        exit 1
    fi

    # Change to project directory
    cd "$PROJECT_ROOT"

    # Run pre-flight health check
    log "Running pre-flight health checks..."
    if ! "$KOTLIN_BIN" scripts/security/lib/health-check.main.kts --execute --no-http >> "$LOG_FILE" 2>&1; then
        log "Warning: Some health checks failed, but continuing..."
    fi

    # Run rotation
    log "Starting credential rotation..."
    local start_time=$(date +%s)

    if "$KOTLIN_BIN" scripts/security/weekly-rotation.main.kts --execute >> "$LOG_FILE" 2>&1; then
        local end_time=$(date +%s)
        local duration=$((end_time - start_time))

        log "✅ Rotation completed successfully in ${duration}s"

        # Run post-flight health check
        log "Running post-flight health checks..."
        "$KOTLIN_BIN" scripts/security/lib/health-check.main.kts --execute >> "$LOG_FILE" 2>&1 || true

        # Send success notification
        send_notification "Credential Rotation Success" \
                         "Weekly credential rotation completed successfully in ${duration}s" \
                         "low"

        # Cleanup old logs (keep last 30 days)
        find "$LOG_DIR" -name "credential-rotation*.log" -mtime +30 -delete 2>/dev/null || true

        log "=========================================="
        exit 0
    else
        local end_time=$(date +%s)
        local duration=$((end_time - start_time))

        log_error "Rotation failed after ${duration}s"
        log_error "Check logs for details: $LOG_FILE"

        # Send failure notification
        send_notification "Credential Rotation FAILED" \
                         "Weekly credential rotation failed after ${duration}s. Check logs: $LOG_FILE" \
                         "urgent"

        # Dump last 50 lines of log to error log
        tail -50 "$LOG_FILE" >> "$ERROR_LOG"

        log "=========================================="
        exit 1
    fi
}

# Handle script arguments
case "${1:-}" in
    --dry-run)
        log "DRY RUN MODE - No actual changes will be made"
        cd "$PROJECT_ROOT"
        exec "$KOTLIN_BIN" scripts/security/weekly-rotation.main.kts --execute --dry-run
        ;;
    --help|-h)
        echo "Credential Rotation Cron Wrapper"
        echo ""
        echo "Usage: $0 [OPTIONS]"
        echo ""
        echo "Options:"
        echo "  (no args)   Run weekly credential rotation"
        echo "  --dry-run   Run in dry-run mode (no changes)"
        echo "  --help      Show this help message"
        echo ""
        echo "Logs:"
        echo "  Main log:  $LOG_FILE"
        echo "  Error log: $ERROR_LOG"
        exit 0
        ;;
    "")
        main
        ;;
    *)
        echo "Unknown option: $1"
        echo "Run with --help for usage"
        exit 1
        ;;
esac
