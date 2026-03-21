# Security Scripts

- `create-shadow-agent-account.main.kts <username>`: create LDAP and database shadow access plus password file for model-context-server.
- `delete-shadow-agent-account.main.kts <username>`: remove LDAP/database shadow access and delete the password file.
- `generate-ldap-bootstrap.main.kts [output-file]`: render `stack.config/ldap/bootstrap_ldap.ldif.template` using current env values.
- `provision-shadow-database-access.sh <create|delete> <username> [password]`: low-level helper for database grants.
