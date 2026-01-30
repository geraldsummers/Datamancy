# Integration Test Runner Container
# Runs all integration/e2e tests from inside Docker network

FROM eclipse-temurin:21-jre-alpine

# Install test utilities and Docker CLI for labware tests
RUN apk add --no-cache bash curl jq docker-cli

# Create app user and add to docker group for socket access
RUN addgroup -S datamancer && adduser -S datamancer -G datamancer \
    && addgroup datamancer docker 2>/dev/null || true

WORKDIR /app

# Copy pre-built shadow JAR from Gradle
COPY src/test-runner/build/libs/test-runner.jar /app/test-runner.jar

# Set ownership
RUN chown -R datamancer:datamancer /app

USER datamancer

# Run test-runner
ENTRYPOINT ["java", "-jar", "/app/test-runner.jar", "--env", "container", "--suite"]
CMD ["all"]
