FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    p7zip-full \
    man-db \
    manpages \
    manpages-dev \
    gosu \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r pipeline && useradd -r -g pipeline pipeline

WORKDIR /app

COPY kotlin.src/knowledge-ingestion/build/libs/knowledge-ingestion-1.0.0-all.jar /app/knowledge-ingestion.jar

RUN mkdir -p /app/config && chown -R pipeline:pipeline /app

RUN echo '#!/bin/bash\n\
set -e\n\
if [ -d /data ]; then\n\
  echo "Fixing /data permissions..."\n\
  chown -R pipeline:pipeline /data || echo "Warning: Could not change /data ownership"\n\
fi\n\
exec gosu pipeline "$@"\n\
' > /entrypoint.sh && chmod +x /entrypoint.sh

EXPOSE 8090

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "/app/knowledge-ingestion.jar"]
