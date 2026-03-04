FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    gosu \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r pipeline && useradd -r -g pipeline pipeline

WORKDIR /app

COPY stack.kotlin/embedding-worker/build/libs/embedding-worker-1.0.0-all.jar /app/embedding-worker.jar

RUN mkdir -p /app/config && chown -R pipeline:pipeline /app

RUN echo '#!/bin/bash\n\
set -e\n\
exec gosu pipeline "$@"\n\
' > /entrypoint.sh && chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "/app/embedding-worker.jar"]
