#!/bin/bash
set -e

# Generate log config in /tmp since /data might not be writable
LOG_CONFIG="/tmp/${SYNAPSE_SERVER_NAME}.log.config"

echo "Generating log config at $LOG_CONFIG"
cat > "$LOG_CONFIG" << 'EOF'
version: 1

formatters:
  precise:
    format: '%(asctime)s - %(name)s - %(lineno)d - %(levelname)s - %(request)s - %(message)s'

handlers:
  console:
    class: logging.StreamHandler
    formatter: precise

loggers:
  synapse:
    level: INFO
  synapse.storage.SQL:
    level: INFO

root:
  level: INFO
  handlers: [console]
EOF

# Start Synapse
exec python -m synapse.app.homeserver --config-path=/data/homeserver.yaml
