FROM jupyterhub/jupyterhub:latest

# Install jupyterlab, remote user authenticator, and notebook kernels
RUN pip install --no-cache-dir \
    jupyterlab \
    certifi \
    jupyterhub-remote-user-authenticator \
    notebook \
    ipykernel

# Install ca-certificates for custom CA support
USER root
RUN apt-get update && \
    apt-get install -y ca-certificates && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy and install custom CA certificate into Python's certifi bundle
COPY ./src/jupyterhub/caddy-ca.crt /tmp/caddy-ca.crt
RUN cat /tmp/caddy-ca.crt >> /srv/venv/lib/python3.12/site-packages/certifi/cacert.pem && \
    cat /tmp/caddy-ca.crt >> /etc/ssl/certs/ca-certificates.crt && \
    update-ca-certificates

# Create directory for user notebooks
RUN mkdir -p /home/jovyan/notebooks && \
    chmod 755 /home/jovyan && \
    chmod 777 /home/jovyan/notebooks
