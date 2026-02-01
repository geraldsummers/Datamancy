#!/bin/bash
# Forgejo Actions Setup Script
# Enables Actions and generates runner registration token

set -e

echo "=== Forgejo Actions Setup ==="

# Wait for Forgejo to be fully ready
echo "Waiting for Forgejo to be ready..."
until curl -sf http://localhost:3000/api/healthz > /dev/null; do
    sleep 2
done

echo "Forgejo is ready!"

# Check if actions are already enabled
ACTIONS_ENABLED=$(forgejo --config /data/gitea/conf/app.ini admin config get actions.ENABLED 2>/dev/null || echo "false")

if [ "$ACTIONS_ENABLED" = "true" ]; then
    echo "✓ Actions already enabled"
else
    echo "▸ Enabling Forgejo Actions..."

    # Enable actions in configuration
    forgejo --config /data/gitea/conf/app.ini admin config set actions ENABLED true
    forgejo --config /data/gitea/conf/app.ini admin config set actions DEFAULT_ACTIONS_URL "https://code.forgejo.org"

    echo "✓ Actions enabled"
fi

# Generate runner registration token if it doesn't exist in environment
if [ -z "${FORGEJO_RUNNER_TOKEN}" ]; then
    echo "▸ Generating runner registration token..."

    # Use forgejo CLI to generate token (requires admin privileges)
    # This creates a token that runners can use to register
    TOKEN=$(forgejo --config /data/gitea/conf/app.ini actions generate-runner-token 2>/dev/null || echo "")

    if [ -n "$TOKEN" ]; then
        echo "✓ Runner token generated"
        echo ""
        echo "Add this to your .env file:"
        echo "FORGEJO_RUNNER_TOKEN=${TOKEN}"
        echo ""
    else
        echo "⚠️  Could not auto-generate token"
        echo "Generate manually in Forgejo UI:"
        echo "  Site Administration > Actions > Runners > Create new Runner"
        echo ""
    fi
else
    echo "✓ Runner token already configured in environment"
fi

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Next steps:"
echo "1. Add FORGEJO_RUNNER_TOKEN to .env"
echo "2. Start forgejo-runner: docker-compose up -d forgejo-runner"
echo "3. Verify runner: Check Site Admin > Actions > Runners in Forgejo UI"
echo ""
