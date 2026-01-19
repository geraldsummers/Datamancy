# Testing & Deployment Guide

## 🎯 **How It Works**

### Architecture
```
[Your Laptop]                    [latium.local Server]
     │                                    │
     ├─ Build stack                       │
     ├─ Rsync to server ─────────────────→│
     │                                    ├─ docker-compose.yml
     │                                    ├─ docker-compose.test-runner.yml
     │                                    ├─ test-scripts/
     │                                    │   ├─ test-agent-stack.main.kts
     │                                    │   └─ test-stack.sh
     │                                    │
     │                                    ├─ docker compose up -d
     │                                    │
     └─ SSH: run test-stack.sh ─────────→├─ Run tests internally
                                          └─ Results returned
```

### Key Principles
1. **Build locally** - Build happens on your laptop
2. **Deploy via rsync** - Fast, incremental sync to server
3. **Tests run on server** - Test scripts bundled with deployment
4. **Internal network access** - Tests run inside Docker networks

---

## 🚀 **Quick Start**

### One Command E2E
```bash
# Build → Deploy → Start → Test (all in one)
./scripts/deploy-test-e2e.sh foundation
```

### Step by Step
```bash
# 1. Build and deploy
./scripts/deploy.sh

# 2. Start stack (on server)
ssh gerald@latium.local 'cd ~/.datamancy && docker compose up -d'

# 3. Run tests (on server)
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh foundation'
```

---

## 📁 **Files Created**

### Local Scripts (Your Laptop)
| File | Purpose |
|------|---------|
| `scripts/deploy.sh` | Build stack and rsync to server |
| `scripts/deploy-test-e2e.sh` | Complete E2E: deploy + start + test |

### Server-Side Scripts (Bundled in Deployment)
| File | Purpose |
|------|---------|
| `test-scripts/test-stack.sh` | Runs tests on server |
| `test-scripts/test-agent-stack.main.kts` | Kotlin test script |
| `test-scripts/*.md` | Documentation |
| `docker-compose.test-runner.yml` | Test runner container definition |

### Build System Changes
| File | Change |
|------|--------|
| `build-datamancy.main.kts` | Added `copyTestScripts()` function |
| `compose.templates/docker-compose.test-runner.yml` | Test runner overlay |
| `configs.templates/infrastructure/caddy/Caddyfile` | Removed agent-tool-server external route |
| `compose.templates/datamancy-services.yml` | Removed caddy network from agent-tool-server |

---

## 🧪 **Test Suites**

### Available Suites
```bash
# Fast (~2s) - Basic connectivity
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh foundation'

# Moderate (~15s) - Docker operations
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh docker'

# Slow (~30s) - LLM integration
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh llm'

# Knowledge base (~10s) - Database queries
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh knowledge-base'

# End-to-end (~60s) - Full workflows
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh e2e'

# All tests (~90s)
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh all'
```

---

## 🔒 **Security Changes Applied**

### Agent-Tool-Server Lockdown ✅
- ❌ **Removed** from external access (`https://agent-tool-server.datamancy.net`)
- ❌ **Removed** from Caddy network
- ✅ **Internal-only** - accessible only from Docker networks
- ✅ **Tests run internally** - via test runner container

### Why This Matters
The agent-tool-server provides:
- Docker container spawning
- Database queries
- SSH operations
- Host command execution

**External exposure = massive attack surface**, even with auth.

---

## 📊 **Workflow Examples**

### Development Workflow
```bash
# 1. Make changes to code
vim src/agent-tool-server/src/main/kotlin/Main.kt

# 2. Deploy
./scripts/deploy.sh

# 3. Restart affected service
ssh gerald@latium.local 'cd ~/.datamancy && docker compose restart agent-tool-server'

# 4. Test
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh foundation'
```

### Pre-Production Validation
```bash
# Full E2E before merging to main
./scripts/deploy-test-e2e.sh all

# If tests pass, tag and push
git tag -a v1.2.3 -m "Release 1.2.3"
git push --tags
```

### Debugging Failed Tests
```bash
# Run tests with verbose output
ssh gerald@latium.local 'cd ~/.datamancy && \
    docker compose -f docker-compose.yml -f docker-compose.test-runner.yml \
    run --rm stack-test-runner bash'

# Inside container, run tests manually
./test-agent-stack.main.kts --env internal --suite foundation

# Check agent-tool-server logs
docker logs agent-tool-server --tail 100
```

---

## 🔧 **Troubleshooting**

### "agent-tool-server not running"
```bash
# Check status
ssh gerald@latium.local 'cd ~/.datamancy && docker compose ps'

# Start it
ssh gerald@latium.local 'cd ~/.datamancy && docker compose up -d agent-tool-server'

# Check logs
ssh gerald@latium.local 'docker logs agent-tool-server'
```

### "Connection refused" in tests
```bash
# Verify test runner can reach services
ssh gerald@latium.local 'cd ~/.datamancy && \
    docker compose run --rm stack-test-runner \
    wget -qO- http://agent-tool-server:8081/health'

# Should return: {"status":"ok"}
```

### "Kotlin script compilation failed"
```bash
# The test runner container has Kotlin bundled
# If compilation fails, check syntax:
kotlin test-agent-stack.main.kts --help
```

### Tests work locally but fail on server
```bash
# Compare environments
./test-agent-stack.main.kts --env local  # Your laptop
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh foundation'  # Server

# Check network differences
docker network inspect datamancy-stack_ai
```

---

## ⚡ **Performance Notes**

### Build Times
- **Full build:** ~1min (Gradle + Kotlin compilation)
- **Incremental (skip Gradle):** ~5s
- **Rsync (first time):** ~10s (full sync)
- **Rsync (incremental):** ~2s (only changed files)

### Test Times
| Suite | Duration | Network Calls |
|-------|----------|---------------|
| foundation | 1-2s | 3 HTTP requests |
| docker | 10-15s | Container lifecycle |
| llm | 20-30s | Model inference |
| knowledge-base | 5-10s | DB queries |
| e2e | 30-60s | Multi-step workflows |
| all | 60-90s | Everything |

### Optimization Tips
```bash
# Skip Gradle if code didn't change
./build-datamancy.main.kts --skip-gradle

# Run only failed suite
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh docker'

# Parallel rsync (faster for large deploys)
rsync -avz --delete --compress-level=1 dist/ gerald@latium.local:~/.datamancy/
```

---

## 🎓 **Advanced Usage**

### Custom Test Environment
```bash
# Override environment in test script
ssh gerald@latium.local 'cd ~/.datamancy && \
    TEST_USER_CONTEXT="my-user" \
    docker compose -f docker-compose.yml -f docker-compose.test-runner.yml \
    run --rm stack-test-runner \
    ./test-agent-stack.main.kts --env internal --suite knowledge-base'
```

### CI/CD Integration
```yaml
# .github/workflows/test.yml
name: Integration Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Setup SSH
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.DEPLOY_SSH_KEY }}" > ~/.ssh/id_ed25519
          chmod 600 ~/.ssh/id_ed25519
          ssh-keyscan latium.local >> ~/.ssh/known_hosts

      - name: Deploy and Test
        run: |
          ./scripts/deploy-test-e2e.sh all
```

### Multiple Environments
```bash
# Deploy to staging
SSH_HOST=gerald@staging.local ./scripts/deploy.sh

# Deploy to production
SSH_HOST=gerald@latium.local ./scripts/deploy.sh

# Test both
ssh gerald@staging.local '~/.datamancy/test-scripts/test-stack.sh all'
ssh gerald@latium.local '~/.datamancy/test-scripts/test-stack.sh all'
```

---

## 📚 **Related Documentation**

- `scripts/stack-health/TESTING-GUIDE.md` - Detailed testing approaches
- `SECURITY-CHANGES.md` - Security lockdown details
- `scripts/stack-health/README.md` - Quick reference
- `docs/monitoring/stack-health-monitor-design.md` - Future monitoring service

---

## ✅ **Summary**

You now have a complete testing infrastructure:

1. **Build locally** - Fast, no server resources used
2. **Deploy via rsync** - Incremental, efficient
3. **Tests run on server** - Inside Docker networks
4. **Scripts bundled** - Always in sync with deployment
5. **Internal-only** - agent-tool-server not exposed externally

**Next step:** Run your first E2E test!
```bash
./scripts/deploy-test-e2e.sh foundation
```
