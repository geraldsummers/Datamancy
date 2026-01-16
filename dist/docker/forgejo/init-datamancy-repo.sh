#!/bin/bash
set -e

DATA_DIR="/data/git/repositories/datamancy"
MARKER_FILE="/data/.datamancy-repo-initialized"
SOURCE_DIR="/tmp/datamancy-source"

# Only run on first initialization
if [ -f "$MARKER_FILE" ]; then
    echo "Datamancy repository already initialized, skipping..."
    exit 0
fi

echo "Initializing Datamancy repository..."

# Wait for Forgejo to be ready
MAX_RETRIES=30
RETRY_COUNT=0
while ! nc -z localhost 3000; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo "ERROR: Forgejo did not become ready in time"
        exit 1
    fi
    echo "Waiting for Forgejo to start... ($RETRY_COUNT/$MAX_RETRIES)"
    sleep 2
done

sleep 5  # Extra time for full initialization

# Create datamancy user via CLI
echo "Creating datamancy system user..."
forgejo admin user create \
    --admin \
    --username datamancy \
    --password "${STACK_ADMIN_PASSWORD}" \
    --email "${STACK_ADMIN_EMAIL}" \
    --must-change-password=false \
    || echo "User may already exist, continuing..."

# Create repository directory structure
mkdir -p "$DATA_DIR"
cd "$DATA_DIR"

# Initialize bare git repository
git init --bare

# Configure repository
git config --file config core.bare true
git config --file config receive.denyNonFastForwards false

# Create a temporary working directory to populate the repo
WORK_DIR=$(mktemp -d)
cd "$WORK_DIR"

git init
git config user.name "Datamancy System"
git config user.email "${STACK_ADMIN_EMAIL}"

# Copy source files (excluding build artifacts, dist/, etc.)
rsync -av \
    --exclude='.gradle/' \
    --exclude='build/' \
    --exclude='dist/' \
    --exclude='.git/' \
    --exclude='*.class' \
    --exclude='*.jar' \
    --exclude='.idea/' \
    --exclude='*.iml' \
    --exclude='.env' \
    --exclude='volumes/' \
    "$SOURCE_DIR/" ./

# Create .gitignore for the repo
cat > .gitignore <<'EOF'
# Build artifacts
.gradle/
build/
dist/
*.class
*.jar
*.war

# IDE
.idea/
*.iml
.vscode/

# Secrets
.env
*.key
*.pem

# Runtime
volumes/
logs/
EOF

# Create initial commit
git add .
git commit -m "Initial commit: Datamancy infrastructure-as-code

This repository contains the complete Datamancy stack configuration:
- Service compose templates
- Configuration templates
- Build system
- Service registry

Secrets and runtime values are managed via environment variables.
"

# Push to the bare repository
git remote add origin "$DATA_DIR"
git push origin master

# Cleanup
cd /
rm -rf "$WORK_DIR"

# Update repository ownership
chown -R git:git "$DATA_DIR"

# Create marker file
touch "$MARKER_FILE"
chown git:git "$MARKER_FILE"

echo "Datamancy repository initialized successfully!"
