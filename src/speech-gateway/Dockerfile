# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
# Copy gradle wrapper and configuration
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
# Copy speech-gateway source
COPY src/speech-gateway ./src/speech-gateway
# Build fat jar
RUN ./gradlew --no-daemon :speech-gateway:shadowJar

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/src/speech-gateway/build/libs/*-all.jar app.jar

EXPOSE 8091

ENV PORT=8091

CMD ["java", "-jar", "app.jar"]
