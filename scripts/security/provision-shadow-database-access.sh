#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <create|delete> <username> [password]" >&2
  exit 1
}

require_container() {
  local name="$1"
  if ! docker ps --format '{{.Names}}' | grep -Fxq "$name"; then
    echo "Required container '$name' is not running" >&2
    exit 1
  fi
}

normalize_base_username() {
  local raw="$1"
  if [[ "$raw" == *-agent ]]; then
    raw="${raw%-agent}"
  fi
  if [[ ! "$raw" =~ ^[A-Za-z0-9_.-]{1,64}$ ]]; then
    echo "Invalid username: $1" >&2
    exit 1
  fi
  printf '%s' "$raw"
}

command -v docker >/dev/null 2>&1 || {
  echo "docker is required" >&2
  exit 1
}

action="${1:-}"
base_username="${2:-}"
password="${3:-}"
[[ -n "$action" && -n "$base_username" ]] || usage
[[ "$action" == "create" || "$action" == "delete" ]] || usage
if [[ "$action" == "create" && -z "$password" ]]; then
  usage
fi

base_username="$(normalize_base_username "$base_username")"
shadow_username="${base_username}-agent"
postgres_container="${POSTGRES_CONTAINER:-postgres}"
mariadb_container="${MARIADB_CONTAINER:-mariadb}"

require_container "$postgres_container"
require_container "$mariadb_container"

if [[ "$action" == "create" ]]; then
  docker exec -i \
    -e SHADOW_DB_USERNAME="$shadow_username" \
    -e SHADOW_DB_PASSWORD="$password" \
    "$postgres_container" \
    sh -lc 'psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v shadow_user="$SHADOW_DB_USERNAME" -v shadow_password="$SHADOW_DB_PASSWORD"' <<'EOSQL'
SELECT format('CREATE USER %I WITH PASSWORD %L', :'shadow_user', :'shadow_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'shadow_user')\gexec
SELECT format('ALTER USER %I WITH PASSWORD %L', :'shadow_user', :'shadow_password')
WHERE EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'shadow_user')\gexec
EOSQL

  for db in grafana planka mastodon forgejo; do
    docker exec -i \
      -e SHADOW_DB_USERNAME="$shadow_username" \
      -e TARGET_DB="$db" \
      "$postgres_container" \
      sh -lc 'psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$TARGET_DB" -v shadow_user="$SHADOW_DB_USERNAME"' <<'EOSQL'
CREATE SCHEMA IF NOT EXISTS agent_observer;
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'shadow_user')\gexec
SELECT format('GRANT USAGE ON SCHEMA agent_observer TO %I', :'shadow_user')\gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO %I', :'shadow_user')\gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA agent_observer GRANT SELECT ON TABLES TO %I', :'shadow_user')\gexec
EOSQL
  done

  docker exec -i \
    -e SHADOW_DB_USERNAME="$shadow_username" \
    -e SHADOW_DB_PASSWORD="$password" \
    "$mariadb_container" \
    sh -lc 'mariadb -u root -p"$MYSQL_ROOT_PASSWORD" --ssl=0' <<EOSQL
CREATE USER IF NOT EXISTS '${shadow_username}'@'%' IDENTIFIED BY '${password}';
ALTER USER '${shadow_username}'@'%' IDENTIFIED BY '${password}';
GRANT SELECT ON bookstack.* TO '${shadow_username}'@'%';
GRANT SELECT ON ccnet.* TO '${shadow_username}'@'%';
GRANT SELECT ON seafile.* TO '${shadow_username}'@'%';
GRANT SELECT ON seahub.* TO '${shadow_username}'@'%';
GRANT SELECT ON ccnet_db.* TO '${shadow_username}'@'%';
GRANT SELECT ON seafile_db.* TO '${shadow_username}'@'%';
GRANT SELECT ON seahub_db.* TO '${shadow_username}'@'%';
FLUSH PRIVILEGES;
EOSQL
else
  for db in grafana planka mastodon forgejo; do
    docker exec -i \
      -e SHADOW_DB_USERNAME="$shadow_username" \
      -e TARGET_DB="$db" \
      "$postgres_container" \
      sh -lc 'psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$TARGET_DB" -v shadow_user="$SHADOW_DB_USERNAME"' <<'EOSQL'
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'shadow_user') THEN
    EXECUTE format('DROP OWNED BY %I', :'shadow_user');
  END IF;
END $$;
EOSQL
  done

  docker exec -i \
    -e SHADOW_DB_USERNAME="$shadow_username" \
    "$postgres_container" \
    sh -lc 'psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v shadow_user="$SHADOW_DB_USERNAME"' <<'EOSQL'
SELECT format('DROP ROLE IF EXISTS %I', :'shadow_user')\gexec
EOSQL

  docker exec -i "$mariadb_container" sh -lc 'mariadb -u root -p"$MYSQL_ROOT_PASSWORD" --ssl=0' <<EOSQL
DROP USER IF EXISTS '${shadow_username}'@'%';
FLUSH PRIVILEGES;
EOSQL
fi

echo "${action^}d shadow database access for ${shadow_username}"
