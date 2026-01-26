# CI/CD Pipeline Testing Guide

Comprehensive testing for the Datamancy CI/CD pipeline using labware socket.

## Test Suites

### 1. Labware Socket Tests (`LabwareDockerTestsTest`)

**Purpose:** Verify labware socket connectivity and isolation

**Tests:**
- ✓ Socket file exists at `/run/labware-docker.sock`
- ✓ Socket is connectable
- ✓ Docker daemon responds to ping
- ✓ Can list containers
- ✓ Isolation from production (no container overlap)
- ✓ Supports image operations
- ✓ Supports container creation
- ✓ Forgejo runner has access

### 2. CI/CD Pipeline Tests (`CICDPipelineTestsTest`)

**Purpose:** End-to-end CI/CD workflow validation

**Tests:**
- ✓ Build Docker images on labware
- ✓ Push images to registry
- ✓ Deploy containers from registry
- ✓ Verify deployment isolation
- ✓ Multi-stage build support
- ✓ Build Datamancy service images
- ✓ Docker Compose deployments
- ✓ Resource limits enforcement
- ✓ Network isolation

## Quick Start

### Run All Tests

```bash
cd /home/gerald/IdeaProjects/Datamancy
./scripts/test-cicd.sh all
```

### Run Specific Test Suites

```bash
# Labware socket tests only
./scripts/test-cicd.sh labware

# CI/CD pipeline tests only
./scripts/test-cicd.sh cicd

# Image building tests
./scripts/test-cicd.sh build

# Registry operations
./scripts/test-cicd.sh registry

# Deployment tests
./scripts/test-cicd.sh deployment
```

### Verbose Output

```bash
VERBOSE=true ./scripts/test-cicd.sh all
```

## Using Gradle Directly

### Run All CI/CD Tests

```bash
./gradlew test-runner:test --tests "*Labware*,*CICD*"
```

### Run Individual Test Class

```bash
# Labware socket tests
./gradlew test-runner:test --tests "LabwareDockerTestsTest"

# CI/CD pipeline tests
./gradlew test-runner:test --tests "CICDPipelineTestsTest"
```

### Run Single Test

```bash
# Test specific functionality
./gradlew test-runner:test --tests "CICDPipelineTestsTest.should build Docker image on labware socket"
```

### Watch Mode (Re-run on Changes)

```bash
./gradlew test-runner:test --tests "*CICD*" --continuous
```

## From Docker (Deployed Environment)

### Run in Test Runner Container

```bash
cd ~/.datamancy
docker-compose run --rm test-runner ./gradlew test --tests "*Labware*,*CICD*"
```

### Quick Health Check

```bash
docker-compose run --rm test-runner ./scripts/test-cicd.sh labware
```

## Test Output

### Success Example

```
╔════════════════════════════════════════════════════════════════╗
║       Datamancy CI/CD Pipeline Test Suite                     ║
╚════════════════════════════════════════════════════════════════╝

▸ Checking labware socket...
✓ Labware socket exists
▸ Testing socket connectivity...
✓ Labware Docker socket is accessible
▸ Checking test-runner availability...
✓ test-runner module found

═══════════════════════════════════════════════════════════════
  Running Test Suites
═══════════════════════════════════════════════════════════════

Test filter: *Labware*,*CICD*

BUILD SUCCESSFUL in 45s

═══════════════════════════════════════════════════════════════
✓ All tests passed!

Test Results:
  • Labware socket: ✓ Verified
  • Image building: ✓ Working
  • Registry ops:   ✓ Working
  • Deployments:    ✓ Working
  • Isolation:      ✓ Confirmed

Your CI/CD pipeline is ready! 🚀
═══════════════════════════════════════════════════════════════
```

## Prerequisites

### 1. Labware Socket Mounted

```bash
# Check socket exists
test -S /run/labware-docker.sock && echo "✓ Socket exists"

# Test connectivity
docker -H unix:///run/labware-docker.sock ps
```

If socket is missing, see [LABWARE_SOCKET.md](LABWARE_SOCKET.md) for setup.

### 2. Registry Running

```bash
# Check registry is accessible
curl http://registry:5000/v2/

# Or from outside containers
curl http://localhost:5000/v2/
```

### 3. Test Runner Built

```bash
./gradlew test-runner:build
```

## Test Details

### Image Building Test

**What it does:**
1. Creates temporary Dockerfile
2. Builds image on labware socket
3. Verifies image exists
4. Cleans up

**Expected result:** Image builds successfully on isolated labware Docker

### Registry Push/Pull Test

**What it does:**
1. Builds test image
2. Tags for registry
3. Pushes to registry
4. Removes local copy
5. Pulls from registry
6. Verifies content

**Expected result:** Full push/pull cycle works

### Deployment Test

**What it does:**
1. Builds image
2. Pushes to registry
3. Removes local copy
4. Deploys (pull + run)
5. Verifies output

**Expected result:** Complete deployment workflow succeeds

### Isolation Test

**What it does:**
1. Starts container on labware
2. Checks visibility on labware
3. Checks NOT visible on production
4. Verifies zero overlap

**Expected result:** Containers isolated between labware and production

## Troubleshooting

### Socket Not Found

```bash
# Check mount
mount | grep labware-docker.sock

# Remount if needed
sudo sshfs -o allow_other,IdentityFile=/root/.ssh/labware_key \
  root@LABWARE_VM_IP:/var/run/docker.sock \
  /run/labware-docker.sock
```

### Registry Connection Failed

```bash
# Check registry is running
docker ps | grep registry

# Check registry health
curl http://registry:5000/v2/

# Restart if needed
docker-compose restart registry
```

### Tests Timeout

```bash
# Check labware VM is responsive
ssh root@labware-vm "docker ps"

# Check network latency
ping labware-vm

# Increase test timeout (in test code)
```

### Permission Denied

```bash
# Check socket permissions
ls -la /run/labware-docker.sock

# Fix if needed
sudo chmod 666 /run/labware-docker.sock

# Or ensure user is in docker group
sudo usermod -aG docker $USER
```

### Build Failures on Labware

```bash
# Check labware Docker has resources
ssh root@labware-vm "docker system df"

# Clean up if needed
docker -H unix:///run/labware-docker.sock system prune -af

# Check labware VM disk space
ssh root@labware-vm "df -h"
```

## Continuous Integration

### Add to Forgejo Actions

Add to `.forgejo/workflows/ci.yml`:

```yaml
- name: Run CI/CD Tests
  run: |
    ./scripts/test-cicd.sh all
```

### Pre-commit Hook

```bash
#!/bin/bash
# .git/hooks/pre-commit
./scripts/test-cicd.sh labware || {
  echo "Labware socket tests failed!"
  exit 1
}
```

### Scheduled Testing

```yaml
# .forgejo/workflows/scheduled-tests.yml
on:
  schedule:
    - cron: '0 */6 * * *'  # Every 6 hours

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Test CI/CD Pipeline
        run: ./scripts/test-cicd.sh all
```

## Performance Benchmarks

### Expected Test Duration

| Test Suite | Tests | Duration | Notes |
|------------|-------|----------|-------|
| Labware Socket | 8 | ~10s | Connectivity checks |
| CI/CD Pipeline | 9 | ~2-3min | Includes builds |
| Full Suite | 17 | ~3-4min | All tests |

### Optimization Tips

1. **Parallel execution:** Run independent tests concurrently
2. **Image caching:** Use `--cache-from` for faster builds
3. **Registry proximity:** Keep registry close to labware VM
4. **Resource allocation:** Give labware VM adequate CPU/RAM

## Related Documentation

- [LABWARE_SOCKET.md](LABWARE_SOCKET.md) - Socket setup guide
- [CI_CD_ARCHITECTURE.md](CI_CD_ARCHITECTURE.md) - Overall architecture
- [.forgejo/README.md](../.forgejo/README.md) - Forgejo Actions setup

---

**Test early, test often, deploy confidently! 🧪✨**
