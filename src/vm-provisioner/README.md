# VM Provisioning Service

Sovereign VM provisioning and management service with integrated SSH key management for the Datamancy stack.

## Features

- **VM Lifecycle Management**: Create, start, stop, and delete VMs via libvirt/QEMU
- **SSH Key Management**: Generate and manage 4096-bit RSA SSH key pairs
- **Cloud Image Support**: Download and provision VMs from cloud images (Ubuntu, Debian, etc.)
- **Cloud-Init Integration**: Automatic SSH key injection via cloud-init
- **KFuncDB Integration**: Full tool definitions for agent-tool-server
- **RESTful API**: Simple HTTP API for all operations

## Architecture

```
┌─────────────────────┐
│  agent-tool-server  │  (KFuncDB tools)
└──────────┬──────────┘
           │ HTTP REST API
           ▼
┌─────────────────────┐
│  vm-provisioner     │
│  (Ktor Server)      │
└──────────┬──────────┘
           │
           ├──► virsh/libvirt  (VM management)
           ├──► qemu-img       (Disk operations)
           ├──► genisoimage    (Cloud-init ISOs)
           └──► BouncyCastle   (SSH keys)
```

## API Endpoints

### VM Management

#### List VMs
```bash
GET /api/vms
```

Response:
```json
{
  "vms": [
    {
      "name": "test-vm",
      "uuid": "abc123...",
      "state": "running",
      "memory": 2048,
      "vcpus": 2,
      "autostart": false
    }
  ]
}
```

#### Get VM Info
```bash
GET /api/vms/{name}
```

#### Create VM
```bash
POST /api/vms
Content-Type: application/json

{
  "name": "my-vm",
  "memory": 2048,
  "vcpus": 2,
  "diskSize": 20,
  "network": "default",
  "imageUrl": "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img"
}
```

#### Start VM
```bash
POST /api/vms/{name}/start
```

#### Stop VM
```bash
POST /api/vms/{name}/stop
```

#### Delete VM
```bash
DELETE /api/vms/{name}
```

### SSH Key Management

#### Generate Key Pair
```bash
POST /api/ssh-keys
Content-Type: application/json

{
  "name": "my-key",
  "comment": "My SSH Key"
}
```

Response:
```json
{
  "name": "my-key",
  "publicKey": "ssh-rsa AAAAB3NzaC1yc2EA... my-key",
  "privateKeyPath": "/app/ssh_keys/my-key",
  "publicKeyPath": "/app/ssh_keys/my-key.pub"
}
```

#### List Keys
```bash
GET /api/ssh-keys
```

#### Get Public Key
```bash
GET /api/ssh-keys/{name}/public
```

#### Inject SSH Key into VM
```bash
POST /api/vms/{vmName}/ssh-keys/{keyName}
```

Creates a cloud-init ISO and instructs to restart the VM with the ISO mounted.

## KFuncDB Tool Definitions

The following tools are available in `agent-tool-server` via `VmProvisioningPlugin`:

### VM Tools

- **vm_list** - List all virtual machines
- **vm_create** - Create and start a new VM
- **vm_start** - Start a stopped VM
- **vm_stop** - Gracefully shutdown a VM
- **vm_delete** - Delete a VM and its disk
- **vm_inject_ssh_key** - Inject SSH key into VM via cloud-init

### SSH Key Tools

- **ssh_key_generate** - Generate a 4096-bit RSA key pair
- **ssh_key_list** - List all managed SSH keys

## Usage Examples

### Creating a VM with Cloud Image

```kotlin
// Via KFuncDB tool
val result = vm_create(
    name = "ubuntu-dev",
    memory = 4096,
    vcpus = 4,
    diskSize = 40,
    network = "default",
    imageUrl = "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img"
)
```

### Generating and Injecting SSH Key

```kotlin
// Generate key
val keyPair = ssh_key_generate(
    name = "dev-key",
    comment = "Development SSH Key"
)

// Inject into VM
vm_inject_ssh_key(
    vmName = "ubuntu-dev",
    keyName = "dev-key"
)

// Restart VM for cloud-init to apply
vm_stop("ubuntu-dev")
// Wait a few seconds
vm_start("ubuntu-dev")
```

### Accessing VM via SSH

```bash
# Get the VM's IP (you'll need to query libvirt or use virsh)
VM_IP=$(virsh domifaddr ubuntu-dev | grep ipv4 | awk '{print $4}' | cut -d'/' -f1)

# SSH using the generated key
ssh -i /path/to/ssh_keys/dev-key ubuntu@$VM_IP
```

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `VM_PROVISIONER_PORT` | `8092` | HTTP server port |
| `LIBVIRT_URI` | `qemu:///system` | Libvirt connection URI |
| `SSH_KEY_DIR` | `/app/ssh_keys` | Directory for SSH keys |
| `LIBVIRT_STORAGE_POOL` | `default` | Libvirt storage pool name |
| `LIBVIRT_STORAGE_PATH` | `/var/lib/libvirt/images` | Path to VM disk images |

## Docker Deployment

### Prerequisites

1. **Host Requirements**:
   - libvirt/QEMU installed on host
   - KVM enabled (`/dev/kvm` accessible)
   - libvirt socket at `/var/run/libvirt/libvirt-sock`

2. **Verify KVM**:
```bash
# Check KVM module loaded
lsmod | grep kvm

# Check device accessible
ls -l /dev/kvm
```

3. **Verify libvirt**:
```bash
# Check libvirtd running
systemctl status libvirtd

# Test virsh works
virsh list --all
```

### Starting the Service

```bash
# With infrastructure profile
docker compose --profile infrastructure up -d vm-provisioner

# Or with compute profile
docker compose --profile compute up -d vm-provisioner
```

### Volumes

The service requires these volume mounts:

- `/var/run/libvirt/libvirt-sock` - Libvirt socket (read/write)
- `${VOLUMES_ROOT}/vm-provisioner/ssh_keys` - SSH key storage
- `${VOLUMES_ROOT}/vm-provisioner/vms` - VM disk images

## Security Considerations

### SSH Keys

- Private keys stored with `600` permissions
- 4096-bit RSA keys (strong encryption)
- Keys never transmitted over network (only public keys)

### VM Isolation

- VMs run in KVM isolation
- Network isolation via libvirt networks
- No direct host filesystem access from VMs

### Privileged Container

The container runs with `privileged: true` to access KVM and libvirt. This is required for:
- `/dev/kvm` access
- libvirt socket communication
- QEMU process spawning

**Production Recommendations**:
1. Use AppArmor/SELinux profiles to limit capabilities
2. Run on dedicated VM host (not production app server)
3. Implement network segmentation for VM networks
4. Use firewall rules to restrict VM network access

## Troubleshooting

### Container can't connect to libvirt

```bash
# Check libvirt socket exists and is accessible
ls -l /var/run/libvirt/libvirt-sock

# Check libvirtd is running on host
systemctl status libvirtd

# Check container has socket mounted
docker exec vm-provisioner ls -l /var/run/libvirt/libvirt-sock
```

### VM creation fails

```bash
# Check qemu-img works
docker exec vm-provisioner qemu-img --version

# Check storage directory writable
docker exec vm-provisioner ls -la /var/lib/libvirt/images

# Check libvirt default network exists
virsh net-list --all
```

### SSH key injection doesn't work

Cloud-init requires:
1. VM must be created from a cloud image (has cloud-init installed)
2. VM must be restarted after ISO creation
3. VM needs network access to download packages

Check cloud-init logs in VM:
```bash
virsh console <vm-name>
# Login (if you have console access)
sudo cat /var/log/cloud-init.log
```

## Development

### Building

```bash
# Build with Gradle
./gradlew :vm-provisioner:build

# Build fat JAR
./gradlew :vm-provisioner:shadowJar

# Build Docker image
cd src/vm-provisioner
docker build -t vm-provisioner .
```

### Testing Locally

```bash
# Run without Docker
cd src/vm-provisioner
export LIBVIRT_URI="qemu:///system"
export SSH_KEY_DIR="./test-keys"
export LIBVIRT_STORAGE_PATH="./test-vms"

./gradlew run
```

### Adding to agent-tool-server

The `VmProvisioningPlugin` is automatically registered if present in the classpath. To enable:

1. Ensure `VM_PROVISIONER_URL` environment variable is set in agent-tool-server
2. Service will be discovered and tools registered automatically

## Integration with LLM Agents

Example agent workflow:

```
Agent: I need a development environment for testing.

1. vm_create(name="test-env", memory=4096, vcpus=2, imageUrl="<ubuntu-cloud-image>")
2. ssh_key_generate(name="test-key")
3. vm_inject_ssh_key(vmName="test-env", keyName="test-key")
4. vm_stop("test-env")
5. wait(10 seconds)
6. vm_start("test-env")
7. [Agent can now SSH into test-env]
```

## Future Enhancements

- [ ] Support for VM snapshots
- [ ] VM cloning functionality
- [ ] Network bridge management
- [ ] Storage pool management
- [ ] VM templates/presets
- [ ] Metrics and monitoring integration
- [ ] Multi-host libvirt support
- [ ] GPU passthrough configuration

## License

Part of the Datamancy sovereign compute stack.
