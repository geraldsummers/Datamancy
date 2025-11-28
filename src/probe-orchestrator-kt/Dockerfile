# Build stage
FROM gradle:8.5-jdk21 AS builder

WORKDIR /build
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN gradle clean shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /build/build/libs/probe-orchestrator-kt.jar app.jar

EXPOSE 8089

CMD ["java", "-jar", "app.jar"]
