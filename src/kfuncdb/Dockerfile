# Build stage
FROM gradle:8.10.2-jdk17-alpine AS build
WORKDIR /src
COPY . .
# Build fat jar
RUN gradle --no-daemon clean shadowJar

# Create minimal plugin jars that only contain llm-plugin.json for each plugin
RUN mkdir -p /tmp/hosttools-jar /tmp/ops-jar /tmp/browser-jar /tmp/llmcompletion-jar
COPY plugins/hosttools/llm-plugin.json /tmp/hosttools-jar/llm-plugin.json
COPY plugins/ops/llm-plugin.json /tmp/ops-jar/llm-plugin.json
COPY plugins/browser/llm-plugin.json /tmp/browser-jar/llm-plugin.json
COPY plugins/llmcompletion/llm-plugin.json /tmp/llmcompletion-jar/llm-plugin.json
RUN (cd /tmp/hosttools-jar && \
     printf "Manifest-Version: 1.0\n" > MANIFEST.MF && \
     jar --create --file /tmp/hosttools.jar -m MANIFEST.MF -C /tmp/hosttools-jar .)
RUN (cd /tmp/ops-jar && \
     printf "Manifest-Version: 1.0\n" > MANIFEST.MF && \
     jar --create --file /tmp/ops.jar -m MANIFEST.MF -C /tmp/ops-jar .)
RUN (cd /tmp/browser-jar && \
     printf "Manifest-Version: 1.0\n" > MANIFEST.MF && \
     jar --create --file /tmp/browser.jar -m MANIFEST.MF -C /tmp/browser-jar .)
RUN (cd /tmp/llmcompletion-jar && \
     printf "Manifest-Version: 1.0\n" > MANIFEST.MF && \
     jar --create --file /tmp/llmcompletion.jar -m MANIFEST.MF -C /tmp/llmcompletion-jar .)

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV KFUNCDB_PORT=8081 \
    KFUNCDB_PLUGINS_DIR=/app/plugins \
    KFUNCDB_ALLOW_CAPS=host.shell.read,host.docker.inspect

# Install minimal tools for healthcheck (wget) and create non-root user
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -g root appuser \
    && mkdir -p /app/plugins \
    && chown -R appuser:root /app

# App jar
COPY --from=build /src/build/libs/*-all.jar /app/app.jar
# Plugins directory and plugin jars
COPY --from=build /tmp/hosttools.jar /app/plugins/hosttools.jar
COPY --from=build /tmp/ops.jar /app/plugins/ops.jar
COPY --from=build /tmp/browser.jar /app/plugins/browser.jar
COPY --from=build /tmp/llmcompletion.jar /app/plugins/llmcompletion.jar

EXPOSE 8081
USER appuser
CMD ["sh", "-lc", "exec java -jar /app/app.jar"]
