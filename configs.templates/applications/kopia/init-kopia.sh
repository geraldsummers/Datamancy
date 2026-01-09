#!/usr/bin/env sh
# Kopia repository initialization and server start script - IDEMPOTENT (safe to run on every start)
# Configures snapshot policies and retention rules for Datamancy backup strategy
set -eu

KOPIA_REPO_PATH="${KOPIA_REPO_PATH:-/repository}"
KOPIA_PASSWORD="${KOPIA_PASSWORD:-changeme}"
VOLUMES_ROOT="${VOLUMES_ROOT:-/app/volumes}"

echo "[kopia-init] Using repo path: ${KOPIA_REPO_PATH}"
echo "[kopia-init] Volumes root: ${VOLUMES_ROOT}"

# Ensure repository exists and is connected
# Check if already connected (kopia.repository config file in user's home)
if kopia repository status >/dev/null 2>&1; then
  echo "[kopia-init] Already connected to repository"
elif [ -f "$KOPIA_REPO_PATH/kopia.repository" ]; then
  echo "[kopia-init] Connecting to existing repository..."
  kopia repository connect filesystem --path="$KOPIA_REPO_PATH" --password "$KOPIA_PASSWORD"
else
  echo "[kopia-init] Creating new repository..."
  mkdir -p "$KOPIA_REPO_PATH"
  kopia repository create filesystem --path="$KOPIA_REPO_PATH" --password "$KOPIA_PASSWORD"
fi

# Configure global snapshot policies
echo "[kopia-init] Configuring snapshot policies..."
kopia policy set --global \
    --compression=zstd \
    --keep-latest=30 \
    --keep-hourly=24 \
    --keep-daily=7 \
    --keep-weekly=4 \
    --keep-monthly=12 \
    --keep-annual=3 \
    --add-ignore='.cache' \
    --add-ignore='node_modules' \
    --add-ignore='*.tmp' \
    --add-ignore='*.log' || echo "[kopia-init] Policy update failed (may already be set)"

# List configured policies
echo "[kopia-init] Current global policy:"
kopia policy show --global || true

echo "[kopia-init] Repository ready"
echo "[kopia-init] Starting Kopia server on :51515 (authentication via Authelia forward-auth)"
exec kopia server start \
    --insecure \
    --address=0.0.0.0:51515 \
    --ui \
    --without-password
