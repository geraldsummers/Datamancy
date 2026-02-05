FROM eclipse-temurin:21-jre

# Install documentation packages, tools, and gosu for user switching
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    p7zip-full \
    man-db \
    manpages \
    manpages-dev \
    gosu \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r datamancer && useradd -r -g datamancer datamancer

WORKDIR /app

# Copy JAR
COPY kotlin.src/pipeline/build/libs/pipeline.jar /app/pipeline.jar

# Create config directory
RUN mkdir -p /app/config && chown -R datamancer:datamancer /app

# Create entrypoint script to fix /data permissions and load credentials
RUN echo '#!/bin/bash\n\
set -e\n\
# Fix /data directory permissions if mounted\n\
if [ -d /data ]; then\n\
  echo "Fixing /data permissions..."\n\
  chown -R datamancer:datamancer /data || echo "Warning: Could not change /data ownership"\n\
fi\n\
# Load BookStack API credentials from shared volume if available\n\
if [ -f /shared-credentials/bookstack-api-token.env ]; then\n\
  echo "[Pipeline] Loading BookStack API credentials from shared volume..."\n\
  source /shared-credentials/bookstack-api-token.env\n\
  # Export the variables so they persist through gosu\n\
  export BOOKSTACK_TOKEN_ID BOOKSTACK_TOKEN_SECRET\n\
  export BOOKSTACK_API_TOKEN_ID BOOKSTACK_API_TOKEN_SECRET\n\
  echo "[Pipeline] ✓ BookStack credentials loaded and exported"\n\
elif [ -z "$BOOKSTACK_TOKEN_ID" ] || [ -z "$BOOKSTACK_TOKEN_SECRET" ]; then\n\
  echo "[Pipeline] WARNING: BookStack credentials not found in shared volume or env"\n\
  echo "[Pipeline] BookStack integration will be disabled"\n\
fi\n\
# Drop to datamancer user and run app\n\
exec gosu datamancer "$@"\n\
' > /entrypoint.sh && chmod +x /entrypoint.sh

EXPOSE 8090

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "/app/pipeline.jar"]
