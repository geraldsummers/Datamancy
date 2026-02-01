#!/bin/bash
# Entrypoint wrapper for docker-mailserver
# Substitutes environment variables in dovecot-ldap.conf.ext before starting

set -euo pipefail

# Substitute environment variables in dovecot LDAP config using sed
if [[ -f /etc/dovecot/dovecot-ldap.conf.ext.template ]]; then
    # Replace ${LDAP_BIND_PW} with the actual value
    sed "s|\${LDAP_BIND_PW}|${LDAP_BIND_PW}|g" \
        /etc/dovecot/dovecot-ldap.conf.ext.template > /etc/dovecot/dovecot-ldap.conf.ext
    chmod 644 /etc/dovecot/dovecot-ldap.conf.ext
fi

# Run the cert finder script first (original entrypoint behavior)
/bin/bash /tmp/docker-mailserver/find-certs.sh
