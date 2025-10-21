FROM lscr.io/linuxserver/jellyfin:latest

# Download and install SSO-Auth plugin
# Get the latest release from https://github.com/9p4/jellyfin-plugin-sso
RUN apt-get update && apt-get install -y curl unzip \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /config/plugins/SSO-Auth \
    && curl -L https://github.com/9p4/jellyfin-plugin-sso/releases/download/v3.5.2.0/sso-authentication_3.5.2.0.zip -o /tmp/sso.zip \
    && unzip /tmp/sso.zip -d /tmp/sso \
    && mv /tmp/sso/SSO-Auth.dll /config/plugins/SSO-Auth/ \
    && rm -rf /tmp/sso /tmp/sso.zip

# Copy SSO configuration
COPY sso-config.xml /config/plugins/configurations/SSO-Auth.xml

# Ensure proper permissions
RUN chown -R abc:abc /config/plugins
