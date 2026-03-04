#!/bin/bash
set -e
echo "Waiting for config directory..."
while [ ! -d /config ]; do
    sleep 1
done
mkdir -p /config/plugins/configurations
mkdir -p /config/data
if [ -f /config-templates/SSO-Auth.xml ]; then
    echo "Copying SSO-Auth configuration..."
    cp -f /config-templates/SSO-Auth.xml /config/plugins/configurations/SSO-Auth.xml
    echo "SSO-Auth configuration template copied"
fi
echo "Starting Jellyfin..."
exec /jellyfin/jellyfin \
    --datadir /config \
    --cachedir /cache \
    --ffmpeg /usr/lib/jellyfin-ffmpeg/ffmpeg
