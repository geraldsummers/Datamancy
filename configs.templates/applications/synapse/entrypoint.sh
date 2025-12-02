#!/bin/bash
set -e

# Generate log config if it doesn't exist
LOG_CONFIG="/data/${SYNAPSE_SERVER_NAME}.log.config"

if [ ! -f "$LOG_CONFIG" ]; then
  echo "Generating log config at $LOG_CONFIG"
  cat > "$LOG_CONFIG" << 'EOF'
version: 1

formatters:
  precise:
    format: '%(asctime)s - %(name)s - %(lineno)d - %(levelname)s - %(request)s - %(message)s'

handlers:
  file:
    class: logging.handlers.TimedRotatingFileHandler
    formatter: precise
    filename: /homeserver.log
    when: midnight
    backupCount: 3
    encoding: utf8

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
  handlers: [file, console]
EOF
fi

# Start Synapse
exec python -m synapse.app.homeserver --config-path=/data/homeserver.yaml
