#!/usr/bin/env bash
set -euo pipefail

echo "[stackops] Creating system user and adding to docker group (idempotent)"

if ! id -u stackops >/dev/null 2>&1; then
  sudo useradd --system --create-home --shell /usr/sbin/nologin stackops
  echo "[stackops] User created"
else
  echo "[stackops] User already exists"
fi

if ! id -nG stackops | grep -q "\bdocker\b"; then
  sudo usermod -aG docker stackops
  echo "[stackops] Added to docker group"
else
  echo "[stackops] Already in docker group"
fi

echo "[stackops] Ensure home ssh dir exists with proper perms"
sudo -u stackops mkdir -p /home/stackops/.ssh
sudo -u stackops chmod 700 /home/stackops/.ssh
sudo chown -R stackops:stackops /home/stackops/.ssh

cat <<'EONOTE'
Next steps:
  - Generate an ed25519 keypair on your admin machine:
      ssh-keygen -t ed25519 -f stackops_ed25519 -C "stackops@$(hostname)"
  - Place private key at volumes/secrets/stackops_ed25519 in the repo (chmod 600)
  - Append public key to /home/stackops/.ssh/authorized_keys on the host,
    but only after installing the forced wrapper (see 02-install-stackops-wrapper.sh)
EONOTE
