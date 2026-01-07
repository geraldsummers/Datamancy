#!/bin/bash
# Wrapper script to substitute environment variables in init.sql
# MariaDB will execute this as part of /docker-entrypoint-initdb.d/

set -e

# Use envsubst to replace environment variables in the template
envsubst < /docker-entrypoint-initdb.d/init-template.sql | mysql -u root -p"${MYSQL_ROOT_PASSWORD}"

echo "MariaDB initialization completed with environment variable substitution"
