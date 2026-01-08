#!/bin/bash
# Datamancy Deployment Validation Script
# Checks deployment health and provides actionable diagnostics

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

DEPLOYMENT_DIR="${DEPLOYMENT_DIR:-/mnt/btrfs_raid_1_01_docker/datamancy}"

echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}   Datamancy Deployment Validation${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""

cd "$DEPLOYMENT_DIR" || exit 1

# Counter for issues
TOTAL_CHECKS=0
PASSED_CHECKS=0
FAILED_CHECKS=0
WARNINGS=0

function check_pass() {
    TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
    PASSED_CHECKS=$((PASSED_CHECKS + 1))
    echo -e "  ${GREEN}✓${NC} $1"
}

function check_fail() {
    TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
    FAILED_CHECKS=$((FAILED_CHECKS + 1))
    echo -e "  ${RED}✗${NC} $1"
    if [ -n "$2" ]; then
        echo -e "    ${YELLOW}→${NC} Fix: $2"
    fi
}

function check_warn() {
    WARNINGS=$((WARNINGS + 1))
    echo -e "  ${YELLOW}⚠${NC} $1"
    if [ -n "$2" ]; then
        echo -e "    ${YELLOW}→${NC} $2"
    fi
}

function section() {
    echo ""
    echo -e "${BLUE}▶ $1${NC}"
}

# ============================================================================
# Check 1: Environment File
# ============================================================================
section "Environment Configuration"

if [ ! -f ".env" ]; then
    check_fail ".env file missing" "Copy .env.example to .env and configure"
    exit 1
else
    check_pass ".env file exists"
fi

# Check critical env vars
CRITICAL_VARS=(
    "DOMAIN"
    "STACK_ADMIN_EMAIL"
    "LDAP_ADMIN_PASSWORD"
    "AUTHELIA_JWT_SECRET"
    "POSTGRES_PASSWORD"
    "LITELLM_MASTER_KEY"
)

source .env 2>/dev/null || true

for var in "${CRITICAL_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        check_fail "$var not set in .env" "Add $var to .env file"
    else
        check_pass "$var is configured"
    fi
done

# Check for NEW required secrets (Mastodon 4.5+)
MASTODON_SECRETS=(
    "MASTODON_ACTIVE_RECORD_ENCRYPTION_PRIMARY_KEY"
    "MASTODON_ACTIVE_RECORD_ENCRYPTION_DETERMINISTIC_KEY"
    "MASTODON_ACTIVE_RECORD_ENCRYPTION_KEY_DERIVATION_SALT"
)

for var in "${MASTODON_SECRETS[@]}"; do
    if [ -z "${!var}" ]; then
        check_fail "$var missing (required for Mastodon 4.5+)" "Regenerate .env with updated build script"
    else
        check_pass "$var present"
    fi
done

# ============================================================================
# Check 2: Docker Compose
# ============================================================================
section "Docker Compose Configuration"

if ! docker compose config > /dev/null 2>&1; then
    check_fail "docker-compose.yml is invalid" "Run: docker compose config to see errors"
else
    check_pass "docker-compose.yml is valid"
fi

# Check for unset variables
UNSET_VARS=$(docker compose config 2>&1 | grep -c "variable is not set" || true)
if [ "$UNSET_VARS" -gt 0 ]; then
    check_warn "$UNSET_VARS environment variables not set" "Check docker compose config output"
else
    check_pass "All compose variables are set"
fi

# ============================================================================
# Check 3: Container Status
# ============================================================================
section "Container Health"

TOTAL_CONTAINERS=$(docker ps -a --format '{{.Names}}' | wc -l)
RUNNING_CONTAINERS=$(docker ps --format '{{.Names}}' | wc -l)
HEALTHY_CONTAINERS=$(docker ps --format '{{.Names}}\t{{.Status}}' | grep -c "healthy" || true)
RESTARTING_CONTAINERS=$(docker ps -a --format '{{.Names}}\t{{.Status}}' | grep -c "Restarting" || true)
EXITED_CONTAINERS=$(docker ps -a --format '{{.Names}}\t{{.Status}}' | grep -c "Exited" || true)

echo -e "  ${CYAN}Total containers:${NC} $TOTAL_CONTAINERS"
echo -e "  ${GREEN}Running:${NC} $RUNNING_CONTAINERS"
echo -e "  ${GREEN}Healthy:${NC} $HEALTHY_CONTAINERS"

if [ "$RESTARTING_CONTAINERS" -gt 0 ]; then
    echo -e "  ${RED}Restarting:${NC} $RESTARTING_CONTAINERS"
    echo -e "  ${YELLOW}Containers in restart loop:${NC}"
    docker ps -a --format '{{.Names}}\t{{.Status}}' | grep "Restarting" | while IFS=$'\t' read name status; do
        echo -e "    ${RED}●${NC} $name - $status"
    done
fi

if [ "$EXITED_CONTAINERS" -gt 0 ]; then
    # Filter out vector-bootstrap which is supposed to exit
    UNEXPECTED_EXITED=$(docker ps -a --format '{{.Names}}\t{{.Status}}' | grep "Exited" | grep -v "vector-bootstrap" | wc -l || true)
    if [ "$UNEXPECTED_EXITED" -gt 0 ]; then
        check_warn "$UNEXPECTED_EXITED containers exited unexpectedly"
        docker ps -a --format '{{.Names}}\t{{.Status}}' | grep "Exited" | grep -v "vector-bootstrap" | while IFS=$'\t' read name status; do
            echo -e "    ${YELLOW}●${NC} $name - $status"
        done
    fi
fi

RUNNING_PERCENT=$((RUNNING_CONTAINERS * 100 / TOTAL_CONTAINERS))
if [ "$RUNNING_PERCENT" -ge 90 ]; then
    check_pass "${RUNNING_PERCENT}% of containers running"
elif [ "$RUNNING_PERCENT" -ge 75 ]; then
    check_warn "${RUNNING_PERCENT}% of containers running" "Investigate restarting containers"
else
    check_fail "Only ${RUNNING_PERCENT}% of containers running" "Critical system failure - check logs"
fi

# ============================================================================
# Check 4: Critical Services
# ============================================================================
section "Critical Service Health"

# Function to check if a service is healthy
check_service_health() {
    local service=$1
    local description=$2

    if ! docker ps --format '{{.Names}}' | grep -q "^${service}$"; then
        check_fail "$description ($service) not running" "docker logs $service"
        return 1
    fi

    local status=$(docker ps --format '{{.Names}}\t{{.Status}}' | grep "^${service}" | cut -f2)

    if echo "$status" | grep -q "healthy"; then
        check_pass "$description is healthy"
        return 0
    elif echo "$status" | grep -q "Up"; then
        check_pass "$description is running"
        return 0
    else
        check_fail "$description is $status" "docker logs $service"
        return 1
    fi
}

check_service_health "authelia" "Authelia (SSO/Authentication)"
check_service_health "caddy" "Caddy (Reverse Proxy)"
check_service_health "postgres" "PostgreSQL Database"
check_service_health "ldap" "LDAP Server"
check_service_health "valkey" "Valkey (Redis)"

# ============================================================================
# Check 5: Database Connectivity
# ============================================================================
section "Database Connectivity"

# Test postgres connectivity
if docker exec postgres psql -U admin -d postgres -c "SELECT 1;" > /dev/null 2>&1; then
    check_pass "PostgreSQL is accepting connections"

    # Check if databases exist
    EXPECTED_DBS=("authelia" "grafana" "mastodon" "planka" "synapse")
    for db in "${EXPECTED_DBS[@]}"; do
        if docker exec postgres psql -U admin -lqt | cut -d \| -f 1 | grep -qw "$db"; then
            check_pass "Database '$db' exists"
        else
            check_fail "Database '$db' missing" "Re-run postgres init script"
        fi
    done
else
    check_fail "Cannot connect to PostgreSQL" "Check postgres logs: docker logs postgres"
fi

# ============================================================================
# Check 6: Network Connectivity
# ============================================================================
section "Internal Network Connectivity"

# Test if Authelia is reachable from Caddy
if docker exec caddy wget -q -O- http://authelia:9091/api/health 2>/dev/null | grep -q "UP"; then
    check_pass "Caddy can reach Authelia"
else
    check_fail "Caddy cannot reach Authelia" "Check authelia logs and network configuration"
fi

# Test if services can reach postgres
if docker exec authelia nc -z postgres 5432 2>/dev/null; then
    check_pass "Services can reach PostgreSQL"
else
    check_warn "Network connectivity issues detected" "Check docker networks"
fi

# ============================================================================
# Check 7: TLS Certificates
# ============================================================================
section "TLS Certificate Status"

CERT_COUNT=$(docker exec caddy find /data/caddy/certificates -name "*.crt" 2>/dev/null | wc -l || echo "0")
if [ "$CERT_COUNT" -gt 0 ]; then
    check_pass "$CERT_COUNT TLS certificates provisioned"
else
    check_warn "No TLS certificates found" "Certificates may still be provisioning (check in 5-10 minutes)"
fi

# ============================================================================
# Check 8: Volume Mounts
# ============================================================================
section "Volume Mounts"

VOLUMES_ROOT="${VOLUMES_ROOT:-/mnt/btrfs_raid_1_01_docker/volumes}"

if [ -d "$VOLUMES_ROOT" ]; then
    check_pass "Volumes root exists: $VOLUMES_ROOT"

    # Check critical volume directories
    CRITICAL_VOLUMES=("postgres/data" "caddy/data" "ldap/data")
    for vol in "${CRITICAL_VOLUMES[@]}"; do
        if [ -d "$VOLUMES_ROOT/$vol" ]; then
            check_pass "Volume $vol exists"
        else
            check_warn "Volume $vol missing" "May be created on first container start"
        fi
    done
else
    check_fail "Volumes root missing: $VOLUMES_ROOT" "Create directory: mkdir -p $VOLUMES_ROOT"
fi

# ============================================================================
# Check 9: Configuration Templates
# ============================================================================
section "Configuration Processing"

# Check for unprocessed templates ({{VAR}} that should be ${VAR})
UNPROCESSED=$(grep -r "{{[A-Z_]*}}" configs/ 2>/dev/null | wc -l || echo "0")
if [ "$UNPROCESSED" -eq 0 ]; then
    check_pass "All configuration templates processed"
else
    check_warn "$UNPROCESSED unprocessed template variables found" "Re-run build script"
fi

# ============================================================================
# Summary
# ============================================================================
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}   Validation Summary${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""

SUCCESS_RATE=$((PASSED_CHECKS * 100 / TOTAL_CHECKS))

echo -e "  Total Checks:    $TOTAL_CHECKS"
echo -e "  ${GREEN}✓ Passed:${NC}        $PASSED_CHECKS"
echo -e "  ${RED}✗ Failed:${NC}        $FAILED_CHECKS"
echo -e "  ${YELLOW}⚠ Warnings:${NC}      $WARNINGS"
echo ""
echo -e "  ${CYAN}Success Rate:${NC}    ${SUCCESS_RATE}%"
echo ""

if [ "$FAILED_CHECKS" -eq 0 ]; then
    echo -e "${GREEN}🎉 Deployment validation passed!${NC}"
    echo ""
    echo -e "Status: ${GREEN}HEALTHY${NC}"
    exit 0
elif [ "$SUCCESS_RATE" -ge 75 ]; then
    echo -e "${YELLOW}⚠️  Deployment is functional but has issues${NC}"
    echo ""
    echo -e "Status: ${YELLOW}DEGRADED${NC}"
    echo -e "Action: Review failures above and fix critical issues"
    exit 1
else
    echo -e "${RED}❌ Deployment validation failed!${NC}"
    echo ""
    echo -e "Status: ${RED}CRITICAL${NC}"
    echo -e "Action: Fix critical failures before proceeding"
    exit 2
fi
