#!/bin/sh
# Update dnsmasq config with caddy's current IP

# Get caddy's IP from Docker's DNS
CADDY_IP=$(getent hosts caddy | awk '{print $1}' | head -1)

if [ -z "$CADDY_IP" ]; then
    echo "ERROR: Could not resolve caddy container"
    exit 1
fi

echo "Caddy IP: $CADDY_IP"

# Generate dnsmasq config
cat > /etc/dnsmasq.d/stack-local.conf <<EOF
# Auto-generated - points *.stack.local to caddy container
address=/stack.local/$CADDY_IP
address=/.stack.local/$CADDY_IP
EOF

echo "DNS config updated successfully"
