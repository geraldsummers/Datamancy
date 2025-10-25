#!/bin/bash
# Provenance: Freshness Rule - Agent pre-flight check
# Purpose: CLI tool for agent to check freshness status before taking actions
# Usage: ./agent-preflight.sh [--service SERVICE] [--warn-only] [--quiet]

set -e

REPO_ROOT="${REPO_ROOT:-/home/gerald/Documents/IdeaProjects/Datamancy}"
STATUS_FILE="$REPO_ROOT/data/freshness-status.json"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
SERVICE=""
WARN_ONLY=false
QUIET=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --service)
            SERVICE="$2"
            shift 2
            ;;
        --warn-only)
            WARN_ONLY=true
            shift
            ;;
        --quiet)
            QUIET=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--service SERVICE] [--warn-only] [--quiet]"
            exit 1
            ;;
    esac
done

# Check if status file exists
if [ ! -f "$STATUS_FILE" ]; then
    echo -e "${RED}✗ Freshness status file not found: $STATUS_FILE${NC}"
    echo "  Run: ./scripts/update-freshness-status.sh"
    exit 1
fi

# Check if jq is available
if ! command -v jq &> /dev/null; then
    echo -e "${RED}✗ jq is required but not installed${NC}"
    exit 1
fi

# Function to format duration
format_duration() {
    local seconds=$1
    if [ "$seconds" = "null" ] || [ -z "$seconds" ]; then
        echo "unknown"
    elif [ "$seconds" -lt 60 ]; then
        echo "${seconds}s"
    elif [ "$seconds" -lt 3600 ]; then
        echo "$((seconds / 60))m"
    elif [ "$seconds" -lt 86400 ]; then
        echo "$((seconds / 3600))h"
    else
        echo "$((seconds / 86400))d"
    fi
}

# Function to print service status
print_service_status() {
    local svc=$1
    local status=$(jq -r ".services[\"$svc\"].status // \"unknown\"" "$STATUS_FILE")
    local last_test=$(jq -r ".services[\"$svc\"].last_test // null" "$STATUS_FILE")
    local staleness=$(jq -r ".services[\"$svc\"].staleness_seconds // null" "$STATUS_FILE")
    local change_source=$(jq -r ".services[\"$svc\"].change_source // \"unknown\"" "$STATUS_FILE")

    local staleness_str=$(format_duration "$staleness")

    case $status in
        functional)
            echo -e "${GREEN}✓${NC} $svc: ${GREEN}Functional${NC}"
            [ "$QUIET" = false ] && echo "    Last test passed $(date -d @"$last_test" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo 'recently')"
            ;;
        needs_retest)
            echo -e "${YELLOW}⚠${NC} $svc: ${YELLOW}Needs Re-test${NC} (stale by $staleness_str)"
            [ "$QUIET" = false ] && echo "    Container changed at commit $change_source"
            return 1
            ;;
        untested)
            echo -e "${YELLOW}⚠${NC} $svc: ${YELLOW}Untested${NC} (no test results)"
            [ "$QUIET" = false ] && echo "    Run tests first: docker compose run test-runner"
            return 1
            ;;
        test_failed)
            echo -e "${RED}✗${NC} $svc: ${RED}Test Failed${NC}"
            [ "$QUIET" = false ] && echo "    Last test failed - needs investigation"
            return 1
            ;;
        unknown)
            echo -e "${BLUE}?${NC} $svc: ${BLUE}Unknown${NC}"
            return 1
            ;;
        *)
            echo -e "${BLUE}?${NC} $svc: $status"
            return 1
            ;;
    esac
    return 0
}

# Main logic
EXIT_CODE=0

if [ -n "$SERVICE" ]; then
    # Check single service
    if ! print_service_status "$SERVICE"; then
        EXIT_CODE=1
    fi
else
    # Check all services
    [ "$QUIET" = false ] && echo -e "${BLUE}==> Freshness Rule Status Check${NC}"
    [ "$QUIET" = false ] && echo ""

    SERVICES=$(jq -r '.services | keys[]' "$STATUS_FILE")

    for svc in $SERVICES; do
        if ! print_service_status "$svc"; then
            EXIT_CODE=1
        fi
    done

    [ "$QUIET" = false ] && echo ""
    [ "$QUIET" = false ] && echo -e "${BLUE}Rule:${NC} Services are 'Functional' only when last passing test > last change"
fi

# Exit with appropriate code
if [ "$WARN_ONLY" = true ]; then
    exit 0
else
    exit $EXIT_CODE
fi
