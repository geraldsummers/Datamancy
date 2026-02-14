#!/bin/sh
set -e

# Start D-Bus daemon (required for Avahi)
mkdir -p /var/run/dbus
dbus-daemon --system --fork

# Start Avahi daemon for mDNS
avahi-daemon --daemonize --no-chroot

# Clean up any existing socket
rm -f /var/run/docker.sock

# Establish SSH tunnel to isolated Docker VM
exec ssh -o StrictHostKeyChecking=accept-new \
         -N \
         -L /var/run/docker.sock:/var/run/docker.sock \
         ${ISOLATED_DOCKER_VM_HOST}
