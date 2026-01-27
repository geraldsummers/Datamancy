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
COPY build/libs/pipeline.jar /app/pipeline.jar

# Create config directory
RUN mkdir -p /app/config && chown -R datamancer:datamancer /app

# Create entrypoint script to fix /data permissions
RUN echo '#!/bin/bash\n\
set -e\n\
# Fix /data directory permissions if mounted\n\
if [ -d /data ]; then\n\
  echo "Fixing /data permissions..."\n\
  chown -R datamancer:datamancer /data || echo "Warning: Could not change /data ownership"\n\
fi\n\
# Drop to datamancer user and run app\n\
exec gosu datamancer "$@"\n\
' > /entrypoint.sh && chmod +x /entrypoint.sh

EXPOSE 8090

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "/app/pipeline.jar"]
