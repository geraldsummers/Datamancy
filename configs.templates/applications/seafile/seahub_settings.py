# Seahub (Seafile Web) Settings
# This file configures the Seafile web interface

# LDAP Authentication Configuration
ENABLE_LDAP = True
LDAP_SERVER_URL = 'ldap://ldap:389'
LDAP_BASE_DN = 'ou=users,dc=stack,dc=local'
LDAP_ADMIN_DN = 'cn=admin,dc=stack,dc=local'
LDAP_ADMIN_PASSWORD = '{{STACK_ADMIN_PASSWORD}}'

# LDAP User Attributes
LDAP_LOGIN_ATTR = 'uid'
LDAP_USER_FIRST_NAME_ATTR = 'givenName'
LDAP_USER_LAST_NAME_ATTR = 'sn'
LDAP_USER_NAME_REVERSE = False
LDAP_FILTER = 'objectClass=inetOrgPerson'

# User Search Scope
LDAP_USER_OBJECT_CLASS = 'inetOrgPerson'

# Import users automatically on first login
LDAP_SYNC_INTERVAL = 60  # minutes

# Email attribute (optional, if available in LDAP)
LDAP_CONTACT_EMAIL_ATTR = 'mail'
