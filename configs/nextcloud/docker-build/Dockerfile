# Nextcloud with SSL certificate trust for internal HTTPS
FROM nextcloud:stable-apache

USER root

# Install ca-certificates
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

# Copy Caddy CA certificate
COPY caddy-ca.crt /usr/local/share/ca-certificates/caddy-ca.crt

# Copy runtime script that will add cert to bundle
COPY add-cert-runtime.sh /usr/local/bin/add-cert-runtime.sh
RUN chmod +x /usr/local/bin/add-cert-runtime.sh

# Split and add certs at build time (for reference)
RUN csplit -f /usr/local/share/ca-certificates/caddy-ca- -b "%02d.crt" -z /usr/local/share/ca-certificates/caddy-ca.crt "/-----BEGIN CERTIFICATE-----/" "{*}" && \
    update-ca-certificates

# Disable SSL verification for local development (INSECURE - only for dev/testing)
RUN echo "curl.cainfo=" >> /usr/local/etc/php/conf.d/zz-dev-insecure.ini && \
    echo "openssl.cafile=" >> /usr/local/etc/php/conf.d/zz-dev-insecure.ini

# Modify entrypoint to run our cert script first
RUN mv /entrypoint.sh /entrypoint-original.sh
COPY entrypoint-wrapper.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

