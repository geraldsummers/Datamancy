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

RUN groupadd -g 1000 datamancer && useradd -u 1000 -g datamancer datamancer -m && \
    groupadd -f docker && usermod -aG docker datamancer

RUN mkdir -p /etc/docker && \
    echo '{"insecure-registries": ["192.168.0.11:5000", "registry:5000"]}' > /etc/docker/daemon.json

WORKDIR /app

COPY kotlin.src/test-runner/build/libs/test-runner-1.0-SNAPSHOT-all.jar /app/test-runner.jar
COPY containers.src/test-runner/playwright-tests /app/playwright-tests

RUN chown -R datamancer:datamancer /app && \
    mkdir -p /app/test-results && \
    chown -R datamancer:datamancer /app/test-results && \
    mkdir -p /home/datamancer/.docker/buildx && \
    touch /home/datamancer/.docker/config.json && \
    chown -R datamancer:datamancer /home/datamancer/.docker && \
    chmod -R 755 /home/datamancer/.docker

# Install Playwright dependencies and browsers
WORKDIR /app/playwright-tests
RUN npm ci && npx playwright install --with-deps chromium firefox

WORKDIR /app

ENV DOCKER_CONFIG=/home/datamancer/.docker

USER datamancer

ENTRYPOINT ["java", "-jar", "/app/test-runner.jar", "--env", "container", "--suite"]
CMD ["all"]
