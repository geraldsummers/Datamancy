#!/bin/sh
# Dex entrypoint: substitute BASE_DOMAIN and start Dex

set -e

# Default BASE_DOMAIN if not provided
export BASE_DOMAIN="${BASE_DOMAIN:-lab.localhost}"

echo "Generating Dex config with BASE_DOMAIN=${BASE_DOMAIN}"

# Substitute environment variables in config template using sed
sed "s/\${BASE_DOMAIN}/$BASE_DOMAIN/g" /etc/dex/config.template.yaml > /etc/dex/config.yaml

echo "Starting Dex..."
exec dex serve /etc/dex/config.yaml
