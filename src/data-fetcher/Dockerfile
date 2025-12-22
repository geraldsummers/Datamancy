# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
COPY . /workspace
RUN gradle :data-fetcher:shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install wget for healthchecks
RUN apk add --no-cache wget

# Copy the built JAR from builder
COPY --from=build /workspace/src/data-fetcher/build/libs/data-fetcher.jar /app/data-fetcher.jar

# Create directories
RUN mkdir -p /app/config /app/data

# Set permissions for non-root user
RUN addgroup -S datamancer && adduser -S datamancer -G datamancer && \
    chown -R datamancer:datamancer /app

USER datamancer

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8095/health || exit 1

EXPOSE 8095

ENTRYPOINT ["java", "-jar", "/app/data-fetcher.jar"]
