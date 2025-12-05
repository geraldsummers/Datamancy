# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
# Copy gradle wrapper and configuration
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
# Copy rag-gateway source
COPY src/rag-gateway ./src/rag-gateway
# Build fat jar
RUN ./gradlew --no-daemon :rag-gateway:shadowJar

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/src/rag-gateway/build/libs/rag-gateway.jar app.jar

EXPOSE 8094

ENV PORT=8094

CMD ["java", "-jar", "app.jar"]
