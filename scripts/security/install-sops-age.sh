#!/bin/bash
set -euo pipefail

# Install SOPS and Age for encrypted secrets management
# Supports: Debian/Ubuntu, RHEL/Fedora, macOS

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info() { echo -e "${GREEN}[INFO]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# Detect OS
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    if [ -f /etc/debian_version ]; then
        OS="debian"
    elif [ -f /etc/redhat-release ]; then
        OS="redhat"
    else
        error "Unsupported Linux distribution"
    fi
elif [[ "$OSTYPE" == "darwin"* ]]; then
    OS="macos"
else
    error "Unsupported OS: $OSTYPE"
fi

info "Detected OS: $OS"

# Install Age
install_age() {
    info "Installing age..."

    case $OS in
        debian)
            if ! command -v age &> /dev/null; then
                # Age is in Debian 11+ and Ubuntu 21.04+
                if apt-cache show age &> /dev/null; then
                    apt-get update
                    apt-get install -y age
                else
                    # Install from GitHub releases
                    AGE_VERSION="v1.1.1"
                    wget -O /tmp/age.tar.gz "https://github.com/FiloSottile/age/releases/download/${AGE_VERSION}/age-${AGE_VERSION}-linux-amd64.tar.gz"
                    tar -xzf /tmp/age.tar.gz -C /tmp
                    mv /tmp/age/age /usr/local/bin/
                    mv /tmp/age/age-keygen /usr/local/bin/
                    rm -rf /tmp/age*
                fi
            else
                info "age already installed"
            fi
            ;;
        redhat)
            if ! command -v age &> /dev/null; then
                AGE_VERSION="v1.1.1"
                wget -O /tmp/age.tar.gz "https://github.com/FiloSottile/age/releases/download/${AGE_VERSION}/age-${AGE_VERSION}-linux-amd64.tar.gz"
                tar -xzf /tmp/age.tar.gz -C /tmp
                mv /tmp/age/age /usr/local/bin/
                mv /tmp/age/age-keygen /usr/local/bin/
                rm -rf /tmp/age*
            else
                info "age already installed"
            fi
            ;;
        macos)
            if ! command -v age &> /dev/null; then
                if command -v brew &> /dev/null; then
                    brew install age
                else
                    error "Homebrew not found. Install from: https://brew.sh"
                fi
            else
                info "age already installed"
            fi
            ;;
    esac

    age --version
}

# Install SOPS
install_sops() {
    info "Installing SOPS..."

    SOPS_VERSION="v3.8.1"

    case $OS in
        debian|redhat)
            if ! command -v sops &> /dev/null; then
                wget -O /usr/local/bin/sops "https://github.com/getsops/sops/releases/download/${SOPS_VERSION}/sops-${SOPS_VERSION}.linux.amd64"
                chmod +x /usr/local/bin/sops
            else
                info "sops already installed"
            fi
            ;;
        macos)
            if ! command -v sops &> /dev/null; then
                if command -v brew &> /dev/null; then
                    brew install sops
                else
                    error "Homebrew not found. Install from: https://brew.sh"
                fi
            else
                info "sops already installed"
            fi
            ;;
    esac

    sops --version
}

# Main installation
echo "========================================"
info "SOPS + Age Installation"
echo "========================================"
echo

install_age
echo
install_sops
echo

echo "========================================"
info "Installation complete!"
echo "========================================"
echo
info "Next steps:"
info "1. Generate age keypair: kotlin scripts/setup-secrets.main.kts init"
info "2. Encrypt .env file: kotlin scripts/setup-secrets.main.kts encrypt"
info "3. Update configs: kotlin scripts/process-config-templates.main.kts"
