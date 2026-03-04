#!/usr/bin/with-contenv bash
CONF_DIR="/config/qBittorrent"
CONF_FILE="$CONF_DIR/qBittorrent.conf"
echo "[qbittorrent-init] Configuring subnet whitelist for auth bypass..."
mkdir -p "$CONF_DIR"
if [ ! -f "$CONF_FILE" ] || ! grep -q "AuthSubnetWhitelistEnabled" "$CONF_FILE"; then
    echo "[qbittorrent-init] Applying pre-configured settings..."
    if [ ! -f "$CONF_FILE" ]; then
        cat > "$CONF_FILE" << 'EOF'
[AutoRun]
enabled=false
program=
[BitTorrent]
Session\AddTorrentStopped=false
Session\DefaultSavePath=/downloads/
Session\Port=6881
Session\QueueingSystemEnabled=true
Session\SSL\Port=49582
Session\ShareLimitAction=Stop
Session\TempPath=/downloads/incomplete/
[LegalNotice]
Accepted=true
[Meta]
MigrationVersion=8
[Network]
PortForwardingEnabled=false
Proxy\HostnameLookupEnabled=false
Proxy\Profiles\BitTorrent=true
Proxy\Profiles\Misc=true
Proxy\Profiles\RSS=true
[Preferences]
Connection\PortRangeMin=6881
Connection\UPnP=false
Downloads\SavePath=/downloads/
Downloads\TempPath=/downloads/incomplete/
WebUI\Address=*
WebUI\ServerDomains=*
WebUI\AuthSubnetWhitelistEnabled=true
WebUI\AuthSubnetWhitelist=10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
WebUI\BypassLocalAuth=true
EOF
    else
        if ! grep -q "\[Preferences\]" "$CONF_FILE"; then
            echo "" >> "$CONF_FILE"
            echo "[Preferences]" >> "$CONF_FILE"
        fi
        if ! grep -q "AuthSubnetWhitelistEnabled" "$CONF_FILE"; then
            sed -i '/\[Preferences\]/a WebUI\\AuthSubnetWhitelistEnabled=true\nWebUI\\AuthSubnetWhitelist=10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16\nWebUI\\BypassLocalAuth=true' "$CONF_FILE"
        fi
    fi
    echo "[qbittorrent-init] Subnet whitelist configured successfully"
else
    echo "[qbittorrent-init] Subnet whitelist already configured"
fi
chown -R abc:abc "$CONF_DIR"
chmod 644 "$CONF_FILE"
echo "[qbittorrent-init] Configuration complete"
