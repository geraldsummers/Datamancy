FROM ghcr.io/home-assistant/home-assistant:stable

# Install HACS (Home Assistant Community Store) and hass-auth-header
# HACS is required to easily install custom components like hass-auth-header
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        wget \
        unzip \
    ; \
    rm -rf /var/lib/apt/lists/*; \
    # Download and install HACS
    wget -q -O /tmp/hacs.zip https://github.com/hacs/integration/releases/latest/download/hacs.zip; \
    mkdir -p /config/custom_components; \
    unzip -q /tmp/hacs.zip -d /config/custom_components/hacs; \
    rm /tmp/hacs.zip; \
    # Download and install hass-auth-header
    wget -q -O /tmp/auth-header.zip https://github.com/BeryJu/hass-auth-header/archive/refs/heads/master.zip; \
    unzip -q /tmp/auth-header.zip -d /tmp; \
    mv /tmp/hass-auth-header-master/custom_components/auth_header /config/custom_components/; \
    rm -rf /tmp/auth-header.zip /tmp/hass-auth-header-master; \
    # Set proper permissions
    chown -R root:root /config/custom_components; \
    chmod -R 755 /config/custom_components

# Copy configuration
COPY configuration.yaml /config/configuration.yaml
