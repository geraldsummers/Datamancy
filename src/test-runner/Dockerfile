# Integration Test Runner Container
# Runs all integration/e2e tests from inside Docker network
# Build: docker build -f Dockerfile.test-runner -t datamancy/test-runner .

FROM eclipse-temurin:21-jdk-alpine

# Install dependencies
RUN apk add --no-cache \
    wget unzip bash curl jq python3 \
    gradle git

# Install Kotlin 2.0.21 (matches project version)
RUN wget -q https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip && \
    unzip -q kotlin-compiler-2.0.21.zip && \
    mv kotlinc /opt/kotlinc && \
    rm kotlin-compiler-2.0.21.zip && \
    ln -s /opt/kotlinc/bin/kotlin /usr/local/bin/kotlin && \
    ln -s /opt/kotlinc/bin/kotlinc /usr/local/bin/kotlinc && \
    ln -s /opt/kotlinc/bin/kotlin-main-kts /usr/local/bin/kotlin-main-kts

# Verify installation
RUN kotlin -version

WORKDIR /tests

# Copy gradle wrapper and build files for dependency resolution
COPY gradle /tests/gradle/
COPY gradlew /tests/
COPY build.gradle.kts settings.gradle.kts /tests/
COPY gradle.properties /tests/

# Copy version catalog
COPY gradle/libs.versions.toml /tests/gradle/

# Copy test-runner module
COPY src/test-runner /tests/src/test-runner/

# Pre-download dependencies (cache layer)
RUN ./gradlew :test-runner:dependencies --no-daemon || true

# Build test-runner
RUN ./gradlew :test-runner:installDist --no-daemon

# Entry point script
COPY scripts/test-runner/docker-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["all"]
