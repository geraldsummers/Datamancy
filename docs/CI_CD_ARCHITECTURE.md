# CI/CD Architecture - Self-Upgrading Platform

Complete architecture documentation for Datamancy's self-hosting CI/CD pipeline.

## 🎯 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         PRODUCTION HOST                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│  │  Forgejo     │  │  Registry    │  │  Kopia       │                  │
│  │  (Git+CI)    │  │  (Images)    │  │  (Backups)   │                  │
│  └──────────────┘  └──────────────┘  └──────────────┘                  │
│         │                  │                  │                          │
│  ┌──────────────┐          │                  │                          │
│  │ Forgejo      │          │                  │                          │
│  │ Runner       │◄─────────┘                  │                          │
│  └──────┬───────┘                             │                          │
│         │                                      │                          │
│         │ mounts                               │                          │
│         ▼                                      │                          │
│  /var/run/dind-vm.sock ◄──────────────────┐   │                          │
│  (via SSHFS/NFS)                          │   │                          │
└───────────────────────────────────────────┼───┼──────────────────────────┘
                                            │   │
                           Network/Mount    │   │
                                            │   │
┌───────────────────────────────────────────┼───┼──────────────────────────┐
│                       SANDBOX VM          │   │                          │
│                                           │   │                          │
│  ┌────────────────────────────────────────┘   │                          │
│  │  Docker Daemon                             │                          │
│  │  /var/run/docker.sock                      │                          │
│  └────────┬───────────────────────────────────┘                          │
│           │                                                               │
│           ▼                                                               │
│  ┌──────────────────────┐  ┌──────────────────────┐                     │
│  │  PR-123 Deployment   │  │  PR-124 Deployment   │   (Isolated)        │
│  │  (untrusted code)    │  │  (untrusted code)    │                     │
│  └──────────────────────┘  └──────────────────────┘                     │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

## 🔄 Complete Self-Upgrade Flow

### 1. Development (Jupyter → Git)

```
JupyterLab (Human + AI)
    ├── Edit Kotlin code in notebooks
    ├── Use Gradle for builds (gradle build, gradle test)
    ├── JupyterLab Git extension for commits
    └── git push origin feature-branch
         ↓
    Forgejo receives push
```

### 2. CI Pipeline (Forgejo Actions)

**Workflow: `.forgejo/workflows/ci.yml`**

```yaml
on: [push, pull_request]
steps:
  1. Run unit tests (./gradlew test)
  2. Build stack (./build-datamancy.main.kts)
  3. Build Docker images (tag: git-sha)
  4. Push to registry:5000
  5. Upload dist/ artifacts
```

**Runner:** `forgejo-runner` container
- Uses external VM socket: `/var/run/dind-vm.sock`
- Builds in isolated environment
- No access to production containers

### 3. Sandbox Deployment (PR Preview)

**Workflow: `.forgejo/workflows/deploy-sandbox.yml`**

```yaml
on: pull_request
steps:
  1. Download build artifacts
  2. SSH to sandbox VM
  3. Deploy to /deployments/pr-{number}
  4. docker-compose up -d (on VM)
  5. Run integration tests
  6. Post preview URL in PR comment
```

**Isolation:**
- Each PR gets own deployment on VM
- VM has no access to production data
- Cleanup on PR close

### 4. Human Review & Merge

```
Developer reviews PR
    ├── Checks sandbox preview
    ├── Reviews code changes
    └── Approves & merges to master
         ↓
    Merge triggers production promotion
```

### 5. Production Promotion

**Workflow: `.forgejo/workflows/promote-production.yml`**

```yaml
on:
  push:
    branches: [master, main]
steps:
  1. pre-promotion-snapshot.sh
     ├── Kopia snapshot all volumes
     ├── Git tag current state
     └── Backup configs

  2. promote.sh {version}
     ├── Pull images from registry
     ├── Update docker-compose.yml tags
     ├── Rolling update (service by service)
     └── Health checks (60s timeout)

  3. IF SUCCESS:
     ├── Tag deployment
     └── Notify success

  4. IF FAILURE:
     ├── rollback.sh (auto)
     ├── Restore Kopia snapshot
     ├── Revert to previous version
     └── Alert admin
```

## 📁 File Structure

```
Datamancy/
├── .forgejo/
│   ├── workflows/
│   │   ├── ci.yml                    # Build & test
│   │   ├── deploy-sandbox.yml        # PR previews
│   │   └── promote-production.yml    # Production deployment
│   └── README.md                     # Setup guide
│
├── scripts/
│   └── deployment/
│       ├── pre-promotion-snapshot.sh # Kopia backup
│       ├── promote.sh                # Safe promotion
│       └── rollback.sh               # Restore snapshot
│
├── compose.templates/
│   ├── productivity/
│   │   ├── forgejo.yml              # Git server (Actions enabled)
│   │   └── forgejo-runner.yml       # CI/CD runner
│   └── infrastructure/
│       └── registry.yml              # Docker image registry
│
├── configs.templates/
│   └── applications/
│       ├── forgejo/
│       │   ├── init-forgejo.sh      # OIDC setup
│       │   └── setup-actions.sh     # Actions setup
│       └── forgejo-runner/
│           └── config.yaml           # Runner config
│
└── docs/
    ├── SANDBOX_VM_SETUP.md          # VM setup guide
    └── CI_CD_ARCHITECTURE.md         # This file
```

## 🔒 Security Model

### Trust Boundaries

1. **Trusted Zone (Production Host)**
   - Forgejo (git server)
   - Registry (signed images)
   - Kopia (encrypted backups)
   - Production containers

2. **Build Zone (Forgejo Runner)**
   - Can build images
   - Can push to registry
   - Uses VM socket (isolated)
   - No direct production access

3. **Untrusted Zone (Sandbox VM)**
   - Runs arbitrary PR code
   - Completely isolated
   - No production data access
   - Disposable deployments

### Socket Security

```
Production: /var/run/docker.sock
    └── Production containers (protected)

Mounted: /var/run/dind-vm.sock
    └── Points to VM socket (isolated)

Sandbox VM: /var/run/docker.sock
    └── Sandbox containers (untrusted)
```

**Forgejo runner only sees sandbox VM socket**, never production.

## 🛡️ Rollback Strategy

### Automatic Rollback

Triggered when:
- Health checks fail after promotion
- Services don't start within timeout
- Critical errors during deployment

Process:
```bash
scripts/rollback.sh
    ├── docker-compose down
    ├── kopia snapshot restore {id}
    ├── git checkout {previous-tag}
    ├── docker-compose up -d
    └── Alert admin
```

### Manual Rollback

```bash
# List available snapshots
ls ~/.datamancy/snapshots/*.manifest.json

# Rollback to specific snapshot
cd ~/.datamancy
./scripts/deployment/rollback.sh pre-promotion-abc123-20260126-143022

# Or use latest
./scripts/deployment/rollback.sh
```

## 📊 Monitoring & Observability

### CI/CD Metrics

**Forgejo UI:**
- Actions → View workflow runs
- Check build times, success rates
- Download job logs

**Grafana Dashboards:**
- Deployment frequency
- Rollback rate
- Health check success rate

### Health Checks

Every service has health check in `docker-compose.yml`:
```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost/health"]
  interval: 60s
  timeout: 10s
  retries: 3
```

Promotion waits for all health checks to pass.

## 🎯 Configuration

### Environment Variables

**`.env` (Production Host)**
```bash
# Sandbox VM socket path (mounted by host)
DIND_SOCKET_PATH=/var/run/dind-vm.sock

# Forgejo Actions
FORGEJO_RUNNER_TOKEN=<from-forgejo-ui>

# Docker Registry
REGISTRY=localhost:5000
```

### Forgejo Secrets

**Repository Settings → Secrets:**
```
SANDBOX_VM_HOST       # e.g., 192.168.1.100
SANDBOX_VM_SSH_KEY    # SSH private key
PRODUCTION_HOST       # e.g., datamancy.net
PRODUCTION_SSH_KEY    # SSH private key
```

## 🚀 Quick Start

### Initial Setup

```bash
# 1. Build with new CI/CD components
cd /home/gerald/IdeaProjects/Datamancy
./build-datamancy.main.kts

# 2. Setup sandbox VM (see SANDBOX_VM_SETUP.md)
# - Install Docker on VM
# - Configure SSH access
# - Mount VM socket on production host

# 3. Deploy services
cd dist/
docker-compose up -d forgejo registry forgejo-runner

# 4. Configure Forgejo
# - Generate runner token (UI or script)
# - Add to .env: FORGEJO_RUNNER_TOKEN=...
# - Restart runner: docker-compose restart forgejo-runner

# 5. Add repository secrets (Forgejo UI)
# - SANDBOX_VM_HOST, SANDBOX_VM_SSH_KEY
# - PRODUCTION_HOST, PRODUCTION_SSH_KEY

# 6. Test with a PR!
```

### Test Pipeline

```bash
# Create test branch
git checkout -b test-cicd
echo "# Test" >> README.md
git add README.md
git commit -m "test: CI/CD pipeline"
git push origin test-cicd

# Create PR in Forgejo
# Watch workflow run in Actions tab
# Check PR comments for sandbox preview URL

# Merge PR
# Watch production promotion in Actions
```

## 📚 Key Benefits

1. **Self-Hosting**: Entire CI/CD runs on your infrastructure
2. **Isolation**: Untrusted code runs in separate VM
3. **Safety**: Automatic rollback on failure
4. **Traceability**: Git tags, Kopia snapshots, audit logs
5. **Self-Upgrading**: Platform develops itself via Jupyter
6. **Zero Vendor Lock-in**: Forgejo (open source), local registry

## 🔗 Related Documentation

- [Forgejo README](.forgejo/README.md) - Detailed setup
- [Sandbox VM Setup](SANDBOX_VM_SETUP.md) - VM configuration
- [Deployment Scripts](../scripts/deployment/) - Promotion/rollback
- [Workflow Files](../.forgejo/workflows/) - CI/CD definitions

---

**🧞‍♂️ Your self-upgrading platform is complete!**
