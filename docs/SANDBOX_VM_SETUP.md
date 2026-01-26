# Sandbox VM Setup Guide

This guide explains how to set up an external VM with Docker for isolated CI/CD sandbox deployments.

## 🎯 Architecture

```
Production Host (datamancy.net)
├── Forgejo + Runner (builds code)
├── Docker Registry (stores images)
└── Socket Mount: /var/run/dind-vm.sock
         ↓ (SSHFS/NFS)
    Sandbox VM (isolated)
    └── Docker Daemon (runs untrusted builds)
        └── Socket: /var/run/docker.sock
```

**Key Design:**
- Sandbox VM runs Docker daemon
- Production host mounts VM's Docker socket as a file
- Forgejo runner uses mounted socket to deploy to sandbox
- Complete isolation: sandbox cannot access production

## 📋 Prerequisites

- Linux VM with Docker installed
- SSH access from production host to VM
- Network connectivity between production and VM
- At least 4GB RAM, 50GB disk for VM

## 🔧 VM Setup

### 1. Install Docker on Sandbox VM

```bash
# On sandbox VM
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# Create directory for deployments
mkdir -p /deployments

# Verify Docker is running
docker ps
```

### 2. Configure Docker Daemon

Edit `/etc/docker/daemon.json`:

```json
{
  "insecure-registries": ["YOUR_PRODUCTION_HOST:5000"],
  "storage-driver": "overlay2",
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
```

Replace `YOUR_PRODUCTION_HOST` with your production server IP/hostname.

```bash
# Restart Docker to apply changes
systemctl restart docker
```

### 3. Configure SSH Access

On production host:

```bash
# Generate SSH key for VM access (if not exists)
ssh-keygen -t ed25519 -f ~/.ssh/sandbox_vm_key -N ""

# Copy public key to VM
ssh-copy-id -i ~/.ssh/sandbox_vm_key.pub root@SANDBOX_VM_IP
```

Test SSH access:

```bash
ssh -i ~/.ssh/sandbox_vm_key root@SANDBOX_VM_IP "docker ps"
```

## 🔌 Mount VM Socket on Production Host

### Option A: SSHFS (Recommended)

**Install SSHFS:**
```bash
# On production host
sudo apt-get install sshfs

# Create mount point
sudo mkdir -p /var/run/dind-vm
sudo chown $(whoami):$(whoami) /var/run/dind-vm
```

**Mount the socket:**
```bash
# Mount VM's Docker socket
sshfs -o allow_other,default_permissions,IdentityFile=~/.ssh/sandbox_vm_key \
  root@SANDBOX_VM_IP:/var/run/docker.sock \
  /var/run/dind-vm.sock
```

**Test the mount:**
```bash
# This should show the VM's Docker containers
DOCKER_HOST=unix:///var/run/dind-vm.sock docker ps
```

**Make it persistent** (add to `/etc/fstab`):
```
root@SANDBOX_VM_IP:/var/run/docker.sock /var/run/dind-vm.sock fuse.sshfs noauto,x-systemd.automount,_netdev,users,idmap=user,IdentityFile=/home/YOUR_USER/.ssh/sandbox_vm_key,allow_other,default_permissions 0 0
```

Replace `SANDBOX_VM_IP` and `YOUR_USER`.

### Option B: NFS (Alternative)

**On sandbox VM:**
```bash
# Install NFS server
apt-get install nfs-kernel-server

# Export Docker socket directory
echo "/var/run *(rw,sync,no_subtree_check,no_root_squash)" >> /etc/exports

# Restart NFS
exportfs -ra
systemctl restart nfs-kernel-server
```

**On production host:**
```bash
# Install NFS client
apt-get install nfs-common

# Mount
mount -t nfs SANDBOX_VM_IP:/var/run/docker.sock /var/run/dind-vm.sock
```

## ⚙️ Configure Datamancy Stack

### 1. Add to `.env`

```bash
# Sandbox VM Docker socket path (on host)
DIND_SOCKET_PATH=/var/run/dind-vm.sock
```

### 2. Configure Forgejo Secrets

In Forgejo web UI (Repository → Settings → Secrets):

**Add these secrets:**

| Secret Name | Value | Description |
|------------|-------|-------------|
| `SANDBOX_VM_HOST` | `192.168.1.100` | Sandbox VM IP/hostname |
| `SANDBOX_VM_SSH_KEY` | `<private key>` | SSH private key content |
| `PRODUCTION_HOST` | `datamancy.net` | Production hostname |
| `PRODUCTION_SSH_KEY` | `<private key>` | Production SSH key |

**How to get private key:**
```bash
cat ~/.ssh/sandbox_vm_key
# Copy entire output including BEGIN/END lines
```

### 3. Rebuild Stack

```bash
cd /home/gerald/IdeaProjects/Datamancy
./build-datamancy.main.kts

cd dist/
docker-compose up -d forgejo-runner
```

### 4. Verify Runner Connection

```bash
# Check runner can access VM Docker
docker-compose exec forgejo-runner sh -c "docker ps"

# Should show VM's containers, not host containers
```

## ✅ Testing

### Test 1: Socket Access

```bash
# From production host
DOCKER_HOST=unix:///var/run/dind-vm.sock docker run --rm hello-world

# Should pull and run on VM, not production host
```

### Test 2: Create Test PR

```bash
git checkout -b test-sandbox
echo "# Test" >> README.md
git add README.md
git commit -m "test: sandbox deployment"
git push origin test-sandbox

# Create PR in Forgejo UI
# Should trigger sandbox deployment workflow
```

### Test 3: Verify Isolation

```bash
# SSH to VM
ssh -i ~/.ssh/sandbox_vm_key root@SANDBOX_VM_IP

# Check deployed containers
docker ps | grep "pr-"

# These should NOT appear on production host
```

## 🔒 Security Notes

1. **Isolation**: Sandbox VM cannot access production data
2. **Socket Permissions**: Only Forgejo runner can access VM socket
3. **Firewall**: Block sandbox VM from accessing production network (except registry)
4. **Secrets**: Never commit SSH keys to git
5. **Cleanup**: Old PR deployments should be cleaned up regularly

## 🧹 Maintenance

### Cleanup Old Deployments

```bash
# On sandbox VM
docker ps -a | grep "pr-" | awk '{print $1}' | xargs docker rm -f
docker system prune -af --volumes
```

### Monitor Disk Usage

```bash
# On sandbox VM
df -h
docker system df
```

### Restart Docker on VM

```bash
# On sandbox VM
systemctl restart docker

# On production host (remount socket)
umount /var/run/dind-vm.sock
# Mount again (see Option A or B above)
```

## 🆘 Troubleshooting

### Socket mount fails
```bash
# Check SSH connectivity
ssh -i ~/.ssh/sandbox_vm_key root@SANDBOX_VM_IP "echo OK"

# Check Docker is running on VM
ssh -i ~/.ssh/sandbox_vm_key root@SANDBOX_VM_IP "systemctl status docker"

# Try manual mount with debug
sshfs -d -o allow_other,IdentityFile=~/.ssh/sandbox_vm_key \
  root@SANDBOX_VM_IP:/var/run/docker.sock /var/run/dind-vm.sock
```

### Runner can't access socket
```bash
# Check socket file exists
ls -la /var/run/dind-vm.sock

# Check permissions
# Should be readable by docker group

# Test from runner container
docker-compose exec forgejo-runner ls -la /var/run/dind-vm.sock
```

### Workflows fail with socket errors
```bash
# Check Forgejo runner logs
docker-compose logs -f forgejo-runner

# Verify DOCKER_HOST is set correctly in runner
docker-compose exec forgejo-runner env | grep DOCKER_HOST
```

### Can't pull from registry
```bash
# On sandbox VM, verify insecure registry is configured
cat /etc/docker/daemon.json

# Test manual pull
ssh root@SANDBOX_VM_IP "docker pull YOUR_PRODUCTION_HOST:5000/datamancy-jupyterhub:latest"
```

## 📚 Additional Resources

- [Docker Socket Forwarding](https://docs.docker.com/engine/security/protect-access/)
- [SSHFS Documentation](https://github.com/libfuse/sshfs)
- [Forgejo Actions](https://forgejo.org/docs/latest/user/actions/)
