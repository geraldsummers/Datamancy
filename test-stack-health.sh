#!/usr/bin/env bash
#
# Datamancy Stack Health Check Script
# Verifies that all services in the stack are healthy
#

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PASSED=0
FAILED=0
WARNINGS=0

echo -e "${BLUE}=== Datamancy Stack Health Check ===${NC}\n"

# Helper function to check HTTP endpoint
check_http() {
    local service=$1
    local url=$2
    local expected_code=${3:-200}

    echo -n "Checking $service... "

    if response=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "$url" 2>/dev/null); then
        if [ "$response" = "$expected_code" ]; then
            echo -e "${GREEN}✓ OK${NC} (HTTP $response)"
            ((PASSED++))
            return 0
        else
            echo -e "${RED}✗ FAILED${NC} (HTTP $response, expected $expected_code)"
            ((FAILED++))
            return 1
        fi
    else
        echo -e "${RED}✗ FAILED${NC} (Connection refused or timeout)"
        ((FAILED++))
        return 1
    fi
}

# Helper function to check docker container status
check_container() {
    local container=$1

    echo -n "Checking container $container... "

    if docker ps --format '{{.Names}}' | grep -q "^${container}$"; then
        local health=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "none")
        local status=$(docker inspect --format='{{.State.Status}}' "$container" 2>/dev/null)

        if [ "$status" = "running" ]; then
            if [ "$health" = "healthy" ]; then
                echo -e "${GREEN}✓ OK${NC} (running, healthy)"
                ((PASSED++))
            elif [ "$health" = "none" ]; then
                echo -e "${YELLOW}⚠ WARNING${NC} (running, no healthcheck)"
                ((WARNINGS++))
            else
                echo -e "${RED}✗ FAILED${NC} (running, health: $health)"
                ((FAILED++))
            fi
        else
            echo -e "${RED}✗ FAILED${NC} (status: $status)"
            ((FAILED++))
        fi
    else
        echo -e "${RED}✗ FAILED${NC} (not running)"
        ((FAILED++))
    fi
}

echo -e "${BLUE}--- Core Infrastructure ---${NC}"
check_container "caddy"
check_container "postgres"
check_container "mariadb"
check_container "valkey"
check_container "ldap"
check_container "authelia"

echo -e "\n${BLUE}--- Vector Databases ---${NC}"
check_container "qdrant"
check_container "clickhouse"

echo -e "\n${BLUE}--- Data Services ---${NC}"
check_container "data-fetcher"
check_container "unified-indexer"
check_container "search-service"
check_container "embedding-service"

echo -e "\n${BLUE}--- AI/ML Services ---${NC}"
check_container "vllm"
check_container "litellm"

echo -e "\n${BLUE}--- Agent Services ---${NC}"
check_container "agent-tool-server"

echo -e "\n${BLUE}--- Application Services ---${NC}"
check_container "grafana"
check_container "open-webui"
check_container "vaultwarden"
check_container "bookstack"
check_container "jupyterhub"

echo -e "\n${BLUE}--- HTTP Health Endpoints ---${NC}"
check_http "data-fetcher" "http://localhost:8095/health" 200
check_http "unified-indexer" "http://localhost:8096/health" 200
check_http "search-service" "http://localhost:8097/health" 200
check_http "agent-tool-server" "http://localhost:8081/healthz" 200

echo -e "\n${BLUE}=== Summary ===${NC}"
echo -e "${GREEN}Passed:${NC} $PASSED"
echo -e "${YELLOW}Warnings:${NC} $WARNINGS"
echo -e "${RED}Failed:${NC} $FAILED"

if [ $FAILED -gt 0 ]; then
    echo -e "\n${RED}Health check FAILED${NC}"
    exit 1
elif [ $WARNINGS -gt 0 ]; then
    echo -e "\n${YELLOW}Health check PASSED with warnings${NC}"
    exit 0
else
    echo -e "\n${GREEN}Health check PASSED${NC}"
    exit 0
fi
