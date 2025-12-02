FROM matrixdotorg/synapse:v1.141.0

# Switch to root to install LDAP module
USER root

# Install LDAP authentication module
RUN pip install --no-cache-dir matrix-synapse-ldap3==0.3.0

# Install LDAP client libraries
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ldap-utils \
        libldap-2.5-0 \
        libsasl2-2 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Switch back to synapse user
USER 991

# Default CMD from base image will be used
