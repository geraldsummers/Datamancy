#!/usr/bin/env bash
set -euo pipefail

SSHD_CONFIG=/etc/ssh/sshd_config

echo "[stackops] Hardening sshd for key-only, limited environment (idempotent)"

backup() {
  if [[ ! -f ${SSHD_CONFIG}.bak ]]; then
    sudo cp -a "$SSHD_CONFIG" "${SSHD_CONFIG}.bak"
    echo "[stackops] Backup created at ${SSHD_CONFIG}.bak"
  else
    echo "[stackops] Backup already exists"
  fi
}

apply_kv() {
  local key="$1" val="$2"
  if grep -Eiq "^\s*${key}\s+" "$SSHD_CONFIG"; then
    sudo sed -i -E "s|^\s*(${key})\s+.*$|\1 ${val}|Ig" "$SSHD_CONFIG"
  else
    echo "${key} ${val}" | sudo tee -a "$SSHD_CONFIG" >/dev/null
  fi
}

backup
apply_kv PubkeyAuthentication yes
apply_kv PasswordAuthentication no
apply_kv PermitTunnel no
apply_kv PermitTTY no

echo "[stackops] Reloading sshd"
if command -v systemctl >/dev/null 2>&1; then
  sudo systemctl reload sshd || sudo systemctl restart sshd
else
  # Fallback service name
  sudo service ssh reload || sudo service ssh restart || true
fi

cat <<'EONOTE'
[stackops] sshd hardened. Ensure the authorized_keys entry for stackops uses:
  command="/usr/local/bin/stackops-wrapper",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding <YOUR-PUBLIC-KEY>
EONOTE
