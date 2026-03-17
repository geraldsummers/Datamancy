#!/bin/bash
set -e
export FORGEJO_WORK_DIR=/data/gitea
export FORGEJO_CUSTOM=/data/gitea

run_forgejo() {
    local cmd="$*"
    su -s /bin/sh git -c "FORGEJO_WORK_DIR=/data/gitea FORGEJO_CUSTOM=/data/gitea forgejo --config /data/gitea/conf/app.ini $cmd"
}

check_authelia() {
    if command -v wget >/dev/null 2>&1; then
        wget --quiet --tries=1 --timeout=3 -O /dev/null "$1"
        return $?
    fi
    if command -v curl >/dev/null 2>&1; then
        curl -fsS --max-time 3 "$1" >/dev/null
        return $?
    fi
    return 1
}

ensure_forgejo_api_user() {
    local api_username="${FORGEJO_USERNAME:-${FORGEJO_API_USERNAME:-${STACK_ADMIN_USER:-sysadmin}}}"
    local api_email="${FORGEJO_EMAIL:-${FORGEJO_API_EMAIL:-${STACK_ADMIN_EMAIL:-admin@datamancy.net}}}"
    local api_password="${FORGEJO_PASSWORD:-${FORGEJO_API_PASSWORD:-${STACK_ADMIN_PASSWORD:-}}}"

    if [ -z "$api_password" ]; then
        echo "STACK_ADMIN_PASSWORD/FORGEJO_PASSWORD is empty; skipping Forgejo API user bootstrap."
        return 0
    fi

    if run_forgejo admin user list 2>/dev/null | awk 'NR>1 { print $2 }' | grep -Fxq "$api_username"; then
        echo "Forgejo API user '$api_username' already exists, refreshing password..."
        run_forgejo admin user change-password --username "$api_username" --password "$api_password"
        return 0
    fi

    echo "Creating Forgejo API user '$api_username'..."
    run_forgejo admin user create \
        --username "$api_username" \
        --password "$api_password" \
        --email "$api_email" \
        --admin \
        --must-change-password=false
}

echo "Waiting for Forgejo to be ready..."
for i in $(seq 1 60); do
    if run_forgejo admin auth list >/dev/null 2>&1; then
        echo "Forgejo is ready."
        break
    fi
    echo "Forgejo not ready yet (attempt $i/60), waiting..."
    sleep 2
done

ensure_forgejo_api_user

if run_forgejo admin auth list 2>/dev/null | grep -q "Authelia"; then
    echo "Authelia OIDC authentication source already exists, skipping setup."
    exit 0
fi

echo "Waiting for Authelia OIDC discovery endpoint..."
AUTHELIA_DISCOVERY_URL="https://auth.${DOMAIN}/.well-known/openid-configuration"
for i in $(seq 1 60); do
    if check_authelia "$AUTHELIA_DISCOVERY_URL"; then
        echo "Authelia discovery endpoint is available."
        break
    fi
    echo "Authelia not ready yet (attempt $i/60), waiting..."
    sleep 2
done

echo "Adding Authelia OIDC authentication source to Forgejo..."
run_forgejo admin auth add-oauth \
    --name 'Authelia' \
    --provider 'openidConnect' \
    --key 'forgejo' \
    --secret "${FORGEJO_OAUTH_SECRET}" \
    --auto-discover-url "${AUTHELIA_DISCOVERY_URL}" \
    --scopes openid \
    --scopes profile \
    --scopes email \
    --scopes groups \
    --group-claim-name 'groups' \
    --restricted-group '' \
    --skip-local-2fa
echo "Forgejo OIDC configuration completed successfully!"
echo "Users can now sign in with the 'Sign in with Authelia' button."
