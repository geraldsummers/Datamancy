# Build stage
FROM gradle:8.10.2-jdk17-alpine AS build
WORKDIR /workspace
# Copy root gradle files first
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
# Copy kfuncdb source
COPY src/kfuncdb ./src/kfuncdb
# Build fat jar with all plugins baked in
WORKDIR /workspace
RUN gradle --no-daemon :kfuncdb:shadowJar

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV KFUNCDB_PORT=8081 \
    KFUNCDB_ALLOW_CAPS=host.shell.read,host.docker.inspect,host.docker.write

# Install minimal tools for healthcheck (wget), docker CLI, and create non-root user
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget ca-certificates docker.io \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -g root appuser \
    && chown -R appuser:root /app \
    && usermod -aG docker appuser

# App jar
COPY --from=build /workspace/src/kfuncdb/build/libs/*-all.jar /app/app.jar

EXPOSE 8081
USER appuser
CMD ["sh", "-lc", "exec java -jar /app/app.jar"]
