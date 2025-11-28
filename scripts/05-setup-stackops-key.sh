#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRIVATE_KEY="${REPO_ROOT}/volumes/secrets/stackops_ed25519"
PUBLIC_KEY="${PRIVATE_KEY}.pub"
AUTH_KEYS="/home/stackops/.ssh/authorized_keys"

echo "[stackops] Installing SSH public key to stackops@localhost (idempotent)"

# Check if private key exists
if [[ ! -f "${PRIVATE_KEY}" ]]; then
  echo "[stackops] ERROR: Private key not found at ${PRIVATE_KEY}"
  echo "[stackops] Run scripts/04-generate-ssh-keys.sh first"
  exit 1
fi

# Check if public key exists
if [[ ! -f "${PUBLIC_KEY}" ]]; then
  echo "[stackops] ERROR: Public key not found at ${PUBLIC_KEY}"
  exit 1
fi

# Read public key
PUBKEY_CONTENT=$(cat "${PUBLIC_KEY}")

# Create .ssh directory
sudo mkdir -p /home/stackops/.ssh
sudo chmod 700 /home/stackops/.ssh

# Check if key is already installed
if sudo grep -qF "${PUBKEY_CONTENT}" "${AUTH_KEYS}" 2>/dev/null; then
  echo "[stackops] SSH key already installed in ${AUTH_KEYS}"
  exit 0
fi

# Add forced command with public key to authorized_keys
echo "[stackops] Installing public key with forced command wrapper..."
echo "command=\"/usr/local/bin/stackops-wrapper\",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding ${PUBKEY_CONTENT}" | sudo tee -a "${AUTH_KEYS}" >/dev/null

# Set proper permissions
sudo chmod 600 "${AUTH_KEYS}"
sudo chown -R stackops:stackops /home/stackops/.ssh

echo "[stackops] ✓ SSH key configured for stackops user"
echo ""
echo "[stackops] Test with:"
echo "  ssh -i ${PRIVATE_KEY} stackops@localhost 'docker ps'"
