# Running Tests - Quick Reference

## TL;DR
```bash
# Run everything (unit + integration + e2e)
./gradlew test

# Run just the new end-to-end workflow tests
./gradlew :stack-tests:test --tests "EndToEndWorkflowTests"

# Run just agent-tool-server tests
./gradlew :agent-tool-server:test
```

---

## Prerequisites

### Docker Stack Must Be Running
Most tests require the Docker stack to be up:

```bash
# Option 1: Use stack controller
./stack-controller.main.kts up

# Option 2: Direct docker compose
docker compose up -d
```

### For Stack-Tests (Localhost Testing)
Stack-tests need ports exposed to localhost:

```bash
# Stack controller automatically handles this
./stack-controller.main.kts test-up

# Or manually
docker compose -f docker-compose.yml -f docker-compose.test-ports.yml up -d
```

---

## Test Categories

### 1. Unit Tests (No Docker Required)
Fast, isolated tests with mocks:

```bash
# Run all unit tests
./gradlew test --tests "*Test" --exclude-tests "*IntegrationTest*"

# Specific module
./gradlew :unified-indexer:test --tests "UnifiedIndexerTest"
```

### 2. Integration Tests (Requires Docker)
Tests that connect to real services:

```bash
# All integration tests
./gradlew test --tests "*IntegrationTest*"

# Control panel integration tests
./gradlew :control-panel:test --tests "RealIntegrationTest"

# Data fetcher storage integration tests
./gradlew :data-fetcher:test --tests "StorageRealIntegrationTest"

# Agent tool server integration tests
./gradlew :agent-tool-server:test --tests "AgentToolServerIntegrationTest"
```

### 3. End-to-End Workflow Tests
Full pipeline validation:

```bash
# All e2e tests
./gradlew :stack-tests:test --tests "EndToEndWorkflowTests"

# Specific workflow test
./gradlew :stack-tests:test --tests "EndToEndWorkflowTests.end-to-end*"
```

### 4. Stack Endpoint Tests
Automatic discovery and smoke testing:

```bash
# All discovered endpoints
./gradlew :stack-tests:test --tests "StackEndpointTests"

# The test task automatically:
# - Discovers endpoints from code
# - Brings up stack with test-ports overlay
# - Waits for services to be healthy
# - Runs parallel tests on all endpoints
```

---

## Running Tests By Service

### Control Panel
```bash
./gradlew :control-panel:test

# Specific test
./gradlew :control-panel:test --tests "RealIntegrationTest.test control panel health endpoint"
```

### Data Fetcher
```bash
./gradlew :data-fetcher:test

# Just storage tests
./gradlew :data-fetcher:test --tests "*Storage*"

# Just fetcher tests
./gradlew :data-fetcher:test --tests "*Fetcher*"
```

### Unified Indexer
```bash
./gradlew :unified-indexer:test

# Just diff computation tests
./gradlew :unified-indexer:test --tests "*computeDiff*"
```

### Search Service
```bash
./gradlew :search-service:test
```

### Agent Tool Server
```bash
./gradlew :agent-tool-server:test

# Just plugin tests
./gradlew :agent-tool-server:test --tests "*Plugin*"

# Just SSH tests
./gradlew :agent-tool-server:test --tests "OpsSshPluginTest"

# Just database query tests
./gradlev :agent-tool-server:test --tests "DataSourceQueryPluginTest"
```

---

## Test Options

### Run with Stack Auto-Teardown
```bash
STACK_TESTS_TEARDOWN=true ./gradlew :stack-tests:test
```

### Run in Parallel
```bash
./gradlev test --parallel --max-workers=4
```

### Run with Detailed Output
```bash
./gradlew test --info

# Or for debugging
./gradlew test --debug
```

### Continue on Failure
```bash
./gradlew test --continue
```

### Rerun Failed Tests Only
```bash
./gradlew test --rerun-tasks
```

---

## Environment Variables

### Stack-Tests
- `STACK_TESTS_TEARDOWN=true` - Auto-teardown stack after tests
- `DATA_FETCHER_URL` - Override data-fetcher URL (default: localhost:18095)
- `UNIFIED_INDEXER_URL` - Override indexer URL (default: localhost:18096)
- `SEARCH_SERVICE_URL` - Override search URL (default: localhost:18098)
- `CONTROL_PANEL_URL` - Override control panel URL (default: localhost:18097)

### Agent Tool Server Tests
- `AGENT_TOOL_SERVER_URL` - Override tool server URL (default: agent-tool-server:8081)

### Integration Tests
- `POSTGRES_HOST` - Postgres host (default: postgres or localhost:15432)
- `CLICKHOUSE_HOST` - ClickHouse host (default: clickhouse or localhost:18123)
- `DOCKER_CONTAINER` - Set to any value if running inside Docker

---

## Common Workflows

### Development Workflow
```bash
# 1. Make code changes
vim src/control-panel/src/main/kotlin/...

# 2. Run just that module's tests
./gradlew :control-panel:test

# 3. If all pass, run full test suite
./gradlew test
```

### Pre-Commit Workflow
```bash
# Fast unit tests only
./gradlew test --tests "*Test" --exclude-tests "*IntegrationTest*"
```

### CI Pipeline Workflow
```bash
# Stage 1: Unit tests (fast, no Docker)
./gradlew test --tests "*Test" --exclude-tests "*IntegrationTest*"

# Stage 2: Integration tests (with Docker)
./stack-controller.main.kts up
./gradlew test --tests "*IntegrationTest*"

# Stage 3: End-to-end tests
./gradlew :stack-tests:test
```

### Debug Failing Test
```bash
# Run with stack trace
./gradlew :control-panel:test --tests "RealIntegrationTest.test update source configuration" --stacktrace

# Check service logs
docker compose logs control-panel
docker compose logs postgres
```

---

## Viewing Test Reports

After running tests, open HTML reports in browser:

```bash
# Control panel
xdg-open src/control-panel/build/reports/tests/test/index.html

# Data fetcher
xdg-open src/data-fetcher/build/reports/tests/test/index.html

# Stack tests
xdg-open src/stack-tests/build/reports/tests/test/index.html

# Agent tool server
xdg-open src/agent-tool-server/build/reports/tests/test/index.html
```

Or in IntelliJ:
1. Right-click on test results
2. "Show Test Results in Browser"

---

## Troubleshooting

### Services Not Healthy
```bash
# Check service status
docker compose ps

# Check specific service logs
docker compose logs control-panel
docker compose logs postgres

# Restart problematic service
docker compose restart control-panel
```

### Port Already in Use (Stack-Tests)
```bash
# Find what's using the port
lsof -i :18097

# Kill the process or use different ports
# (Edit docker-compose.test-ports.yml)
```

### Tests Timeout
```bash
# Increase timeout in test code or run with more time
./gradlew test --no-parallel

# Check if services are actually slow
docker stats
```

### Database Not Initialized
```bash
# Manually create databases
docker exec postgres psql -U sysadmin -d postgres -c "CREATE DATABASE datamancy OWNER sysadmin;"

# Or recreate stack
echo "OBLITERATE" | ./stack-controller.main.kts obliterate
./stack-controller.main.kts up
```

---

## Test File Locations

```
src/
├── control-panel/src/test/kotlin/org/datamancy/controlpanel/
│   ├── RealIntegrationTest.kt          (11 tests)
│   ├── IntegrationTest.kt              (5 tests)
│   └── api/*Test.kt                    (API endpoint tests)
│
├── data-fetcher/src/test/kotlin/org/datamancy/datafetcher/
│   ├── storage/StorageRealIntegrationTest.kt  (14 tests)
│   ├── fetchers/*FetcherTest.kt        (Fetcher tests)
│   └── api/*EndpointTest.kt            (API tests)
│
├── unified-indexer/src/test/kotlin/org/datamancy/unifiedindexer/
│   └── UnifiedIndexerTest.kt           (9 tests)
│
├── search-service/src/test/kotlin/org/datamancy/searchservice/
│   └── SearchGateway*Test.kt           (Gateway tests)
│
├── agent-tool-server/src/test/kotlin/org/example/
│   ├── AgentToolServerIntegrationTest.kt  (13 NEW tests)
│   └── plugins/
│       ├── DataSourceQueryPluginTest.kt   (8 NEW tests)
│       └── OpsSshPluginTest.kt            (5 NEW tests)
│
└── stack-tests/src/test/kotlin/org/datamancy/stacktests/
    ├── StackEndpointTests.kt           (63 dynamic tests)
    └── EndToEndWorkflowTests.kt        (6 NEW tests)
```

---

## Quick Sanity Check

Run this to verify your test setup:

```bash
# 1. Ensure stack is up
docker compose ps

# 2. Run health check tests only
./gradlew test --tests "*health*"

# 3. Run one e2e test
./gradlew :stack-tests:test --tests "EndToEndWorkflowTests.verify all services*"
```

If those pass, your test infrastructure is working! 🎉
