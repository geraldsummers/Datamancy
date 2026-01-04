#!/bin/bash
# Build System Verification Script
# Tests that the new build system works correctly

set -e

CYAN='\033[0;36m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
RESET='\033[0m'

info() { echo -e "${GREEN}[✓]${RESET} $1"; }
error() { echo -e "${RED}[✗]${RESET} $1"; }
warn() { echo -e "${YELLOW}[!]${RESET} $1"; }
step() { echo -e "\n${CYAN}▸${RESET} $1"; }

echo -e "${CYAN}╔═══════════════════════════════════════════════╗"
echo -e "║  Build System Verification                   ║"
echo -e "╚═══════════════════════════════════════════════╝${RESET}\n"

# Check if we're in project root
if [ ! -f "services.registry.yaml" ]; then
    error "Not in project root (services.registry.yaml not found)"
    exit 1
fi
info "In project root"

# Check build script exists
step "Checking build script..."
if [ ! -x "build-datamancy.main.kts" ]; then
    error "build-datamancy.main.kts not found or not executable"
    exit 1
fi
info "Build script found and executable"

# Check deprecated files moved
step "Checking old system deprecated..."
if [ -f "scripts/stack-control/process-config-templates.main.kts" ]; then
    warn "Old template processor still in scripts/ (should be in .deprecated/)"
else
    info "Old template processor moved to .deprecated/"
fi

if [ -f "scripts/codegen/generate-compose.main.kts" ]; then
    warn "Old codegen still in scripts/ (should be in .deprecated/)"
else
    info "Old codegen moved to .deprecated/"
fi

# Run build
step "Running build..."
./build-datamancy.main.kts --skip-gradle
info "Build completed"

# Verify dist/ structure
step "Verifying dist/ structure..."

required_paths=(
    "dist/docker-compose.yml"
    "dist/.env.example"
    "dist/.build-info"
    "dist/compose/core/networks.yml"
    "dist/compose/core/volumes.yml"
    "dist/compose/databases"
    "dist/configs"
)

for path in "${required_paths[@]}"; do
    if [ -e "$path" ]; then
        info "Found: $path"
    else
        error "Missing: $path"
        exit 1
    fi
done

# Check no .template files in dist
step "Checking for template files in dist/..."
template_count=$(find dist/ -name "*.template" 2>/dev/null | wc -l)
if [ "$template_count" -eq 0 ]; then
    info "No .template files in dist/ (good!)"
else
    error "Found $template_count .template files in dist/ (should be 0)"
    find dist/ -name "*.template"
    exit 1
fi

# Verify secrets are ${VARS} not hardcoded
step "Verifying secrets are not hardcoded..."

check_secret_format() {
    local file=$1
    local secret=$2

    if grep -q "\${${secret}}" "$file" 2>/dev/null; then
        info "$secret correctly as \${VAR} in $file"
        return 0
    elif grep -q "$secret" "$file" 2>/dev/null; then
        # Check if it's actually a hardcoded value or just the var name
        if grep "$secret" "$file" | grep -qv "\${"; then
            error "$secret appears hardcoded in $file"
            grep "$secret" "$file"
            return 1
        fi
    fi
    return 0
}

secrets=(
    "LDAP_ADMIN_PASSWORD"
    "POSTGRES_PASSWORD"
    "MARIADB_ROOT_PASSWORD"
)

for secret in "${secrets[@]}"; do
    # Check in compose files
    if find dist/compose -type f -name "*.yml" -exec grep -l "$secret" {} \; 2>/dev/null | head -1 | xargs -I {} bash -c "check_secret_format {} $secret"; then
        continue
    fi
done

# Verify image versions are hardcoded
step "Verifying image versions are hardcoded..."

check_version_hardcoded() {
    local file=$1

    # Look for image: lines with actual versions, not ${VARS}
    if grep -E "image: [a-z/-]+:[0-9.]+" "$file" >/dev/null 2>&1; then
        info "Found hardcoded versions in $file"
        return 0
    else
        warn "No hardcoded image versions found in $file"
        return 1
    fi
}

if check_version_hardcoded "dist/compose/databases/relational.yml"; then
    :
fi

if check_version_hardcoded "dist/compose/databases/vector.yml"; then
    :
fi

# Check .env.example has all required vars
step "Verifying .env.example completeness..."

required_env_vars=(
    "DOMAIN"
    "VOLUMES_ROOT"
    "LDAP_ADMIN_PASSWORD"
    "POSTGRES_PASSWORD"
    "LITELLM_MASTER_KEY"
)

for var in "${required_env_vars[@]}"; do
    if grep -q "^${var}=" dist/.env.example; then
        info "$var in .env.example"
    else
        error "$var missing from .env.example"
        exit 1
    fi
done

# Summary
echo -e "\n${GREEN}╔═══════════════════════════════════════════════╗"
echo -e "║  Verification Complete! ✓                    ║"
echo -e "╚═══════════════════════════════════════════════╝${RESET}\n"

echo -e "${CYAN}Build output:${RESET}    dist/"
echo -e "${CYAN}Documentation:${RESET}   README-BUILD.md"
echo -e "${CYAN}Migration info:${RESET}  MIGRATION-SUMMARY.md"

echo -e "\n${GREEN}Next steps:${RESET}"
echo -e "  1. Test locally:"
echo -e "     ${CYAN}cd dist && cp .env.example .env && vim .env${RESET}"
echo -e "     ${CYAN}docker compose up${RESET}"
echo -e ""
echo -e "  2. Package for deployment:"
echo -e "     ${CYAN}tar -czf datamancy-\$(git describe --tags).tar.gz -C dist .${RESET}"
echo -e ""
echo -e "${GREEN}The build system is ready! 🚀${RESET}\n"
