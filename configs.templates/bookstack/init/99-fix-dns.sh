#!/bin/bash
if ! grep -q "mariadb" /etc/hosts; then
    MARIADB_IP=$(getent ahosts mariadb.datamancy_backend | head -n 1 | awk '{print $1}')
    if [ -z "$MARIADB_IP" ]; then
        MARIADB_IP=$(getent ahosts tasks.mariadb | head -n 1 | awk '{print $1}')
    fi
    if [ -n "$MARIADB_IP" ]; then
        echo "$MARIADB_IP mariadb" >> /etc/hosts
        echo "Added mariadb ($MARIADB_IP) to /etc/hosts"
    fi
fi
