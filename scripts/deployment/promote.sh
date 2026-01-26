#!/bin/bash
# Production Promotion Script
# Safely deploys new version with automatic rollback on failure

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOYMENT_ROOT="${DEPLOYMENT_ROOT:-$HOME/.datamancy}"
REGISTRY="${REGISTRY:-localhost:5000}"
VERSION="${1:-latest}"
HEALTH_CHECK_TIMEOUT=60
HEALTH_CHECK_INTERVAL=5

echo "=== Production Promotion ==="
echo "Version: ${VERSION}"
echo "Registry: ${REGISTRY}"
echo ""

if [ "${VERSION}" = "latest" ]; then
    echo "⚠️  WARNING: Promoting 'latest' tag. This is not recommended for production."
    echo "   Use a specific version (git SHA or tag) for traceability."
    echo ""
fi

# Validate we're in deployment directory
if [ ! -f "${DEPLOYMENT_ROOT}/docker-compose.yml" ]; then
    echo "❌ Error: docker-compose.yml not found in ${DEPLOYMENT_ROOT}"
    exit 1
fi

cd "${DEPLOYMENT_ROOT}"

# 1. Pull new images from registry
echo "▸ Pulling new images from registry..."
CUSTOM_IMAGES=(
    "datamancy-jupyterhub"
    "datamancy-jupyter-notebook"
    "datamancy-agent-tool-server"
    "datamancy-search-service"
    "datamancy-test-runner"
)

for image in "${CUSTOM_IMAGES[@]}"; do
    echo "  - Pulling ${REGISTRY}/${image}:${VERSION}"
    docker pull "${REGISTRY}/${image}:${VERSION}" || {
        echo "❌ Failed to pull ${image}:${VERSION}"
        exit 1
    }
    # Tag as latest locally for docker-compose
    docker tag "${REGISTRY}/${image}:${VERSION}" "${image}:${VERSION}"
done

echo "✓ Images pulled successfully"

# 2. Update docker-compose.yml with new image tags
echo "▸ Updating docker-compose.yml image tags..."
cp docker-compose.yml docker-compose.yml.backup

# Update custom image tags in docker-compose.yml
for image in "${CUSTOM_IMAGES[@]}"; do
    sed -i "s|image: ${image}:.*|image: ${image}:${VERSION}|g" docker-compose.yml
done

echo "✓ Image tags updated"

# 3. Identify services that changed
echo "▸ Detecting changed services..."
CHANGED_SERVICES=$(docker-compose config --services | while read -r service; do
    # Check if service uses any of our custom images
    if docker-compose config | grep -A 1 "^  ${service}:" | grep -E "$(IFS=\|; echo "${CUSTOM_IMAGES[*]}")"; then
        echo "${service}"
    fi
done | sort -u)

if [ -z "${CHANGED_SERVICES}" ]; then
    echo "⚠️  No services changed. Nothing to promote."
    exit 0
fi

echo "Changed services:"
echo "${CHANGED_SERVICES}" | sed 's/^/  - /'
echo ""

# 4. Rolling update - update services one by one
echo "▸ Starting rolling update..."
for service in ${CHANGED_SERVICES}; do
    echo ""
    echo "  Updating service: ${service}"

    # Update the service (pull image, recreate container, no downtime)
    docker-compose up -d --no-deps --force-recreate "${service}" || {
        echo "❌ Failed to update ${service}"
        echo "   Initiating rollback..."
        "${SCRIPT_DIR}/rollback.sh"
        exit 1
    }

    echo "  ✓ ${service} updated"
done

echo ""
echo "✓ All services updated"

# 5. Health check validation
echo ""
echo "▸ Running health checks (${HEALTH_CHECK_TIMEOUT}s timeout)..."
ELAPSED=0
ALL_HEALTHY=false

while [ ${ELAPSED} -lt ${HEALTH_CHECK_TIMEOUT} ]; do
    # Check if all services are healthy
    UNHEALTHY_COUNT=$(docker-compose ps --format json | \
        jq -r 'select(.Health == "unhealthy" or .Health == "starting") | .Service' | \
        wc -l)

    if [ "${UNHEALTHY_COUNT}" -eq 0 ]; then
        ALL_HEALTHY=true
        break
    fi

    echo "  Waiting for services to become healthy... (${ELAPSED}s/${HEALTH_CHECK_TIMEOUT}s)"
    sleep ${HEALTH_CHECK_INTERVAL}
    ELAPSED=$((ELAPSED + HEALTH_CHECK_INTERVAL))
done

if [ "${ALL_HEALTHY}" = false ]; then
    echo ""
    echo "❌ Health check failed! Services did not become healthy within ${HEALTH_CHECK_TIMEOUT}s"
    echo ""
    echo "Unhealthy services:"
    docker-compose ps --format json | \
        jq -r 'select(.Health == "unhealthy" or .Health == "starting") | "  - \(.Service): \(.Health)"'
    echo ""
    echo "Initiating automatic rollback..."
    "${SCRIPT_DIR}/rollback.sh"
    exit 1
fi

echo "✓ All services healthy"

# 6. Tag successful promotion in git
echo ""
echo "▸ Tagging successful promotion..."
PROMOTION_TAG="production-${VERSION}-$(date +%Y%m%d-%H%M%S)"
git tag -a "${PROMOTION_TAG}" -m "Production promotion of ${VERSION}" || true

echo "✓ Tagged as ${PROMOTION_TAG}"

# 7. Cleanup old images (keep last 3 versions)
echo ""
echo "▸ Cleaning up old images..."
for image in "${CUSTOM_IMAGES[@]}"; do
    # Keep only last 3 versions
    docker images "${image}" --format "{{.Tag}}" | \
        grep -v "^${VERSION}$" | \
        grep -v "^latest$" | \
        tail -n +4 | \
        xargs -r -I {} docker rmi "${image}:{}" 2>/dev/null || true
done

echo "✓ Cleanup complete"

echo ""
echo "✅ Production promotion successful!"
echo ""
echo "Version: ${VERSION}"
echo "Tag: ${PROMOTION_TAG}"
echo ""
echo "Services updated:"
echo "${CHANGED_SERVICES}" | sed 's/^/  - /'
echo ""
