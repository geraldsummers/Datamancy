#!/bin/bash
set -e

POSTGRES_PIPELINE_PASSWORD="${POSTGRES_PIPELINE_PASSWORD:?ERROR: POSTGRES_PIPELINE_PASSWORD not set}"
POSTGRES_SEARCH_SERVICE_PASSWORD="${POSTGRES_SEARCH_SERVICE_PASSWORD:?ERROR: POSTGRES_SEARCH_SERVICE_PASSWORD not set}"
POSTGRES_TEST_RUNNER_PASSWORD="${POSTGRES_TEST_RUNNER_PASSWORD:?ERROR: POSTGRES_TEST_RUNNER_PASSWORD not set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'pipeline_user') THEN
            CREATE USER pipeline_user WITH PASSWORD \$pwd\$$POSTGRES_PIPELINE_PASSWORD\$pwd\$;
        ELSE
            ALTER USER pipeline_user WITH PASSWORD \$pwd\$$POSTGRES_PIPELINE_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'search_service_user') THEN
            CREATE USER search_service_user WITH PASSWORD \$pwd\$$POSTGRES_SEARCH_SERVICE_PASSWORD\$pwd\$;
        ELSE
            ALTER USER search_service_user WITH PASSWORD \$pwd\$$POSTGRES_SEARCH_SERVICE_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'test_runner_user') THEN
            CREATE USER test_runner_user WITH PASSWORD \$pwd\$$POSTGRES_TEST_RUNNER_PASSWORD\$pwd\$;
        ELSE
            ALTER USER test_runner_user WITH PASSWORD \$pwd\$$POSTGRES_TEST_RUNNER_PASSWORD\$pwd\$;
        END IF;
    END
    \$\$;

    \connect template1
    DROP EXTENSION IF EXISTS timescaledb CASCADE;
    \connect postgres
    DROP EXTENSION IF EXISTS timescaledb CASCADE;

    SELECT 'CREATE DATABASE datamancy OWNER pipeline_user'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'datamancy')\gexec

    GRANT ALL PRIVILEGES ON DATABASE datamancy TO pipeline_user;
    GRANT CONNECT ON DATABASE datamancy TO search_service_user;
    GRANT CONNECT ON DATABASE datamancy TO test_runner_user;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "datamancy" -c "GRANT ALL ON SCHEMA public TO pipeline_user;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "datamancy" -c "GRANT USAGE ON SCHEMA public TO search_service_user;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "datamancy" -c "GRANT USAGE ON SCHEMA public TO test_runner_user;"
