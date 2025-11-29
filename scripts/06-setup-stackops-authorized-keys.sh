#!/usr/bin/env bash
# Script to setup stackops authorized_keys with forced command
# Run with: sudo bash scripts/06-setup-stackops-authorized-keys.sh

set -euo pipefail

STACKOPS_USER="stackops"
STACKOPS_HOME="/home/${STACKOPS_USER}"
SSH_DIR="${STACKOPS_HOME}/.ssh"
AUTH_KEYS="${SSH_DIR}/authorized_keys"
WRAPPER_PATH="/usr/local/bin/stackops-wrapper"
PUB_KEY_FILE="volumes/secrets/stackops_ed25519.pub"

echo "==> Setting up stackops authorized_keys with forced command"

# Check wrapper exists
if [[ ! -f "${WRAPPER_PATH}" ]]; then
    echo "ERROR: stackops-wrapper not found at ${WRAPPER_PATH}"
    echo "Run: sudo bash scripts/02-install-stackops-wrapper.sh"
    exit 1
fi

# Check public key exists
if [[ ! -f "${PUB_KEY_FILE}" ]]; then
    echo "ERROR: Public key not found at ${PUB_KEY_FILE}"
    exit 1
fi

# Create .ssh directory if missing
if [[ ! -d "${SSH_DIR}" ]]; then
    echo "Creating ${SSH_DIR}"
    mkdir -p "${SSH_DIR}"
    chown "${STACKOPS_USER}:${STACKOPS_USER}" "${SSH_DIR}"
    chmod 700 "${SSH_DIR}"
fi

# Read the public key
PUB_KEY=$(cat "${PUB_KEY_FILE}")

# Create authorized_keys with forced command
echo "Writing authorized_keys with forced command prefix"
cat > "${AUTH_KEYS}" <<EOF
command="${WRAPPER_PATH}",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding ${PUB_KEY}
EOF

# Set permissions
chown "${STACKOPS_USER}:${STACKOPS_USER}" "${AUTH_KEYS}"
chmod 600 "${AUTH_KEYS}"

echo "==> Success! authorized_keys configured at ${AUTH_KEYS}"
echo ""
echo "Test SSH access with:"
echo "  ssh -i volumes/secrets/stackops_ed25519 stackops@localhost 'docker ps'"
