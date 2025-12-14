#!/bin/bash
# Extract Caddy's internal CA certificate for services that need to trust it
# This is needed for services like Planka that make HTTPS calls to other internal services

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

CADDY_CONTAINER="caddy"
TARGET_DIR="$HOME/.datamancy/configs/applications/planka"
CERT_FILE="$TARGET_DIR/caddy-ca.crt"

echo "Setting up Caddy CA certificate for internal services..."
echo "Target: $CERT_FILE"

# Create target directory
mkdir -p "$TARGET_DIR"

# Remove if it's a directory (common mistake from config processor)
if [ -d "$CERT_FILE" ]; then
    echo "Removing incorrectly created directory: $CERT_FILE"
    rmdir "$CERT_FILE" 2>/dev/null || rm -rf "$CERT_FILE"
fi

# Check if Caddy container is running
if ! docker ps --format '{{.Names}}' | grep -q "^${CADDY_CONTAINER}$"; then
    echo "⚠ Warning: Caddy container not running, creating placeholder cert"
    # Create a placeholder that won't break volume mounts
    touch "$CERT_FILE"
    echo "# Caddy CA certificate placeholder - will be populated when Caddy starts" > "$CERT_FILE"
    exit 0
fi

# Try to extract the CA certificate from Caddy's internal PKI
echo "Extracting Caddy CA certificate..."

# Caddy stores its internal CA at /data/caddy/pki/authorities/local/root.crt
if docker exec "$CADDY_CONTAINER" test -f /data/caddy/pki/authorities/local/root.crt 2>/dev/null; then
    docker exec "$CADDY_CONTAINER" cat /data/caddy/pki/authorities/local/root.crt > "$CERT_FILE"
    echo "✓ Caddy CA certificate extracted successfully"
else
    echo "⚠ Warning: Caddy CA certificate not found in container, creating placeholder"
    # Create a placeholder
    touch "$CERT_FILE"
    echo "# Caddy CA certificate not yet generated - will be populated after Caddy creates it" > "$CERT_FILE"
    echo "Note: For production with real TLS certs, Planka doesn't need this file"
fi

chmod 644 "$CERT_FILE"
echo "Certificate file: $CERT_FILE"
