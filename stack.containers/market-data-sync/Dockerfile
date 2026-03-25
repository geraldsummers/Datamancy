FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends     gosu     && rm -rf /var/lib/apt/lists/*

RUN groupadd -r pipeline && useradd -r -g pipeline pipeline

WORKDIR /app

COPY stack.kotlin/market-data-sync/build/libs/market-data-sync-*-all.jar /app/market-data-sync.jar

RUN mkdir -p /app/config && chown -R pipeline:pipeline /app

RUN printf '%s\n' '#!/bin/bash' 'set -e' 'exec gosu pipeline "$@"' > /entrypoint.sh && chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "/app/market-data-sync.jar"]
