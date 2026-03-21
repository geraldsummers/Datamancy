# Security Scripts

- Credential source of truth: `DATAMANCY_CREDENTIAL_STORE_FILE`, defaulting to `${XDG_CONFIG_HOME:-~/.config}/datamancy/credentials.env`.
- Shadow account password files: `DATAMANCY_SHADOW_ACCOUNTS_DIR` / `SHADOW_ACCOUNTS_HOST_DIR`, defaulting to `${XDG_STATE_HOME:-~/.local/state}/datamancy/shadow-accounts`.
- `create-shadow-agent-account.main.kts <username>`: create LDAP and database shadow access plus password file for model-context-server.
- `delete-shadow-agent-account.main.kts <username>`: remove LDAP/database shadow access and delete the password file.
- `generate-ldap-bootstrap.main.kts [output-file]`: render `stack.config/ldap/bootstrap_ldap.ldif.template` using current env values.
- `provision-shadow-database-access.sh <create|delete> <username> [password]`: low-level helper for database grants.
