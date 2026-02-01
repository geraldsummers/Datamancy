#!/bin/sh
# Authelia entrypoint - configuration is pre-processed at build time
# This script just launches Authelia with the pre-baked config
set -e

# Run authelia with default args from docker-compose
exec authelia "$@"
