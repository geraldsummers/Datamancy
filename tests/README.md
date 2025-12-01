# Tests

This directory contains all test suites for the Datamancy stack.

## Structure

```
tests/
├── README.md                    # This file
└── diagnostic/                  # Autonomous diagnostic system tests
    ├── README.md                # Detailed testing guide
    ├── test-01-agent-tool-server-tools.sh
    ├── test-02-single-probe.sh
    ├── test-03-screenshot-capture.sh
    ├── test-04-container-diagnostics.sh
    └── test-05-llm-analysis.sh
```

## Quick Start

### Run All Diagnostic Tests
```bash
./scripts/supervisor-session.sh test
```

### Run Individual Test Suites
```bash
# Diagnostic system tests (granular, with logging)
./tests/diagnostic/test-01-agent-tool-server-tools.sh
./tests/diagnostic/test-02-single-probe.sh
# ... etc
```

## Test Suites

### Diagnostic System Tests (`diagnostic/`)

Tests for the autonomous diagnostic and repair system that monitors stack health.

**What it tests**:
- Tool availability (agent-tool-server plugins)
- Service probing (HTTP checks, screenshots, DOM)
- Container diagnostics (logs, stats, inspect)
- LLM-powered root cause analysis
- Fix proposal generation

**Prerequisites**:
```bash
docker compose --profile bootstrap up -d agent-tool-server litellm probe-orchestrator playwright
```

**Documentation**: See [diagnostic/README.md](diagnostic/README.md)

---

## Future Test Suites

Planned test directories:

- `tests/integration/` - Full stack integration tests
- `tests/e2e/` - End-to-end user workflow tests
- `tests/performance/` - Load and performance tests
- `tests/security/` - Security scanning and vulnerability tests

---

## CI/CD Integration

All test suites are designed to be CI/CD friendly with:
- ✅ Clear pass/fail exit codes
- ✅ Detailed logging for debugging
- ✅ Artifact generation for evidence
- ✅ Isolated test execution (no side effects)

### Example GitLab CI
```yaml
test:diagnostic:
  stage: test
  script:
    - docker compose --profile bootstrap up -d
    - ./scripts/supervisor-session.sh test
  artifacts:
    when: on_failure
    paths:
      - volumes/proofs/
    expire_in: 7 days
```

---

## Contributing

When adding new tests:

1. Create appropriate subdirectory (e.g., `tests/security/`)
2. Follow naming convention: `test-NN-description.sh`
3. Include detailed logging with step-by-step output
4. Document in subdirectory README.md
5. Update this file with new test suite section
6. Ensure tests are idempotent and isolated
