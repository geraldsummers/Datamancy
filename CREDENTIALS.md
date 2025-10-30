# Datamancy Test Credentials

## LDAP Users

All LDAP users use the same password for testing purposes.

### Admin User
- **Username**: `admin`
- **Password**: `DatamancyTest2025!`
- **Email**: `admin@stack.local`
- **Groups**: admins, users, openwebui-admin, planka-admin

### Regular User
- **Username**: `user`
- **Password**: `DatamancyTest2025!`
- **Email**: `user@stack.local`
- **Groups**: users

## SSO/Authelia Login

When accessing any SSO-protected service, use:
- **Username**: `admin`
- **Password**: `DatamancyTest2025!`

## Protected Services

The following services use Authelia SSO:
1. Grafana - https://grafana.stack.local
2. Open-WebUI - https://open-webui.stack.local
3. JupyterHub - https://jupyterhub.stack.local
4. Outline - https://outline.stack.local
5. Planka - https://planka.stack.local
6. Vaultwarden - https://vaultwarden.stack.local

## Regenerating LDAP Passwords

If you need to change the password:

```bash
# Generate new SSHA hash
docker exec ldap slappasswd -s "YourNewPassword" -h "{SSHA}"

# Update configs/ldap/bootstrap.ldif with the new hash
# Rebuild LDAP container to apply changes
docker compose up -d --force-recreate ldap
```

## Notes

- These are test credentials for local development only
- Never use these credentials in production
- Password was set on: 2025-10-29
- LDAP database persists in Docker volume `ldap_data`
