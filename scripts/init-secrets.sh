#!/bin/bash
set -euo pipefail

# Secrets initialization script for Datamancy Stack
# This script generates cryptographically secure secrets at runtime
# and stores them in an encrypted volume, never exposing them to logs or human eyes

SECRETS_DIR="${SECRETS_DIR:-/run/secrets}"
SECRETS_FILE="${SECRETS_DIR}/stack_secrets.enc"
SECRETS_KEY_FILE="${SECRETS_DIR}/.key"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# Generate cryptographically secure random string
generate_secret() {
    local length="${1:-32}"
    openssl rand -hex "$length"
}

# Generate base64 encoded secret
generate_secret_b64() {
    local length="${1:-32}"
    openssl rand -base64 "$length" | tr -d '\n'
}

# Generate RSA private key for OIDC
generate_rsa_key() {
    openssl genrsa 4096 2>/dev/null | base64 -w 0
}

# Generate password (alphanumeric with special chars)
generate_password() {
    local length="${1:-24}"
    openssl rand -base64 48 | tr -dc 'A-Za-z0-9!@#$%^&*' | head -c "$length"
}

# Encrypt secrets file
encrypt_secrets() {
    local plain_file="$1"
    local enc_file="$2"
    local key_file="$3"

    # Generate encryption key if it doesn't exist
    if [ ! -f "$key_file" ]; then
        openssl rand -hex 32 > "$key_file"
        chmod 600 "$key_file"
    fi

    # Encrypt using AES-256-CBC
    openssl enc -aes-256-cbc -salt -pbkdf2 -in "$plain_file" -out "$enc_file" -pass file:"$key_file"
    chmod 600 "$enc_file"

    # Remove plaintext file
    shred -vfz -n 3 "$plain_file" 2>/dev/null || rm -f "$plain_file"
}

# Decrypt secrets file
decrypt_secrets() {
    local enc_file="$1"
    local key_file="$2"

    if [ ! -f "$enc_file" ] || [ ! -f "$key_file" ]; then
        return 1
    fi

    openssl enc -aes-256-cbc -d -pbkdf2 -in "$enc_file" -pass file:"$key_file"
}

# Initialize secrets
init_secrets() {
    mkdir -p "$SECRETS_DIR"
    chmod 700 "$SECRETS_DIR"

    local temp_secrets=$(mktemp)
    chmod 600 "$temp_secrets"

    log_info "Generating cryptographically secure secrets..."

    # NEVER log the actual secret values!
    {
        echo "# Datamancy Stack Secrets"
        echo "# Generated at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "# WARNING: This file is encrypted. Do not edit manually."
        echo ""

        # Stack admin credentials
        echo "STACK_ADMIN_USER=admin"
        echo "STACK_ADMIN_PASSWORD=$(generate_password 32)"
        echo "STACK_ADMIN_EMAIL=${STACK_ADMIN_EMAIL:-admin@localhost}"
        echo ""

        # Authelia secrets
        echo "AUTHELIA_JWT_SECRET=$(generate_secret 32)"
        echo "AUTHELIA_SESSION_SECRET=$(generate_secret 32)"
        echo "AUTHELIA_STORAGE_ENCRYPTION_KEY=$(generate_secret 32)"
        echo "AUTHELIA_OIDC_HMAC_SECRET=$(generate_secret 32)"
        echo "AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY=$(generate_rsa_key)"
        echo ""

        # OAuth client secrets
        echo "GRAFANA_OAUTH_SECRET=$(generate_secret 32)"
        echo "VAULTWARDEN_OAUTH_SECRET=$(generate_secret 32)"
        echo "PLANKA_OAUTH_SECRET=$(generate_secret 32)"
        echo "OUTLINE_OAUTH_SECRET=$(generate_secret 32)"
        echo "JUPYTERHUB_OAUTH_SECRET=$(generate_secret 32)"
        echo "LITELLM_OAUTH_SECRET=$(generate_secret 32)"
        echo "OPENWEBUI_OAUTH_SECRET=$(generate_secret 32)"
        echo "PGADMIN_OAUTH_SECRET=$(generate_secret 32)"
        echo "PORTAINER_OAUTH_SECRET=$(generate_secret 32)"
        echo "NEXTCLOUD_OIDC_SECRET=$(generate_secret 32)"
        echo ""

        # Application secrets
        echo "PLANKA_SECRET_KEY=$(generate_secret 32)"
        echo "OUTLINE_SECRET_KEY=$(generate_secret 32)"
        echo "OUTLINE_UTILS_SECRET=$(generate_secret 32)"
        echo "ONLYOFFICE_JWT_SECRET=$(generate_secret 32)"
        echo "VAULTWARDEN_ADMIN_TOKEN=$(generate_secret_b64 32)"
        echo "VAULTWARDEN_SMTP_PASSWORD=$(generate_password 24)"
        echo ""

        # Database passwords
        echo "PLANKA_DB_PASSWORD=$(generate_password 32)"
        echo "OUTLINE_DB_PASSWORD=$(generate_password 32)"
        echo "SYNAPSE_DB_PASSWORD=$(generate_password 32)"
        echo "MAILU_DB_PASSWORD=$(generate_password 32)"
        echo "MARIADB_SEAFILE_ROOT_PASSWORD=$(generate_password 32)"
        echo "MARIADB_SEAFILE_PASSWORD=$(generate_password 32)"
        echo ""

        # Service tokens
        echo "LITELLM_MASTER_KEY=sk-$(generate_secret 32)"
        echo "BROWSERLESS_TOKEN=$(generate_secret 32)"
        echo "KOPIA_PASSWORD=$(generate_password 32)"
        echo "QDRANT_API_KEY=$(generate_secret 32)"
        echo ""

        # API keys (if provided externally, these remain empty)
        echo "HUGGINGFACEHUB_API_TOKEN=${HUGGINGFACEHUB_API_TOKEN:-}"

    } > "$temp_secrets"

    log_info "Encrypting secrets..."
    encrypt_secrets "$temp_secrets" "$SECRETS_FILE" "$SECRETS_KEY_FILE"

    log_info "✓ Secrets generated and encrypted successfully"
    log_warn "Keep the secrets directory secure: $SECRETS_DIR"
}

# Export secrets as environment variables (for Docker Compose)
export_secrets() {
    if [ ! -f "$SECRETS_FILE" ]; then
        log_error "Secrets file not found. Run init first."
        return 1
    fi

    decrypt_secrets "$SECRETS_FILE" "$SECRETS_KEY_FILE"
}

# Rotate a specific secret
rotate_secret() {
    local secret_name="$1"

    log_info "Rotating secret: $secret_name"

    local temp_secrets=$(mktemp)
    chmod 600 "$temp_secrets"

    # Decrypt existing secrets
    decrypt_secrets "$SECRETS_FILE" "$SECRETS_KEY_FILE" > "$temp_secrets"

    # Generate new value based on secret type
    local new_value
    case "$secret_name" in
        *_PASSWORD|*_ADMIN_TOKEN|KOPIA_PASSWORD)
            new_value=$(generate_password 32)
            ;;
        *_RSA_KEY|*_PRIVATE_KEY)
            new_value=$(generate_rsa_key)
            ;;
        LITELLM_MASTER_KEY)
            new_value="sk-$(generate_secret 32)"
            ;;
        *)
            new_value=$(generate_secret 32)
            ;;
    esac

    # Replace the old value (using sed without displaying the value)
    sed -i "s|^${secret_name}=.*|${secret_name}=${new_value}|" "$temp_secrets"

    # Re-encrypt
    encrypt_secrets "$temp_secrets" "$SECRETS_FILE" "$SECRETS_KEY_FILE"

    log_info "✓ Secret rotated: $secret_name"
    log_warn "Services using this secret must be restarted"
}

# Main command handler
case "${1:-}" in
    init)
        if [ -f "$SECRETS_FILE" ]; then
            log_error "Secrets already initialized. Use 'rotate' to change specific secrets."
            exit 1
        fi
        init_secrets
        ;;
    export)
        export_secrets
        ;;
    rotate)
        if [ -z "${2:-}" ]; then
            log_error "Usage: $0 rotate SECRET_NAME"
            exit 1
        fi
        rotate_secret "$2"
        ;;
    *)
        echo "Usage: $0 {init|export|rotate SECRET_NAME}"
        echo ""
        echo "Commands:"
        echo "  init     - Generate and encrypt all secrets (first run only)"
        echo "  export   - Decrypt and export secrets as environment variables"
        echo "  rotate   - Rotate a specific secret value"
        exit 1
        ;;
esac
