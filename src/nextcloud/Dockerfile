FROM nextcloud:31

# Install OIDC login app for OpenID Connect SSO
RUN apt-get update && apt-get install -y git unzip \
    && rm -rf /var/lib/apt/lists/* \
    && cd /usr/src/nextcloud/custom_apps \
    && curl -L https://github.com/pulsejet/nextcloud-oidc-login/releases/latest/download/oidc_login.tar.gz | tar -xz \
    && chown -R www-data:www-data /usr/src/nextcloud/custom_apps/oidc_login
