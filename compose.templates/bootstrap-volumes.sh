#!/bin/bash
# Datamancy Volume Bootstrap Script
# Creates all required volume directories for the stack
# Run this BEFORE docker compose up -d

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() {
    echo -e "${GREEN}[INFO]${NC} $*"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

error() {
    echo -e "${RED}[ERROR]${NC} $*"
    exit 1
}

# Determine deployment root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="${SCRIPT_DIR}"

# Check if docker-compose.yml exists
if [[ ! -f "${DEPLOY_ROOT}/docker-compose.yml" ]]; then
    error "docker-compose.yml not found in ${DEPLOY_ROOT}"
fi

info "Deployment root: ${DEPLOY_ROOT}"

# Change to deployment directory so relative paths work
cd "${DEPLOY_ROOT}"

# Load .env if it exists for VECTOR_DB_ROOT
VECTOR_DB_ROOT="/mnt/sdc1_ctbx500_0385/datamancy/vector-dbs"
if [[ -f "${DEPLOY_ROOT}/.env" ]]; then
    # shellcheck disable=SC1091
    source "${DEPLOY_ROOT}/.env" || warn "Could not source .env file"
fi

info "Creating volume directories..."

# Application volumes (on RAID1 array)
APPLICATION_DIRS=(
    "bookstack_data"
    "caddy_config"
    "caddy_data"
    "element_data"
    "forgejo_data"
    "grafana_data"
    "homeassistant_config"
    "jupyterhub_data"
    "kopia_cache"
    "kopia_data"
    "kopia_repository"
    "ldap_config"
    "ldap_data"
    "litellm_config"
    "mailserver_config"
    "mailserver_data"
    "mailserver_logs"
    "mailserver_state"
    "onlyoffice_data"
    "onlyoffice_log"
    "open_webui_data"
    "planka_data"
    "prometheus_data"
    "qbittorrent_config"
    "qbittorrent_data"
    "radicale_data"
    "roundcube_data"
    "seafile_data"
    "synapse_data"
    "vaultwarden_data"
)

# Database volumes (on RAID1 array)
DATABASE_DIRS=(
    "clickhouse_data"
    "mariadb_data"
    "postgres_data"
    "redis_data"
)

# Vector database volumes (on SSD)
VECTOR_DIRS=(
    "qdrant_data"
)

# Create application directories
info "Creating application volumes..."
for dir in "${APPLICATION_DIRS[@]}"; do
    mkdir -p "./volumes/applications/${dir}"
done

# Create database directories
info "Creating database volumes..."
for dir in "${DATABASE_DIRS[@]}"; do
    mkdir -p "./volumes/databases/${dir}"
done

# Create vector DB directories on SSD
info "Creating vector database volumes on SSD..."
for dir in "${VECTOR_DIRS[@]}"; do
    mkdir -p "${VECTOR_DB_ROOT}/${dir}"
done

# Set ownership if running as root (for system deployments)
if [[ $EUID -eq 0 ]]; then
    if [[ -n "${DOCKER_USER_ID:-}" ]] && [[ -n "${DOCKER_GROUP_ID:-}" ]]; then
        info "Setting ownership to ${DOCKER_USER_ID}:${DOCKER_GROUP_ID}..."
        chown -R "${DOCKER_USER_ID}:${DOCKER_GROUP_ID}" ./volumes/ || warn "Could not set ownership on ./volumes/"
        chown -R "${DOCKER_USER_ID}:${DOCKER_GROUP_ID}" "${VECTOR_DB_ROOT}" || warn "Could not set ownership on ${VECTOR_DB_ROOT}"
    fi
fi

# Count created directories
TOTAL_DIRS=$((${#APPLICATION_DIRS[@]} + ${#DATABASE_DIRS[@]} + ${#VECTOR_DIRS[@]}))

echo ""
info "✓ Created ${TOTAL_DIRS} volume directories successfully"
echo ""
echo "  Applications: ${DEPLOY_ROOT}/volumes/applications (${#APPLICATION_DIRS[@]} dirs)"
echo "  Databases:    ${DEPLOY_ROOT}/volumes/databases (${#DATABASE_DIRS[@]} dirs)"
echo "  Vector DBs:   ${VECTOR_DB_ROOT} (${#VECTOR_DIRS[@]} dirs)"
echo ""
info "Ready to deploy! Run: docker compose up -d"
