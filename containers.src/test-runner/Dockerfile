FROM eclipse-temurin:21-jre-jammy

# Install base utilities, Docker, and Node.js in a single layer to reduce build time
RUN apt-get update && apt-get install -y \
    bash \
    curl \
    jq \
    ca-certificates \
    gnupg \
    lsb-release \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
    tee /etc/apt/sources.list.d/docker.list > /dev/null \
    && apt-get update \
    && apt-get install -y docker-ce-cli docker-compose-plugin \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

RUN docker compose version

RUN groupadd -g 1000 testing_container_user && useradd -u 1000 -g testing_container_user testing_container_user -m && \
    groupadd -f docker && usermod -aG docker testing_container_user

RUN mkdir -p /etc/docker && \
    echo '{"insecure-registries": ["192.168.0.11:5000", "registry:5000"]}' > /etc/docker/daemon.json

WORKDIR /app

COPY kotlin.src/test-runner/build/libs/test-runner-1.0-SNAPSHOT-all.jar /app/test-runner.jar
COPY containers.src/test-runner/playwright-tests /app/playwright-tests

RUN chown -R testing_container_user:testing_container_user /app && \
    mkdir -p /app/test-results && \
    chown -R testing_container_user:testing_container_user /app/test-results && \
    mkdir -p /home/testing_container_user/.docker/buildx && \
    touch /home/testing_container_user/.docker/config.json && \
    chown -R testing_container_user:testing_container_user /home/testing_container_user/.docker && \
    chmod -R 755 /home/testing_container_user/.docker

ENV DOCKER_CONFIG=/home/testing_container_user/.docker

# Switch to testing_container_user BEFORE installing Playwright
USER testing_container_user

# Install Playwright dependencies and browsers as testing_container_user
WORKDIR /app/playwright-tests
RUN npm ci && npx playwright install --with-deps chromium firefox

WORKDIR /app

ENTRYPOINT ["java", "-jar", "/app/test-runner.jar", "--env", "container", "--suite"]
CMD ["all"]
