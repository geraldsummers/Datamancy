FROM eclipse-temurin:21-jre-jammy

# Install base utilities, Docker, SSH, Node.js, and gosu for privilege dropping.
RUN apt-get update && apt-get install -y \
    bash \
    curl \
    jq \
    ca-certificates \
    gnupg \
    lsb-release \
    openssh-client \
    gosu \
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
    echo '{"insecure-registries": ["192.168.0.11:5000", "registry:5000", "isolated-registry:5000"], "dns": ["127.0.0.11"]}' > /etc/docker/daemon.json

WORKDIR /app

COPY tests.kotlin/test-runner/build/libs/test-runner-*-all.jar /app/test-runner.jar
COPY tests.containers/test-runner/playwright-tests /app/playwright-tests
COPY tests.containers/test-runner/container-entrypoint.sh /container-entrypoint.sh

RUN chown -R testing_container_user:testing_container_user /app && \
    mkdir -p /app/test-results && \
    chown -R testing_container_user:testing_container_user /app/test-results && \
    mkdir -p /home/testing_container_user/.docker/buildx && \
    touch /home/testing_container_user/.docker/config.json && \
    chown -R testing_container_user:testing_container_user /home/testing_container_user/.docker && \
    chmod -R 755 /home/testing_container_user/.docker && \
    chmod +x /container-entrypoint.sh

ENV DOCKER_CONFIG=/home/testing_container_user/.docker
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

# Install npm dependencies as testing_container_user.
USER testing_container_user
WORKDIR /app/playwright-tests
RUN npm ci

# Install Playwright system dependencies and a shared browser cache once.
USER root
RUN mkdir -p "$PLAYWRIGHT_BROWSERS_PATH" && \
    npx playwright install --with-deps chromium && \
    chmod -R a+rX "$PLAYWRIGHT_BROWSERS_PATH"

WORKDIR /app
ENTRYPOINT ["/container-entrypoint.sh"]
CMD ["suite"]
