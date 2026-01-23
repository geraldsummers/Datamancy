FROM eclipse-temurin:21-jre-alpine

# Add wget for health checks
RUN apk add --no-cache wget

# Create non-root user
RUN addgroup -S datamancer && adduser -S datamancer -G datamancer

WORKDIR /app

# Copy JAR
COPY build/libs/pipeline.jar /app/pipeline.jar

# Create config directory
RUN mkdir -p /app/config && chown -R datamancer:datamancer /app

USER datamancer

EXPOSE 8090

CMD ["java", "-jar", "/app/pipeline.jar"]
