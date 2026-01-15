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

# Load .env if it exists for custom paths
VECTOR_DB_ROOT="/mnt/sdc1_ctbx500_0385/datamancy/vector-dbs"
QBITTORRENT_DATA_ROOT="./volumes/data/qbittorrent"
SEAFILE_MEDIA_ROOT="./volumes/data/seafile-media"
SEAFILE_FILES_ROOT="./volumes/data/seafile"

if [[ -f "${DEPLOY_ROOT}/.env" ]]; then
    # shellcheck disable=SC1091
    source "${DEPLOY_ROOT}/.env" || warn "Could not source .env file"
fi

info "Creating volume directories..."

# Three-tier structure: data/, config/, credentials/
# Each service gets subdirectories only where needed

# Data directories (persistent application data, databases, user content)
# Note: qbittorrent and seafile have custom paths defined in .env
DATA_DIRS=(
    "bookstack"
    "clickhouse"
    "data-fetcher"
    "element"
    "forgejo"
    "grafana"
    "homeassistant"
    "jupyterhub"
    "kopia/cache"
    "kopia/data"
    "kopia/repository"
    "ldap"
    "mailserver/data"
    "mailserver/logs"
    "mailserver/state"
    "mariadb"
    "onlyoffice/data"
    "onlyoffice/logs"
    "open-webui"
    "planka"
    "postgres"
    "prometheus"
    "radicale"
    "redis"
    "roundcube"
    "synapse"
    "vaultwarden"
)

# Config directories (configuration files)
CONFIG_DIRS=(
    "caddy"
    "ldap"
    "litellm"
    "mailserver"
    "qbittorrent"
)

# Credentials directories (secrets: certs, keys, DKIM, etc.)
CREDENTIALS_DIRS=(
    "caddy"
)

# Vector database volumes (on SSD)
VECTOR_DIRS=(
    "qdrant"
)

# Create data directories
info "Creating data volumes..."
for dir in "${DATA_DIRS[@]}"; do
    mkdir -p "./volumes/data/${dir}"
done

# Create config directories
info "Creating config volumes..."
for dir in "${CONFIG_DIRS[@]}"; do
    mkdir -p "./volumes/config/${dir}"
done

# Create credentials directories
info "Creating credentials volumes..."
for dir in "${CREDENTIALS_DIRS[@]}"; do
    mkdir -p "./volumes/credentials/${dir}"
done

# Create vector DB directories on SSD
info "Creating vector database volumes on SSD..."
for dir in "${VECTOR_DIRS[@]}"; do
    mkdir -p "${VECTOR_DB_ROOT}/${dir}"
done

# Create custom storage directories
info "Creating custom storage directories..."

# Extract parent directories and create them if they're absolute paths
# This handles cases like /mnt/media/qbittorrent -> creates /mnt/media
for path in "${QBITTORRENT_DATA_ROOT}" "${SEAFILE_MEDIA_ROOT}"; do
    # Only try to create parent if it's an absolute path starting with /
    if [[ "${path}" == /* ]]; then
        parent_dir="$(dirname "${path}")"
        if [[ ! -d "${parent_dir}" ]]; then
            info "Creating parent directory: ${parent_dir}"
            mkdir -p "${parent_dir}" || warn "Could not create ${parent_dir} (may need sudo)"
        fi
    fi
done

mkdir -p "${QBITTORRENT_DATA_ROOT}" || warn "Could not create ${QBITTORRENT_DATA_ROOT}"
mkdir -p "${SEAFILE_MEDIA_ROOT}" || warn "Could not create ${SEAFILE_MEDIA_ROOT}"
mkdir -p "${SEAFILE_FILES_ROOT}"

# Set ownership if running as root (for system deployments)
if [[ $EUID -eq 0 ]]; then
    if [[ -n "${DOCKER_USER_ID:-}" ]] && [[ -n "${DOCKER_GROUP_ID:-}" ]]; then
        info "Setting ownership to ${DOCKER_USER_ID}:${DOCKER_GROUP_ID}..."
        chown -R "${DOCKER_USER_ID}:${DOCKER_GROUP_ID}" ./volumes/ || warn "Could not set ownership on ./volumes/"
        chown -R "${DOCKER_USER_ID}:${DOCKER_GROUP_ID}" "${VECTOR_DB_ROOT}" || warn "Could not set ownership on ${VECTOR_DB_ROOT}"
        chown -R "${DOCKER_USER_ID}:${DOCKER_GROUP_ID}" "${QBITTORRENT_DATA_ROOT}" || warn "Could not set ownership on ${QBITTORRENT_DATA_ROOT}"
        chown -R "${DOCKER_USER_ID}:${DOCKER_GROUP_ID}" "${SEAFILE_MEDIA_ROOT}" || warn "Could not set ownership on ${SEAFILE_MEDIA_ROOT}"
        chown -R "${DOCKER_USER_ID}:${DOCKER_GROUP_ID}" "${SEAFILE_FILES_ROOT}" || warn "Could not set ownership on ${SEAFILE_FILES_ROOT}"
    fi
fi

# Count created directories (+ 3 for custom: qbittorrent, seafile_media, seafile_files)
TOTAL_DIRS=$((${#DATA_DIRS[@]} + ${#CONFIG_DIRS[@]} + ${#CREDENTIALS_DIRS[@]} + ${#VECTOR_DIRS[@]} + 3))

echo ""
info "✓ Created ${TOTAL_DIRS} volume directories successfully"
echo ""
echo "  Data:         ${DEPLOY_ROOT}/volumes/data (${#DATA_DIRS[@]} dirs)"
echo "  Config:       ${DEPLOY_ROOT}/volumes/config (${#CONFIG_DIRS[@]} dirs)"
echo "  Credentials:  ${DEPLOY_ROOT}/volumes/credentials (${#CREDENTIALS_DIRS[@]} dirs)"
echo "  Vector DBs:   ${VECTOR_DB_ROOT} (${#VECTOR_DIRS[@]} dirs)"
echo ""
echo "  Custom storage locations:"
echo "    qBittorrent: ${QBITTORRENT_DATA_ROOT}"
echo "    Seafile (media): ${SEAFILE_MEDIA_ROOT}"
echo "    Seafile (files): ${SEAFILE_FILES_ROOT}"
echo ""
info "Ready to deploy! Run: docker compose up -d"
