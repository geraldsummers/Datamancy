FROM eclipse-temurin:21-jre

# Install documentation packages and tools
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    p7zip-full \
    man-db \
    manpages \
    manpages-dev \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r datamancer && useradd -r -g datamancer datamancer

WORKDIR /app

# Copy JAR
COPY build/libs/pipeline.jar /app/pipeline.jar

# Create config directory
RUN mkdir -p /app/config && chown -R datamancer:datamancer /app

USER datamancer

EXPOSE 8090

CMD ["java", "-jar", "/app/pipeline.jar"]
