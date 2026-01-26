# Labware Docker Socket Configuration

**Socket Path:** `/run/labware-docker.sock` (hardcoded)

## Overview

The labware Docker socket provides an isolated Docker daemon for CI/CD sandbox deployments.
All forgejo-runner builds execute on the labware VM, completely isolated from production.

## Architecture

```
Production Host
├── /var/run/docker.sock → Production Docker (DO NOT USE FOR CI/CD)
└── /run/labware-docker.sock → Labware VM Docker (CI/CD builds)
         ↓ (SSHFS mount)
    Labware VM
    └── /var/run/docker.sock → Isolated Docker daemon
```

## Setup

### 1. Labware VM Configuration

```bash
# On labware VM
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# Configure insecure registry for production host
cat > /etc/docker/daemon.json <<EOF
{
  "insecure-registries": ["YOUR_PRODUCTION_HOST:5000"]
}
EOF

systemctl restart docker
```

### 2. Mount Labware Socket on Production Host

```bash
# Install SSHFS
sudo apt-get install sshfs

# Mount labware VM socket
sudo sshfs -o allow_other,default_permissions,IdentityFile=/root/.ssh/labware_key \
  root@LABWARE_VM_IP:/var/run/docker.sock \
  /run/labware-docker.sock

# Verify mount
DOCKER_HOST=unix:///run/labware-docker.sock docker ps
```

### 3. Make Mount Persistent

Add to `/etc/fstab`:
```
root@LABWARE_VM_IP:/var/run/docker.sock /run/labware-docker.sock fuse.sshfs noauto,x-systemd.automount,_netdev,users,idmap=user,IdentityFile=/root/.ssh/labware_key,allow_other,default_permissions 0 0
```

## Testing

### Run Test Suite

```bash
# From production host
cd ~/.datamancy
docker-compose run --rm test-runner \
  ./gradlew test --tests "*LabwareDockerTestsTest*"
```

### Manual Tests

```bash
# Test 1: Socket exists
test -S /run/labware-docker.sock && echo "✓ Socket exists"

# Test 2: Can connect
DOCKER_HOST=unix:///run/labware-docker.sock docker version

# Test 3: Can list containers
DOCKER_HOST=unix:///run/labware-docker.sock docker ps

# Test 4: Can run containers
DOCKER_HOST=unix:///run/labware-docker.sock docker run --rm hello-world

# Test 5: Isolation check (no overlap with production)
PROD_CONTAINERS=$(docker ps --format '{{.Names}}')
LABWARE_CONTAINERS=$(DOCKER_HOST=unix:///run/labware-docker.sock docker ps --format '{{.Names}}')
# These lists should be completely different!
```

### Forgejo Runner Access

```bash
# Check runner can access labware socket
docker exec forgejo-runner test -S /run/labware-docker.sock && echo "✓ Runner has socket access"

# Check runner can use Docker
docker exec forgejo-runner docker ps
```

## Configuration

### Forgejo Runner

The socket is **hardcoded** in `forgejo-runner.yml`:

```yaml
environment:
  DOCKER_HOST: unix:///run/labware-docker.sock
volumes:
  - /run/labware-docker.sock:/run/labware-docker.sock
```

### CI Workflows

Workflows automatically use the labware socket through the runner.
No special configuration needed in `.forgejo/workflows/*.yml`.

## Security

### Isolation Guarantees

1. **Network Isolation**: Labware VM cannot access production network
2. **Filesystem Isolation**: No shared volumes between labware and production
3. **Process Isolation**: Separate Docker daemon, separate containers
4. **Credential Isolation**: No production secrets accessible from labware

### Verification

```bash
# Verify no container name overlap
comm -12 \
  <(docker ps --format '{{.Names}}' | sort) \
  <(DOCKER_HOST=unix:///run/labware-docker.sock docker ps --format '{{.Names}}' | sort)
# Output should be empty!
```

## Troubleshooting

### Socket not found

```bash
# Check mount
mount | grep labware-docker.sock

# Check SSH connectivity
ssh -i /root/.ssh/labware_key root@LABWARE_VM_IP "systemctl status docker"

# Remount
sudo umount /run/labware-docker.sock
sudo sshfs -o allow_other,IdentityFile=/root/.ssh/labware_key \
  root@LABWARE_VM_IP:/var/run/docker.sock \
  /run/labware-docker.sock
```

### Permission denied

```bash
# Check socket permissions
ls -la /run/labware-docker.sock

# Should be accessible to docker group
sudo chmod 666 /run/labware-docker.sock  # If needed
```

### Runner can't access socket

```bash
# Check runner is running
docker ps | grep forgejo-runner

# Check socket mount in runner
docker inspect forgejo-runner | jq '.[0].Mounts[] | select(.Destination=="/run/labware-docker.sock")'

# Restart runner
docker-compose restart forgejo-runner
```

### Labware VM connection lost

```bash
# Check VM is up
ping LABWARE_VM_IP

# Check Docker on VM
ssh root@LABWARE_VM_IP "docker ps"

# Remount socket
sudo umount /run/labware-docker.sock
# Mount again (see Setup section)
```

## Maintenance

### Cleanup Labware VM

```bash
# Remove old containers
DOCKER_HOST=unix:///run/labware-docker.sock docker system prune -af

# Remove old images (keep last 5)
DOCKER_HOST=unix:///run/labware-docker.sock docker images --format "{{.Repository}}:{{.Tag}}" | \
  tail -n +6 | xargs -r docker rmi
```

### Monitor Disk Usage

```bash
# Check labware VM disk
ssh root@LABWARE_VM_IP "df -h"

# Check Docker disk usage
DOCKER_HOST=unix:///run/labware-docker.sock docker system df
```

## Environment Variables

**No environment variables needed!** The socket path is hardcoded.

Old variable (deprecated):
- ~~`DIND_SOCKET_PATH`~~ - No longer used

## Related Files

- `compose.templates/productivity/forgejo-runner.yml` - Runner configuration
- `src/test-runner/src/test/kotlin/org/datamancy/testrunner/suites/LabwareDockerTestsTest.kt` - Test suite
- `.forgejo/workflows/*.yml` - CI/CD workflows (use labware automatically)

---

**✓ Socket path: `/run/labware-docker.sock` (hardcoded, tested, documented)**
