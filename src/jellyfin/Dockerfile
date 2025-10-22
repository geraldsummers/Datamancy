FROM lscr.io/linuxserver/jellyfin:latest

# Download and install SSO-Auth plugin
# Get the latest release from https://github.com/9p4/jellyfin-plugin-sso
RUN apt-get update && apt-get install -y curl unzip gettext-base \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /config/plugins/SSO-Auth /config/plugins/configurations \
    && curl -L https://github.com/9p4/jellyfin-plugin-sso/releases/download/v3.5.2.0/sso-authentication_3.5.2.0.zip -o /tmp/sso.zip \
    && unzip /tmp/sso.zip -d /config/plugins/SSO-Auth \
    && rm -rf /tmp/sso.zip

# Copy SSO configuration template
COPY sso-config.xml /tmp/sso-config.xml

# Copy custom entrypoint for env var substitution
COPY entrypoint.sh /custom-entrypoint.sh
RUN chmod +x /custom-entrypoint.sh

# Ensure proper permissions
RUN chown -R abc:abc /config/plugins

ENTRYPOINT ["/custom-entrypoint.sh"]
