#!/bin/bash
# Pre-Promotion Snapshot Script
# Creates comprehensive backup before production deployment

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOYMENT_ROOT="${DEPLOYMENT_ROOT:-$HOME/.datamancy}"
SNAPSHOT_DIR="${DEPLOYMENT_ROOT}/snapshots"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
VERSION=$(git -C "${DEPLOYMENT_ROOT}" rev-parse --short HEAD 2>/dev/null || echo "unknown")
SNAPSHOT_ID="pre-promotion-${VERSION}-${TIMESTAMP}"

echo "=== Pre-Promotion Snapshot ==="
echo "Timestamp: ${TIMESTAMP}"
echo "Version: ${VERSION}"
echo "Snapshot ID: ${SNAPSHOT_ID}"
echo ""

# Create snapshot directory
mkdir -p "${SNAPSHOT_DIR}"

# 1. Create Kopia snapshot of all volumes
echo "▸ Creating Kopia snapshot..."
cd "${DEPLOYMENT_ROOT}"

# Trigger Kopia snapshot via docker-compose
docker-compose exec -T kopia kopia snapshot create /backup \
    --tags="type:pre-promotion,version:${VERSION},timestamp:${TIMESTAMP}" \
    --description="Pre-promotion snapshot before deploying ${VERSION}" || {
    echo "❌ Kopia snapshot failed!"
    exit 1
}

# Get the snapshot ID from Kopia
KOPIA_SNAPSHOT_ID=$(docker-compose exec -T kopia kopia snapshot list --all --json | \
    jq -r '.[0].id // "unknown"')

echo "✓ Kopia snapshot created: ${KOPIA_SNAPSHOT_ID}"

# 2. Save current git state
echo "▸ Recording git state..."
git -C "${DEPLOYMENT_ROOT}" describe --tags --always > "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.git-version" || \
    git -C "${DEPLOYMENT_ROOT}" rev-parse HEAD > "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.git-version"

echo "✓ Git version saved"

# 3. Backup current docker-compose.yml and .env
echo "▸ Backing up configuration files..."
cp "${DEPLOYMENT_ROOT}/docker-compose.yml" "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.docker-compose.yml"
cp "${DEPLOYMENT_ROOT}/.env" "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.env"

echo "✓ Configuration files backed up"

# 4. Record current image versions
echo "▸ Recording current image versions..."
docker-compose -f "${DEPLOYMENT_ROOT}/docker-compose.yml" config --services | \
while read -r service; do
    docker-compose -f "${DEPLOYMENT_ROOT}/docker-compose.yml" config | \
        grep -A 1 "^  ${service}:" | grep "image:" || echo "  image: unknown"
done > "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.images.txt"

echo "✓ Image versions recorded"

# 5. Create snapshot manifest
echo "▸ Creating snapshot manifest..."
cat > "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.manifest.json" <<EOF
{
  "snapshot_id": "${SNAPSHOT_ID}",
  "timestamp": "${TIMESTAMP}",
  "git_version": "${VERSION}",
  "kopia_snapshot_id": "${KOPIA_SNAPSHOT_ID}",
  "docker_compose_backup": "${SNAPSHOT_ID}.docker-compose.yml",
  "env_backup": "${SNAPSHOT_ID}.env",
  "images_backup": "${SNAPSHOT_ID}.images.txt"
}
EOF

echo "✓ Manifest created"

# 6. Save snapshot ID to a 'latest' file for easy rollback
echo "${SNAPSHOT_ID}" > "${SNAPSHOT_DIR}/latest-snapshot.txt"
echo "${KOPIA_SNAPSHOT_ID}" > "${SNAPSHOT_DIR}/latest-kopia-snapshot.txt"

echo ""
echo "✅ Pre-promotion snapshot complete!"
echo ""
echo "Snapshot ID: ${SNAPSHOT_ID}"
echo "Kopia Snapshot: ${KOPIA_SNAPSHOT_ID}"
echo ""
echo "To rollback to this snapshot, run:"
echo "  ./scripts/deployment/rollback.sh ${SNAPSHOT_ID}"
echo ""

# Return snapshot ID for use in CI/CD
echo "${SNAPSHOT_ID}"
