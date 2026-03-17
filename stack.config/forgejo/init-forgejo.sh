#!/bin/bash
set -e
export FORGEJO_WORK_DIR=/data/gitea
export FORGEJO_CUSTOM=/data/gitea

run_forgejo() {
    local cmd="$*"
    su -s /bin/sh git -c "FORGEJO_WORK_DIR=/data/gitea FORGEJO_CUSTOM=/data/gitea forgejo --config /data/gitea/conf/app.ini $cmd"
}

retry_command() {
    local attempts="$1"
    local delay_seconds="$2"
    local description="$3"
    shift 3

    for i in $(seq 1 "$attempts"); do
        if "$@"; then
            return 0
        fi
        echo "${description} failed (attempt ${i}/${attempts}), retrying in ${delay_seconds}s..."
        sleep "$delay_seconds"
    done

    echo "${description} failed after ${attempts} attempts."
    return 1
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

wait_for_forgejo_schema() {
    echo "Waiting for Forgejo user schema to be ready..."
    for i in $(seq 1 120); do
        if run_forgejo admin user list >/dev/null 2>&1; then
            echo "Forgejo user schema is ready."
            return 0
        fi
        echo "Forgejo schema not ready yet (attempt $i/120), waiting..."
        sleep 2
    done
    echo "Forgejo schema did not become ready in time."
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

    for i in $(seq 1 40); do
        if run_forgejo admin user list 2>/dev/null | awk 'NR>1 { print $2 }' | grep -Fxq "$api_username"; then
            echo "Forgejo API user '$api_username' already exists, refreshing password..."
            if run_forgejo admin user change-password \
                --username "$api_username" \
                --password "$api_password" \
                --must-change-password=false; then
                return 0
            fi
            echo "Unable to refresh password for '$api_username' (attempt $i/40), retrying..."
            sleep 2
            continue
        fi

        echo "Creating Forgejo API user '$api_username'..."
        if run_forgejo admin user create \
            --username "$api_username" \
            --password "$api_password" \
            --email "$api_email" \
            --admin \
            --must-change-password=false; then
            return 0
        fi
        echo "Unable to create user '$api_username' (attempt $i/40), retrying..."
        sleep 2
    done

    echo "Failed to ensure Forgejo API user '$api_username'."
    return 1
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

retry_command 3 2 "Forgejo CLI readiness" run_forgejo admin auth list
wait_for_forgejo_schema
ensure_forgejo_api_user

if run_forgejo admin auth list 2>/dev/null | grep -q "Authelia"; then
    echo "Authelia OIDC authentication source already exists, skipping setup."
    exit 0
fi

echo "Waiting for Authelia OIDC discovery endpoint..."
AUTHELIA_DISCOVERY_URL="https://auth.${DOMAIN}/.well-known/openid-configuration"
authelia_ready=0
for i in $(seq 1 60); do
    if check_authelia "$AUTHELIA_DISCOVERY_URL"; then
        echo "Authelia discovery endpoint is available."
        authelia_ready=1
        break
    fi
    echo "Authelia not ready yet (attempt $i/60), waiting..."
    sleep 2
done
if [ "$authelia_ready" -ne 1 ]; then
    echo "Authelia discovery endpoint did not become available in time."
    exit 1
fi

echo "Adding Authelia OIDC authentication source to Forgejo..."
for i in $(seq 1 30); do
    if run_forgejo admin auth add-oauth \
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
        --skip-local-2fa; then
        echo "Forgejo OIDC configuration completed successfully!"
        echo "Users can now sign in with the 'Sign in with Authelia' button."
        exit 0
    fi

    if run_forgejo admin auth list 2>/dev/null | grep -q "Authelia"; then
        echo "Authelia OIDC authentication source already exists."
        echo "Users can now sign in with the 'Sign in with Authelia' button."
        exit 0
    fi

    echo "Failed to add Authelia OIDC auth source (attempt $i/30), retrying..."
    sleep 2
done

echo "Failed to configure Authelia OIDC source in Forgejo."
exit 1
