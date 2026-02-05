FROM matrixdotorg/synapse:v1.141.0

USER root

RUN pip install --no-cache-dir \
    matrix-synapse-ldap3==0.3.0 \
    authlib==1.3.0

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ldap-utils \
        libldap-common \
        libsasl2-2 \
        gettext-base && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

ENTRYPOINT ["/entrypoint.sh"]
CMD []
