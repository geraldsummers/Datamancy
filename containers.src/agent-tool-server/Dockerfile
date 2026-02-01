# Runtime stage - expects pre-built JAR
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TOOLSERVER_PORT=8081 \
    TOOLSERVER_ALLOW_CAPS=host.shell.read,host.docker.inspect,host.docker.write

# Install minimal tools for healthcheck (wget), docker CLI, openssh-client for ssh-keygen, and create non-root user
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget ca-certificates docker.io openssh-client \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -g root appuser \
    && chown -R appuser:root /app \
    && usermod -aG docker appuser

# App jar
COPY kotlin.src/agent-tool-server/build/libs/agent-tool-server.jar /app/app.jar

EXPOSE 8081
# Run as root to access host Docker socket (mounted at /var/run/docker.sock.host)
# USER appuser
CMD ["sh", "-lc", "exec java -jar /app/app.jar"]
