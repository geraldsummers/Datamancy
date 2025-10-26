# OpenLDAP — Spoke

**Status:** 🟢 Functional
**Phase:** 3
**Hostname:** `ldap:389` (internal only)
**Dependencies:** None

## Purpose

OpenLDAP provides the LDAP directory service for the Datamancy stack, storing users, groups, and organizational units for authentication and authorization via Authelia.

## Configuration

**Image:** `osixia/openldap:1.5.0`
**Volumes:**
- `ldap_data:/var/lib/ldap` (LDAP database)
- `ldap_config:/etc/ldap/slapd.d` (server configuration)
**Networks:** backend
**Ports:** 389 (LDAP), 636 (LDAPS) - internal only

### Key Settings

Organization:
- Organization: Datamancy
- Domain: datamancy.local
- Base DN: `dc=datamancy,dc=local`
- Admin DN: `cn=admin,dc=datamancy,dc=local`
- Admin password: `admin_password_change_me`

Directory Structure:
```
dc=datamancy,dc=local
├── ou=users
│   ├── uid=admin
│   ├── uid=viewer
│   └── uid=authelia (service account)
└── ou=groups
    ├── cn=admins
    └── cn=viewers
```

### Fingerprint Inputs

- Image digest: `osixia/openldap:1.5.0`
- Environment variables (organization, domain, admin password)
- Compose stanza: ldap service block
- LDIF data: users and groups structure

## Access

- **Internal URL:** `ldap://ldap:389`
- **Auth:** Admin bind required for modifications
- **Query:** `ldapsearch -x -H ldap://ldap:389 -b "dc=datamancy,dc=local"`

## Users and Groups

### Users

| UID | CN | Password | Description |
|-----|-----|----------|-------------|
| admin | Admin User | changeme | Full admin access |
| viewer | Viewer User | changeme | Read-only access |
| authelia | Authelia Service | authelia_password_change_me | Service account for Authelia |

### Groups

| CN | Members | Purpose |
|----|---------|---------|
| admins | admin | Full access to all services (2FA required) |
| viewers | viewer | Limited access to Grafana only (1FA) |

## Runbook

### Start/Stop

```bash
docker compose --profile auth up -d ldap
docker compose stop ldap
```

### Logs

```bash
docker compose logs -f ldap
```

### Query LDAP

```bash
# Search all entries
docker exec ldap ldapsearch -x -H ldap://localhost -b "dc=datamancy,dc=local"

# Search for specific user
docker exec ldap ldapsearch -x -H ldap://localhost -b "dc=datamancy,dc=local" "(uid=admin)"

# Search all groups
docker exec ldap ldapsearch -x -H ldap://localhost -b "ou=groups,dc=datamancy,dc=local"

# Verify user's group membership
docker exec ldap ldapsearch -x -H ldap://localhost -b "ou=groups,dc=datamancy,dc=local" "(uniqueMember=uid=admin,ou=users,dc=datamancy,dc=local)"
```

### Add New User

```bash
# Create LDIF file
cat > /tmp/newuser.ldif << 'EOF'
dn: uid=newuser,ou=users,dc=datamancy,dc=local
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: newuser
cn: New User
sn: User
userPassword: {SSHA}hashedpassword
uidNumber: 10002
gidNumber: 10000
homeDirectory: /home/newuser
loginShell: /bin/bash
mail: newuser@datamancy.local
EOF

# Add to LDAP
docker cp /tmp/newuser.ldif ldap:/tmp/newuser.ldif
docker exec ldap ldapadd -x -H ldap://localhost -D "cn=admin,dc=datamancy,dc=local" -w "admin_password_change_me" -f /tmp/newuser.ldif
```

### Add User to Group

```bash
cat > /tmp/addmember.ldif << 'EOF'
dn: cn=admins,ou=groups,dc=datamancy,dc=local
changetype: modify
add: uniqueMember
uniqueMember: uid=newuser,ou=users,dc=datamancy,dc=local
EOF

docker cp /tmp/addmember.ldif ldap:/tmp/addmember.ldif
docker exec ldap ldapmodify -x -H ldap://localhost -D "cn=admin,dc=datamancy,dc=local" -w "admin_password_change_me" -f /tmp/addmember.ldif
```

### Change Password

```bash
# Generate password hash
docker exec ldap slappasswd -s newpassword

# Create modify LDIF with the hash
cat > /tmp/chgpass.ldif << 'EOF'
dn: uid=admin,ou=users,dc=datamancy,dc=local
changetype: modify
replace: userPassword
userPassword: {SSHA}generatedHashHere
EOF

docker cp /tmp/chgpass.ldif ldap:/tmp/chgpass.ldif
docker exec ldap ldapmodify -x -H ldap://localhost -D "cn=admin,dc=datamancy,dc=local" -w "admin_password_change_me" -f /tmp/chgpass.ldif
```

### Common Issues

**Symptom:** "Can't contact LDAP server"
**Cause:** LDAP service not running or wrong hostname
**Fix:** Verify service is up: `docker ps | grep ldap`

**Symptom:** "Invalid credentials" when querying
**Cause:** Wrong admin DN or password
**Fix:** Verify admin password in docker-compose.yml matches LDAP_ADMIN_PASSWORD

**Symptom:** User can't login via Authelia
**Cause:** User doesn't exist in LDAP or wrong DN structure
**Fix:** Verify user exists: `docker exec ldap ldapsearch -x -b "dc=datamancy,dc=local" "(uid=username)"`

**Symptom:** Group membership not recognized
**Cause:** User not added to group or wrong attribute (member vs uniqueMember)
**Fix:** Check group membership: `docker exec ldap ldapsearch -x -b "ou=groups,dc=datamancy,dc=local" "(uniqueMember=uid=username,ou=users,dc=datamancy,dc=local)"`

**Symptom:** "No such object" errors
**Cause:** Base DN doesn't exist or OUs not created
**Fix:** Verify base DN and OUs exist in directory structure

## Related

- Consumers: [Authelia](authelia.md)
- Upstream docs: https://github.com/osixia/docker-openldap

---

**Last updated:** 2025-10-26
**Last change fingerprint:** TBD
