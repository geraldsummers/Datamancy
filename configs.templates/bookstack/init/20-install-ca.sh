#!/usr/bin/with-contenv bash
set -e

CA_SRC="/ca/caddy-ca.crt"
CA_DST="/usr/local/share/ca-certificates/caddy-ca.crt"

if [ ! -f "$CA_SRC" ]; then
    echo "[custom-init] Caddy CA not found at $CA_SRC, skipping CA install"
    exit 0
fi

echo "[custom-init] Installing Caddy CA into system trust store..."
cp "$CA_SRC" "$CA_DST"
chmod 644 "$CA_DST"

if command -v update-ca-certificates >/dev/null 2>&1; then
    update-ca-certificates
    echo "[custom-init] System CA store updated"
else
    echo "[custom-init] update-ca-certificates not available, skipping"
fi
