FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends     gosu     && rm -rf /var/lib/apt/lists/*

RUN groupadd -r pipeline && useradd -r -g pipeline pipeline

WORKDIR /app

COPY stack.kotlin/market-data-state-updater/build/libs/market-data-state-updater-1.0.0-all.jar /app/market-data-state-updater.jar

RUN mkdir -p /app/config && chown -R pipeline:pipeline /app

RUN echo '#!/bin/bash\nset -e\nexec gosu pipeline ""\n' > /entrypoint.sh && chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "/app/market-data-state-updater.jar"]
