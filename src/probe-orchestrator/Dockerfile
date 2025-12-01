# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
# Copy gradle wrapper and configuration
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
# Copy probe-orchestrator source
COPY src/probe-orchestrator ./src/probe-orchestrator
# Build fat jar
RUN ./gradlew --no-daemon :probe-orchestrator:shadowJar

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/src/probe-orchestrator/build/libs/probe-orchestrator-kt.jar app.jar

EXPOSE 8089

CMD ["java", "-jar", "app.jar"]
