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
    sed -i "s|\${GRAFANA_OAUTH_SECRET}|$GRAFANA_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${PGADMIN_OAUTH_SECRET}|$PGADMIN_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${OPENWEBUI_OAUTH_SECRET}|$OPENWEBUI_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${NEXTCLOUD_OAUTH_SECRET}|$NEXTCLOUD_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${DIM_OAUTH_SECRET}|$DIM_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${PLANKA_OAUTH_SECRET}|$PLANKA_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${HOMEASSISTANT_OAUTH_SECRET}|$HOMEASSISTANT_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${JUPYTERHUB_OAUTH_SECRET}|$JUPYTERHUB_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${VAULTWARDEN_OAUTH_SECRET}|$VAULTWARDEN_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${MASTODON_OIDC_SECRET}|$MASTODON_OIDC_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${BOOKSTACK_OAUTH_SECRET}|$BOOKSTACK_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${FORGEJO_OAUTH_SECRET}|$FORGEJO_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${MATRIX_OAUTH_SECRET}|$MATRIX_OAUTH_SECRET|g" "$TEMP_CONFIG" || true
    sed -i "s|\${SOGO_OAUTH_SECRET}|$SOGO_OAUTH_SECRET|g" "$TEMP_CONFIG" || true

    # Use the processed config
    export AUTHELIA_CONFIG_FILE="$TEMP_CONFIG"
fi

# Decode the base64-encoded RSA private key if it's encoded
if [ -n "$AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY" ]; then
    # Check if it's base64 encoded (doesn't start with -----)
    if ! echo "$AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY" | grep -q "^-----BEGIN"; then
        # Decode base64
        export AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY=$(echo "$AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY" | base64 -d)
    fi
fi

# Ensure the database file exists with correct permissions (only if we can write)
if [ ! -f /config/db.sqlite3 ] && [ -w /config ]; then
    touch /config/db.sqlite3
    chmod 600 /config/db.sqlite3
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
