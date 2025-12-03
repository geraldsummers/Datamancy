#!/bin/bash
# Setup BookStack init scripts
# Copies init scripts from templates to volumes directory

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

TEMPLATE_DIR="$PROJECT_ROOT/configs.templates/applications/bookstack/init"
TARGET_DIR="$PROJECT_ROOT/volumes/bookstack_init"

echo "Setting up BookStack init scripts..."
echo "Source: $TEMPLATE_DIR"
echo "Target: $TARGET_DIR"

# Create target directory
mkdir -p "$TARGET_DIR"

# Copy all scripts
if [ -d "$TEMPLATE_DIR" ]; then
    cp -v "$TEMPLATE_DIR"/*.sh "$TARGET_DIR/" 2>/dev/null || true
    chmod +x "$TARGET_DIR"/*.sh 2>/dev/null || true
    echo "✓ BookStack init scripts copied successfully"
else
    echo "⚠ Warning: Template directory not found: $TEMPLATE_DIR"
    exit 1
fi
