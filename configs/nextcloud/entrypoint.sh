#!/bin/bash
set -e

# Update CA certificates - force refresh
echo "Updating CA certificates..."
if [ -f /usr/local/share/ca-certificates/caddy-ca.crt ]; then
    # Split multi-cert file into individual certs (update-ca-certificates requires one cert per file)
    csplit -f /usr/share/ca-certificates/caddy-ca- -b "%02d.crt" -s -z /usr/local/share/ca-certificates/caddy-ca.crt "/-----BEGIN CERTIFICATE-----/" "{*}" 2>/dev/null || true

    # Add split certs to config
    echo "caddy-ca-00.crt" >> /etc/ca-certificates.conf
    echo "caddy-ca-01.crt" >> /etc/ca-certificates.conf

    # Update certificates
    update-ca-certificates --fresh > /dev/null 2>&1

    # Rehash certificate directory
    cd /etc/ssl/certs && c_rehash . > /dev/null 2>&1
fi

# Ensure PHP uses the updated CA bundle and disables SSL verification for local development
if [ -f /etc/ssl/certs/ca-certificates.crt ]; then
    echo "Setting PHP SSL configuration..."
    cat > /usr/local/etc/php/conf.d/ssl-cert.ini <<EOF
openssl.cafile=/etc/ssl/certs/ca-certificates.crt
curl.cainfo=/etc/ssl/certs/ca-certificates.crt
EOF
fi

# Run original entrypoint
exec /entrypoint.sh apache2-foreground
