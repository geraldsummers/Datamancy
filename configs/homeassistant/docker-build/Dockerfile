# Custom Home Assistant with Authelia Header Authentication
# Platform-agnostic: Works with Authelia/LDAP via HTTP headers
# No external dependencies (GitHub, HACS, etc.)
FROM homeassistant/home-assistant:latest

WORKDIR /config

# Note: Home Assistant's command_line auth provider doesn't have access to HTTP headers
# The trusted_networks approach with forward_auth is the correct solution
# This image is kept minimal - authentication happens at the reverse proxy level

# Optional: Add any custom scripts or configurations here
