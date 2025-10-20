#!/bin/bash
# Script to install Caddy's root CA certificate to system trust store

set -e

cd "$(dirname "$0")"

echo "Extracting Caddy root CA certificate..."
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root-ca.crt

echo "Installing certificate to system trust store..."

# For Ubuntu/Debian
if [ -d /usr/local/share/ca-certificates/ ]; then
    sudo cp caddy-root-ca.crt /usr/local/share/ca-certificates/caddy-local-ca.crt
    sudo update-ca-certificates
    echo "✓ Installed to system trust store (Linux)"
fi

# For NSS (Firefox, Chrome, Brave on Linux)
if command -v certutil &> /dev/null; then
    for certDB in $(find ~/.pki -name "cert9.db" 2>/dev/null); do
        certdir=$(dirname "$certDB")
        echo "Installing to NSS database: $certdir"
        certutil -A -n "Caddy Local Authority" -t "C,," -i caddy-root-ca.crt -d "sql:$certdir"
    done

    # Also check Mozilla profiles
    for certDB in $(find ~/.mozilla -name "cert9.db" 2>/dev/null); do
        certdir=$(dirname "$certDB")
        echo "Installing to Mozilla profile: $certdir"
        certutil -A -n "Caddy Local Authority" -t "C,," -i caddy-root-ca.crt -d "sql:$certdir"
    done

    # Brave/Chrome profiles
    for certDB in $(find ~/.config/BraveSoftware ~/.config/google-chrome ~/.config/chromium -name "cert9.db" 2>/dev/null); do
        certdir=$(dirname "$certDB")
        echo "Installing to browser profile: $certdir"
        certutil -A -n "Caddy Local Authority" -t "C,," -i caddy-root-ca.crt -d "sql:$certdir"
    done

    echo "✓ Installed to NSS databases (browsers)"
else
    echo "⚠ certutil not found. Install libnss3-tools to auto-install to browsers"
    echo "  Run: sudo apt install libnss3-tools"
fi

echo ""
echo "Certificate saved to: ./caddy-root-ca.crt"
echo ""
echo "For Brave, you can also manually import at: brave://settings/certificates"
echo "Then import ./caddy-root-ca.crt under the 'Authorities' tab"
