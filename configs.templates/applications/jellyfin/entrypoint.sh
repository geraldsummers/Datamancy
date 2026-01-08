#!/bin/bash
# Jellyfin entrypoint - IDEMPOTENT (runs setup on every container start)
set -e

# Wait for config directory to be available
echo "Waiting for config directory..."
while [ ! -d /config ]; do
    sleep 1
done

# Create necessary directories (idempotent - mkdir -p is safe)
mkdir -p /config/plugins/configurations
mkdir -p /config/data

# Copy SSO configuration if provided (idempotent - cp -f overwrites)
if [ -f /config-templates/SSO-Auth.xml ]; then
    echo "Copying SSO-Auth configuration..."
    cp -f /config-templates/SSO-Auth.xml /config/plugins/configurations/SSO-Auth.xml
    echo "SSO-Auth configuration template copied"
fi

# Start Jellyfin
echo "Starting Jellyfin..."
exec /jellyfin/jellyfin \
    --datadir /config \
    --cachedir /cache \
    --ffmpeg /usr/lib/jellyfin-ffmpeg/ffmpeg
