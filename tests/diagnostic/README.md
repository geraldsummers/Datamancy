# Diagnostic System Testing Guide

## Overview

The autonomous diagnostic system is now tested through granular, isolated test scripts with detailed logging. Each test validates a specific component of the diagnostic pipeline.

## Quick Start

### Run All Tests
```bash
./scripts/supervisor-session.sh test
```

### Run Individual Tests
```bash
./scripts/test-01-kfuncdb-tools.sh            # Tool inventory
./scripts/test-02-single-probe.sh             # Single service probe
./scripts/test-03-screenshot-capture.sh       # Screenshot capture
./scripts/test-04-container-diagnostics.sh    # Container logs/stats
./scripts/test-05-llm-analysis.sh             # LLM analysis
```

---

## Test Descriptions

### Test 01: kfuncdb Tool Inventory
**Purpose**: Verify kfuncdb has all required diagnostic tools loaded

**What it checks**:
- kfuncdb container is running
- Health endpoint responds
- All plugins loaded (core, hosttools, browser, llmcompletion, ops)
- Critical tools available: `browser_screenshot`, `http_get`, `docker_logs`, `docker_stats`, `docker_inspect`

**Common failures**:
- Missing capabilities in `KFUNCDB_ALLOW_CAPS` environment variable
- Plugins skipped due to permission issues

**Fix**:
```bash
# Check current capabilities
docker exec kfuncdb env | grep KFUNCDB_ALLOW_CAPS

# Required capabilities
KFUNCDB_ALLOW_CAPS=host.shell.read,host.docker.write,host.docker.inspect,host.network.http,host.network.ssh
```

---

### Test 02: Single Service Probe
**Purpose**: Test end-to-end probe workflow on one service

**What it checks**:
- probe-orchestrator is running and healthy
- Can send probe request via `/start-probe` endpoint
- Receives valid JSON response with status, reason, screenshot
- Agent executes multiple steps
- Screenshot file is saved and valid

**Usage**:
```bash
# Default: probe kfuncdb health endpoint
./scripts/test-02-single-probe.sh

# Custom target
./scripts/test-02-single-probe.sh http://litellm:4000/health
```

**Common failures**:
- Timeout: LLM not responding (check vllm backend)
- No screenshots: playwright service not running
- Steps = 0: kfuncdb tools not available (run test-01)

---

### Test 03: Screenshot Capture
**Purpose**: Test direct screenshot capture via kfuncdb tool

**What it checks**:
- `browser_screenshot` tool exists in kfuncdb
- Can call tool directly with URL
- Returns valid base64-encoded PNG image
- Image is properly formatted (1280x720 PNG)

**Usage**:
```bash
# Default: screenshot kfuncdb health endpoint
./scripts/test-03-screenshot-capture.sh

# Custom target
./scripts/test-03-screenshot-capture.sh https://example.com
```

**Common failures**:
- Tool not found: Run test-01 to verify kfuncdb configuration
- Small image (<1KB): Target URL not reachable from playwright service
- Network error: playwright container not running

---

### Test 04: Container Diagnostics
**Purpose**: Test Docker diagnostic tools (logs, stats, inspect)

**What it checks**:
- `docker_logs`: Fetch last N lines of container logs
- `docker_stats`: Get CPU, memory, network metrics
- `docker_inspect`: Get container metadata (status, health, restarts)

**Usage**:
```bash
# Default: diagnose kfuncdb container
./scripts/test-04-container-diagnostics.sh

# Custom container
./scripts/test-04-container-diagnostics.sh litellm
```

**Common failures**:
- Permission denied: Docker socket not mounted or group permissions incorrect
- Tool not found: `host.docker.inspect` and `host.docker.write` capabilities missing

---

### Test 05: LLM Analysis
**Purpose**: Test AI-powered root cause analysis and fix proposals

**What it checks**:
- litellm service is running and responsive
- Can call LLM with diagnostic prompt
- LLM returns valid analysis with root cause and fix proposals
- Response is properly formatted JSON

**Usage**:
```bash
# Default: analyze kfuncdb with "failed" status
./scripts/test-05-llm-analysis.sh

# Custom service and status
./scripts/test-05-llm-analysis.sh litellm degraded
```

**Common failures**:
- LLM timeout: vllm backend not running or overloaded
- 500 errors: Model not loaded in vllm
- Empty response: LLM model misconfigured in litellm config

**Debug**:
```bash
# Check vllm status
docker logs vllm --tail 50

# Check litellm config
docker exec litellm cat /app/config.yaml

# Test litellm directly
curl -X POST http://localhost:4000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"hermes-2-pro-mistral-7b","messages":[{"role":"user","content":"Hello"}]}'
```

---

## Troubleshooting

### All tests fail with "container not running"
```bash
# Start required services
docker compose --profile bootstrap up -d kfuncdb litellm probe-orchestrator playwright
```

### Tests 01-04 pass, Test 05 fails
Issue: LLM backend not working

**Diagnosis**:
```bash
# Check if vllm is running
docker ps | grep vllm

# Check if model is loaded
docker logs vllm | grep -i "model"

# Test vllm directly
curl http://localhost:8000/v1/models
```

### Tests 02-03 fail with "playwright" errors
Issue: Browser service not running

**Fix**:
```bash
docker compose --profile bootstrap up -d playwright
```

### Test 01 shows missing tools
Issue: kfuncdb capabilities not configured

**Fix**:
1. Edit `docker-compose.yml`
2. Find `kfuncdb` service
3. Update `KFUNCDB_ALLOW_CAPS` to include all required capabilities
4. Recreate container:
   ```bash
   docker rm -f kfuncdb
   docker compose --profile bootstrap up -d kfuncdb
   ```

---

## Integration with CI/CD

Add to your CI pipeline:

```yaml
test:
  script:
    - docker compose --profile bootstrap up -d
    - ./scripts/supervisor-session.sh test
  artifacts:
    when: on_failure
    paths:
      - volumes/proofs/
```

---

## Expected Test Results

**Healthy system**:
```
TEST 01 PASSED: All critical tools available (90+ total)
TEST 02 PASSED: Probe successful (3-8 steps, <30s)
TEST 03 PASSED: Screenshot capture successful (>10KB image)
TEST 04 PASSED: All container diagnostic tools working
TEST 05 PASSED: LLM analysis successful (<30s response)

TEST SUMMARY
  Passed: 5
  Failed: 0
  Total:  5

✅ ALL TESTS PASSED
```

**Degraded system** (LLM backend down):
```
TEST 01 PASSED ✅
TEST 02 PASSED ✅
TEST 03 PASSED ✅
TEST 04 PASSED ✅
TEST 05 FAILED ❌ (LLM timeout)

⚠️  Diagnostic collection works, but AI analysis unavailable
```

---

## Next Steps After All Tests Pass

1. Run full stack diagnostics:
   ```bash
   ./scripts/supervisor-session.sh diagnose-enhanced
   ```

2. Review generated artifacts:
   ```bash
   ls -lh volumes/proofs/
   ```

3. Review proposed fixes:
   ```bash
   ./scripts/supervisor-session.sh review
   ```

4. Approve and execute fixes (Phase 2 - not yet implemented)
