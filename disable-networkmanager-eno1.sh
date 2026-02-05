#!/bin/bash
set -euo pipefail
echo "=========================================="
echo "Disable NetworkManager on eno1"
echo "=========================================="
echo ""
if [ "$EUID" -ne 0 ]; then
    echo "ERROR: Must run as root"
    exit 1
fi
echo "[1/5] Current state:"
ip addr show eno1 | grep "inet " || true
echo ""
echo "[2/5] Removing NetworkManager connection on eno1..."
nmcli connection show | grep eno1 | awk '{print $1}' | while read conn; do
    echo "  Deleting connection: $conn"
    nmcli connection delete "$conn" || true
done
nmcli connection show | grep -i "wired" | grep -v "br-" | grep -v "docker" | awk '{print $1}' | while read conn; do
    if nmcli connection show "$conn" 2>/dev/null | grep -q "connection.interface-name.*eno1"; then
        echo "  Deleting wired connection: $conn"
        nmcli connection delete "$conn" || true
    fi
done
echo "[3/5] Telling NetworkManager to ignore eno1..."
cat > /etc/NetworkManager/conf.d/99-unmanage-eno1.conf << 'EOF'
[keyfile]
unmanaged-devices=interface-name:eno1
EOF
echo "  Created: /etc/NetworkManager/conf.d/99-unmanage-eno1.conf"
echo "[4/5] Reloading NetworkManager..."
systemctl reload NetworkManager
sleep 2
echo "[5/5] Verifying..."
echo ""
echo "NetworkManager device status:"
nmcli device status | grep eno1 || echo "  eno1 not found (good - unmanaged)"
echo ""
echo "IP addresses on eno1:"
ip addr show eno1 | grep "inet " || true
echo ""
echo "systemd-networkd status:"
networkctl status eno1 | grep -E "(State|Address)" | head -5
echo ""
echo "=========================================="
echo "✓ NetworkManager disabled for eno1"
echo "=========================================="
echo ""
echo "What happened:"
echo "  • Deleted NetworkManager connections for eno1"
echo "  • Added unmanaged-devices config"
echo "  • NetworkManager now ignores eno1"
echo "  • systemd-networkd has exclusive control"
echo ""
echo "Expected result:"
echo "  • Only ONE IP: 192.168.0.11/24"
echo "  • No more DHCP conflicts"
echo "  • Stable network configuration"
echo ""
echo "Note: If dual IP persists, run:"
echo "  sudo ip addr del 192.168.0.14/24 dev eno1"
echo "  sudo systemctl restart systemd-networkd"
echo ""
