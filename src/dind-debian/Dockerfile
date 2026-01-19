# Debian-based Docker-in-Docker image
FROM debian:bookworm-slim

# Install Docker CLI and daemon
RUN apt-get update && \
    apt-get install -y \
        ca-certificates \
        curl \
        gnupg \
        iptables \
        procps \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian bookworm stable" > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y \
        docker-ce \
        docker-ce-cli \
        containerd.io \
        docker-buildx-plugin \
        docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*

# Set up volume for Docker storage
VOLUME /var/lib/docker

# Expose Docker daemon port
EXPOSE 2375 2376

# Create directory for certificates
RUN mkdir -p /certs

# Use overlay2 storage driver by default
ENV DOCKER_TLS_CERTDIR=""

# Entry point script to start Docker daemon
COPY dockerd-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/dockerd-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/dockerd-entrypoint.sh"]
CMD []
