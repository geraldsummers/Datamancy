# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
# Copy gradle wrapper and configuration
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
# Copy agent-tool-server source and build file
COPY src/agent-tool-server ./src/agent-tool-server
# Build fat jar with all plugins baked in
RUN ./gradlew --no-daemon :agent-tool-server:shadowJar

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TOOLSERVER_PORT=8081 \
    TOOLSERVER_ALLOW_CAPS=host.shell.read,host.docker.inspect,host.docker.write

# Install minimal tools for healthcheck (wget), docker CLI, and create non-root user
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget ca-certificates docker.io \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -g root appuser \
    && chown -R appuser:root /app \
    && usermod -aG docker appuser

# App jar
COPY --from=build /workspace/src/agent-tool-server/build/libs/*-all.jar /app/app.jar

EXPOSE 8081
USER appuser
CMD ["sh", "-lc", "exec java -jar /app/app.jar"]
