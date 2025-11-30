# Build stage
FROM gradle:8.10.2-jdk17-alpine AS build
WORKDIR /src
COPY . .
# Build fat jar with all plugins baked in
RUN gradle --no-daemon clean shadowJar

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
COPY --from=build /src/build/libs/*-all.jar /app/app.jar

EXPOSE 8081
USER appuser
CMD ["sh", "-lc", "exec java -jar /app/app.jar"]
