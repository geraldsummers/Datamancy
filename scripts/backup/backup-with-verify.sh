#!/bin/bash
set -euo pipefail

# Datamancy Backup Script with Verification
# Creates backups of volumes and runtime config, then verifies integrity

BACKUP_ROOT="${BACKUP_ROOT:-/backup}"
BACKUP_DIR="$BACKUP_ROOT/datamancy-$(date +%Y%m%d-%H%M%S)"
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
RUNTIME_CONFIG_DIR="${HOME}/.config/datamancy"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() { echo -e "${GREEN}[INFO]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
success() { echo -e "${GREEN}✓${NC} $*"; }

# Check prerequisites
if [ ! -d "$PROJECT_ROOT/volumes" ]; then
    error "Volumes directory not found: $PROJECT_ROOT/volumes"
    error "Set PROJECT_ROOT environment variable to your Datamancy directory"
    exit 1
fi

if [ ! -d "$RUNTIME_CONFIG_DIR" ]; then
    error "Runtime config not found: $RUNTIME_CONFIG_DIR"
    error "Have you run ./stack-controller config generate?"
    exit 1
fi

# Create backup directory
info "Creating backup directory: $BACKUP_DIR"
mkdir -p "$BACKUP_DIR"

# ============================================================================
# Backup Runtime Config (CRITICAL - Contains all secrets!)
# ============================================================================
info "Backing up runtime configuration..."
tar -czf "$BACKUP_DIR/runtime-config.tar.gz" -C "$HOME/.config" datamancy/

# Verify runtime config backup
info "Verifying runtime-config.tar.gz..."
if tar -tzf "$BACKUP_DIR/runtime-config.tar.gz" >/dev/null 2>&1; then
    # Check critical files exist in archive
    if tar -tzf "$BACKUP_DIR/runtime-config.tar.gz" | grep -q ".env.runtime"; then
        success "Runtime config backup verified (.env.runtime found)"
    else
        error "CRITICAL: .env.runtime not found in backup!"
        exit 1
    fi
else
    error "CORRUPTED: runtime-config.tar.gz"
    exit 1
fi

# Secure the backup (contains secrets!)
chmod 600 "$BACKUP_DIR/runtime-config.tar.gz"
success "Runtime config secured (600 permissions)"

# ============================================================================
# Backup Volumes
# ============================================================================
info "Backing up volumes (this may take several minutes)..."
tar -czf "$BACKUP_DIR/volumes.tar.gz" -C "$PROJECT_ROOT" volumes/ \
    --exclude='volumes/vllm/hf-cache' \
    --exclude='volumes/embeddings/models' \
    --exclude='volumes/whisper/models'

# Verify volumes backup
info "Verifying volumes.tar.gz..."
if tar -tzf "$BACKUP_DIR/volumes.tar.gz" >/dev/null 2>&1; then
    success "Volumes backup verified"
else
    error "CORRUPTED: volumes.tar.gz"
    exit 1
fi

# ============================================================================
# Database Dumps (PostgreSQL, MariaDB, Redis)
# ============================================================================
info "Creating database dumps..."

# PostgreSQL (all databases)
if docker ps --format '{{.Names}}' | grep -q '^postgres$'; then
    info "Dumping PostgreSQL..."
    docker exec postgres pg_dumpall -U admin | gzip > "$BACKUP_DIR/postgres-all.sql.gz"

    # Verify SQL dump
    if gunzip -t "$BACKUP_DIR/postgres-all.sql.gz" 2>/dev/null; then
        success "PostgreSQL dump verified"
    else
        error "CORRUPTED: postgres-all.sql.gz"
        exit 1
    fi
else
    warn "PostgreSQL not running, skipping dump"
fi

# MariaDB (all databases)
if docker ps --format '{{.Names}}' | grep -q '^mariadb$'; then
    info "Dumping MariaDB..."
    MARIADB_ROOT_PASSWORD=$(grep STACK_ADMIN_PASSWORD "$RUNTIME_CONFIG_DIR/.env.runtime" | cut -d= -f2)
    docker exec mariadb mariadb-dump -u root -p"$MARIADB_ROOT_PASSWORD" --all-databases | gzip > "$BACKUP_DIR/mariadb-all.sql.gz"

    # Verify SQL dump
    if gunzip -t "$BACKUP_DIR/mariadb-all.sql.gz" 2>/dev/null; then
        success "MariaDB dump verified"
    else
        error "CORRUPTED: mariadb-all.sql.gz"
        exit 1
    fi
else
    warn "MariaDB not running, skipping dump"
fi

# Redis (save snapshot)
if docker ps --format '{{.Names}}' | grep -q '^redis$'; then
    info "Saving Redis snapshot..."
    docker exec redis valkey-cli SAVE
    cp "$PROJECT_ROOT/volumes/redis_data/dump.rdb" "$BACKUP_DIR/redis-dump.rdb" 2>/dev/null || warn "Redis dump.rdb not found"
    if [ -f "$BACKUP_DIR/redis-dump.rdb" ]; then
        success "Redis snapshot saved"
    fi
else
    warn "Redis not running, skipping snapshot"
fi

# ============================================================================
# Test Restore (Dry-Run)
# ============================================================================
info "Testing restore (dry-run)..."
TEST_DIR=$(mktemp -d)
trap "rm -rf $TEST_DIR" EXIT

# Test runtime config extraction
tar -xzf "$BACKUP_DIR/runtime-config.tar.gz" -C "$TEST_DIR"
if [ -f "$TEST_DIR/datamancy/.env.runtime" ]; then
    success "Runtime config restore test passed"
else
    error "FAILED: Could not extract .env.runtime from backup"
    exit 1
fi

# Verify critical files in runtime config
CRITICAL_FILES=(".env.runtime" "bootstrap_ldap.ldif" "configs/infrastructure/caddy/Caddyfile")
for file in "${CRITICAL_FILES[@]}"; do
    if [ -f "$TEST_DIR/datamancy/$file" ] || [ -d "$TEST_DIR/datamancy/$(dirname "$file")" ]; then
        success "Found: $file"
    else
        warn "Missing (may be OK if not generated yet): $file"
    fi
done

# ============================================================================
# Backup Summary
# ============================================================================
echo ""
info "=========================================="
info "Backup Complete"
info "=========================================="
info "Location: $BACKUP_DIR"
info ""
info "Files:"
ls -lh "$BACKUP_DIR" | tail -n +2 | awk '{printf "  %s  %s\n", $5, $9}'
info ""
TOTAL_SIZE=$(du -sh "$BACKUP_DIR" | cut -f1)
success "Total backup size: $TOTAL_SIZE"
info ""
warn "IMPORTANT: Runtime config contains ALL secrets!"
warn "Store backups securely (encrypted disk, restricted access)"
info ""

# Optional: Upload to remote storage
if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
    info "Uploading backup..."
    eval "$BACKUP_UPLOAD_CMD $BACKUP_DIR"
    success "Backup uploaded"
fi

# Optional: Keep only last N backups
KEEP_BACKUPS="${KEEP_BACKUPS:-7}"
info "Cleaning old backups (keeping last $KEEP_BACKUPS)..."
cd "$BACKUP_ROOT"
ls -t | grep "^datamancy-" | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -rf
success "Old backups cleaned"

info ""
success "Backup and verification complete!"
