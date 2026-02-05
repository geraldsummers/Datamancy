FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TOOLSERVER_PORT=8081 \
    TOOLSERVER_ALLOW_CAPS=host.shell.read,host.docker.inspect,host.docker.write

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget ca-certificates docker.io openssh-client \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -g root appuser \
    && chown -R appuser:root /app \
    && usermod -aG docker appuser

COPY kotlin.src/agent-tool-server/build/libs/agent-tool-server-1.0-SNAPSHOT-all.jar /app/app.jar

EXPOSE 8081
CMD ["sh", "-lc", "exec java -jar /app/app.jar"]
