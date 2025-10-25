#!/bin/sh
# Provenance: OAuth2-Proxy CA certificate trust setup
# Purpose: Trust self-signed CA before starting OAuth2-Proxy
# Architecture: Distroless image - CA must be installed differently

set -e

# Note: OAuth2-Proxy uses distroless base image which doesn't have update-ca-certificates
# Instead, we rely on mounting the CA cert and using SSL_CERT_DIR environment variable
echo "✓ OAuth2-Proxy: Using CA certificate from SSL_CERT_DIR"

# Start oauth2-proxy with all arguments passed through
exec /bin/oauth2-proxy "$@"
