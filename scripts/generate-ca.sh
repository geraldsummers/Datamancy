#!/bin/sh
set -e

DOMAIN="${DOMAIN:-test.local}"
CERTS_DIR="/certs"
CA_KEY="$CERTS_DIR/ca.key"
CA_CERT="$CERTS_DIR/ca.crt"
WILDCARD_KEY="$CERTS_DIR/wildcard.$DOMAIN.key"
WILDCARD_CERT="$CERTS_DIR/wildcard.$DOMAIN.crt"
WILDCARD_CSR="$CERTS_DIR/wildcard.$DOMAIN.csr"

echo "==> Installing OpenSSL..."
apk add --no-cache openssl

# Check if CA already exists
if [ -f "$CA_CERT" ] && [ -f "$CA_KEY" ]; then
    echo "==> CA certificate already exists, skipping generation"
else
    echo "==> Generating CA certificate..."
    openssl req -x509 -nodes -new -sha256 -days 3650 -newkey rsa:4096 \
        -keyout "$CA_KEY" \
        -out "$CA_CERT" \
        -subj "/C=US/ST=State/L=City/O=Datamancy/OU=Testing/CN=Datamancy Root CA"

    echo "==> CA certificate generated successfully"
fi

# Check if wildcard cert already exists
if [ -f "$WILDCARD_CERT" ] && [ -f "$WILDCARD_KEY" ]; then
    echo "==> Wildcard certificate already exists, skipping generation"
else
    echo "==> Generating wildcard certificate for *.$DOMAIN..."

    # Generate private key
    openssl req -new -nodes -newkey rsa:2048 \
        -keyout "$WILDCARD_KEY" \
        -out "$WILDCARD_CSR" \
        -subj "/C=US/ST=State/L=City/O=Datamancy/OU=Testing/CN=*.$DOMAIN"

    # Create extensions file for SAN
    cat > "$CERTS_DIR/wildcard.ext" <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = *.$DOMAIN
DNS.2 = $DOMAIN
EOF

    # Sign certificate with CA
    openssl x509 -req -sha256 -days 365 \
        -in "$WILDCARD_CSR" \
        -CA "$CA_CERT" \
        -CAkey "$CA_KEY" \
        -CAcreateserial \
        -out "$WILDCARD_CERT" \
        -extfile "$CERTS_DIR/wildcard.ext"

    # Cleanup
    rm -f "$WILDCARD_CSR" "$CERTS_DIR/wildcard.ext" "$CERTS_DIR/ca.srl"

    echo "==> Wildcard certificate generated successfully"
fi

# Set permissions
chmod 644 "$CA_CERT" "$WILDCARD_CERT"
chmod 600 "$CA_KEY" "$WILDCARD_KEY"

echo "==> Certificate generation complete!"
echo "    CA Cert: $CA_CERT"
echo "    CA Key: $CA_KEY"
echo "    Wildcard Cert: $WILDCARD_CERT"
echo "    Wildcard Key: $WILDCARD_KEY"
echo ""
echo "==> To trust the CA on your host:"
echo "    Linux: sudo cp $CA_CERT /usr/local/share/ca-certificates/datamancy-ca.crt && sudo update-ca-certificates"
echo "    macOS: sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain $CA_CERT"
