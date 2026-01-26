#!/bin/bash
# Rollback Script
# Restores system to previous snapshot after failed promotion

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOYMENT_ROOT="${DEPLOYMENT_ROOT:-$HOME/.datamancy}"
SNAPSHOT_DIR="${DEPLOYMENT_ROOT}/snapshots"
SNAPSHOT_ID="${1:-}"

echo "=== Production Rollback ==="
echo ""

# Determine snapshot to rollback to
if [ -z "${SNAPSHOT_ID}" ]; then
    # Use latest snapshot if none specified
    if [ ! -f "${SNAPSHOT_DIR}/latest-snapshot.txt" ]; then
        echo "❌ Error: No snapshot ID provided and no latest snapshot found"
        echo "Usage: $0 [SNAPSHOT_ID]"
        exit 1
    fi
    SNAPSHOT_ID=$(cat "${SNAPSHOT_DIR}/latest-snapshot.txt")
    echo "⚠️  No snapshot ID provided, using latest: ${SNAPSHOT_ID}"
fi

echo "Snapshot ID: ${SNAPSHOT_ID}"
echo ""

# Validate snapshot exists
if [ ! -f "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.manifest.json" ]; then
    echo "❌ Error: Snapshot manifest not found: ${SNAPSHOT_ID}.manifest.json"
    echo ""
    echo "Available snapshots:"
    ls -1 "${SNAPSHOT_DIR}"/*.manifest.json 2>/dev/null | sed 's/.*\//  - /' || echo "  (none)"
    exit 1
fi

# Load snapshot manifest
KOPIA_SNAPSHOT_ID=$(jq -r '.kopia_snapshot_id' "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.manifest.json")
GIT_VERSION=$(jq -r '.git_version' "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.manifest.json")

echo "Kopia Snapshot: ${KOPIA_SNAPSHOT_ID}"
echo "Git Version: ${GIT_VERSION}"
echo ""

# Confirm rollback (skip in CI/automated environments)
if [ -t 0 ] && [ "${CI:-false}" != "true" ]; then
    read -p "⚠️  This will restore the system to snapshot ${SNAPSHOT_ID}. Continue? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Rollback cancelled."
        exit 0
    fi
fi

cd "${DEPLOYMENT_ROOT}"

# 1. Stop all services
echo "▸ Stopping all services..."
docker-compose down || {
    echo "⚠️  Warning: docker-compose down failed, continuing..."
}

echo "✓ Services stopped"

# 2. Restore volumes from Kopia snapshot
echo ""
echo "▸ Restoring volumes from Kopia snapshot..."
echo "  This may take several minutes depending on data size..."

# Start Kopia service temporarily for restore
docker-compose up -d kopia
sleep 5

# Restore snapshot
docker-compose exec -T kopia kopia snapshot restore "${KOPIA_SNAPSHOT_ID}" /backup || {
    echo "❌ Kopia restore failed!"
    echo "   Manual intervention required."
    exit 1
}

echo "✓ Volumes restored"

# 3. Restore configuration files
echo ""
echo "▸ Restoring configuration files..."
cp "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.docker-compose.yml" docker-compose.yml
cp "${SNAPSHOT_DIR}/${SNAPSHOT_ID}.env" .env

echo "✓ Configuration restored"

# 4. Checkout git version (if in a git repo)
if [ -d .git ]; then
    echo ""
    echo "▸ Checking out git version..."
    git fetch --all --tags
    git checkout "${GIT_VERSION}" || {
        echo "⚠️  Warning: Could not checkout git version ${GIT_VERSION}"
        echo "   Continuing with current git state..."
    }
    echo "✓ Git version restored"
fi

# 5. Pull images from backed-up configuration
echo ""
echo "▸ Pulling images from snapshot configuration..."
docker-compose pull || {
    echo "⚠️  Warning: Some images could not be pulled"
    echo "   Proceeding with locally available images..."
}

echo "✓ Images pulled"

# 6. Start services
echo ""
echo "▸ Starting services..."
docker-compose up -d

echo "✓ Services started"

# 7. Wait for health checks
echo ""
echo "▸ Waiting for services to become healthy (60s timeout)..."
ELAPSED=0
TIMEOUT=60
ALL_HEALTHY=false

while [ ${ELAPSED} -lt ${TIMEOUT} ]; do
    UNHEALTHY_COUNT=$(docker-compose ps --format json | \
        jq -r 'select(.Health == "unhealthy" or .Health == "starting") | .Service' | \
        wc -l)

    if [ "${UNHEALTHY_COUNT}" -eq 0 ]; then
        ALL_HEALTHY=true
        break
    fi

    echo "  Waiting... (${ELAPSED}s/${TIMEOUT}s)"
    sleep 5
    ELAPSED=$((ELAPSED + 5))
done

if [ "${ALL_HEALTHY}" = false ]; then
    echo ""
    echo "⚠️  Warning: Not all services became healthy within timeout"
    echo ""
    echo "Current status:"
    docker-compose ps
    echo ""
    echo "Manual verification required!"
    exit 1
fi

echo "✓ All services healthy"

# 8. Record rollback in audit log
echo ""
echo "▸ Recording rollback in audit log..."
AUDIT_LOG="${DEPLOYMENT_ROOT}/snapshots/rollback-audit.log"
echo "$(date -Iseconds) | Rollback to ${SNAPSHOT_ID} (Kopia: ${KOPIA_SNAPSHOT_ID}) from git ${GIT_VERSION}" >> "${AUDIT_LOG}"

echo "✓ Audit log updated"

echo ""
echo "✅ Rollback complete!"
echo ""
echo "System restored to:"
echo "  Snapshot: ${SNAPSHOT_ID}"
echo "  Git Version: ${GIT_VERSION}"
echo "  Kopia Snapshot: ${KOPIA_SNAPSHOT_ID}"
echo ""
echo "Verify system status:"
echo "  docker-compose ps"
echo "  docker-compose logs --tail=50"
echo ""
