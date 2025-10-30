#!/bin/bash
set -e

# Install Caddy CA certificate
if [ -f /tmp/caddy-ca.crt ]; then
    cp /tmp/caddy-ca.crt /usr/local/share/ca-certificates/caddy-ca.crt
    update-ca-certificates

    # Also append to Python certifi bundle
    CERTIFI_PATH=$(python -c "import certifi; print(certifi.where())" 2>/dev/null || echo "/usr/local/lib/python3.11/site-packages/certifi/cacert.pem")
    if [ -f "$CERTIFI_PATH" ]; then
        cat /tmp/caddy-ca.crt >> "$CERTIFI_PATH"
    fi
fi

# Start Open-WebUI with the original command
exec bash /app/backend/start.sh
