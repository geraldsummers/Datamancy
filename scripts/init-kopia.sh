#!/bin/sh
# Initialize Kopia repository and start server
set -e

echo "=== Kopia Initialization ==="

REPO_PATH="${KOPIA_REPO_PATH:-/repository}"
KOPIA_PASSWORD="${KOPIA_PASSWORD:-changeme}"

if [ ! -f "${REPO_PATH}/kopia.repository.f" ]; then
  echo "Initializing Kopia repository at ${REPO_PATH}..."

  # Create repository
  kopia repository create filesystem \
    --path="${REPO_PATH}" \
    --password="${KOPIA_PASSWORD}" \
    --no-check-for-updates

  echo "✅ Kopia repository initialized"
else
  echo "✅ Kopia repository already exists"
fi

echo "Starting Kopia server..."
exec kopia server start \
  --insecure \
  --address=0.0.0.0:51515 \
  --server-username=admin \
  --server-password="${KOPIA_PASSWORD}"
