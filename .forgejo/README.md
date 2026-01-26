# Forgejo Actions CI/CD Configuration

This directory contains Forgejo Actions workflows for the Datamancy self-upgrading platform.

## 🚀 Workflows

### 1. **ci.yml** - Build and Test
Triggers on: `push`, `pull_request`

- Runs Gradle tests
- Builds Datamancy stack
- Builds and tags Docker images
- Pushes images to local registry
- Runs validation tests

### 2. **deploy-sandbox.yml** - Preview Deployments
Triggers on: `pull_request` (opened, synchronize, reopened)

- Downloads build artifacts
- Deploys to isolated sandbox VM
- Runs integration tests
- Posts preview URL as PR comment
- Isolated testing environment per PR

**Note:** Requires external sandbox VM setup. See [SANDBOX_VM_SETUP.md](../docs/SANDBOX_VM_SETUP.md)

### 3. **promote-production.yml** - Production Deployment
Triggers on: `push to master/main`, `workflow_dispatch`

- Creates Kopia snapshot (backup)
- Promotes images to production
- Runs smoke tests
- Auto-rollback on failure
- Tags successful deployments

## 🔧 Setup Instructions

### 1. Enable Forgejo Actions

Actions are automatically enabled via environment variables in `forgejo.yml`:
```yaml
FORGEJO__actions__ENABLED: true
FORGEJO__actions__DEFAULT_ACTIONS_URL: https://code.forgejo.org
```

### 2. Generate Runner Registration Token

**Option A: Via Web UI (Recommended)**
1. Log in to Forgejo as admin
2. Go to: Site Administration → Actions → Runners
3. Click "Create new Runner"
4. Copy the registration token
5. Add to `.env`:
   ```bash
   FORGEJO_RUNNER_TOKEN=your-token-here
   ```

**Option B: Via Script**
```bash
docker-compose exec forgejo /configs/applications/forgejo/setup-actions.sh
```

### 3. Start Forgejo Runner

```bash
cd dist/  # or ~/.datamancy
docker-compose up -d forgejo-runner
```

Verify runner is connected:
- Go to: Site Administration → Actions → Runners
- Should see "datamancy-runner-1" with status "Idle"

### 4. Configure Secrets (for sandbox/production deployment)

In Forgejo repository settings → Secrets:

**Required Secrets:**
- `SANDBOX_VM_HOST` - IP/hostname of sandbox VM (e.g., `192.168.1.100`)
- `SANDBOX_VM_SSH_KEY` - SSH private key for sandbox VM access
- `PRODUCTION_HOST` - Production server hostname (e.g., `datamancy.net`)
- `PRODUCTION_SSH_KEY` - SSH private key for production access

**Setup Sandbox VM:** Follow [SANDBOX_VM_SETUP.md](../docs/SANDBOX_VM_SETUP.md) to configure external VM

**How to add secrets:**
1. Repository → Settings → Secrets and Variables → Actions
2. Click "New secret"
3. Add each secret with appropriate value

### 5. Test the Pipeline

**Create a test commit:**
```bash
git checkout -b test-ci
echo "# Test" >> README.md
git add README.md
git commit -m "test: CI pipeline"
git push origin test-ci
```

**Check workflow status:**
- Go to repository → Actions
- Should see "CI - Build and Test" running

**Create a PR:**
- Create PR from `test-ci` to `master`
- Should trigger sandbox deployment
- Check PR comments for preview URL

## 📋 Runner Configuration

Runner config: `configs.templates/applications/forgejo-runner/config.yaml`

Key settings:
- **Capacity**: 3 parallel jobs
- **Timeout**: 3 hours per job
- **Labels**: ubuntu-latest, ubuntu-22.04, linux
- **Network**: `datamancy-stack_docker-proxy`
- **Docker access**: Shared socket for image builds

## 🔍 Troubleshooting

### Runner not connecting
```bash
# Check runner logs
docker-compose logs -f forgejo-runner

# Verify Forgejo is reachable
docker-compose exec forgejo-runner curl http://forgejo:3000/api/healthz

# Re-register runner
docker-compose exec forgejo-runner forgejo-runner register \
  --instance http://forgejo:3000 \
  --token YOUR_TOKEN_HERE
```

### Jobs failing
```bash
# Check runner has Docker access
docker-compose exec forgejo-runner docker ps

# Check network connectivity
docker-compose exec forgejo-runner ping registry
docker-compose exec forgejo-runner ping dind

# View job logs in Forgejo UI
```

### Registry access issues
```bash
# Test registry from runner
docker-compose exec forgejo-runner curl http://registry:5000/v2/

# Check registry logs
docker-compose logs registry
```

## 🎯 Workflow Sequence

```
Developer: Edit code in Jupyter
    ↓
Developer: git push origin feature-branch
    ↓
Forgejo: Receive push → trigger ci.yml
    ↓
CI: Build + Test + Push images to registry
    ↓
Developer: Create PR
    ↓
Forgejo: Trigger deploy-sandbox.yml
    ↓
Sandbox: Deploy to DinD VM → Run tests → Comment preview URL
    ↓
Developer: Review + Approve PR
    ↓
Forgejo: Merge PR → trigger promote-production.yml
    ↓
Production: Snapshot → Promote → Test → Success (or Auto-rollback)
```

## 📚 Additional Resources

- [Forgejo Actions Documentation](https://forgejo.org/docs/latest/user/actions/)
- [Forgejo Runner Setup](https://code.forgejo.org/forgejo/runner)
- [GitHub Actions Compatibility](https://forgejo.org/docs/latest/user/actions/#github-actions-compatibility)

## 🧞‍♂️ Self-Upgrade Complete!

Your Datamancy stack can now upgrade itself through Jupyter → Forgejo → CI/CD → Production!
