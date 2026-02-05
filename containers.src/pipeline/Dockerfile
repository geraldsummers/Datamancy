FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    p7zip-full \
    man-db \
    manpages \
    manpages-dev \
    gosu \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r datamancer && useradd -r -g datamancer datamancer

WORKDIR /app

COPY kotlin.src/pipeline/build/libs/pipeline-1.0.0-all.jar /app/pipeline.jar

RUN mkdir -p /app/config && chown -R datamancer:datamancer /app

RUN echo '#!/bin/bash\n\
set -e\n\
if [ -d /data ]; then\n\
  echo "Fixing /data permissions..."\n\
  chown -R datamancer:datamancer /data || echo "Warning: Could not change /data ownership"\n\
fi\n\
if [ -f /shared-credentials/bookstack-api-token.env ]; then\n\
  echo "[Pipeline] Loading BookStack API credentials from shared volume..."\n\
  source /shared-credentials/bookstack-api-token.env\n\
  export BOOKSTACK_TOKEN_ID BOOKSTACK_TOKEN_SECRET\n\
  export BOOKSTACK_API_TOKEN_ID BOOKSTACK_API_TOKEN_SECRET\n\
  echo "[Pipeline] ✓ BookStack credentials loaded and exported"\n\
elif [ -z "$BOOKSTACK_TOKEN_ID" ] || [ -z "$BOOKSTACK_TOKEN_SECRET" ]; then\n\
  echo "[Pipeline] WARNING: BookStack credentials not found in shared volume or env"\n\
  echo "[Pipeline] BookStack integration will be disabled"\n\
fi\n\
exec gosu datamancer "$@"\n\
' > /entrypoint.sh && chmod +x /entrypoint.sh

EXPOSE 8090

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "/app/pipeline.jar"]
