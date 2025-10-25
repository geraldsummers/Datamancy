#!/usr/bin/env bash
# Containerized test runner
# Runs Playwright tests in a container with CA trust

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."

echo "Running tests in container..."

docker run --rm \
  --network datamancy_datamancy \
  --add-host stack.local:$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' traefik) \
  -v "${PROJECT_ROOT}/certs/ca.crt:/usr/local/share/ca-certificates/datamancy-ca.crt:ro" \
  -v "${SCRIPT_DIR}:/tests" \
  -w /tests \
  -e NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/datamancy-ca.crt \
  mcr.microsoft.com/playwright:v1.44.0-jammy \
  /bin/bash -c "
    update-ca-certificates && \
    npm install && \
    npx playwright test
  "

echo ""
echo "✓ Tests completed"
