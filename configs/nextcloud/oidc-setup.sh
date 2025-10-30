#!/bin/bash
# Nextcloud OIDC Setup Script
# This script runs as a pre-installation hook to prepare OIDC configuration

set -e

echo "===== Nextcloud OIDC Pre-Installation Hook ====="
echo "This hook prepares the environment for OIDC installation"
echo "OIDC configuration will be applied after Nextcloud is fully installed"

# The actual OIDC configuration will be done via occ commands
# after Nextcloud completes its initial setup
# This can be done manually or via a post-installation script

echo "===== Pre-installation hook completed ====="
