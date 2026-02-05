#!/bin/bash
DOMAIN="${DOMAIN:-example.com}"
MAIL_DOMAIN="mail.${DOMAIN}"
CERT_LOCATIONS=(
    "/caddy-certs/caddy/certificates/acme.zerossl.com-v2-dv90/${MAIL_DOMAIN}/${MAIL_DOMAIN}.crt"
    "/caddy-certs/caddy/certificates/acme-v02.api.letsencrypt.org-directory/${MAIL_DOMAIN}/${MAIL_DOMAIN}.crt"
    "/caddy-certs/caddy/certificates/local/${MAIL_DOMAIN}/${MAIL_DOMAIN}.crt"
)
KEY_LOCATIONS=(
    "/caddy-certs/caddy/certificates/acme.zerossl.com-v2-dv90/${MAIL_DOMAIN}/${MAIL_DOMAIN}.key"
    "/caddy-certs/caddy/certificates/acme-v02.api.letsencrypt.org-directory/${MAIL_DOMAIN}/${MAIL_DOMAIN}.key"
    "/caddy-certs/caddy/certificates/local/${MAIL_DOMAIN}/${MAIL_DOMAIN}.key"
)
for cert in "${CERT_LOCATIONS[@]}"; do
    if [ -f "$cert" ]; then
        export SSL_CERT_PATH="$cert"
        echo "[mailserver] Found certificate: $cert"
        break
    fi
done
for key in "${KEY_LOCATIONS[@]}"; do
    if [ -f "$key" ]; then
        export SSL_KEY_PATH="$key"
        echo "[mailserver] Found key: $key"
        break
    fi
done
if [ -z "$SSL_CERT_PATH" ] || [ -z "$SSL_KEY_PATH" ]; then
    echo "[mailserver] ERROR: Could not find SSL certificate or key!"
    echo "[mailserver] Checked locations:"
    printf '%s\n' "${CERT_LOCATIONS[@]}"
    echo "[mailserver] Consider:"
    echo "  1. Wait for Caddy to generate certs (may take 30-60s)"
    echo "  2. Check Caddy logs: docker logs caddy"
    echo "  3. Verify mail.${DOMAIN} resolves to this server"
    exit 1
fi
echo "[mailserver] SSL configured: $SSL_CERT_PATH"
echo "SSL_CERT_PATH=$SSL_CERT_PATH" > /tmp/docker-mailserver/.ssl-env
echo "SSL_KEY_PATH=$SSL_KEY_PATH" >> /tmp/docker-mailserver/.ssl-env
export SSL_CERT_PATH
export SSL_KEY_PATH
env SSL_CERT_PATH="$SSL_CERT_PATH" SSL_KEY_PATH="$SSL_KEY_PATH" supervisord -c /etc/supervisor/supervisord.conf &
echo "[mailserver] Waiting for services to start..."
for i in {1..60}; do
    if supervisorctl status postfix | grep -q RUNNING && supervisorctl status dovecot | grep -q RUNNING; then
        echo "[mailserver] Services are running, setting up DKIM..."
        break
    fi
    if [ $i -eq 60 ]; then
        echo "[mailserver] WARNING: Services didn't start in time, DKIM setup skipped"
        wait
        exit 0
    fi
    sleep 1
done
(
    sleep 5
    /bin/bash /tmp/docker-mailserver/setup-dkim.sh 2>&1 | sed 's/^/[DKIM-setup] /'
) &
wait
