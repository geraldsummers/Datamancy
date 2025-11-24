#!/bin/bash
# This script runs at container startup to ensure Caddy CA is trusted

if [ -f /usr/local/share/ca-certificates/caddy-ca.crt ]; then
    echo "Adding Caddy CA certificate to system bundle..."

    # Append to the main CA bundle file
    if ! grep -q "Caddy Local Authority" /etc/ssl/certs/ca-certificates.crt 2>/dev/null; then
        echo "" >> /etc/ssl/certs/ca-certificates.crt
        cat /usr/local/share/ca-certificates/caddy-ca.crt >> /etc/ssl/certs/ca-certificates.crt
        echo "Caddy CA appended to system bundle"

        # Rehash certificates so OpenSSL can find them
        cd /etc/ssl/certs && c_rehash . >/dev/null 2>&1
        echo "Certificate directory rehashed"
    fi
fi
