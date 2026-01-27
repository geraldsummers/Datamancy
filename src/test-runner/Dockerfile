# Integration Test Runner Container
# Runs all integration/e2e tests from inside Docker network

FROM eclipse-temurin:21-jre-alpine

# Install test utilities
RUN apk add --no-cache bash curl jq

# Create app user
RUN addgroup -S datamancer && adduser -S datamancer -G datamancer

WORKDIR /app

# Copy pre-built distribution from Gradle installDist
COPY src/test-runner/build/install/test-runner /app/

# Set ownership and make scripts executable
RUN chown -R datamancer:datamancer /app && \
    chmod +x /app/bin/test-runner

USER datamancer

# Run test-runner
ENTRYPOINT ["/app/bin/test-runner", "--env", "container", "--suite"]
CMD ["all"]
