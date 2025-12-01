# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
# Copy gradle wrapper and configuration
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
# Copy vllm-router source
COPY src/vllm-router ./src/vllm-router
# Build fat jar
RUN ./gradlew --no-daemon :vllm-router:shadowJar

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/src/vllm-router/build/libs/*-all.jar app.jar

EXPOSE 8010

ENV PORT=8010

CMD ["java", "-jar", "app.jar"]
