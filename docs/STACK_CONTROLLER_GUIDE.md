# Stack Controller Guide

## Overview

`stack-controller.main.kts` is the central management tool for the Datamancy sovereign compute cluster. It consolidates all operational commands into a single, discoverable interface.

## Quick Start

```bash
# View all commands
./stack-controller.main.kts help

# Start the stack
./stack-controller.main.kts up

# Encrypt secrets
./stack-controller.main.kts secrets encrypt

# Process configuration templates
./stack-controller.main.kts config process
```

## Command Reference

### Secrets Management

```bash
# Encrypt .env to .env.enc (replaces ./encrypt-env.sh)
./stack-controller.main.kts secrets encrypt

# Decrypt .env.enc to .env (replaces ./decrypt-env.sh)
./stack-controller.main.kts secrets decrypt

# Initialize SOPS/Age encryption
./stack-controller.main.kts secrets init
```

### Stack Operations

```bash
# Start entire stack
./stack-controller.main.kts up

# Start specific profile
./stack-controller.main.kts up --profile=applications

# Stop all services
./stack-controller.main.kts down

# Restart a service
./stack-controller.main.kts restart caddy

# View logs
./stack-controller.main.kts logs vllm

# Follow logs
./stack-controller.main.kts logs vllm -f

# Show status
./stack-controller.main.kts status
```

### Configuration

```bash
# Process templates (replaces kotlin scripts/process-config-templates.main.kts)
./stack-controller.main.kts config process

# Generate .env from defaults (replaces kotlin scripts/configure-environment.kts)
./stack-controller.main.kts config generate
```

### Deployment (Server Setup - requires sudo)

```bash
# Create stackops system user
sudo ./stack-controller.main.kts deploy create-user

# Install SSH forced-command wrapper
sudo ./stack-controller.main.kts deploy install-wrapper

# Harden SSH daemon
sudo ./stack-controller.main.kts deploy harden-sshd

# Generate SSH keypair
./stack-controller.main.kts deploy generate-keys

# Deploy Age key to stackops user
sudo ./stack-controller.main.kts deploy age-key
```

### Maintenance

```bash
# Create volume directories
./stack-controller.main.kts volumes create

# Clean unused Docker resources
./stack-controller.main.kts clean docker

# Sync LDAP users to services (Mailu, etc.)
./stack-controller.main.kts ldap sync

# Remote LDAP sync via SSH (using forced-command wrapper)
ssh stackops@server.example.com "kotlin stack-controller.main.kts ldap sync"
```

## Script Organization

### Directory Structure

```
./
├── stack-controller.main.kts    # Main entry point
└── scripts/
    ├── core/                     # Essential operational scripts
    │   ├── process-config-templates.main.kts
    │   ├── configure-environment.kts
    │   └── create-volume-dirs.main.kts
    ├── deployment/               # Server deployment scripts
    │   ├── create_debian_distribution.main.kts
    │   └── stackops.main.kts (legacy)
    ├── backup/                   # Backup/recovery scripts
    │   ├── backup-databases.main.kts
    │   ├── restore-from-backup.main.kts
    │   ├── verify-backups.main.kts
    │   ├── setup-backup-automation.main.kts
    │   └── dr-drill.main.kts
    ├── security/                 # Security setup scripts
    │   ├── setup-secrets-encryption.main.kts
    │   ├── setup-log-centralization.main.kts
    │   └── install-sops-age.sh
    └── maintenance/              # Utility scripts
        └── cleandocker.main.kts
```

### Script Categories

#### Core Scripts
Essential for daily operations:
- **process-config-templates.kts**: Processes `configs.templates/` → `configs/`
- **configure-environment.kts**: Generates `.env` with random secrets
- **create-volume-dirs.kts**: Creates volume directory structure

#### Deployment Scripts
Used during server provisioning:
- **create_debian_distribution.kts**: Creates deployable Debian image
- **stackops.kts**: Legacy server deployment script (replaced by stack-controller commands)

#### Backup Scripts
Data protection and disaster recovery:
- **backup-databases.kts**: Backup all databases to Kopia repository
- **restore-from-backup.kts**: Restore databases from backup
- **verify-backups.kts**: Test backup integrity
- **setup-backup-automation.kts**: Configure automated backups
- **dr-drill.kts**: Disaster recovery drill/simulation

#### Security Scripts
Security initialization and hardening:
- **setup-secrets-encryption.kts**: Initialize SOPS/Age encryption
- **setup-log-centralization.kts**: Configure centralized logging
- **install-sops-age.sh**: Install SOPS and Age tools

#### Maintenance Scripts
System cleanup and maintenance:
- **cleandocker.kts**: Clean unused Docker resources

## Migration from Old Commands

### Bash Scripts (Deleted)
| Old Command | New Command |
|-------------|-------------|
| `./encrypt-env.sh` | `./stack-controller.main.kts secrets encrypt` |
| `./decrypt-env.sh` | `./stack-controller.main.kts secrets decrypt` |

### Direct Script Calls (Now Integrated)
| Old Command | New Command |
|-------------|-------------|
| `kotlin scripts/process-config-templates.main.kts` | `./stack-controller.main.kts config process` |
| `kotlin scripts/configure-environment.kts` | `./stack-controller.main.kts config generate` |
| `kotlin scripts/create-volume-dirs.main.kts` | `./stack-controller.main.kts volumes create` |
| `kotlin scripts/stackops.main.kts create-user` | `sudo ./stack-controller.main.kts deploy create-user` |

### Still Callable Directly
Some scripts are still called directly for specific use cases:

```bash
# Backup operations
kotlin scripts/backup/backup-databases.main.kts
kotlin scripts/backup/restore-from-backup.main.kts
kotlin scripts/backup/verify-backups.main.kts

# Security setup
kotlin scripts/security/setup-secrets-encryption.main.kts
kotlin scripts/security/setup-log-centralization.main.kts

# Deployment
kotlin scripts/deployment/create_debian_distribution.main.kts
```

## Typical Workflows

### Initial Setup (Workstation)

```bash
# 1. Generate secrets
./stack-controller.main.kts config generate

# 2. Initialize encryption
./stack-controller.main.kts secrets init

# 3. Encrypt secrets
./stack-controller.main.kts secrets encrypt

# 4. Process templates
./stack-controller.main.kts config process

# 5. Commit encrypted secrets
git add .env.enc .sops.yaml configs/
git commit -m "Initialize encrypted configuration"
git push
```

### Server Deployment

```bash
# On server (as root)
sudo ./stack-controller.main.kts deploy create-user
sudo ./stack-controller.main.kts deploy install-wrapper
sudo ./stack-controller.main.kts deploy harden-sshd

# Deploy Age key
sudo ./stack-controller.main.kts deploy age-key

# Switch to stackops user
sudo su - stackops
cd /home/stackops/datamancy

# Start stack
./stack-controller.main.kts up --profile=infrastructure
./stack-controller.main.kts up --profile=databases
./stack-controller.main.kts up --profile=applications
```

### Daily Operations

```bash
# Check status
./stack-controller.main.kts status

# View logs
./stack-controller.main.kts logs caddy -f

# Restart problematic service
./stack-controller.main.kts restart vllm

# Sync LDAP users to email
./stack-controller.main.kts ldap sync

# Update configuration
./stack-controller.main.kts secrets decrypt
nano .env
./stack-controller.main.kts secrets encrypt
./stack-controller.main.kts config process
./stack-controller.main.kts restart authelia
```

### Backup & Recovery

```bash
# Manual backup
kotlin scripts/backup/backup-databases.main.kts

# Verify backups
kotlin scripts/backup/verify-backups.main.kts

# Setup automated backups
kotlin scripts/backup/setup-backup-automation.main.kts

# Disaster recovery drill
kotlin scripts/backup/dr-drill.main.kts
```

## Tips & Best Practices

### Secrets Management
- **Always encrypt before committing**: Never commit plaintext `.env`
- **Backup Age key**: Store `~/.config/sops/age/keys.txt` securely
- **Rotate regularly**: Use `./stack-controller.main.kts secrets init` to generate new keys

### Stack Operations
- **Use profiles**: Start services incrementally (`infrastructure` → `databases` → `applications`)
- **Check logs first**: Before restarting, check logs to understand the issue
- **Graceful restarts**: Use `restart` instead of `down` → `up` when possible

### Configuration
- **Test locally first**: Process templates and test on workstation before deploying
- **Version control**: Commit `.env.enc` and `configs/` to track changes
- **Document changes**: Add comments to `.env.example` when adding new variables

### Deployment
- **SSH key-only**: Disable password authentication on production servers
- **Forced commands**: Use SSH wrapper to restrict commands
- **Principle of least privilege**: stackops user has minimal permissions

## Troubleshooting

### "sops not found"
```bash
# Install SOPS and Age
bash scripts/security/install-sops-age.sh
```

### "Age key not found"
```bash
# Initialize encryption (generates key)
./stack-controller.main.kts secrets init
```

### "Command failed"
```bash
# Check if you're in project root
pwd
# Should be: /path/to/Datamancy

# Check permissions
ls -la stack-controller.main.kts
# Should be: -rwxr-xr-x

# Make executable if needed
chmod +x stack-controller.main.kts
```

### "Service unhealthy"
```bash
# Check logs
./stack-controller.main.kts logs <service>

# Check status
./stack-controller.main.kts status

# Restart service
./stack-controller.main.kts restart <service>
```

## See Also

- [LDAP Sync Service](../src/ldap-sync-service/README.md)
- [Backup Strategy](./BACKUP_STRATEGY.md)
- [Script Reorganization Plan](../SCRIPT_REORGANIZATION_PLAN.md)
