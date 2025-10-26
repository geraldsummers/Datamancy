#!/usr/bin/env bash
# Generate local CA and wildcard *.stack.local certificate
# Provenance: Phase 0 scaffolding
set -euo pipefail

CERT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/certs"
DOMAIN="${STACK_HOST:-stack.local}"

mkdir -p "$CERT_DIR"
cd "$CERT_DIR"

echo "==> Generating CA certificate..."
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
  -out ca.crt \
  -subj "/C=US/ST=Local/L=Local/O=Datamancy/CN=Datamancy Root CA"

echo "==> Generating wildcard certificate for *.$DOMAIN..."
openssl genrsa -out wildcard.key 4096
openssl req -new -key wildcard.key -out wildcard.csr \
  -subj "/C=US/ST=Local/L=Local/O=Datamancy/CN=*.$DOMAIN"

# Create SAN config
cat > san.cnf <<EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
C = US
ST = Local
L = Local
O = Datamancy
CN = *.$DOMAIN

[v3_req]
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = $DOMAIN
DNS.2 = *.$DOMAIN
EOF

openssl x509 -req -in wildcard.csr -CA ca.crt -CAkey ca.key \
  -CAcreateserial -out wildcard.crt -days 825 -sha256 \
  -extfile san.cnf -extensions v3_req

# Verify
openssl x509 -in wildcard.crt -text -noout | grep -A1 "Subject Alternative Name"

echo "==> CA and wildcard certificate generated."
echo "    CA: $CERT_DIR/ca.crt"
echo "    Wildcard: $CERT_DIR/wildcard.{crt,key}"
echo ""
echo "To trust the CA (Ubuntu/Debian):"
echo "  sudo cp $CERT_DIR/ca.crt /usr/local/share/ca-certificates/datamancy-ca.crt"
echo "  sudo update-ca-certificates"
