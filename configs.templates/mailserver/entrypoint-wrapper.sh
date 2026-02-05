#!/bin/bash
set -euo pipefail
if [[ -f /etc/dovecot/dovecot-ldap.conf.ext.template ]]; then
    sed "s|\${LDAP_BIND_PW}|${LDAP_BIND_PW}|g" \
        /etc/dovecot/dovecot-ldap.conf.ext.template > /etc/dovecot/dovecot-ldap.conf.ext
    chmod 644 /etc/dovecot/dovecot-ldap.conf.ext
fi
/bin/bash /tmp/docker-mailserver/find-certs.sh
