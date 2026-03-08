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

echo "Waiting for Forgejo to be ready..."
for i in $(seq 1 60); do
    if run_forgejo admin auth list >/dev/null 2>&1; then
        echo "Forgejo is ready."
        break
    fi
    echo "Forgejo not ready yet (attempt $i/60), waiting..."
    sleep 2
done

if run_forgejo admin auth list 2>/dev/null | grep -q "Authelia"; then
    echo "Authelia OIDC authentication source already exists, skipping setup."
    exit 0
fi

echo "Waiting for Authelia OIDC discovery endpoint..."
AUTHELIA_DISCOVERY_URL="http://authelia:9091/.well-known/openid-configuration"
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
