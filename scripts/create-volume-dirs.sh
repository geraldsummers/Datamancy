#!/usr/bin/env bash
set -euo pipefail

###############################################
# create-volume-dirs.sh
#
# Procedurally creates all data volume directories
# defined in docker-compose.yml if they don't exist.
#
# Usage:
#   ./scripts/create-volume-dirs.sh [VOLUMES_ROOT]
#
# If VOLUMES_ROOT is not provided, it will be read
# from .env file or default to ./volumes
###############################################

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get the script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Change to project root
cd "${PROJECT_ROOT}"

# Determine VOLUMES_ROOT
if [ $# -ge 1 ]; then
    VOLUMES_ROOT="$1"
elif [ -f .env ]; then
    # Try to extract VOLUMES_ROOT from .env file
    VOLUMES_ROOT=$(grep -E "^VOLUMES_ROOT=" .env | cut -d '=' -f2- | tr -d '"' | tr -d "'")
    if [ -z "${VOLUMES_ROOT}" ]; then
        echo -e "${YELLOW}Warning: VOLUMES_ROOT not found in .env, using default${NC}"
        VOLUMES_ROOT="./volumes"
    fi
else
    echo -e "${YELLOW}Warning: .env file not found, using default VOLUMES_ROOT${NC}"
    VOLUMES_ROOT="./volumes"
fi

# Make VOLUMES_ROOT absolute if it's relative
if [[ ! "${VOLUMES_ROOT}" = /* ]]; then
    VOLUMES_ROOT="${PROJECT_ROOT}/${VOLUMES_ROOT}"
fi

echo -e "${GREEN}=== Creating Volume Directories ===${NC}"
echo -e "VOLUMES_ROOT: ${VOLUMES_ROOT}"
echo ""

# Extract volume directories from docker-compose.yml
# Look for lines like: device: ${VOLUMES_ROOT}/some_directory
VOLUME_DIRS=$(grep -E "^\s+device:\s+\\\$\{VOLUMES_ROOT\}" docker-compose.yml | \
    sed -E 's/.*\$\{VOLUMES_ROOT\}\/(.*)/\1/' | \
    sort -u)

if [ -z "${VOLUME_DIRS}" ]; then
    echo -e "${RED}Error: No volume directories found in docker-compose.yml${NC}"
    exit 1
fi

# Count total directories
TOTAL=$(echo "${VOLUME_DIRS}" | wc -l | tr -d ' ')
CREATED=0
EXISTED=0
FAILED=0

echo -e "Found ${TOTAL} volume directories to check/create"
echo ""

# Create each directory
while IFS= read -r dir; do
    FULL_PATH="${VOLUMES_ROOT}/${dir}"

    if [ -d "${FULL_PATH}" ]; then
        echo -e "${GREEN}✓${NC} Already exists: ${dir}"
        EXISTED=$((EXISTED + 1))
    else
        if mkdir -p "${FULL_PATH}" 2>/dev/null; then
            echo -e "${GREEN}✓${NC} Created: ${dir}"
            CREATED=$((CREATED + 1))
        else
            echo -e "${RED}✗${NC} Failed to create: ${dir}"
            FAILED=$((FAILED + 1))
        fi
    fi
done <<< "${VOLUME_DIRS}"

echo ""
echo -e "${GREEN}=== Summary ===${NC}"
echo -e "Total directories: ${TOTAL}"
echo -e "${GREEN}Created: ${CREATED}${NC}"
echo -e "${YELLOW}Already existed: ${EXISTED}${NC}"
if [ ${FAILED} -gt 0 ]; then
    echo -e "${RED}Failed: ${FAILED}${NC}"
    echo ""
    echo -e "${RED}Some directories could not be created. Please check permissions.${NC}"
    exit 1
else
    echo -e "${GREEN}All volume directories are ready!${NC}"
fi

# Check if additional directories are needed for configs
echo ""
echo -e "${GREEN}=== Checking Config Directories ===${NC}"

CONFIG_DIRS=(
    "secrets"
    "authelia"
)

for dir in "${CONFIG_DIRS[@]}"; do
    FULL_PATH="${VOLUMES_ROOT}/${dir}"
    if [ -d "${FULL_PATH}" ]; then
        echo -e "${GREEN}✓${NC} Already exists: ${dir}"
    else
        if mkdir -p "${FULL_PATH}" 2>/dev/null; then
            echo -e "${GREEN}✓${NC} Created: ${dir}"
        else
            echo -e "${YELLOW}⚠${NC} Could not create: ${dir} (may not be needed)"
        fi
    fi
done

echo ""
echo -e "${GREEN}Done!${NC}"
