# Freshness Rule Implementation

## Overview

The **Freshness Rule** ensures that services are only considered "Functional" when they have been **successfully tested more recently than their last change**. This prevents the agent (and humans) from using stale, untested configurations.

**Rule:** `Service is Functional ⟺ (last_passing_test_timestamp > last_change_timestamp)`

## Architecture

### Components

1. **`data/freshness-status.json`** - Single source of truth
   - Agent-readable status file
   - Contains timestamps for all services
   - Updated by change-tracker and test-runner

2. **`change-tracker`** - Detects service changes
   - Runs at stack startup
   - Inspects Docker container creation times
   - Reads git commit history
   - Writes initial status file

3. **`test-runner`** - Executes UI tests
   - Playwright tests with real browser
   - Outputs per-service test results
   - Updates `data/tests/freshness/*.json`

4. **`status-aggregator`** - Merges results
   - Runs after tests complete
   - Combines change timestamps + test results
   - Generates final `freshness-status.json`

5. **`agent-preflight.sh`** - Agent check script
   - CLI tool for agent to verify freshness
   - Color-coded status output
   - Exit codes for automation

### Data Flow

```
┌─────────────────┐
│ docker-compose  │
│    up/restart   │
└────────┬────────┘
         │
         v
┌─────────────────┐     ┌──────────────────┐
│ change-tracker  │────>│ freshness-status │
│  (one-shot)     │     │      .json       │<────┐
└─────────────────┘     └──────────────────┘     │
         │                       ^                │
         v                       │                │
┌─────────────────┐              │                │
│  test-runner    │              │                │
│  (one-shot)     │──────────────┘                │
└─────────────────┘                               │
         │                                         │
         v                                         │
┌─────────────────┐                               │
│status-aggregator│───────────────────────────────┘
│  (one-shot)     │
└─────────────────┘
         │
         v
┌─────────────────┐
│ agent-preflight │
│     (CLI)       │
└─────────────────┘
```

## Status File Schema

**Location:** `data/freshness-status.json`

```json
{
  "last_updated": "2025-10-25T14:30:22Z",
  "version": "1.0.0",
  "freshness_rule": "Service is 'functional' only when last passing test > last change",
  "services": {
    "grafana": {
      "status": "functional",
      "last_change": 1729872000,
      "last_test": 1729872622,
      "last_test_result": "pass",
      "change_source": "e2ed27f",
      "staleness_seconds": 0
    },
    "traefik": {
      "status": "needs_retest",
      "last_change": 1729872500,
      "last_test": 1729872000,
      "last_test_result": "pass",
      "change_source": "docker-compose.yml:L58",
      "staleness_seconds": 500
    }
  }
}
```

### Status Values

- **`functional`** - Last passing test is newer than last change ✅
- **`needs_retest`** - Service changed after last test ⚠️
- **`untested`** - No test results available ⚠️
- **`test_failed`** - Most recent test failed ❌
- **`unknown`** - Cannot determine status ❓

## Usage

### For Humans

#### Check current freshness status:
```bash
./scripts/agent-preflight.sh
```

#### Check specific service:
```bash
./scripts/agent-preflight.sh --service grafana
```

#### Update status after manual changes:
```bash
./scripts/update-freshness-status.sh
```

#### Run tests and update freshness:
```bash
docker compose run test-runner
docker compose run status-aggregator
```

### For Agent (Claude)

#### Pre-flight check before any action:
```bash
# Check if services are fresh
./scripts/agent-preflight.sh --warn-only

# Parse status file
cat data/freshness-status.json | jq '.services'
```

#### Agent decision tree:

```
User: "Deploy new Grafana config"
  ↓
Agent reads: data/freshness-status.json
  ↓
Is Grafana status == "functional"?
  ├─ YES → Proceed with deployment
  └─ NO  → Run tests first
           ↓
           docker compose run test-runner
           docker compose run status-aggregator
           ↓
           Re-check status
           ↓
           Proceed or report failure
```

### Automation / CI

#### GitHub Actions example:
```yaml
- name: Freshness Gate
  run: |
    ./scripts/agent-preflight.sh
    # Exit 1 if any service is stale
```

#### Pre-commit hook:
```bash
#!/bin/bash
# .git/hooks/pre-commit
if git diff --cached --name-only | grep -q 'docker-compose.yml\|configs/'; then
    echo "⚠ Stack configs changed - tests will be marked stale"
fi
```

## Testing the Implementation

### End-to-end flow:

```bash
# 1. Start infrastructure
docker compose --profile infra up -d traefik grafana browserless homepage socket-proxy

# 2. Run change tracker + tests + aggregator
docker compose run change-tracker
docker compose run test-runner
docker compose run status-aggregator

# 3. Verify freshness status
./scripts/agent-preflight.sh

# Expected output:
# ✓ grafana: Functional
# ✓ traefik: Functional
# ✓ homepage: Functional

# 4. Make a change (restart a service)
docker compose restart grafana

# 5. Update freshness status
./scripts/update-freshness-status.sh

# 6. Check status again
./scripts/agent-preflight.sh

# Expected output:
# ⚠ grafana: Needs Re-test (stale by 30s)
# ✓ traefik: Functional
# ✓ homepage: Functional

# 7. Re-test
docker compose run test-runner
docker compose run status-aggregator

# 8. Verify freshness restored
./scripts/agent-preflight.sh

# Expected output:
# ✓ grafana: Functional
# ✓ traefik: Functional
# ✓ homepage: Functional
```

## Integration Points

### Homepage Widget (Future)

The homepage can display freshness status by reading `freshness-status.json`:

```javascript
// configs/homepage/widgets/freshness.js
const fs = require('fs');
const status = JSON.parse(fs.readFileSync('/data/freshness-status.json'));

for (const [service, info] of Object.entries(status.services)) {
  console.log(`${service}: ${info.status}`);
}
```

### Prometheus Metrics (Future)

Export freshness as metrics:

```
datamancy_service_freshness{service="grafana"} 1  # 1 = functional, 0 = stale
datamancy_service_staleness_seconds{service="grafana"} 0
```

### Alert Rules (Future)

Alert if services are stale:

```yaml
- alert: ServiceNeedsRetest
  expr: datamancy_service_freshness < 1
  for: 1h
  annotations:
    summary: "{{ $labels.service }} needs re-testing"
```

## Files Reference

- `scripts/update-freshness-status.sh` - Aggregator script
- `scripts/agent-preflight.sh` - CLI status checker
- `tests/run-with-freshness.sh` - Test wrapper
- `tests/entrypoint.sh` - Test runner entrypoint (updated)
- `docker-compose.yml` - Added change-tracker, status-aggregator
- `data/freshness-status.json` - Single source of truth
- `data/tests/freshness/*.json` - Per-service test results

## Troubleshooting

### Status file not found
```bash
./scripts/update-freshness-status.sh
```

### Docker permission denied
```bash
# Check socket-proxy is running
docker compose ps socket-proxy

# Verify DOCKER_HOST in aggregator
docker compose config | grep DOCKER_HOST
```

### Test results not updating
```bash
# Check test runner logs
docker compose logs test-runner

# Verify freshness directory exists
ls -la data/tests/freshness/
```

### Timestamps incorrect
```bash
# Verify system time
date -u

# Check container timezone
docker compose exec grafana date
```

## Design Decisions

### Why git commit timestamps?
- **Pro:** Survives restarts, provides audit trail
- **Con:** Requires git in containers
- **Alternative:** Docker inspect timestamps (current implementation)

### Why JSON over database?
- **Pro:** Simple, human-readable, no dependencies
- **Con:** No locking, potential race conditions
- **Mitigation:** One-shot containers run sequentially

### Why separate per-service test files?
- **Pro:** Granular tracking, easy to parse
- **Con:** More files to manage
- **Alternative:** Single results.json with array (also supported)

## Future Enhancements

1. **Real-time watcher:** Background daemon watching config file changes
2. **Dependency cascade:** If Prometheus changes, Grafana becomes stale
3. **Flake detection:** Track test stability over time
4. **Auto-remediation:** Agent automatically re-runs tests on stale services
5. **Web UI:** Interactive dashboard showing freshness timeline

---

**Bottom line:** The Freshness Rule gives the agent (and humans) a **machine-readable contract** that prevents using untested configurations. Every action starts with a freshness check.
