#!/bin/bash
set -e

# Start Forgejo in the background
/bin/s6-svscan /etc/s6 &
FORGEJO_PID=$!

# Run initialization script (will only run once)
/usr/local/bin/init-datamancy-repo.sh &

# Wait for Forgejo process
wait $FORGEJO_PID
