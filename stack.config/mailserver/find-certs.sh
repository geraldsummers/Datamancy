#!/bin/bash
set -euo pipefail

DOMAIN="${DOMAIN:-example.com}"
MAIL_DOMAIN="mail.${DOMAIN}"

# Caddy stores certs in /certs/certificates/ (named volume caddy_certs)
# Mailserver mounts this as /caddy-certs
CERT_KEY_PAIRS=(
    "/caddy-certs/certificates/acme-v02.api.letsencrypt.org-directory/${MAIL_DOMAIN}/${MAIL_DOMAIN}.crt|/caddy-certs/certificates/acme-v02.api.letsencrypt.org-directory/${MAIL_DOMAIN}/${MAIL_DOMAIN}.key"
    "/caddy-certs/certificates/acme.zerossl.com-v2-dv90/${MAIL_DOMAIN}/${MAIL_DOMAIN}.crt|/caddy-certs/certificates/acme.zerossl.com-v2-dv90/${MAIL_DOMAIN}/${MAIL_DOMAIN}.key"
)

find_cert_pair() {
    local pair cert key
    for pair in "${CERT_KEY_PAIRS[@]}"; do
        cert="${pair%%|*}"
        key="${pair##*|}"
        if [ -f "$cert" ] && [ -f "$key" ]; then
            SSL_CERT_PATH="$cert"
            SSL_KEY_PATH="$key"
            export SSL_CERT_PATH SSL_KEY_PATH
            echo "[mailserver] Found certificate/key pair: $cert"
            return 0
        fi
    done
    return 1
}

wait_seconds="${MAIL_CERT_WAIT_SECONDS:-180}"
for i in $(seq 1 "$wait_seconds"); do
    if find_cert_pair; then
        break
    fi
    if [ "$i" -eq 1 ]; then
        echo "[mailserver] Waiting for ACME certificate for ${MAIL_DOMAIN} from Caddy..."
    fi
    sleep 1
done

if [ -z "${SSL_CERT_PATH:-}" ] || [ -z "${SSL_KEY_PATH:-}" ]; then
    echo "[mailserver] ERROR: Could not find ACME SSL certificate/key for ${MAIL_DOMAIN}."
    echo "[mailserver] Checked pairs:"
    printf '  - %s\n' "${CERT_KEY_PAIRS[@]}"
    echo "[mailserver] Required fixes:"
    echo "  1. Ensure Caddy route exists for mail.${DOMAIN}"
    echo "  2. Verify DNS for mail.${DOMAIN} points to this server"
    echo "  3. Check Caddy logs: docker logs caddy"
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
