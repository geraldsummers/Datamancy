# Datamancy Testing Quick Start

## TL;DR

```bash
# Run all tests
./test-all.sh

# Or run specific test suites
./test-unit.sh              # Fast unit tests
./test-integration.sh       # Integration tests with databases
./test-e2e-pipeline.sh      # Complete data pipeline test
./test-stack-health.sh      # Check if all services are healthy
```

## What's Been Tested

### ✅ Fully Implemented & Working

1. **ContentHasher** - SHA-256 content hashing
   ```bash
   ./gradlew :data-fetcher:test --tests "ContentHasherTest"
   ```

2. **HTML to Markdown Conversion** - HTML parsing and conversion
   ```bash
   ./gradlew :data-fetcher:test --tests "HtmlToMarkdownConverterTest"
   ```

3. **Checkpoint Store** - PostgreSQL persistence (with testcontainers)
   ```bash
   ./gradlew :data-fetcher:test --tests "CheckpointStoreIntegrationTest"
   ```

4. **Dedupe Store** - Deduplication logic (with testcontainers)
   ```bash
   ./gradlew :data-fetcher:test --tests "DedupeStoreIntegrationTest"
   ```

5. **Configuration Loading** - YAML config parsing
   ```bash
   ./gradlew :data-fetcher:test --tests "FetchConfigTest"
   ```

### 📝 Placeholder Tests (Structure Ready)

These tests have the structure in place but need implementation:
- API endpoint tests (need Ktor application setup)
- Fetcher tests (need mocking)
- Vector search tests
- Full-text search tests
- Database connectivity tests

## Quick Examples

### Run Unit Tests for One Module

```bash
./gradlew :data-fetcher:test
```

### Run Integration Tests (Requires Docker)

```bash
# Start test databases
docker-compose -f docker-compose.test.yml up -d

# Run integration tests
./gradlew :data-fetcher:test --tests "*IntegrationTest"

# Cleanup
docker-compose -f docker-compose.test.yml down
```

### Check Stack Health

```bash
# Make sure your stack is running first
docker-compose up -d

# Then check health
./test-stack-health.sh
```

Output will show:
- ✓ (green) = Service healthy
- ⚠ (yellow) = Service running but no healthcheck
- ✗ (red) = Service not running or unhealthy

## Test Documentation

- **TESTING.md** - Complete testing guide with examples
- **TEST_PLAN_SUMMARY.md** - What was implemented and how to use it

## Common Commands

```bash
# Build all modules
./gradlew build

# Run tests for specific module
./gradlew :data-fetcher:test
./gradlew :unified-indexer:test
./gradlew :search-service:test

# Run tests with more output
./gradlew test --info

# Run single test class
./gradlew test --tests "ContentHasherTest"

# Run tests matching pattern
./gradlew test --tests "*IntegrationTest"

# Generate coverage report
./gradlew test jacocoTestReport
```

## Requirements

- **JDK 21** (for Kotlin builds)
- **Docker** (for integration and E2E tests)
- **docker-compose** (for test databases)
- **bash** (for test scripts)
- **curl** (for health checks)

## Troubleshooting

### Tests fail with connection errors

```bash
# Make sure test databases are running
docker-compose -f docker-compose.test.yml ps

# Restart test databases
docker-compose -f docker-compose.test.yml down
docker-compose -f docker-compose.test.yml up -d
sleep 15  # Wait for initialization
```

### Port conflicts

```bash
# Stop production stack first
docker-compose down

# Then run tests
./test-integration.sh
```

### Gradle daemon issues

```bash
# Stop all Gradle daemons
./gradlew --stop

# Try again
./test-unit.sh
```

## Next Steps

See **TESTING.md** for:
- Writing your own tests
- CI/CD integration
- Best practices
- Advanced usage

---

**Happy Testing! 🧪**
