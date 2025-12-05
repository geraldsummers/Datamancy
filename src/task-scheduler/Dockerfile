# Build stage
FROM gradle:8.10.2-jdk21-alpine AS build
WORKDIR /workspace
# Copy gradle wrapper and configuration
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
# Copy task-scheduler source
COPY src/task-scheduler ./src/task-scheduler
# Build fat jar
RUN ./gradlew --no-daemon :task-scheduler:shadowJar

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/src/task-scheduler/build/libs/task-scheduler.jar app.jar

EXPOSE 8093

ENV PORT=8093

CMD ["java", "-jar", "app.jar"]
