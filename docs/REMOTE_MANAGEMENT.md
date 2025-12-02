# Remote Stack Management via SSH

## Overview

The stack controller can be invoked remotely via SSH using the forced-command wrapper. This allows secure remote management without exposing a full shell.

## Setup

### On Server

```bash
# 1. Create stackops user
sudo ./stack-controller.main.kts deploy create-user

# 2. Install SSH wrapper
sudo ./stack-controller.main.kts deploy install-wrapper

# 3. Harden SSH daemon
sudo ./stack-controller.main.kts deploy harden-sshd

# 4. Deploy Age encryption key
sudo ./stack-controller.main.kts deploy age-key

# 5. Generate SSH keypair for agent access
./stack-controller.main.kts deploy generate-keys

# 6. Display public key
cat volumes/secrets/stackops_ed25519.pub
```

### In SSH authorized_keys

Add to `/home/stackops/.ssh/authorized_keys`:

```bash
command="/usr/local/bin/stackops-wrapper",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding ssh-ed25519 AAAA...your-public-key-here stackops@datamancy
```

## Allowed Commands

The SSH wrapper restricts commands to:

- `docker ps`
- `docker logs <service>`
- `docker restart <service>`
- `docker compose <args>`
- `./stack-controller.main.kts <command>`
- `kotlin stack-controller.main.kts <command>`

Any other command will be rejected.

## Remote Operations

### View Stack Status

```bash
ssh stackops@server.example.com "docker ps"
ssh stackops@server.example.com "kotlin stack-controller.main.kts status"
```

### View Logs

```bash
# Docker logs
ssh stackops@server.example.com "docker logs caddy"

# Via stack-controller
ssh stackops@server.example.com "kotlin stack-controller.main.kts logs caddy"
```

### Restart Services

```bash
# Direct docker restart
ssh stackops@server.example.com "docker restart vllm"

# Via stack-controller
ssh stackops@server.example.com "kotlin stack-controller.main.kts restart vllm"
```

### LDAP Sync

```bash
# Sync LDAP users to Mailu
ssh stackops@server.example.com "kotlin stack-controller.main.kts ldap sync"
```

### Stack Operations

```bash
# Start services
ssh stackops@server.example.com "docker compose --profile applications up -d"

# Stop services
ssh stackops@server.example.com "kotlin stack-controller.main.kts down"

# Restart stack
ssh stackops@server.example.com "docker compose restart"
```

## Security Features

### 1. Forced Command Wrapper

The SSH wrapper (`/usr/local/bin/stackops-wrapper`):
- Validates all commands against an allowlist
- Rejects any command not explicitly allowed
- Shows allowed commands on rejection

### 2. Auto-Decrypt Secrets

The wrapper automatically decrypts `.env.enc` if needed:
- Checks for Age key at `/home/stackops/.config/sops/age/keys.txt`
- Decrypts `.env.enc` → `.env` before running commands
- Sets proper permissions (600) on `.env`

### 3. Restricted SSH Options

The `authorized_keys` entry enforces:
- `command="..."`: Forces all connections through wrapper
- `no-agent-forwarding`: Disables SSH agent forwarding
- `no-port-forwarding`: Disables port forwarding
- `no-pty`: Disables pseudo-terminal allocation
- `no-user-rc`: Disables user RC files
- `no-X11-forwarding`: Disables X11 forwarding

### 4. Key-Only Authentication

SSH daemon hardening:
- `PubkeyAuthentication yes`: Only SSH keys allowed
- `PasswordAuthentication no`: Passwords disabled
- `PermitTunnel no`: Tunneling disabled
- `PermitTTY no`: Interactive TTY disabled

## Agent Integration

The probe-orchestrator can use SSH to manage the stack remotely:

```kotlin
// In probe-orchestrator/src/main/kotlin/org/datamancy/probe/Probe-Orchestrator.kt

tools["ssh_exec_whitelisted"] = object : Tool {
    override suspend fun call(args: JsonObject): ToolResult {
        val cmd = args.getString("cmd")

        // Examples:
        // "kotlin stack-controller.main.kts restart vllm"
        // "kotlin stack-controller.main.kts ldap sync"
        // "docker logs vllm --tail 100"

        val result = execSsh(cmd)
        return ToolResult(output = result)
    }
}
```

## Automation Examples

### Cron Job for LDAP Sync

Add to host crontab:

```bash
# Sync LDAP to Mailu daily at 2 AM
0 2 * * * ssh -i /path/to/key stackops@localhost "kotlin stack-controller.main.kts ldap sync" >> /var/log/ldap-sync.log 2>&1
```

### Monitoring Script

```bash
#!/bin/bash
# monitor-stack.sh - Check stack health remotely

SERVER="stackops@prod-server.example.com"

echo "=== Stack Status ==="
ssh $SERVER "kotlin stack-controller.main.kts status"

echo ""
echo "=== Unhealthy Services ==="
ssh $SERVER "docker ps --filter health=unhealthy"

echo ""
echo "=== Recent Errors ==="
ssh $SERVER "docker compose logs --tail 50 | grep -i error"
```

### Agent-Triggered LDAP Sync

When an agent detects a new LDAP user:

```bash
# Agent executes via SSH tool
ssh stackops@server "kotlin stack-controller.main.kts ldap sync"
```

## Troubleshooting

### Command Rejected

```bash
$ ssh stackops@server "ls -la"
Command not allowed: ls -la

Allowed commands:
  docker ps
  docker logs
  docker restart
  docker compose
  ./stack-controller.main.kts
  kotlin stack-controller.main.kts
```

**Fix**: Use one of the allowed commands.

### Age Key Not Found

```
ERROR: Age key not found
Expected: /home/stackops/.config/sops/age/keys.txt
```

**Fix**: Deploy Age key:
```bash
sudo ./stack-controller.main.kts deploy age-key
```

### SOPS Not Installed

```
sops: command not found
```

**Fix**: Install SOPS on server:
```bash
bash scripts/security/install-sops-age.sh
```

### Connection Refused

```
ssh: connect to host server.example.com port 22: Connection refused
```

**Fix**:
1. Check SSH daemon is running: `systemctl status sshd`
2. Check firewall allows port 22
3. Verify server hostname/IP is correct

## Best Practices

1. **Use SSH keys**: Never use password authentication
2. **Rotate keys regularly**: Generate new keypairs every 6-12 months
3. **Monitor access logs**: Check `/var/log/auth.log` for SSH activity
4. **Limit access**: Only add public keys for authorized users/agents
5. **Test locally first**: Verify commands work before using remotely
6. **Use tmux/screen**: For long-running operations, use screen sessions

## See Also

- [Stack Controller Guide](./STACK_CONTROLLER_GUIDE.md)
- [LDAP Sync Service](../src/ldap-sync-service/README.md)
- [Probe Orchestrator SSH Plugin](../src/agent-tool-server/src/main/kotlin/org/example/plugins/OpsSshPlugin.kt)
