#!/bin/bash
# Mailserver cert finder - checks multiple locations for Caddy certs
# Works with both local_certs (development) and ACME (production)

DOMAIN="${DOMAIN:-example.com}"
MAIL_DOMAIN="mail.${DOMAIN}"

# Possible cert locations (in priority order)
CERT_LOCATIONS=(
    # Let's Encrypt / ZeroSSL (production)
    "/caddy-certs/caddy/certificates/acme.zerossl.com-v2-dv90/${MAIL_DOMAIN}/${MAIL_DOMAIN}.crt"
    "/caddy-certs/caddy/certificates/acme-v02.api.letsencrypt.org-directory/${MAIL_DOMAIN}/${MAIL_DOMAIN}.crt"

    # Caddy local certs (development/testing)
    "/caddy-certs/caddy/certificates/local/${MAIL_DOMAIN}/${MAIL_DOMAIN}.crt"
)

KEY_LOCATIONS=(
    # Let's Encrypt / ZeroSSL (production)
    "/caddy-certs/caddy/certificates/acme.zerossl.com-v2-dv90/${MAIL_DOMAIN}/${MAIL_DOMAIN}.key"
    "/caddy-certs/caddy/certificates/acme-v02.api.letsencrypt.org-directory/${MAIL_DOMAIN}/${MAIL_DOMAIN}.key"

    # Caddy local certs (development/testing)
    "/caddy-certs/caddy/certificates/local/${MAIL_DOMAIN}/${MAIL_DOMAIN}.key"
)

# Find first existing cert
for cert in "${CERT_LOCATIONS[@]}"; do
    if [ -f "$cert" ]; then
        export SSL_CERT_PATH="$cert"
        echo "[mailserver] Found certificate: $cert"
        break
    fi
done

# Find first existing key
for key in "${KEY_LOCATIONS[@]}"; do
    if [ -f "$key" ]; then
        export SSL_KEY_PATH="$key"
        echo "[mailserver] Found key: $key"
        break
    fi
done

# Verify both were found
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

# Start the actual mailserver with found cert paths
exec /usr/bin/dumb-init /usr/local/bin/start-mailserver.sh
