FROM eclipse-temurin:21-jre-alpine

# Install wget for healthcheck
RUN apk add --no-cache wget

# Create app user
RUN addgroup -S datamancer && adduser -S datamancer -G datamancer

WORKDIR /app

# Copy JAR (shadowJar strips version)
COPY build/libs/data-vector-indexer.jar /app/app.jar

# Set ownership
RUN chown -R datamancer:datamancer /app

USER datamancer

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 -O /dev/null http://localhost:8100/health || exit 1

# Run
EXPOSE 8100
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
