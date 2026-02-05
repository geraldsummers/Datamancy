#!/bin/bash
set -euo pipefail
echo "=========================================="
echo "Datamancy Network Configuration Fix"
echo "=========================================="
echo ""
if [ "$EUID" -ne 0 ]; then
    echo "ERROR: This script must be run as root"
    echo "Usage: sudo $0"
    exit 1
fi
INTERFACES_FILE="/etc/network/interfaces"
BACKUP_FILE="${INTERFACES_FILE}.backup"
if [ ! -f "$BACKUP_FILE" ]; then
    echo "[1/6] Backing up $INTERFACES_FILE..."
    cp "$INTERFACES_FILE" "$BACKUP_FILE"
    echo "      ✓ Backup created at $BACKUP_FILE"
else
    echo "[1/6] Backup already exists at $BACKUP_FILE"
fi
echo "[2/6] Checking $INTERFACES_FILE configuration..."
if grep -q "iface eno1 inet dhcp" "$INTERFACES_FILE" 2>/dev/null; then
    echo "      ! Found DHCP config for eno1 - will fix"
    cat > "$INTERFACES_FILE" << 'EOF'
source /etc/network/interfaces.d/*
auto lo
iface lo inet loopback
EOF
    echo "      ✓ Updated $INTERFACES_FILE"
else
    echo "      ✓ Already configured correctly"
fi
echo "[3/6] Releasing any DHCP leases on eno1..."
if ip addr show eno1 | grep -q "192.168.0.13"; then
    dhclient -r eno1 2>/dev/null || true
    ip addr del 192.168.0.13/24 dev eno1 2>/dev/null || true
    echo "      ✓ Released DHCP IP 192.168.0.13"
else
    echo "      ✓ No DHCP lease to release"
fi
echo "[4/6] Restarting systemd-networkd..."
systemctl restart systemd-networkd
sleep 2
echo "      ✓ Service restarted"
echo "[5/6] Configuring TCP keepalive settings..."
NEEDS_SYSCTL_UPDATE=0
if ! grep -q "^net.ipv4.tcp_keepalive_time = 300" /etc/sysctl.conf 2>/dev/null; then
    NEEDS_SYSCTL_UPDATE=1
fi
if [ $NEEDS_SYSCTL_UPDATE -eq 1 ]; then
    sed -i '/^net.ipv4.tcp_keepalive_time/d' /etc/sysctl.conf 2>/dev/null || true
    sed -i '/^net.ipv4.tcp_keepalive_intvl/d' /etc/sysctl.conf 2>/dev/null || true
    sed -i '/^net.ipv4.tcp_keepalive_probes/d' /etc/sysctl.conf 2>/dev/null || true
    cat >> /etc/sysctl.conf << 'EOF'
net.ipv4.tcp_keepalive_time = 300
net.ipv4.tcp_keepalive_intvl = 30
net.ipv4.tcp_keepalive_probes = 5
EOF
    echo "      ✓ Added TCP keepalive settings to /etc/sysctl.conf"
else
    echo "      ✓ TCP keepalive already configured"
fi
sysctl -w net.ipv4.tcp_keepalive_time=300 >/dev/null
sysctl -w net.ipv4.tcp_keepalive_intvl=30 >/dev/null
sysctl -w net.ipv4.tcp_keepalive_probes=5 >/dev/null
echo "      ✓ Applied TCP keepalive settings"
echo "[6/6] Verifying network configuration..."
echo ""
echo "Network Interface Status:"
ip addr show eno1 | grep "inet " | sed 's/^/    /'
echo ""
IP_COUNT=$(ip addr show eno1 | grep -c "inet 192.168.0" || true)
if [ "$IP_COUNT" -eq 1 ]; then
    echo "    ✓ Single IP address configured (GOOD)"
elif [ "$IP_COUNT" -gt 1 ]; then
    echo "    ⚠ WARNING: Multiple IP addresses still present"
    echo "    This may resolve after DHCP lease expires"
fi
echo ""
echo "TCP Keepalive Settings:"
echo "    keepalive_time:   $(sysctl -n net.ipv4.tcp_keepalive_time)s (should be 300)"
echo "    keepalive_intvl:  $(sysctl -n net.ipv4.tcp_keepalive_intvl)s (should be 30)"
echo "    keepalive_probes: $(sysctl -n net.ipv4.tcp_keepalive_probes) (should be 5)"
echo ""
echo "Testing connectivity..."
if ping -c 2 -W 2 192.168.0.1 >/dev/null 2>&1; then
    echo "    ✓ Gateway reachable (192.168.0.1)"
else
    echo "    ✗ Cannot reach gateway - check network!"
    exit 1
fi
if ping -c 2 -W 2 8.8.8.8 >/dev/null 2>&1; then
    echo "    ✓ Internet reachable (8.8.8.8)"
else
    echo "    ⚠ Cannot reach internet - check DNS/routing"
fi
echo ""
echo "=========================================="
echo "✓ Network configuration fix complete!"
echo "=========================================="
echo ""
echo "What was fixed:"
echo "  • Removed DHCP config from /etc/network/interfaces"
echo "  • Released conflicting DHCP IP (192.168.0.13)"
echo "  • Configured TCP keepalive: 5 min (was 2 hours)"
echo "  • systemd-networkd now has sole control of eno1"
echo ""
echo "Expected results:"
echo "  • No more SSH connection drops"
echo "  • Stable long-running downloads (Wikipedia, etc)"
echo "  • Single IP address: 192.168.0.11"
echo ""
echo "Note: If dual IP persists, it will clear when DHCP lease expires (~12h)"
echo "      You can speed this up by rebooting the server."
echo ""
