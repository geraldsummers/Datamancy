# User Template Policy - Per-user secret isolation
# Dynamically grants access to user's own path based on LDAP username
# Assigned to members of the 'users' LDAP group

# Full access to own user path (uses identity templating)
# e.g., if username is 'alice', grants access to secret/data/users/alice/*
path "secret/data/users/{{identity.entity.aliases.auth_ldap_*.name}}/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

path "secret/metadata/users/{{identity.entity.aliases.auth_ldap_*.name}}/*" {
  capabilities = ["list", "read", "delete"]
}

# List own user directory
path "secret/metadata/users/{{identity.entity.aliases.auth_ldap_*.name}}" {
  capabilities = ["list", "read"]
}

# Read-only access to shared service secrets
path "secret/data/services/*" {
  capabilities = ["read", "list"]
}

path "secret/metadata/services/*" {
  capabilities = ["list", "read"]
}

# Read-only access to own agent secrets (if they exist)
path "secret/data/agents/{{identity.entity.aliases.auth_ldap_*.name}}-agent/*" {
  capabilities = ["read", "list"]
}

path "secret/metadata/agents/{{identity.entity.aliases.auth_ldap_*.name}}-agent/*" {
  capabilities = ["list", "read"]
}

# Allow users to look up their own token
path "auth/token/lookup-self" {
  capabilities = ["read"]
}

# Allow users to renew their own token
path "auth/token/renew-self" {
  capabilities = ["update"]
}

# Allow users to revoke their own token
path "auth/token/revoke-self" {
  capabilities = ["update"]
}
