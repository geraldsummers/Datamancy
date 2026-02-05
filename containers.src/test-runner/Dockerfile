# Integration Test Runner Container
# Runs all integration/e2e tests from inside Docker network

FROM eclipse-temurin:21-jre-jammy

# Install prerequisites
RUN apt-get update && apt-get install -y \
    bash \
    curl \
    jq \
    ca-certificates \
    gnupg \
    lsb-release \
    && rm -rf /var/lib/apt/lists/*

# Install Docker from official Docker repository
RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc && \
    chmod a+r /etc/apt/keyrings/docker.asc && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
    tee /etc/apt/sources.list.d/docker.list > /dev/null && \
    apt-get update && \
    apt-get install -y docker-ce-cli docker-compose-plugin && \
    rm -rf /var/lib/apt/lists/*

# Verify docker compose plugin is available
RUN docker compose version

# Create app user and add to docker group for socket access
RUN groupadd -r datamancer && useradd -r -g datamancer datamancer && \
    groupadd -f docker && usermod -aG docker datamancer

# Configure Docker daemon to allow insecure registry access
# This is needed for CI/CD tests that push to HTTP-only registry
RUN mkdir -p /etc/docker && \
    echo '{"insecure-registries": ["192.168.0.11:5000", "registry:5000"]}' > /etc/docker/daemon.json

WORKDIR /app

# Copy pre-built shadow JAR from Gradle
COPY kotlin.src/test-runner/build/libs/test-runner-1.0-SNAPSHOT-all.jar /app/test-runner.jar

# Set ownership
RUN chown -R datamancer:datamancer /app

USER datamancer

# Run test-runner
ENTRYPOINT ["java", "-jar", "/app/test-runner.jar", "--env", "container", "--suite"]
CMD ["all"]
