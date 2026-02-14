#!/bin/sh
set -e

# Start D-Bus daemon (required for Avahi)
mkdir -p /var/run/dbus
rm -f /var/run/dbus/dbus.pid /var/run/dbus/system_bus_socket
dbus-daemon --system --fork

# Start Avahi daemon for mDNS
avahi-daemon --daemonize --no-chroot

# Clean up any existing socket
rm -f /var/run/docker.sock

# Establish SSH tunnel to isolated Docker VM with retry logic
echo "Connecting to ${ISOLATED_DOCKER_VM_HOST}..."
while true; do
    ssh -o StrictHostKeyChecking=accept-new \
        -o ServerAliveInterval=60 \
        -o ServerAliveCountMax=3 \
        -N \
        -L /var/run/docker.sock:/var/run/docker.sock \
        ${ISOLATED_DOCKER_VM_HOST}

    echo "SSH connection lost. Retrying in 5 seconds..."
    sleep 5
done
