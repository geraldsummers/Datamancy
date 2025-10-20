FROM nextcloud:latest

# Install user_backend_remote app for forward auth (SSO via headers)
RUN apt-get update && apt-get install -y git \
    && rm -rf /var/lib/apt/lists/* \
    && git clone https://github.com/AlexHjelm/user_backend_remote.git /usr/src/nextcloud/custom_apps/user_backend_remote \
    && chown -R www-data:www-data /usr/src/nextcloud/custom_apps/user_backend_remote
