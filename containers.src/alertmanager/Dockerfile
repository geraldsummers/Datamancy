FROM prom/alertmanager:v0.28.1

# Install gettext for envsubst (environment variable substitution)
# This allows the entrypoint script to safely substitute NTFY credentials
USER root
RUN apk add --no-cache gettext
USER nobody

# Entrypoint will be mounted from configs at runtime
