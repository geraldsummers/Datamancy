#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS_DIR="${REPO_ROOT}/volumes/secrets"
PRIVATE_KEY="${SECRETS_DIR}/stackops_ed25519"
PUBLIC_KEY="${PRIVATE_KEY}.pub"

echo "[stackops] Generating SSH keypair for stackops user (idempotent)"

# Create secrets directory if it doesn't exist
mkdir -p "${SECRETS_DIR}"

# Check if key already exists
if [[ -f "${PRIVATE_KEY}" ]]; then
  echo "[stackops] SSH key already exists at ${PRIVATE_KEY}"
  echo "[stackops] Public key fingerprint:"
  ssh-keygen -lf "${PRIVATE_KEY}" 2>/dev/null || echo "  (unable to read)"
  exit 0
fi

# Generate keypair
echo "[stackops] Generating new ed25519 keypair..."
ssh-keygen -t ed25519 -f "${PRIVATE_KEY}" -C "stackops@datamancy" -N ""

# Set proper permissions
chmod 600 "${PRIVATE_KEY}"
chmod 644 "${PUBLIC_KEY}"

echo "[stackops] ✓ SSH keypair generated:"
echo "  Private: ${PRIVATE_KEY}"
echo "  Public:  ${PUBLIC_KEY}"
echo ""
echo "[stackops] Public key fingerprint:"
ssh-keygen -lf "${PRIVATE_KEY}"
echo ""
echo "[stackops] Next step: run scripts/05-setup-stackops-key.sh to install to host"
