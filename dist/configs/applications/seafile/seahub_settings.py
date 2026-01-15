# -*- coding: utf-8 -*-
# Seafile Seahub Additional Settings
# This file is appended to seahub_settings.py to provide additional configuration
# Note: Database configuration is handled by Seafile's built-in SEAFILE_MYSQL_DB_* env vars

SECRET_KEY = "${SEAFILE_SECRET_KEY}"

TIME_ZONE = 'UTC'

# File server configuration
FILE_SERVER_ROOT = "https://seafile.datamancy.net/seafhttp"

# Email configuration (uses Mailu)
EMAIL_USE_TLS = True
EMAIL_HOST = 'smtp.datamancy.net'
EMAIL_HOST_USER = 'seafile@datamancy.net'
EMAIL_HOST_PASSWORD = '${SEAFILE_EMAIL_PASSWORD}'
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
ONLYOFFICE_APIJS_URL = 'https://onlyoffice.datamancy.net/web-apps/apps/api/documents/api.js'
ONLYOFFICE_FILE_EXTENSION = ('doc', 'docx', 'ppt', 'pptx', 'xls', 'xlsx', 'odt', 'fodt', 'odp', 'fodp', 'ods', 'fods', 'csv', 'ppsx', 'pps')
ONLYOFFICE_EDIT_FILE_EXTENSION = ('docx', 'pptx', 'xlsx')
ONLYOFFICE_JWT_SECRET = '${ONLYOFFICE_JWT_SECRET}'
