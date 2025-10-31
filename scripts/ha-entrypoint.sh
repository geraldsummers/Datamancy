#!/bin/sh
# Home Assistant entrypoint with onboarding bypass and user creation
set -e

# Run onboarding bypass
/init-homeassistant.sh

# Start user creation in background (will wait for auth file)
(
  python3 /init-homeassistant-user.py || echo "Warning: User creation failed"
) &

# Execute Home Assistant as PID 1
exec /init
