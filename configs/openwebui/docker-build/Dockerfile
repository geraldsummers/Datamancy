# Open-WebUI with SSL certificate trust for internal HTTPS
FROM ghcr.io/open-webui/open-webui:main

# Install ca-certificates package
USER root
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

# Copy Caddy CA certificate at build time
COPY caddy-ca.crt /usr/local/share/ca-certificates/caddy-ca.crt

# Update CA bundle and append Caddy cert to certifi for Python
RUN update-ca-certificates && \
    certifi_path=$(python -c "import certifi; print(certifi.where())") && \
    echo "" >> "$certifi_path" && \
    cat /usr/local/share/ca-certificates/caddy-ca.crt >> "$certifi_path" && \
    echo "Caddy CA added to certifi bundle at build time"

# Copy custom entrypoint
COPY entrypoint_patch.sh /entrypoint_patch.sh
RUN chmod +x /entrypoint_patch.sh

USER root
ENTRYPOINT ["/entrypoint_patch.sh"]
