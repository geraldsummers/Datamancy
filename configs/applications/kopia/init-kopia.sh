#!/usr/bin/env sh
# Minimal Kopia init and server start script
set -eu

KOPIA_REPO_PATH="${KOPIA_REPO_PATH:-/repository}"
KOPIA_PASSWORD="${KOPIA_PASSWORD:-changeme}"

echo "[kopia-init] Using repo path: ${KOPIA_REPO_PATH}"

# Ensure repository exists and is connected
if ! kopia repository status >/dev/null 2>&1; then
  if [ -d "$KOPIA_REPO_PATH" ] && [ "$(ls -A "$KOPIA_REPO_PATH" 2>/dev/null | wc -l || true)" -gt 0 ]; then
    echo "[kopia-init] Connecting to existing repository..."
    kopia repository connect filesystem --path="$KOPIA_REPO_PATH" --password "$KOPIA_PASSWORD"
  else
    echo "[kopia-init] Creating new repository..."
    mkdir -p "$KOPIA_REPO_PATH"
    kopia repository create filesystem --path="$KOPIA_REPO_PATH" --password "$KOPIA_PASSWORD"
  fi
fi

echo "[kopia-init] Starting Kopia server on :51515"
exec kopia server start --insecure --address=0.0.0.0:51515 --ui
