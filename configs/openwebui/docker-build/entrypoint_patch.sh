#!/bin/bash
set -e

echo "Starting Open-WebUI with SSL certificate configuration..."

# If certificate is mounted at runtime, update it
if [ -f /tmp/caddy-ca.crt ]; then
    echo "Runtime CA certificate found, updating trust store..."
    cp /tmp/caddy-ca.crt /usr/local/share/ca-certificates/caddy-ca.crt
    update-ca-certificates

    # Update Python certifi bundle - MUST use cat to append, not replace
    certifi_path=$(python -c "import certifi; print(certifi.where())")
    echo "Certifi bundle location: $certifi_path"

    # Append Caddy CA to certifi bundle (don't replace the whole file)
    # Check if Caddy cert is already in the bundle by looking for the cert itself
    if ! tail -100 "$certifi_path" | grep -q "BEGIN CERTIFICATE" 2>/dev/null || \
       ! tail -100 "$certifi_path" | grep -q "Caddy Local Authority" 2>/dev/null; then
        echo "" >> "$certifi_path"
        cat /usr/local/share/ca-certificates/caddy-ca.crt >> "$certifi_path"
        echo "Caddy CA certificate added to certifi bundle"
    else
        echo "Caddy CA certificate already in certifi bundle"
    fi

    echo "Certificate trust updated successfully"
else
    echo "Using built-in CA certificate from build time"
fi

# Export SSL environment variables for Python httpx/requests
export SSL_CERT_FILE=$(python -c "import certifi; print(certifi.where())")
export REQUESTS_CA_BUNDLE="$SSL_CERT_FILE"
echo "SSL_CERT_FILE set to: $SSL_CERT_FILE"

# Verify and fix certificate in certifi bundle
if [ -f /usr/local/share/ca-certificates/caddy-ca.crt ]; then
    echo "Caddy CA certificate is installed in system store"
    certifi_path=$(python -c "import certifi; print(certifi.where())")
    echo "Certifi bundle location: $certifi_path"

    # Always ensure it's in certifi bundle by checking and adding if missing
    if tail -100 "$certifi_path" | grep -q "Caddy Local Authority" 2>/dev/null; then
        echo "✓ Caddy CA certificate already in certifi bundle"
    else
        echo "⚠ Caddy CA certificate NOT found in certifi bundle - adding it now"
        echo "" >> "$certifi_path"
        cat /usr/local/share/ca-certificates/caddy-ca.crt >> "$certifi_path"
        echo "✓ Caddy CA certificate added to certifi bundle"
    fi
else
    echo "WARNING: No Caddy CA certificate found!"
fi

# Start Open-WebUI normally
exec bash /app/backend/start.sh
