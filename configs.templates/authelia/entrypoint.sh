#!/bin/sh
# Authelia entrypoint - IDEMPOTENT (processes config on every container start)
set -e

# Process configuration file template (idempotent - writes to /tmp)
if [ -f /config/configuration.yml ]; then
    echo "Processing configuration template..."
    # Create a temporary processed config
    TEMP_CONFIG=/tmp/configuration.yml

    # Replace ${VARIABLE} patterns using sed
    # We do this one at a time to avoid sed regex issues
    cp /config/configuration.yml "$TEMP_CONFIG"

    # Replace domain references
    sed -i "s|\${DOMAIN}|$DOMAIN|g" "$TEMP_CONFIG" || true

    # Replace OAuth secret references (use plain secrets, not hashes for now)
    # Note: Grafana, Home Assistant, JupyterHub use forward_auth - no OIDC secrets needed
    sed -i "s|\${PGADMIN_OAUTH_SECRET}|$PGADMIN_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${OPENWEBUI_OAUTH_SECRET}|$OPENWEBUI_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${DIM_OAUTH_SECRET}|$DIM_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${PLANKA_OAUTH_SECRET}|$PLANKA_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${VAULTWARDEN_OAUTH_SECRET}|$VAULTWARDEN_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${MASTODON_OIDC_SECRET}|$MASTODON_OIDC_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${BOOKSTACK_OAUTH_SECRET}|$BOOKSTACK_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${FORGEJO_OAUTH_SECRET}|$FORGEJO_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${MATRIX_OAUTH_SECRET}|$MATRIX_OAUTH_SECRET|g" "$TEMP_CONFIG" || true

    # Use the processed config (set variable for later use, don't export as env var)
    AUTHELIA_CONFIG_FILE="$TEMP_CONFIG"
fi

# Generate OIDC RSA signing key if it doesn't exist
if [ ! -f /config/oidc_rsa.pem ] && [ -w /config ]; then
    echo "Generating OIDC RSA signing key..."
    openssl genrsa -out /config/oidc_rsa.pem 4096
    chmod 600 /config/oidc_rsa.pem
    echo "OIDC RSA key generated"
fi

# Run authelia with the processed config
# The command args from docker-compose will be added after our config
if [ -n "$AUTHELIA_CONFIG_FILE" ]; then
    # Remove any --config arguments from $@ since we're providing our own
    FILTERED_ARGS=""
    SKIP_NEXT=false
    for arg in "$@"; do
        if [ "$SKIP_NEXT" = "true" ]; then
            SKIP_NEXT=false
            continue
        fi
        if [ "$arg" = "--config" ]; then
            SKIP_NEXT=true
            continue
        fi
        FILTERED_ARGS="$FILTERED_ARGS $arg"
    done
    exec authelia --config "$AUTHELIA_CONFIG_FILE" $FILTERED_ARGS
else
    exec authelia "$@"
fi
