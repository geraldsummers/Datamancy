#!/usr/bin/env sh
set -eu
KOPIA_REPO_PATH="${KOPIA_REPO_PATH:-/repository}"
KOPIA_PASSWORD="${KOPIA_PASSWORD:?ERROR: KOPIA_PASSWORD must be set}"
KOPIA_SERVER_USERNAME="${KOPIA_SERVER_USERNAME:-kopia}"
KOPIA_SERVER_PASSWORD="${KOPIA_SERVER_PASSWORD:-$KOPIA_PASSWORD}"
VOLUMES_ROOT="${VOLUMES_ROOT:-/app/volumes}"
echo "[kopia-init] Using repo path: ${KOPIA_REPO_PATH}"
echo "[kopia-init] Volumes root: ${VOLUMES_ROOT}"
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
echo "[kopia-init] Current global policy:"
kopia policy show --global || true
echo "[kopia-init] Repository ready"
echo "[kopia-init] Starting Kopia server on :51515 (basic auth enabled; TLS expected at reverse proxy)"
exec kopia server start \
    --insecure \
    --address=0.0.0.0:51515 \
    --ui \
    --server-username="$KOPIA_SERVER_USERNAME" \
    --server-password="$KOPIA_SERVER_PASSWORD"
