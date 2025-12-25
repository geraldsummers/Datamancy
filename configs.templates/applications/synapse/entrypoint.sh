#!/bin/bash
set -e

# Generate log config in /tmp since /data might not be writable
LOG_CONFIG="/tmp/${SYNAPSE_SERVER_NAME}.log.config"
LOG_LEVEL="${SYNAPSE_LOG_LEVEL:-WARNING}"

echo "Generating log config at $LOG_CONFIG with level $LOG_LEVEL"
cat > "$LOG_CONFIG" << EOF
version: 1

formatters:
  precise:
    format: '%(asctime)s - %(name)s - %(lineno)d - %(levelname)s - %(request)s - %(message)s'

handlers:
  console:
    class: logging.StreamHandler
    formatter: precise

loggers:
  synapse.storage.databases.main.event_push_actions:
    level: $LOG_LEVEL
  synapse.util.caches.lrucache:
    level: $LOG_LEVEL
  synapse.util.task_scheduler:
    level: $LOG_LEVEL
  synapse.storage.background_updates:
    level: $LOG_LEVEL
  synapse.storage.SQL:
    level: $LOG_LEVEL

root:
  level: $LOG_LEVEL
  handlers: [console]

disable_existing_loggers: false
EOF

# Start Synapse
exec python -m synapse.app.homeserver --config-path=/data/homeserver.yaml
