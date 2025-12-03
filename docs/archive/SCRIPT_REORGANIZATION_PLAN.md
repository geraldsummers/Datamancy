# Script Reorganization Plan

## Current Problems
1. **Root directory clutter**: `encrypt-env.sh`, `decrypt-env.sh` scripts at root
2. **Flat scripts/ directory**: All scripts mixed together regardless of purpose
3. **Duplicate functionality**: bash scripts + stackops doing similar things
4. **Non-obvious naming**: `stackops.main.kts` not discoverable

## Proposed New Structure

```
./
├── stack-controller           # Main entry point (moved from scripts/stackops.main.kts)
├── .env.enc                   # Encrypted secrets (stays)
├── .env.example               # Secret template (stays)
├── .sops.yaml                 # SOPS config (stays)
│
└── scripts/
    ├── core/                  # Essential operational scripts
    │   ├── process-config-templates.main.kts
    │   ├── configure-environment.kts
    │   └── create-volume-dirs.main.kts
    │
    ├── deployment/            # Server deployment scripts
    │   └── create_debian_distribution.main.kts
    │
    ├── backup/                # Backup/recovery scripts
    │   ├── backup-databases.main.kts
    │   ├── restore-from-backup.main.kts
    │   ├── verify-backups.main.kts
    │   ├── setup-backup-automation.main.kts
    │   └── dr-drill.main.kts
    │
    ├── security/              # Security setup scripts
    │   ├── setup-secrets-encryption.main.kts
    │   └── setup-log-centralization.main.kts
    │
    └── maintenance/           # Utility scripts
        └── cleandocker.main.kts
```

## stack-controller Commands

### Integrated Secrets Commands (replaces bash scripts)
```bash
./stack-controller secrets encrypt    # Was: ./encrypt-env.sh
./stack-controller secrets decrypt    # Was: ./decrypt-env.sh
./stack-controller secrets init       # Was: kotlin scripts/setup-secrets-encryption.main.kts
```

### Stack Operations
```bash
./stack-controller up --profile=applications
./stack-controller down
./stack-controller restart caddy
./stack-controller logs vllm
./stack-controller status
```

### Deployment (existing stackops functionality)
```bash
./stack-controller deploy create-user
./stack-controller deploy install-wrapper
./stack-controller deploy harden-sshd
./stack-controller deploy generate-keys
./stack-controller deploy age-key
```

### Configuration
```bash
./stack-controller config process     # Replaces: kotlin scripts/process-config-templates.main.kts
./stack-controller config generate    # Replaces: kotlin scripts/configure-environment.kts
```

### Maintenance
```bash
./stack-controller volumes create     # Replaces: kotlin scripts/create-volume-dirs.main.kts
./stack-controller clean docker       # Replaces: kotlin scripts/cleandocker.main.kts
./stack-controller ldap sync          # New: run LDAP sync service
```

## Migration Steps

1. ✅ Create enhanced stack-controller with all integrated commands
2. ✅ Move stack-controller to root directory
3. ✅ Reorganize scripts/ into subdirectories
4. ✅ Delete obsolete bash scripts (encrypt-env.sh, decrypt-env.sh)
5. ✅ Update documentation
6. ✅ Test all commands

## Benefits

- **Single entry point**: `./stack-controller` for everything
- **Discoverable**: Clear command structure with help text
- **Organized**: Scripts grouped by purpose
- **Clean root**: No shell scripts cluttering root directory
- **Consistent**: All Kotlin scripts, one tool
