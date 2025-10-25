#!/bin/sh
# CA Generation Script - Self-contained certificate authority
# Provenance: openssl standard practice
# Generates CA and SAN cert for stack.local
# Compatible with Alpine Linux (one-shot container)

set -e

CERT_DIR="/certs"
CA_DAYS=3650
CERT_DAYS=825

# Install OpenSSL if not present (Alpine)
if ! command -v openssl >/dev/null 2>&1; then
    echo "Installing openssl..."
    apk add --no-cache openssl
fi

# Create certs directory if it doesn't exist
mkdir -p "${CERT_DIR}"

# Skip if certificates already exist and are valid
if [ -f "${CERT_DIR}/ca.crt" ] && [ -f "${CERT_DIR}/stack.local.crt" ] && [ -f "${CERT_DIR}/fullchain.pem" ]; then
    echo "✓ Certificates already exist. Skipping generation."
    exit 0
fi

# Generate CA key and certificate
echo "Generating CA..."
openssl genrsa -out "${CERT_DIR}/ca.key" 4096

openssl req -x509 -new -nodes \
  -key "${CERT_DIR}/ca.key" \
  -sha256 -days "${CA_DAYS}" \
  -out "${CERT_DIR}/ca.crt" \
  -subj "/C=US/ST=State/L=City/O=Datamancy/CN=Datamancy Root CA"

echo "CA generated: ${CERT_DIR}/ca.crt"

# Generate server key
echo "Generating server certificate for stack.local..."
openssl genrsa -out "${CERT_DIR}/stack.local.key" 2048

# Create CSR with SAN
cat > "${CERT_DIR}/stack.local.cnf" <<EOF
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = v3_req

[dn]
C=US
ST=State
L=City
O=Datamancy
CN=stack.local

[v3_req]
keyUsage = keyEncipherment, dataEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = stack.local
DNS.2 = localhost
IP.1 = 127.0.0.1
EOF

openssl req -new \
  -key "${CERT_DIR}/stack.local.key" \
  -out "${CERT_DIR}/stack.local.csr" \
  -config "${CERT_DIR}/stack.local.cnf"

# Sign with CA
openssl x509 -req \
  -in "${CERT_DIR}/stack.local.csr" \
  -CA "${CERT_DIR}/ca.crt" \
  -CAkey "${CERT_DIR}/ca.key" \
  -CAcreateserial \
  -out "${CERT_DIR}/stack.local.crt" \
  -days "${CERT_DAYS}" \
  -sha256 \
  -extfile "${CERT_DIR}/stack.local.cnf" \
  -extensions v3_req

echo "Server certificate generated: ${CERT_DIR}/stack.local.crt"

# Create fullchain (cert + CA)
cat "${CERT_DIR}/stack.local.crt" "${CERT_DIR}/ca.crt" > "${CERT_DIR}/fullchain.pem"
cp "${CERT_DIR}/stack.local.key" "${CERT_DIR}/privkey.pem"

echo "Fullchain created: ${CERT_DIR}/fullchain.pem"

# Set permissions
chmod 600 "${CERT_DIR}"/*.key "${CERT_DIR}"/privkey.pem
chmod 644 "${CERT_DIR}"/*.crt "${CERT_DIR}"/*.pem

echo ""
echo "✓ CA and certificates generated successfully"
echo ""
echo "To trust the CA on your host:"
echo "  sudo cp ${CERT_DIR}/ca.crt /usr/local/share/ca-certificates/datamancy-ca.crt"
echo "  sudo update-ca-certificates"
echo ""
echo "Add to /etc/hosts:"
echo "  127.0.0.1 stack.local"
