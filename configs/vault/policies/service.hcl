# Service Policy - For application/service accounts
# Read-only access to shared service secrets
# Typically assigned via AppRole authentication

# Read-only access to service secrets
path "secret/data/services/*" {
  capabilities = ["read", "list"]
}

path "secret/metadata/services/*" {
  capabilities = ["list", "read"]
}

# Allow services to renew their own tokens
path "auth/token/renew-self" {
  capabilities = ["update"]
}

# Allow services to look up their own token info
path "auth/token/lookup-self" {
  capabilities = ["read"]
}
