# Datamancy Credentials Reference

**Auto-generated from credentials.schema.yaml**

This document provides a complete reference of all credentials, secrets, and configuration values used in the Datamancy stack.

---

## Table of Contents

- [Credential Types](#credential-types)
- [Domain & Admin Configuration](#domain--admin-configuration)
- [Core Infrastructure](#core-infrastructure)
- [Database Passwords](#database-passwords)
- [OAuth OIDC Secrets](#oauth-oidc-secrets)
- [Application Secrets](#application-secrets)
- [User-Provided Credentials](#user-provided-credentials)
- [Configuration Values](#configuration-values)
- [Template Processing Rules](#template-processing-rules)

---

## Credential Types

| Type | Description | Generation Method |
|------|-------------|-------------------|
| `hex_secret` | 64-character hexadecimal | `openssl rand -hex 32` |
| `laravel_key` | Laravel APP_KEY format | `base64:...` from `openssl rand -base64 32` |
| `rsa_key` | RSA 4096 private key | `openssl genrsa 4096` |
| `oauth_secret` | OAuth client secret + argon2id hash | hex_secret + Authelia hash |
| `user_provided` | User must provide | Empty by default |
| `config_value` | Non-secret configuration | From datamancy.config.yaml |

## Domain & Admin Configuration

### `DOMAIN`

**Description:** Primary domain for the stack

**Type:** `config_value`

**Source:** `datamancy.config.yaml`

---

### `MAIL_DOMAIN`

**Description:** Mail server domain

**Type:** `config_value`

**Source:** `datamancy.config.yaml`

---

### `LDAP_DOMAIN`

**Description:** LDAP domain

**Type:** `config_value`

**Source:** `datamancy.config.yaml`

---

### `LDAP_BASE_DN`

**Description:** LDAP base DN

**Type:** `config_value`

**Source:** `datamancy.config.yaml`

---

### `STACK_ADMIN_EMAIL`

**Description:** Administrator email address

**Type:** `config_value`

**Source:** `datamancy.config.yaml`

---

### `STACK_ADMIN_USER`

**Description:** Administrator username

**Type:** `config_value`

**Source:** `datamancy.config.yaml`

---

### `STACK_ADMIN_PASSWORD`

**Description:** Primary administrator password

**Type:** `hex_secret`

**Used by:**
- homeassistant
- kopia
- filebrowser
- ldap_bootstrap_sysadmin

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `ADMIN_SSHA_PASSWORD` | ssha | `configs/infrastructure/ldap/bootstrap_ldap.ldif` |

---

### `LDAP_ADMIN_PASSWORD`

**Description:** LDAP admin bind password

**Type:** `hex_secret`

**Used by:**
- authelia
- ldap
- mailserver
- synapse
- lam
- homeassistant
- forgejo

**Baked into configs:**
- `configs/infrastructure/mailserver/ldap-domains.cf`
- `configs/infrastructure/mailserver/dovecot-ldap.conf.ext`

---

## Core Infrastructure

### `STACK_ADMIN_PASSWORD`

**Description:** Primary administrator password

**Type:** `hex_secret`

**Used by:**
- homeassistant
- kopia
- filebrowser
- ldap_bootstrap_sysadmin

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `ADMIN_SSHA_PASSWORD` | ssha | `configs/infrastructure/ldap/bootstrap_ldap.ldif` |

---

### `LDAP_ADMIN_PASSWORD`

**Description:** LDAP admin bind password

**Type:** `hex_secret`

**Used by:**
- authelia
- ldap
- mailserver
- synapse
- lam
- homeassistant
- forgejo

**Baked into configs:**
- `configs/infrastructure/mailserver/ldap-domains.cf`
- `configs/infrastructure/mailserver/dovecot-ldap.conf.ext`

---

## Database Passwords

### `CLICKHOUSE_ADMIN_PASSWORD`

**Description:** ClickHouse admin password

**Type:** `hex_secret`

**Used by:**
- clickhouse
- observers

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `CLICKHOUSE_ADMIN_PASSWORD_HASH` | sha256 | `configs/databases/clickhouse/users.xml` |

---

### `AUTHELIA_DB_PASSWORD`

**Description:** Authelia PostgreSQL password

**Type:** `hex_secret`

---

### `BOOKSTACK_DB_PASSWORD`

**Description:** BookStack MariaDB password

**Type:** `hex_secret`

---

### `FORGEJO_DB_PASSWORD`

**Description:** Forgejo PostgreSQL password

**Type:** `hex_secret`

---

### `GRAFANA_DB_PASSWORD`

**Description:** Grafana PostgreSQL password

**Type:** `hex_secret`

---

### `HOMEASSISTANT_DB_PASSWORD`

**Description:** Home Assistant PostgreSQL password

**Type:** `hex_secret`

---

### `MASTODON_DB_PASSWORD`

**Description:** Mastodon PostgreSQL password

**Type:** `hex_secret`

---

### `OPENWEBUI_DB_PASSWORD`

**Description:** Open WebUI PostgreSQL password

**Type:** `hex_secret`

---

### `PLANKA_DB_PASSWORD`

**Description:** Planka PostgreSQL password

**Type:** `hex_secret`

---

### `ROUNDCUBE_DB_PASSWORD`

**Description:** Roundcube PostgreSQL password

**Type:** `hex_secret`

---

### `SYNAPSE_DB_PASSWORD`

**Description:** Matrix Synapse PostgreSQL password

**Type:** `hex_secret`

---

### `VAULTWARDEN_DB_PASSWORD`

**Description:** Vaultwarden PostgreSQL password

**Type:** `hex_secret`

---

## OAuth OIDC Secrets

### `PGADMIN_OAUTH_SECRET`

**Description:** PgAdmin OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `PGADMIN_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

### `OPENWEBUI_OAUTH_SECRET`

**Description:** Open WebUI OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `OPENWEBUI_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

### `DIM_OAUTH_SECRET`

**Description:** DIM OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `DIM_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

### `PLANKA_OAUTH_SECRET`

**Description:** Planka OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `PLANKA_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

### `VAULTWARDEN_OAUTH_SECRET`

**Description:** Vaultwarden OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `VAULTWARDEN_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

### `MASTODON_OAUTH_SECRET`

**Description:** Mastodon OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `MASTODON_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

### `BOOKSTACK_OAUTH_SECRET`

**Description:** BookStack OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `BOOKSTACK_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

### `FORGEJO_OAUTH_SECRET`

**Description:** Forgejo OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `FORGEJO_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

### `MATRIX_OAUTH_SECRET`

**Description:** Matrix Synapse OIDC client secret

**Type:** `oauth_secret`

**Hash Variants:**

| Variable | Algorithm | Used In |
|----------|-----------|---------|
| `MATRIX_OAUTH_SECRET_HASH` | argon2id | `configs/applications/authelia/configuration.yml` |

---

## Application Secrets

### `AGENT_QDRANT_API_KEY`

**Description:** Agent access to Qdrant vector DB

**Type:** `hex_secret`

---

### `AUTHELIA_JWT_SECRET`

**Description:** Authelia JWT signing secret

**Type:** `hex_secret`

---

### `AUTHELIA_SESSION_SECRET`

**Description:** Authelia session encryption secret

**Type:** `hex_secret`

---

### `AUTHELIA_STORAGE_ENCRYPTION_KEY`

**Description:** Authelia storage encryption key

**Type:** `hex_secret`

---

### `AUTHELIA_OIDC_HMAC_SECRET`

**Description:** Authelia OIDC HMAC secret

**Type:** `hex_secret`

---

### `JELLYFIN_OIDC_SECRET`

**Description:** Jellyfin OIDC secret

**Type:** `hex_secret`

---

### `JUPYTERHUB_CRYPT_KEY`

**Description:** JupyterHub encryption key

**Type:** `hex_secret`

---

### `LITELLM_MASTER_KEY`

**Description:** LiteLLM master API key

**Type:** `hex_secret`

---

### `ONLYOFFICE_JWT_SECRET`

**Description:** OnlyOffice JWT secret

**Type:** `hex_secret`

---

### `PLANKA_SECRET_KEY`

**Description:** Planka secret key

**Type:** `hex_secret`

---

### `QDRANT_API_KEY`

**Description:** Qdrant vector database API key

**Type:** `hex_secret`

---

### `SEAFILE_JWT_KEY`

**Description:** Seafile JWT key

**Type:** `hex_secret`

---

### `SEAFILE_SECRET_KEY`

**Description:** Seafile secret key

**Type:** `hex_secret`

---

### `SYNAPSE_REGISTRATION_SECRET`

**Description:** Matrix Synapse registration secret

**Type:** `hex_secret`

---

### `SYNAPSE_MACAROON_SECRET`

**Description:** Matrix Synapse macaroon secret

**Type:** `hex_secret`

---

### `SYNAPSE_FORM_SECRET`

**Description:** Matrix Synapse form secret

**Type:** `hex_secret`

---

### `MASTODON_SECRET_KEY_BASE`

**Description:** Mastodon secret key base

**Type:** `hex_secret`

---

### `MASTODON_OTP_SECRET`

**Description:** Mastodon OTP secret

**Type:** `hex_secret`

---

### `MASTODON_VAPID_PRIVATE_KEY`

**Description:** Mastodon VAPID private key

**Type:** `hex_secret`

---

### `MASTODON_VAPID_PUBLIC_KEY`

**Description:** Mastodon VAPID public key

**Type:** `hex_secret`

---

### `MASTODON_OIDC_SECRET`

**Description:** Mastodon OIDC secret

**Type:** `hex_secret`

---

### `MASTODON_ACTIVE_RECORD_ENCRYPTION_PRIMARY_KEY`

**Description:** Mastodon ActiveRecord encryption primary key

**Type:** `hex_secret`

---

### `MASTODON_ACTIVE_RECORD_ENCRYPTION_DETERMINISTIC_KEY`

**Description:** Mastodon ActiveRecord encryption deterministic key

**Type:** `hex_secret`

---

### `MASTODON_ACTIVE_RECORD_ENCRYPTION_KEY_DERIVATION_SALT`

**Description:** Mastodon ActiveRecord encryption salt

**Type:** `hex_secret`

---

### `AUTHELIA_JWT_SECRET`

**Description:** Authelia JWT signing secret

**Type:** `hex_secret`

---

### `AUTHELIA_SESSION_SECRET`

**Description:** Authelia session encryption secret

**Type:** `hex_secret`

---

### `AUTHELIA_OIDC_HMAC_SECRET`

**Description:** Authelia OIDC HMAC secret

**Type:** `hex_secret`

---

### `BOOKSTACK_APP_KEY`

**Description:** BookStack Laravel application key

**Type:** `laravel_key`

---

### `JELLYFIN_OIDC_SECRET`

**Description:** Jellyfin OIDC secret

**Type:** `hex_secret`

---

### `ONLYOFFICE_JWT_SECRET`

**Description:** OnlyOffice JWT secret

**Type:** `hex_secret`

---

### `PLANKA_SECRET_KEY`

**Description:** Planka secret key

**Type:** `hex_secret`

---

### `SEAFILE_SECRET_KEY`

**Description:** Seafile secret key

**Type:** `hex_secret`

---

### `SYNAPSE_REGISTRATION_SECRET`

**Description:** Matrix Synapse registration secret

**Type:** `hex_secret`

---

### `SYNAPSE_MACAROON_SECRET`

**Description:** Matrix Synapse macaroon secret

**Type:** `hex_secret`

---

### `SYNAPSE_FORM_SECRET`

**Description:** Matrix Synapse form secret

**Type:** `hex_secret`

---

### `MASTODON_SECRET_KEY_BASE`

**Description:** Mastodon secret key base

**Type:** `hex_secret`

---

### `MASTODON_OTP_SECRET`

**Description:** Mastodon OTP secret

**Type:** `hex_secret`

---

### `MASTODON_OIDC_SECRET`

**Description:** Mastodon OIDC secret

**Type:** `hex_secret`

---

## User-Provided Credentials

### `HUGGINGFACEHUB_API_TOKEN`

**Description:** HuggingFace Hub API token (required for model downloads)

**Type:** `user_provided`

**Default:** ``

---

### `FORGEJO_RUNNER_REGISTRATION_TOKEN`

**Description:** Forgejo CI runner registration token (generated post-deployment)

**Type:** `user_provided`

**Default:** ``

---

## Configuration Values

### `API_LITELLM_ALLOWLIST`

**Description:** IP allowlist for LiteLLM API

**Type:** `config_value`

**Default:** `127.0.0.1 172.16.0.0/12 192.168.0.0/16`

---

### `VECTOR_EMBED_SIZE`

**Description:** Vector embedding dimension (BAAI/bge-base-en-v1.5)

**Type:** `config_value`

**Default:** `768`

---

### `FORGEJO_RUNNER_NAME`

**Description:** Forgejo runner instance name

**Type:** `config_value`

**Default:** `datamancy-runner`

---

### `FORGEJO_RUNNER_LABELS`

**Description:** Forgejo runner labels

**Type:** `config_value`

**Default:** `ubuntu-latest:docker://node:20-bullseye,ubuntu-22.04:docker://node:20-bullseye`

---

## Template Processing Rules

These rules define how credentials are transformed and substituted into configuration files.

### Rule: `clickhouse_users`

**Path Pattern:** `databases/clickhouse/users.xml`

**Substitutions:**

| Variable | Source | Transform |
|----------|--------|-----------|
| `CLICKHOUSE_ADMIN_PASSWORD` | `CLICKHOUSE_ADMIN_PASSWORD` | `sha256` |
| `DATAMANCY_SERVICE_PASSWORD` | `DATAMANCY_SERVICE_PASSWORD` | `sha256` |

---

### Rule: `ldap_bootstrap`

**Path Pattern:** `infrastructure/ldap/bootstrap_ldap.ldif`

**Substitutions:**

| Variable | Source | Transform |
|----------|--------|-----------|
| `ADMIN_SSHA_PASSWORD` | `STACK_ADMIN_PASSWORD` | `ssha` |
| `USER_SSHA_PASSWORD` | `STACK_ADMIN_PASSWORD` | `ssha` |

---

### Rule: `mailserver_ldap`

**Path Pattern:** `infrastructure/mailserver/(ldap-domains.cf|dovecot-ldap.conf.ext)`

**Substitutions:**

| Variable | Source | Transform |
|----------|--------|-----------|
| `LDAP_ADMIN_PASSWORD` | `LDAP_ADMIN_PASSWORD` | `none` |

**Then:** Convert remaining `{{VAR}}` → `${VAR}` for runtime substitution

---

### Rule: `authelia_oidc_key`

**Path Pattern:** `applications/authelia/configuration.yml`

**Substitutions:**

| Variable | Source | Transform |
|----------|--------|-----------|
| `AUTHELIA_OIDC_PRIVATE_KEY` | `AUTHELIA_OIDC_PRIVATE_KEY` | `multiline_yaml_indent` |

---

### Rule: `default`

**Path Pattern:** `.*`

**Then:** Convert remaining `{{VAR}}` → `${VAR}` for runtime substitution

---

## Statistics

- **Total Credentials:** 71
- **Secrets:** 59
- **User-Provided:** 2
- **Config Values:** 10
- **OAuth Pairs:** 9
- **Template Rules:** 5

- **Hash Variants:** 12

---

*Generated from `credentials.schema.yaml` by `generate-credential-docs.main.kts`*
