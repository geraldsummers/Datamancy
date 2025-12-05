# -*- coding: utf-8 -*-
# Seafile Seahub Settings
# This file is mounted into the container to provide database configuration

SECRET_KEY = "{{SEAFILE_SECRET_KEY}}"

TIME_ZONE = 'UTC'

# Database Configuration
DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.mysql",
        "NAME": "seahub_db",
        "USER": "seafile",
        "PASSWORD": "{{MARIADB_SEAFILE_PASSWORD}}",
        "HOST": "mysql",
        "PORT": "3306",
        "OPTIONS": {
            "charset": "utf8mb4",
        }
    }
}

# File server configuration
FILE_SERVER_ROOT = "https://seafile.{{DOMAIN}}/seafhttp"

# Email configuration (uses Mailu)
EMAIL_USE_TLS = True
EMAIL_HOST = 'smtp.{{DOMAIN}}'
EMAIL_HOST_USER = 'seafile@{{DOMAIN}}'
EMAIL_HOST_PASSWORD = '{{SEAFILE_EMAIL_PASSWORD}}'
EMAIL_PORT = 587
DEFAULT_FROM_EMAIL = EMAIL_HOST_USER
SERVER_EMAIL = EMAIL_HOST_USER

# Enable remote user authentication from Authelia via Caddy forward_auth
ENABLE_REMOTE_USER_AUTHENTICATION = True
REMOTE_USER_HEADER = 'HTTP_REMOTE_USER'
REMOTE_USER_CREATE_UNKNOWN_USER = True
REMOTE_USER_PROTECTED_PATH = ['/accounts/login/']

# OnlyOffice Document Server Integration
ENABLE_ONLYOFFICE = True
VERIFY_ONLYOFFICE_CERTIFICATE = False
ONLYOFFICE_APIJS_URL = 'https://onlyoffice.{{DOMAIN}}/web-apps/apps/api/documents/api.js'
ONLYOFFICE_FILE_EXTENSION = ('doc', 'docx', 'ppt', 'pptx', 'xls', 'xlsx', 'odt', 'fodt', 'odp', 'fodp', 'ods', 'fods', 'csv', 'ppsx', 'pps')
ONLYOFFICE_EDIT_FILE_EXTENSION = ('docx', 'pptx', 'xlsx')
ONLYOFFICE_JWT_SECRET = '{{ONLYOFFICE_JWT_SECRET}}'
