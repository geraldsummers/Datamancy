#!/bin/bash

#
# Credential Rotation Systemd Installation Script
# Sets up systemd service and timer for automated weekly credential rotation
#

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[1;36m'
NC='\033[0m' # No Color

PROJECT_ROOT="/home/gerald/IdeaProjects/Datamancy"
SYSTEMD_DIR="/etc/systemd/system"
SERVICE_NAME="credential-rotation"

# Check if running as root
if [ "$EUID" -eq 0 ]; then
    echo -e "${RED}ERROR: Do not run as root. Use sudo when prompted.${NC}"
    exit 1
fi

echo -e "${CYAN}╔════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  Credential Rotation Systemd Installation         ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════╝${NC}"
echo ""

# Check prerequisites
echo -e "${YELLOW}[1/7]${NC} Checking prerequisites..."

if [ ! -d "$PROJECT_ROOT" ]; then
    echo -e "${RED}✗ Project directory not found: $PROJECT_ROOT${NC}"
    exit 1
fi

if [ ! -f "$PROJECT_ROOT/scripts/security/weekly-rotation.main.kts" ]; then
    echo -e "${RED}✗ Rotation script not found${NC}"
    exit 1
fi

if ! command -v kotlin &> /dev/null; then
    echo -e "${RED}✗ Kotlin not found. Please install Kotlin first.${NC}"
    exit 1
fi

KOTLIN_BIN=$(which kotlin)
echo -e "${GREEN}✓ Prerequisites OK${NC}"
echo -e "  Project: $PROJECT_ROOT"
echo -e "  Kotlin: $KOTLIN_BIN"
echo ""

# Create service file with correct paths
echo -e "${YELLOW}[2/7]${NC} Creating systemd service file..."

sudo tee "$SYSTEMD_DIR/$SERVICE_NAME.service" > /dev/null << EOF
[Unit]
Description=Weekly Credential Rotation for Datamancy Stack
After=network.target docker.service
Requires=docker.service
Documentation=file://$PROJECT_ROOT/scripts/security/README.md

[Service]
Type=oneshot
User=$USER
Group=$USER
WorkingDirectory=$PROJECT_ROOT

# Environment
Environment="PATH=/usr/local/bin:/usr/bin:/bin:/snap/bin"
Environment="KOTLIN_HOME=/snap/kotlin/current"

# Pre-flight checks
ExecStartPre=/usr/bin/docker ps --quiet --filter name=postgres
ExecStartPre=/usr/bin/bash -c 'test -f $PROJECT_ROOT/.env'

# Main rotation script
ExecStart=$KOTLIN_BIN $PROJECT_ROOT/scripts/security/weekly-rotation.main.kts --execute

# Post-rotation health check
ExecStartPost=$KOTLIN_BIN $PROJECT_ROOT/scripts/security/lib/health-check.main.kts --execute

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$SERVICE_NAME

# Security hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=$PROJECT_ROOT/secrets

# Timeouts
TimeoutStartSec=900
TimeoutStopSec=60

# Restart policy (don't restart on failure, let timer handle it)
Restart=no

[Install]
WantedBy=multi-user.target
EOF

echo -e "${GREEN}✓ Service file created${NC}"
echo ""

# Create timer file
echo -e "${YELLOW}[3/7]${NC} Creating systemd timer file..."

sudo tee "$SYSTEMD_DIR/$SERVICE_NAME.timer" > /dev/null << 'EOF'
[Unit]
Description=Weekly Credential Rotation Timer
Documentation=file:///home/gerald/IdeaProjects/Datamancy/scripts/security/README.md
Requires=credential-rotation.service

[Timer]
# Run every Sunday at 2:00 AM
OnCalendar=Sun *-*-* 02:00:00

# If system was off, run on next boot (within 1 hour)
Persistent=true

# Add random delay of 0-5 minutes to avoid thundering herd
RandomizedDelaySec=300

# Time unit for calendar events
AccuracySec=1min

[Install]
WantedBy=timers.target
EOF

echo -e "${GREEN}✓ Timer file created${NC}"
echo ""

# Reload systemd
echo -e "${YELLOW}[4/7]${NC} Reloading systemd daemon..."
sudo systemctl daemon-reload
echo -e "${GREEN}✓ Systemd reloaded${NC}"
echo ""

# Enable timer
echo -e "${YELLOW}[5/7]${NC} Enabling timer..."
sudo systemctl enable $SERVICE_NAME.timer
echo -e "${GREEN}✓ Timer enabled${NC}"
echo ""

# Start timer
echo -e "${YELLOW}[6/7]${NC} Starting timer..."
sudo systemctl start $SERVICE_NAME.timer
echo -e "${GREEN}✓ Timer started${NC}"
echo ""

# Verify installation
echo -e "${YELLOW}[7/7]${NC} Verifying installation..."

STATUS=$(sudo systemctl is-active $SERVICE_NAME.timer)
if [ "$STATUS" = "active" ]; then
    echo -e "${GREEN}✓ Timer is active${NC}"
else
    echo -e "${RED}✗ Timer is not active: $STATUS${NC}"
    exit 1
fi

echo ""
echo -e "${CYAN}════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}✓ Installation complete!${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════${NC}"
echo ""

# Show status
echo -e "${CYAN}Status:${NC}"
sudo systemctl status $SERVICE_NAME.timer --no-pager --lines=5
echo ""

echo -e "${CYAN}Next scheduled run:${NC}"
sudo systemctl list-timers --no-pager | grep $SERVICE_NAME
echo ""

# Show useful commands
echo -e "${CYAN}Useful commands:${NC}"
echo ""
echo -e "  ${YELLOW}Check timer status:${NC}"
echo -e "    sudo systemctl status $SERVICE_NAME.timer"
echo ""
echo -e "  ${YELLOW}Check service status:${NC}"
echo -e "    sudo systemctl status $SERVICE_NAME.service"
echo ""
echo -e "  ${YELLOW}View logs:${NC}"
echo -e "    sudo journalctl -u $SERVICE_NAME.service -f"
echo ""
echo -e "  ${YELLOW}Manually trigger rotation:${NC}"
echo -e "    sudo systemctl start $SERVICE_NAME.service"
echo ""
echo -e "  ${YELLOW}Test with dry-run:${NC}"
echo -e "    $PROJECT_ROOT/scripts/security/cron-wrapper.sh --dry-run"
echo ""
echo -e "  ${YELLOW}Disable timer:${NC}"
echo -e "    sudo systemctl stop $SERVICE_NAME.timer"
echo -e "    sudo systemctl disable $SERVICE_NAME.timer"
echo ""
echo -e "${GREEN}Rotation will run every Sunday at 2:00 AM${NC}"
echo ""
