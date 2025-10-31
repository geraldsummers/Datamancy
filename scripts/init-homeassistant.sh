#!/bin/sh
# Initialize Home Assistant - bypass onboarding wizard
set -e

echo "=== Home Assistant Initialization ==="

if [ ! -f /config/.storage/onboarding ]; then
  echo "Creating onboarding bypass file..."
  mkdir -p /config/.storage

  cat > /config/.storage/onboarding <<'EOF'
{
  "version": 1,
  "minor_version": 3,
  "key": "onboarding",
  "data": {
    "done": ["user", "core_config", "integration", "analytics"]
  }
}
EOF

  echo "✅ Home Assistant onboarding bypass created"
else
  echo "✅ Home Assistant already initialized"
fi

echo "=== Home Assistant initialization complete ==="
