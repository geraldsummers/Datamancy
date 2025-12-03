FROM gradle:8.11.1-jdk21 AS builder

WORKDIR /build
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN gradle :vm-provisioner:shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy

# Install libvirt client libraries and QEMU tools
RUN apt-get update && apt-get install -y \
    libvirt-clients \
    libvirt-daemon-system \
    qemu-utils \
    qemu-system-x86 \
    genisoimage \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /build/src/vm-provisioner/build/libs/vm-provisioner.jar /app/vm-provisioner.jar

# Create directories
RUN mkdir -p /app/ssh_keys /var/lib/libvirt/images

# Expose API port
EXPOSE 8092

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8092/healthz || exit 1

ENTRYPOINT ["java", "-jar", "/app/vm-provisioner.jar"]
