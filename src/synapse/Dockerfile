FROM matrixdotorg/synapse:v1.141.0

# Switch to root to install modules
USER root

# Install LDAP authentication module and OIDC dependencies
RUN pip install --no-cache-dir \
    matrix-synapse-ldap3==0.3.0 \
    authlib==1.3.0

# Install LDAP client libraries and envsubst (gettext-base)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ldap-utils \
        libldap-common \
        libsasl2-2 \
        gettext-base && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# User is specified in docker-compose.yml via DOCKER_USER_ID/DOCKER_GROUP_ID
# Don't hardcode USER here

# Override ENTRYPOINT to use our custom entrypoint
ENTRYPOINT ["/entrypoint.sh"]
CMD []
