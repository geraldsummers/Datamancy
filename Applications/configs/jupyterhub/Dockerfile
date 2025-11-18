FROM jupyterhub/jupyterhub:latest

# Install oauthenticator, dockerspawner, and jupyterlab
RUN pip install --no-cache-dir oauthenticator dockerspawner jupyterlab certifi

# Install ca-certificates for custom CA support
USER root
RUN apt-get update && \
    apt-get install -y ca-certificates && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy and install custom CA certificate into Python's certifi bundle
COPY caddy-ca.crt /tmp/caddy-ca.crt
RUN cat /tmp/caddy-ca.crt >> /srv/venv/lib/python3.12/site-packages/certifi/cacert.pem && \
    cat /tmp/caddy-ca.crt >> /etc/ssl/certs/ca-certificates.crt && \
    update-ca-certificates

# Create docker group for socket access and add root user to it
RUN groupadd -g 984 docker || true && usermod -aG docker root
