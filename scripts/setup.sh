#!/bin/bash
set -e

echo "=========================================="
echo "Datamancy Stack Setup"
echo "=========================================="
echo ""

# Check if .env exists
if [ ! -f .env ]; then
    echo "Creating .env from .env.example..."
    cp .env.example .env
    echo "⚠️  Please edit .env file and replace all 'changeme_*' values with secure secrets!"
    echo ""
    exit 1
fi

echo "✓ .env file found"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "❌ Docker is not running. Please start Docker and try again."
    exit 1
fi

echo "✓ Docker is running"
echo ""

# Generate secrets if needed
echo "Generating secrets..."
echo ""

generate_secret() {
    openssl rand -hex 32
}

# Check if AUTHELIA_JWT_SECRET is still default
if grep -q "your-jwt-secret-here" .env; then
    echo "⚠️  Warning: Found default values in .env file"
    echo "   Generating random secrets automatically..."
    echo ""

    JWT_SECRET=$(generate_secret)
    SESSION_SECRET=$(generate_secret)
    STORAGE_KEY=$(generate_secret)
    HMAC_SECRET=$(generate_secret)

    # Replace in .env
    sed -i "s/your-jwt-secret-here/$JWT_SECRET/" .env
    sed -i "s/your-session-secret-here/$SESSION_SECRET/" .env
    sed -i "s/your-storage-encryption-key-here/$STORAGE_KEY/" .env
    sed -i "s/your-oidc-hmac-secret-here/$HMAC_SECRET/" .env

    echo "✓ Generated Authelia secrets"
fi

# Generate RSA key if needed
if grep -q "Your RSA private key here" .env; then
    echo "Generating RSA key pair for OIDC..."
    mkdir -p .secrets
    openssl genrsa -out .secrets/oidc-private.pem 4096 2>/dev/null
    openssl rsa -in .secrets/oidc-private.pem -pubout -out .secrets/oidc-public.pem 2>/dev/null

    # Replace in .env (this is complex, better to do manually)
    echo "⚠️  RSA private key generated at .secrets/oidc-private.pem"
    echo "   Please manually update AUTHELIA_OIDC_ISSUER_PRIVATE_KEY in .env"
    echo ""
fi

echo "=========================================="
echo "Hashing OAuth Secrets for Authelia"
echo "=========================================="
echo ""
echo "You need to hash your OAuth client secrets for Authelia."
echo "Run this command for each OAuth secret:"
echo ""
echo "docker run --rm authelia/authelia:latest authelia crypto hash generate pbkdf2 --variant sha512 --password 'YOUR_SECRET'"
echo ""
echo "Then update configs/authelia/configuration.yml with the hashed values."
echo ""

echo "=========================================="
echo "DNS Configuration"
echo "=========================================="
echo ""
echo "Add these entries to your /etc/hosts file:"
echo ""
echo "127.0.0.1 auth.stack.local"
echo "127.0.0.1 grafana.stack.local"
echo "127.0.0.1 adminer.stack.local"
echo "127.0.0.1 pgadmin.stack.local"
echo "127.0.0.1 portainer.stack.local"
echo "127.0.0.1 localai.stack.local"
echo "127.0.0.1 open-webui.stack.local"
echo "127.0.0.1 kopia.stack.local"
echo "127.0.0.1 nextcloud.stack.local"
echo "127.0.0.1 vaultwarden.stack.local"
echo "127.0.0.1 benthos.stack.local"
echo "127.0.0.1 jellyfin.stack.local"
echo "127.0.0.1 homeassistant.stack.local"
echo "127.0.0.1 planka.stack.local"
echo "127.0.0.1 outline.stack.local"
echo "127.0.0.1 browserless.stack.local"
echo "127.0.0.1 couchdb.stack.local"
echo "127.0.0.1 clickhouse.stack.local"
echo ""

echo "=========================================="
echo "Ready to Start"
echo "=========================================="
echo ""
echo "Start the stack with:"
echo "  docker-compose up -d"
echo ""
echo "View logs with:"
echo "  docker-compose logs -f"
echo ""
echo "Stop the stack with:"
echo "  docker-compose down"
echo ""
echo "Default credentials (change immediately!):"
echo "  LDAP Admin: cn=admin,dc=stack,dc=local / ChangeMe123!"
echo "  Test User: uid=admin,ou=users,dc=stack,dc=local / ChangeMe123!"
echo ""
