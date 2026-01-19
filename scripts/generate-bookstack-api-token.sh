#!/bin/bash
# Script to programmatically generate BookStack API tokens
# Creates a service account token with no expiration

set -e

# Configuration
TOKEN_NAME="${1:-datamancy-service}"
USER_ID="${2:-1}"  # Default to admin user
DB_PASSWORD="8d55027c87ea8270012cf0b996240c3ba769c33c020d87f36ca67b460d9737fd"

echo "🔑 Generating BookStack API Token"
echo "=================================="
echo "Token Name: $TOKEN_NAME"
echo "User ID: $USER_ID"
echo

# Generate random token_id and secret (matching BookStack's format)
TOKEN_ID=$(openssl rand -hex 16)
SECRET=$(openssl rand -hex 32)

# Hash the secret using bcrypt (BookStack uses Laravel's Hash::make which is bcrypt)
# Note: We use PHP's password_hash which is compatible with Laravel's bcrypt
SECRET_HASH=$(docker exec bookstack php -r "echo password_hash('$SECRET', PASSWORD_BCRYPT);")

# Set expiration to far future (10 years)
EXPIRES_AT=$(date -d '+10 years' '+%Y-%m-%d')

echo "📝 Generated credentials:"
echo "  Token ID: $TOKEN_ID"
echo "  Secret:   $SECRET"
echo "  Expires:  $EXPIRES_AT"
echo

# Insert into database
echo "💾 Inserting into database..."
docker exec mariadb mariadb -u bookstack -p"$DB_PASSWORD" bookstack <<EOF
INSERT INTO api_tokens (name, token_id, secret, user_id, expires_at, created_at, updated_at)
VALUES (
    '$TOKEN_NAME',
    '$TOKEN_ID',
    '$SECRET_HASH',
    $USER_ID,
    '$EXPIRES_AT',
    NOW(),
    NOW()
);
EOF

if [ $? -eq 0 ]; then
    echo "✅ Token created successfully!"
    echo
    echo "📋 Add these to your .env file:"
    echo "=================================="
    echo "BOOKSTACK_API_TOKEN_ID=$TOKEN_ID"
    echo "BOOKSTACK_API_TOKEN_SECRET=$SECRET"
    echo
    echo "🔐 Store these credentials securely!"
else
    echo "❌ Failed to create token"
    exit 1
fi
