#!/bin/sh
set -e

# Ensure config file exists and is readable
if [ ! -f "/opt/akkoma/config/prod.secret.exs" ]; then
  echo "ERROR: Config file not found at /opt/akkoma/config/prod.secret.exs"
  exit 1
fi

echo "Config file found at /opt/akkoma/config/prod.secret.exs"

# Call original entrypoint
exec /pleroma/docker-entrypoint.sh "$@"
