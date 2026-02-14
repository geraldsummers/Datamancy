#!/bin/sh
set -e

# Start D-Bus daemon (required for Avahi)
mkdir -p /var/run/dbus
rm -f /var/run/dbus/dbus.pid /var/run/dbus/system_bus_socket
dbus-daemon --system --fork

# Start Avahi daemon for mDNS
avahi-daemon --daemonize --no-chroot

# Wait for Avahi to be ready
sleep 2

# Clean up any existing socket
rm -f /var/run/docker.sock

# Resolve .local hostname if needed
HOST="${ISOLATED_DOCKER_VM_HOST}"
if echo "$HOST" | grep -q '\.local'; then
    echo "Resolving mDNS hostname: $HOST"
    HOSTNAME_PART=$(echo "$HOST" | cut -d'@' -f2)
    USER_PART=$(echo "$HOST" | cut -d'@' -f1)

    # Try to resolve with avahi-resolve
    RESOLVED_IP=$(avahi-resolve -4 -n "$HOSTNAME_PART" 2>/dev/null | awk '{print $2}' || echo "")

    if [ -n "$RESOLVED_IP" ]; then
        echo "Resolved $HOSTNAME_PART to $RESOLVED_IP"
        HOST="${USER_PART}@${RESOLVED_IP}"
    else
        echo "Warning: Could not resolve $HOSTNAME_PART, will try hostname as-is"
    fi
fi

# Establish SSH tunnel to isolated Docker VM with retry logic
echo "Connecting to ${HOST}..."
while true; do
    ssh -o StrictHostKeyChecking=accept-new \
        -o ServerAliveInterval=60 \
        -o ServerAliveCountMax=3 \
        -N \
        -L /var/run/docker.sock:/var/run/docker.sock \
        ${HOST}

    echo "SSH connection lost. Retrying in 5 seconds..."
    sleep 5
done
