#!/bin/bash
# Generate SSHA password hashes for LDAP users

# This script generates the password hashes used in bootstrap.ldif
# Password: TestAuth123!

echo "Generating SSHA password hash for: TestAuth123!"
slappasswd -s "TestAuth123!" -h {SSHA}

echo ""
echo "Use this hash in the bootstrap.ldif file for userPassword attribute"
