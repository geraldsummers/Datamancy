#!/bin/bash
# Fix missing PostgreSQL users on existing database
# Run this after postgres container is started: ./fix-missing-postgres-users.sh

set -e

echo "Creating missing PostgreSQL users..."

# Source environment variables from .env file if it exists
if [ -f .env ]; then
    source .env
else
    echo "ERROR: .env file not found"
    exit 1
fi

# Check required passwords are set
for var in POSTGRES_FORGEJO_PASSWORD POSTGRES_TXGATEWAY_PASSWORD \
           POSTGRES_PIPELINE_PASSWORD POSTGRES_SEARCH_SERVICE_PASSWORD \
           POSTGRES_TEST_RUNNER_PASSWORD; do
    if [ -z "${!var}" ]; then
        echo "ERROR: $var not set in environment"
        exit 1
    fi
done

# Create missing users
docker exec -i postgres psql -U postgres <<EOSQL
DO \$\$
BEGIN
    -- Create forgejo user if missing
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'forgejo') THEN
        CREATE USER forgejo WITH PASSWORD '${POSTGRES_FORGEJO_PASSWORD}';
        RAISE NOTICE 'Created user: forgejo';
    ELSE
        RAISE NOTICE 'User forgejo already exists';
    END IF;

    -- Create txgateway user if missing
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'txgateway') THEN
        CREATE USER txgateway WITH PASSWORD '${POSTGRES_TXGATEWAY_PASSWORD}';
        RAISE NOTICE 'Created user: txgateway';
    ELSE
        RAISE NOTICE 'User txgateway already exists';
    END IF;

    -- Create pipeline_user if missing
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'pipeline_user') THEN
        CREATE USER pipeline_user WITH PASSWORD '${POSTGRES_PIPELINE_PASSWORD}';
        RAISE NOTICE 'Created user: pipeline_user';
    ELSE
        RAISE NOTICE 'User pipeline_user already exists';
    END IF;

    -- Create search_service_user if missing
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'search_service_user') THEN
        CREATE USER search_service_user WITH PASSWORD '${POSTGRES_SEARCH_SERVICE_PASSWORD}';
        RAISE NOTICE 'Created user: search_service_user';
    ELSE
        RAISE NOTICE 'User search_service_user already exists';
    END IF;

    -- Create test_runner_user if missing
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'test_runner_user') THEN
        CREATE USER test_runner_user WITH PASSWORD '${POSTGRES_TEST_RUNNER_PASSWORD}';
        RAISE NOTICE 'Created user: test_runner_user';
    ELSE
        RAISE NOTICE 'User test_runner_user already exists';
    END IF;
END
\$\$;

-- Create databases if missing
SELECT 'CREATE DATABASE forgejo OWNER forgejo'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'forgejo')\gexec

SELECT 'CREATE DATABASE txgateway OWNER txgateway'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'txgateway')\gexec

SELECT 'CREATE DATABASE datamancy OWNER pipeline_user'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'datamancy')\gexec

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE forgejo TO forgejo;
GRANT ALL PRIVILEGES ON DATABASE txgateway TO txgateway;
GRANT ALL PRIVILEGES ON DATABASE datamancy TO pipeline_user;
GRANT CONNECT ON DATABASE datamancy TO search_service_user;
GRANT CONNECT ON DATABASE datamancy TO test_runner_user;

EOSQL

echo "✓ Missing PostgreSQL users created successfully"
