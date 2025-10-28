#!/bin/sh
set -e

# Decode the base64-encoded RSA private key if it's encoded
if [ -n "$AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY" ]; then
    # Check if it's base64 encoded (doesn't start with -----)
    if ! echo "$AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY" | grep -q "^-----BEGIN"; then
        # Decode base64
        export AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY=$(echo "$AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY" | base64 -d)
    fi
fi

# Run the original authelia command
exec authelia "$@"
