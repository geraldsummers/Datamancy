# Build stage
FROM gradle:8.5-jdk21 AS builder

WORKDIR /build
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN gradle clean shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /build/build/libs/ktspeechgateway.jar app.jar

EXPOSE 8091

ENV PORT=8091

CMD ["java", "-jar", "app.jar"]
