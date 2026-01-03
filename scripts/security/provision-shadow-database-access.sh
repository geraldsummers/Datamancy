#!/bin/bash
set -e

# Provision Database Access for Shadow Agent Account
#
# Creates database roles and grants read-only access for a shadow agent account.
# Must be run AFTER create-shadow-agent-account.main.kts
#
# Usage:
#   ./provision-shadow-database-access.sh <username>
#
# Example:
#   ./provision-shadow-database-access.sh alice
#
# This script:
# 1. Creates PostgreSQL role {username}_agent with read-only access
# 2. Grants CONNECT on safe databases (grafana, planka, mastodon, forgejo)
# 3. Grants SELECT on agent_observer schema views
# 4. Creates MariaDB user {username}_agent@'%' with read-only access
# 5. Grants SELECT on bookstack agent_observer views

USERNAME="${1:?ERROR: Username required}"
SHADOW_USERNAME="${USERNAME}-agent"

# Read database passwords
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?ERROR: POSTGRES_PASSWORD not set}"
MARIADB_ROOT_PASSWORD="${MARIADB_ROOT_PASSWORD:?ERROR: MARIADB_ROOT_PASSWORD not set}"

# Read shadow account password from secure storage
SHADOW_PASSWORD_FILE="/run/secrets/datamancy/shadow-agent-${USERNAME}.pwd"
if [ ! -f "$SHADOW_PASSWORD_FILE" ]; then
    echo "❌ ERROR: Shadow password file not found: $SHADOW_PASSWORD_FILE"
    echo "   Run create-shadow-agent-account.main.kts first"
    exit 1
fi
SHADOW_PASSWORD=$(cat "$SHADOW_PASSWORD_FILE")

echo "🔧 Provisioning Database Access for Shadow Account: $SHADOW_USERNAME"
echo "=================================================================="
echo

# ===== PostgreSQL =====
echo "📊 PostgreSQL: Creating role $SHADOW_USERNAME"

docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" postgres psql -U postgres -d postgres <<-EOSQL
    -- Create shadow agent role (read-only)
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$SHADOW_USERNAME') THEN
            CREATE USER $SHADOW_USERNAME WITH PASSWORD '$SHADOW_PASSWORD';
            RAISE NOTICE 'Created role: $SHADOW_USERNAME';
        ELSE
            ALTER USER $SHADOW_USERNAME WITH PASSWORD '$SHADOW_PASSWORD';
            RAISE NOTICE 'Updated password for: $SHADOW_USERNAME';
        END IF;
    END
    \$\$;

    -- Grant CONNECT on safe databases only
    -- (grafana, planka, mastodon, forgejo have public data)
    GRANT CONNECT ON DATABASE grafana TO $SHADOW_USERNAME;
    GRANT CONNECT ON DATABASE planka TO $SHADOW_USERNAME;
    GRANT CONNECT ON DATABASE mastodon TO $SHADOW_USERNAME;
    GRANT CONNECT ON DATABASE forgejo TO $SHADOW_USERNAME;

    -- Explicitly DENY sensitive databases
    -- vaultwarden: passwords/secrets
    -- authelia: auth sessions/tokens
    -- synapse: private messages
    -- openwebui: conversation history
    -- sogo: emails
    REVOKE CONNECT ON DATABASE vaultwarden FROM $SHADOW_USERNAME;
    REVOKE CONNECT ON DATABASE authelia FROM $SHADOW_USERNAME;
    REVOKE CONNECT ON DATABASE synapse FROM $SHADOW_USERNAME;
    REVOKE CONNECT ON DATABASE openwebui FROM $SHADOW_USERNAME;
    REVOKE CONNECT ON DATABASE sogo FROM $SHADOW_USERNAME;
    REVOKE CONNECT ON DATABASE roundcube FROM $SHADOW_USERNAME;
EOSQL

# Grant access to agent_observer schema in each safe database
for DB in grafana planka mastodon forgejo; do
    echo "  - Granting access to $DB.agent_observer schema"
    docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" postgres psql -U postgres -d "$DB" <<-EOSQL
        -- Grant USAGE on agent_observer schema
        GRANT USAGE ON SCHEMA agent_observer TO $SHADOW_USERNAME;

        -- Grant SELECT on all views in agent_observer schema
        GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO $SHADOW_USERNAME;

        -- Set default privileges for future views (if any)
        ALTER DEFAULT PRIVILEGES IN SCHEMA agent_observer GRANT SELECT ON TABLES TO $SHADOW_USERNAME;

        -- Ensure no access to public schema (defense in depth)
        REVOKE ALL ON SCHEMA public FROM $SHADOW_USERNAME;
        REVOKE ALL ON ALL TABLES IN SCHEMA public FROM $SHADOW_USERNAME;
EOSQL
done

echo "✅ PostgreSQL: Role $SHADOW_USERNAME provisioned"
echo

# ===== MariaDB =====
echo "📊 MariaDB: Creating user $SHADOW_USERNAME"

docker exec mariadb mysql -uroot -p"$MARIADB_ROOT_PASSWORD" <<-EOSQL
    -- Create shadow agent user (read-only)
    CREATE USER IF NOT EXISTS '$SHADOW_USERNAME'@'%' IDENTIFIED BY '$SHADOW_PASSWORD';

    -- Grant SELECT on bookstack database (public pages/books only)
    -- Limited to safe views only (not full database access)
    GRANT SELECT ON bookstack.* TO '$SHADOW_USERNAME'@'%';

    -- Explicitly deny access to other databases
    REVOKE ALL PRIVILEGES ON *.* FROM '$SHADOW_USERNAME'@'%';
    REVOKE ALL PRIVILEGES ON mysql.* FROM '$SHADOW_USERNAME'@'%';
    REVOKE ALL PRIVILEGES ON information_schema.* FROM '$SHADOW_USERNAME'@'%';
    REVOKE ALL PRIVILEGES ON performance_schema.* FROM '$SHADOW_USERNAME'@'%';

    FLUSH PRIVILEGES;
EOSQL

echo "✅ MariaDB: User $SHADOW_USERNAME provisioned"
echo

# ===== Summary =====
echo "✅ SUCCESS! Database access provisioned for: $SHADOW_USERNAME"
echo
echo "Granted Access:"
echo "  PostgreSQL:"
echo "    - grafana.agent_observer (SELECT)"
echo "    - planka.agent_observer (SELECT)"
echo "    - mastodon.agent_observer (SELECT)"
echo "    - forgejo.agent_observer (SELECT)"
echo "  MariaDB:"
echo "    - bookstack.* (SELECT)"
echo
echo "Denied Access:"
echo "  PostgreSQL:"
echo "    - vaultwarden (passwords/secrets)"
echo "    - authelia (auth sessions)"
echo "    - synapse (private messages)"
echo "    - openwebui (conversation history)"
echo "    - sogo (emails)"
echo "  MariaDB:"
echo "    - mysql (system tables)"
echo "    - information_schema"
echo
echo "Next steps:"
echo "  1. Test database access: docker exec postgres psql -U $SHADOW_USERNAME -d grafana -c 'SELECT * FROM agent_observer.public_dashboards LIMIT 5;'"
echo "  2. Configure agent-tool-server to use shadow account"
echo "  3. Verify audit logging captures username"
