FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    gosu \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r pipeline && useradd -r -g pipeline pipeline

WORKDIR /app

COPY kotlin.src/content-publisher/build/libs/content-publisher-1.0.0-all.jar /app/content-publisher.jar

RUN mkdir -p /app/config && chown -R pipeline:pipeline /app

RUN echo '#!/bin/bash\n\
set -e\n\
if [ -f /shared-credentials/bookstack-api-token.env ]; then\n\
  echo "[ContentPublisher] Loading BookStack API credentials from shared volume..."\n\
  source /shared-credentials/bookstack-api-token.env\n\
  export BOOKSTACK_TOKEN_ID BOOKSTACK_TOKEN_SECRET\n\
  export BOOKSTACK_API_TOKEN_ID BOOKSTACK_API_TOKEN_SECRET\n\
  echo "[ContentPublisher] ✓ BookStack credentials loaded and exported"\n\
elif [ -z "$BOOKSTACK_TOKEN_ID" ] || [ -z "$BOOKSTACK_TOKEN_SECRET" ]; then\n\
  echo "[ContentPublisher] WARNING: BookStack credentials not found in shared volume or env"\n\
  echo "[ContentPublisher] BookStack integration will be disabled"\n\
fi\n\
exec gosu pipeline "$@"\n\
' > /entrypoint.sh && chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "/app/content-publisher.jar"]
