#!/bin/bash
set -e
export FORGEJO_WORK_DIR=/data/gitea
export FORGEJO_CUSTOM=/data/gitea

run_forgejo() {
    local cmd="$*"
    su -s /bin/sh git -c "FORGEJO_WORK_DIR=/data/gitea FORGEJO_CUSTOM=/data/gitea forgejo --config /data/gitea/conf/app.ini $cmd"
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
echo "Adding Authelia OIDC authentication source to Forgejo..."
run_forgejo admin auth add-oauth \
    --name 'Authelia' \
    --provider 'openidConnect' \
    --key 'forgejo' \
    --secret "${FORGEJO_OAUTH_SECRET}" \
    --auto-discover-url "https://auth.${DOMAIN}/.well-known/openid-configuration" \
    --scopes 'openid profile email groups' \
    --group-claim-name 'groups' \
    --restricted-group '' \
    --auto-register \
    --skip-local-2fa
echo "Forgejo OIDC configuration completed successfully!"
echo "Users can now sign in with the 'Sign in with Authelia' button."
