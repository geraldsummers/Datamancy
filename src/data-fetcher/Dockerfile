FROM gradle:8.11.1-jdk21 AS builder

WORKDIR /app

# Copy Gradle files first for better caching
COPY src/data-fetcher/build.gradle.kts .
COPY src/data-fetcher/settings.gradle.kts .
COPY src/data-fetcher/gradle.properties .

# Download dependencies
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src/data-fetcher/src ./src

# Build the application
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Install curl for healthchecks
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy the built JAR from builder
COPY --from=builder /app/build/libs/data-fetcher.jar /app/data-fetcher.jar

# Create directories
RUN mkdir -p /app/config /app/data

# Set permissions for non-root user
RUN useradd -m -u 1000 datamancer && \
    chown -R datamancer:datamancer /app

USER datamancer

EXPOSE 8095

ENTRYPOINT ["java", "-jar", "/app/data-fetcher.jar"]
