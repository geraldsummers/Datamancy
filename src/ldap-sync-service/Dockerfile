FROM gradle:8.11.1-jdk21 AS builder

WORKDIR /build
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN gradle :ldap-sync-service:shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy

# Install docker CLI for Mailu sync plugin
RUN apt-get update && \
    apt-get install -y docker.io && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /build/src/ldap-sync-service/build/libs/ldap-sync-service-*.jar /app/ldap-sync-service.jar

# Run as non-root user (but needs docker socket access)
RUN groupadd -g 1000 syncservice && \
    useradd -u 1000 -g syncservice -s /bin/bash syncservice

USER syncservice

ENTRYPOINT ["java", "-jar", "/app/ldap-sync-service.jar"]
CMD ["sync"]
