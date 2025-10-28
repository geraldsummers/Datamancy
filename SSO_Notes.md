
---

# **SSO Setup Automation Prompt for Authelia OIDC**

## **Overview**

Each service requires a static OIDC client entry in Authelia’s configuration (`identity_providers.oidc.clients`).
All `client_secret` values must be unique and securely stored (hashing recommended).

---

## **1. Authelia OIDC Client Registration Example**

```yaml
clients:
  - client_id: 'grafana'
    client_name: 'Grafana'
    client_secret: '<random-secret>'
    redirect_uris:
      - 'https://grafana.stack.local/login/generic_oauth'
    scopes: [openid, profile, email, groups]
    grant_types: ['authorization_code']
    response_types: ['code']
    token_endpoint_auth_method: 'client_secret_basic'
```

Repeat for each service with their respective `client_id` and redirect URI:

| Service        | Client ID        | Redirect URI                                                                   |
| -------------- | ---------------- | ------------------------------------------------------------------------------ |
| pgAdmin        | `pgadmin`        | `https://pgadmin.stack.local/oauth2/authorize`                                 |
| Portainer      | `portainer`      | `https://portainer.stack.local`                                                |
| Open WebUI     | `open-webui`     | `https://open-webui.stack.local/oauth/oidc/callback`                           |
| Nextcloud      | `nextcloud`      | `https://nextcloud.stack.local/apps/oidc_login/oidc` or `/apps/user_oidc/code` |
| Vaultwarden    | `vaultwarden`    | `https://vaultwarden.stack.local/identity/connect/oidc-signin`                 |
| Jellyfin       | `jellyfin`       | `https://jellyfin.stack.local/sso/OID/redirect/authelia`                       |
| Planka         | `planka`         | `https://planka.stack.local/oidc-callback`                                     |
| Outline        | `outline`        | `https://outline.stack.local/oidc-callback`                                    |
| Proxmox        | `proxmox`        | `https://proxmox.stack.local`                                                  |
| Home Assistant | `home-assistant` | `https://homeassistant.stack.local/auth/oidc/callback`                         |

---

## **2. Service Configuration Steps**

### **Grafana**

Set the following in Docker Compose:

```yaml
environment:
  - GF_SERVER_ROOT_URL=https://grafana.stack.local
  - GF_AUTH_GENERIC_OAUTH_ENABLED=true
  - GF_AUTH_GENERIC_OAUTH_NAME=Authelia
  - GF_AUTH_GENERIC_OAUTH_CLIENT_ID=grafana
  - GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET=<secret>
  - GF_AUTH_GENERIC_OAUTH_SCOPES=openid profile email groups
  - GF_AUTH_GENERIC_OAUTH_AUTH_URL=https://auth.stack.local/api/oidc/authorization
  - GF_AUTH_GENERIC_OAUTH_TOKEN_URL=https://auth.stack.local/api/oidc/token
  - GF_AUTH_GENERIC_OAUTH_API_URL=https://auth.stack.local/api/oidc/userinfo
  - GF_AUTH_GENERIC_OAUTH_LOGIN_ATTRIBUTE_PATH=preferred_username
  - GF_AUTH_GENERIC_OAUTH_GROUPS_ATTRIBUTE_PATH=groups
  - GF_AUTH_GENERIC_OAUTH_NAME_ATTRIBUTE_PATH=name
```

*Idempotent: safe to rerun.*

---

### **pgAdmin**

Mount or create `config_local.py`:

```python
AUTHENTICATION_SOURCES = ['oauth2', 'internal']
OAUTH2_AUTO_CREATE_USER = True
OAUTH2_CONFIG = [{
    'OAUTH2_NAME': 'Authelia',
    'OAUTH2_CLIENT_ID': 'pgadmin',
    'OAUTH2_CLIENT_SECRET': '<secret>',
    'OAUTH2_API_BASE_URL': 'https://auth.stack.local',
    'OAUTH2_AUTHORIZATION_URL': 'https://auth.stack.local/api/oidc/authorization',
    'OAUTH2_TOKEN_URL': 'https://auth.stack.local/api/oidc/token',
    'OAUTH2_USERINFO_ENDPOINT': 'https://auth.stack.local/api/oidc/userinfo',
    'OAUTH2_SERVER_METADATA_URL': 'https://auth.stack.local/.well-known/openid-configuration',
    'OAUTH2_SCOPE': 'openid email profile',
    'OAUTH2_USERNAME_CLAIM': 'email'
}]
```

*Ensure only one OAuth2 section exists (idempotent).*

---

### **Portainer**

Configure via REST API:

```bash
curl -X PUT "https://portainer.stack.local/api/settings" \
  -H "Authorization: Bearer <admin-key>" \
  -H "Content-Type: application/json" \
  -d '{
    "AuthenticationMethod": 2,
    "OAuthSettings": {
      "ClientID": "portainer",
      "ClientSecret": "<secret>",
      "AccessTokenURI": "https://auth.stack.local/api/oidc/token",
      "AuthorizationURI": "https://auth.stack.local/api/oidc/authorization",
      "ResourceURI": "https://auth.stack.local/api/oidc/userinfo",
      "RedirectURI": "https://portainer.stack.local",
      "UserIdentifier": "preferred_username",
      "Scopes": "openid profile groups email",
      "OAuthAutoCreateUsers": false,
      "SSO": true
    }
  }'
```

*Check existing settings before updating (idempotent).*

---

### **Open WebUI**

```yaml
environment:
  - WEBUI_URL=https://open-webui.stack.local
  - ENABLE_OAUTH_SIGNUP=true
  - OAUTH_MERGE_ACCOUNTS_BY_EMAIL=true
  - OAUTH_CLIENT_ID=open-webui
  - OAUTH_CLIENT_SECRET=<secret>
  - OPENID_PROVIDER_URL=https://auth.stack.local/.well-known/openid-configuration
  - OAUTH_PROVIDER_NAME=Authelia
  - OAUTH_SCOPES=openid email profile groups
  - ENABLE_OAUTH_ROLE_MANAGEMENT=true
  - OAUTH_ALLOWED_ROLES=openwebui,openwebui-admin
  - OAUTH_ADMIN_ROLES=openwebui-admin
  - OAUTH_ROLES_CLAIM=groups
```

---

### **Nextcloud**

For the OIDC Login app:

```php
'oidc_login_provider_url'   => 'https://auth.stack.local',
'oidc_login_client_id'      => 'nextcloud',
'oidc_login_client_secret'  => '<secret>',
'oidc_login_scope'          => 'openid profile email groups nextcloud_userinfo',
'oidc_login_username_attribute' => 'email',
```

For OIDC User Backend:

```bash
occ user_oidc:provider Authelia \
  --clientid="nextcloud" \
  --clientsecret="<secret>" \
  --discoveryuri="https://auth.stack.local/.well-known/openid-configuration"
```

---

### **Vaultwarden**

```yaml
environment:
  - SSO_ENABLED=true
  - SSO_AUTHORITY=https://auth.stack.local
  - SSO_CLIENT_ID=vaultwarden
  - SSO_CLIENT_SECRET=<secret>
  - SSO_SCOPES=profile email offline_access vaultwarden
  - SSO_PKCE=true
  - SSO_ROLES_ENABLED=true
```

---

### **Jellyfin**

`SSO-Auth.xml`:

```xml
<PluginConfiguration>
  <OidEndpoint>https://auth.stack.local</OidEndpoint>
  <OidClientId>jellyfin</OidClientId>
  <OidSecret><secret></OidSecret>
  <RoleClaim>groups</RoleClaim>
  <OidScopes><string>groups</string></OidScopes>
</PluginConfiguration>
```

---

### **Planka**

```yaml
environment:
  - OIDC_ISSUER=https://auth.stack.local
  - OIDC_CLIENT_ID=planka
  - OIDC_CLIENT_SECRET=<secret>
  - OIDC_SCOPES=openid profile email
  - OIDC_ADMIN_ROLES=planka-admin
```

---

### **Outline**

```yaml
environment:
  - URL=https://outline.stack.local
  - OIDC_CLIENT_ID=outline
  - OIDC_CLIENT_SECRET=<secret>
  - OIDC_AUTH_URI=https://auth.stack.local/api/oidc/authorization
  - OIDC_TOKEN_URI=https://auth.stack.local/api/oidc/token
  - OIDC_USERINFO_URI=https://auth.stack.local/api/oidc/userinfo
  - OIDC_USERNAME_CLAIM=preferred_username
  - OIDC_SCOPES=openid offline_access profile email
```

---

### **Proxmox / PBS**

```bash
pveum realm add authelia-oidc --type openid \
  --issuer-url https://auth.stack.local \
  --client-id proxmox \
  --client-key <secret> \
  --username-claim email \
  --scope openid,email,profile,groups
```

---

### **Home Assistant**

```yaml
auth_oidc:
  client_id: 'home-assistant'
  client_secret: '<secret>'
  discovery_url: 'https://auth.stack.local/.well-known/openid-configuration'
  display_name: 'Authelia'
  roles:
    admin: 'admins'
```

---

## **References**

* [Authelia OIDC Documentation](https://www.authelia.com)
* Service-specific guides: Grafana, pgAdmin, Open WebUI, Nextcloud, Vaultwarden, Jellyfin, Planka, Outline, Proxmox, Home Assistant, Portainer.

---
