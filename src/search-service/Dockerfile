FROM eclipse-temurin:21-jre-alpine

# Create app user
RUN addgroup -S datamancer && adduser -S datamancer -G datamancer

WORKDIR /app

# Copy JAR
COPY src/search-service/build/libs/search-service.jar /app/app.jar

# Set ownership
RUN chown -R datamancer:datamancer /app

USER datamancer

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8097/health || exit 1

# Run
EXPOSE 8097
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
