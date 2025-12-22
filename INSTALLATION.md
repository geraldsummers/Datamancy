# Datamancy Installation Guide

## Overview

Datamancy uses a two-part architecture:

1. **Git Repository** - Source code, templates, and installer (this directory)
2. **Installation Directory** (`~/.datamancy/`) - Complete working installation outside git tree

This separation allows you to safely update the source code without affecting your running stack or losing configuration.

## Quick Start

### First-Time Installation

```bash
# Clone the repository
git clone <repository-url> datamancy
cd datamancy

# Run the installer
./install-datamancy.main.kts

# Optional: Add controller to PATH
echo 'export PATH="$HOME/.datamancy:$PATH"' >> ~/.bashrc
source ~/.bashrc

# Start the stack
datamancy-controller up
```

### Updating Existing Installation

```bash
# Update source code
cd datamancy
git pull

# Re-run installer (preserves configs and data)
./install-datamancy.main.kts

# Restart stack to apply changes
datamancy-controller down
datamancy-controller up
```

## Directory Structure

### Git Repository (Development)
```
datamancy/
├── install-datamancy.main.kts      # Installer script
├── datamancy-controller.main.kts   # Runtime controller (source)
├── docker-compose.yml              # Stack definition
├── configs.templates/              # Configuration templates
├── scripts/                        # Utility scripts
└── src/                           # Service source code
```

### Installation Directory (Runtime)
```
~/.datamancy/
├── datamancy-controller            # Runtime controller (executable)
├── docker-compose.yml              # Stack definition (copy)
├── docker-compose.test-ports.yml   # Test overlay (copy)
├── .env                           # Generated secrets and config
├── .version                       # Installation version marker
├── configs/                       # Generated configs (from templates)
│   ├── applications/
│   ├── databases/
│   └── infrastructure/
├── configs.templates/             # Templates (copy)
├── volumes/                       # Service data directories
│   ├── applications/
│   ├── databases/
│   └── ldap_init/
├── scripts/                       # Utility scripts (copy)
│   └── stack-control/
├── src/                          # Service source (copy, for builds)
│   ├── control-panel/
│   ├── data-fetcher/
│   └── ...
├── build.gradle.kts              # Build configuration
└── bootstrap_ldap.ldif           # Generated LDAP bootstrap
```

## Key Features

### Separation of Concerns

- **Source Repository**: Edit, version control, develop
- **Installation**: Run, manage, persist data

### Safe Updates

1. Make changes in git repository
2. Run installer to sync changes to `~/.datamancy/`
3. User data and configs remain untouched

### Secret Management

- Secrets generated in `~/.datamancy/.env`
- Never committed to git
- Owned by user (not root)

### Volume Management

- All volumes in `~/.datamancy/volumes/`
- Bind mounts (not Docker volumes)
- Easy backup and inspection

## Common Operations

### Installation

```bash
# First-time install
./install-datamancy.main.kts

# Force reinstall (upgrade)
./install-datamancy.main.kts --force
```

### Stack Management

```bash
# Start stack
datamancy-controller up

# Stop stack
datamancy-controller down

# Check status
datamancy-controller status

# Start with test ports (for integration tests)
datamancy-controller test-up
```

### Configuration

```bash
# Generate new .env (warning: overwrites existing)
datamancy-controller config generate

# Regenerate configs from templates
datamancy-controller config process
```

### Complete Cleanup

```bash
# Remove all data, keep installation
datamancy-controller obliterate

# Remove installation too
rm -rf ~/.datamancy/
```

## Migration from Old Stack Controller

If you have an existing installation using the old `stack-controller.main.kts`:

1. **Backup your .env** (contains secrets):
   ```bash
   cp ~/.datamancy/.env ~/.datamancy/.env.backup
   ```

2. **Run the installer**:
   ```bash
   ./install-datamancy.main.kts
   ```

3. **Switch to new controller**:
   ```bash
   # Old way
   ./stack-controller.main.kts up

   # New way
   ~/.datamancy/datamancy-controller up
   ```

4. **Optional: Add to PATH**:
   ```bash
   echo 'export PATH="$HOME/.datamancy:$PATH"' >> ~/.bashrc
   source ~/.bashrc
   datamancy-controller up  # Now works from anywhere
   ```

The old `stack-controller.main.kts` can be removed after successful migration.

## Troubleshooting

### Installation fails with "not found" errors

Make sure you're running the installer from the git repository root:
```bash
cd /path/to/datamancy-git-repo
./install-datamancy.main.kts
```

### Controller says "not installed"

Run the installer first:
```bash
./install-datamancy.main.kts
```

### Configs not updating after git pull

Re-run the installer to sync changes:
```bash
./install-datamancy.main.kts
datamancy-controller config process  # Regenerate configs
```

### Services failing to start

Check that all configs are present:
```bash
ls -la ~/.datamancy/configs/
ls -la ~/.datamancy/.env
```

Regenerate if needed:
```bash
datamancy-controller config process
```

## Development Workflow

### Making Changes

1. Edit files in git repository
2. Commit changes
3. Run installer to test
4. Restart affected services

### Testing Changes

```bash
# Sync changes to installation
./install-datamancy.main.kts

# Restart stack
datamancy-controller down
datamancy-controller up

# Or restart specific service
cd ~/.datamancy
docker compose restart <service-name>
```

### Adding New Services

1. Add service to `docker-compose.yml` in git repo
2. Add templates to `configs.templates/` if needed
3. Update installer if new files need copying
4. Run installer and restart stack

## Security Considerations

### File Permissions

- `~/.datamancy/` owned by user (not root)
- `.env` file is mode 600 (user read/write only)
- Scripts are executable where appropriate

### Secrets

- Generated in `~/.datamancy/.env`
- Never leave `~/.datamancy/` directory
- Backed up separately from git repo

### Isolation

- Stack runs in Docker containers
- User-mode only (no root required)
- Network isolation via Docker networks

## Backup Strategy

### What to Back Up

**Critical** (must backup):
- `~/.datamancy/.env` - Secrets and configuration
- `~/.datamancy/volumes/` - All application data

**Optional** (can regenerate):
- `~/.datamancy/configs/` - Generated from templates
- `~/.datamancy/bootstrap_ldap.ldif` - Generated from .env

**Never backup** (git-managed):
- Git repository contents
- Build artifacts

### Backup Command

```bash
# Backup critical data
tar czf datamancy-backup-$(date +%Y%m%d).tar.gz \
  ~/.datamancy/.env \
  ~/.datamancy/volumes/

# Restore
tar xzf datamancy-backup-YYYYMMDD.tar.gz -C /
```

## Advanced Usage

### Running from Git Repo (Development)

You can still run directly from git for development:

```bash
cd /path/to/git/repo

# Use docker compose directly
docker compose --env-file ~/.datamancy/.env up

# Or use original script location
./stack-controller.main.kts up  # Still works if not deleted
```

### Multiple Installations

Not recommended, but possible with different home directories:

```bash
# Install for different user
sudo -u otheruser ./install-datamancy.main.kts

# Each user gets own ~/.datamancy/
```

### Custom Installation Location

Edit `install-datamancy.main.kts` and change:
```kotlin
private fun installDir(): Path {
    return Paths.get("/custom/path")
}
```

## Version Management

Check installed version:
```bash
cat ~/.datamancy/.version
```

Check available version (in git):
```bash
cd /path/to/git/repo
git describe --always --tags
```

Update to latest:
```bash
cd /path/to/git/repo
git pull
./install-datamancy.main.kts
```

## Support

For issues:
1. Check logs: `docker compose logs <service>`
2. Check status: `datamancy-controller status`
3. Try regenerating configs: `datamancy-controller config process`
4. Last resort: `datamancy-controller obliterate` and start fresh
