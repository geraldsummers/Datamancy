# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
# Copy gradle wrapper and configuration
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
# Copy playwright-controller source
COPY src/playwright-controller ./src/playwright-controller
# Build fat jar
RUN ./gradlew --no-daemon :playwright-controller:shadowJar

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install system dependencies and Playwright browser dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        wget \
        ca-certificates \
        python3 \
        python3-venv \
        libxcb-shm0 \
        libx11-xcb1 \
        libx11-6 \
        libxcb1 \
        libxext6 \
        libxrandr2 \
        libxcomposite1 \
        libxcursor1 \
        libxdamage1 \
        libxfixes3 \
        libxi6 \
        libgtk-3-0t64 \
        libpangocairo-1.0-0 \
        libpango-1.0-0 \
        libatk1.0-0t64 \
        libcairo-gobject2 \
        libcairo2 \
        libgdk-pixbuf-2.0-0 \
        libglib2.0-0t64 \
        libxrender1 \
        libasound2t64 \
        libdbus-1-3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -m -g root appuser \
    && chown -R appuser:root /app

# App jar
COPY --from=build /workspace/src/playwright-controller/build/libs/*-all.jar /app/app.jar

# Install Playwright and browsers as appuser
USER appuser
RUN python3 -m venv /app/venv \
    && /app/venv/bin/pip install playwright==1.49.0 \
    && /app/venv/bin/playwright install firefox

EXPOSE 3000
CMD ["java", "-jar", "/app/app.jar"]
