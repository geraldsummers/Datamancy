#!/bin/bash
# Provenance: OpenLDAP initialization script
# Purpose: Hash passwords and bootstrap LDAP directory
# Reference: https://www.openldap.org/doc/admin24/

set -e

echo "==> Initializing LDAP directory"

# Wait for slapd to be ready
for i in {1..30}; do
    if ldapsearch -x -H ldap://localhost -b "" -s base &>/dev/null; then
        echo "✓ LDAP server is ready"
        break
    fi
    echo "Waiting for LDAP server... ($i/30)"
    sleep 1
done

# Check if already initialized
if ldapsearch -x -H ldap://localhost -b "dc=datamancy,dc=local" -s base &>/dev/null; then
    echo "✓ LDAP directory already initialized"
    exit 0
fi

# Hash passwords (password: 'password' for all test users)
ADMIN_PASS=$(slappasswd -s "password")
TEST_PASS=$(slappasswd -s "password")

# Create temporary LDIF with hashed passwords
cat > /tmp/bootstrap-hashed.ldif <<EOF
dn: dc=datamancy,dc=local
objectClass: top
objectClass: dcObject
objectClass: organization
o: Datamancy
dc: datamancy

dn: ou=people,dc=datamancy,dc=local
objectClass: organizationalUnit
ou: people

dn: ou=groups,dc=datamancy,dc=local
objectClass: organizationalUnit
ou: groups

dn: uid=admin,ou=people,dc=datamancy,dc=local
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: admin
cn: Admin User
sn: User
givenName: Admin
mail: admin@datamancy.local
uidNumber: 10000
gidNumber: 10000
homeDirectory: /home/admin
loginShell: /bin/bash
userPassword: ${ADMIN_PASS}

dn: uid=testuser,ou=people,dc=datamancy,dc=local
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: testuser
cn: Test User
sn: User
givenName: Test
mail: testuser@datamancy.local
uidNumber: 10001
gidNumber: 10001
homeDirectory: /home/testuser
loginShell: /bin/bash
userPassword: ${TEST_PASS}

dn: cn=admins,ou=groups,dc=datamancy,dc=local
objectClass: groupOfNames
cn: admins
member: uid=admin,ou=people,dc=datamancy,dc=local

dn: cn=users,ou=groups,dc=datamancy,dc=local
objectClass: groupOfNames
cn: users
member: uid=testuser,ou=people,dc=datamancy,dc=local
member: uid=admin,ou=people,dc=datamancy,dc=local
EOF

# Load the bootstrap data
ldapadd -x -H ldap://localhost -D "cn=admin,dc=datamancy,dc=local" -w "${LDAP_ADMIN_PASSWORD}" -f /tmp/bootstrap-hashed.ldif

echo "✓ LDAP directory initialized successfully"
echo "  - Admin user: admin / password"
echo "  - Test user: testuser / password"
