# Integration Testing Guide

This project includes real integration tests that connect to actual running services in the Docker environment.

## Overview

Integration tests are different from unit tests:
- **Unit tests**: Mock external dependencies, run in isolation
- **Integration tests**: Connect to real databases, services, and APIs running in Docker

## Quick Start

### Running All Integration Tests

```bash
./run-integration-tests.main.kts
```

This will:
1. Build the test runner Docker image
2. Start the test container inside the Docker network
3. Run all tests matching `*RealIntegrationTest` and `*IntegrationTest`
4. Display results and generate HTML reports

### Running Specific Tests

```bash
# Run only control-panel integration tests
./run-integration-tests.main.kts :control-panel:test --tests "*RealIntegrationTest"

# Run only storage integration tests
./run-integration-tests.main.kts :data-fetcher:test --tests "*.StorageRealIntegrationTest"

# Run a specific test method
./run-integration-tests.main.kts :control-panel:test --tests "*.RealIntegrationTest.test control panel health endpoint"
```

## Test Architecture

### Test Runner Container

The integration tests run inside a Docker container (`integration-test-runner`) that:
- Has access to the `backend` and `database` Docker networks
- Can reach services by hostname: `postgres`, `clickhouse`, `control-panel`, etc.
- Uses the same environment variables as production services
- Mounts source code as a volume for live updates

### Environment Variables

Tests can access these environment variables:

**Databases:**
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `CLICKHOUSE_HOST`, `CLICKHOUSE_PORT`, `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD`

**Services:**
- `CONTROL_PANEL_URL=http://control-panel:8097`
- `DATA_FETCHER_URL=http://data-fetcher:8095`
- `UNIFIED_INDEXER_URL=http://unified-indexer:8096`
- `SEARCH_SERVICE_URL=http://search-service:8098`

## Writing Integration Tests

### Naming Convention

Integration test classes should end with:
- `RealIntegrationTest` - for tests connecting to real services
- `IntegrationTest` - for general integration tests

### JUnit 5 Annotations

Use JUnit 5 annotations (the project uses `useJUnitPlatform()`):

```kotlin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class MyRealIntegrationTest {
    @BeforeEach
    fun setup() {
        // Setup code
    }

    @Test
    fun `test real service connection`() {
        // Test code
    }

    @AfterEach
    fun cleanup() {
        // Cleanup code
    }
}
```

### Example: Testing HTTP Endpoints

```kotlin
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ServiceIntegrationTest {
    private val serviceUrl = System.getenv("CONTROL_PANEL_URL") ?: "http://control-panel:8097"

    @Test
    fun `test health endpoint`() = runBlocking {
        val client = HttpClient(CIO)
        val response = client.get("$serviceUrl/health")
        assertEquals(200, response.status.value)
        client.close()
    }
}
```

### Example: Testing Database Connections

```kotlin
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertTrue

class DatabaseIntegrationTest {
    @Test
    fun `test postgres connection`() {
        val host = System.getenv("POSTGRES_HOST") ?: "postgres"
        val port = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432
        val db = System.getenv("POSTGRES_DB") ?: "datamancy"
        val user = System.getenv("POSTGRES_USER") ?: "datamancer"
        val password = System.getenv("POSTGRES_PASSWORD") ?: "password"

        val conn = DriverManager.getConnection(
            "jdbc:postgresql://$host:$port/$db",
            user,
            password
        )

        assertTrue(conn.isValid(5))
        conn.close()
    }
}
```

## Test Reports

After running tests, HTML reports are generated at:
- `src/control-panel/build/reports/tests/test/index.html`
- `src/data-fetcher/build/reports/tests/test/index.html`
- `src/unified-indexer/build/reports/tests/test/index.html`

## Troubleshooting

### "Docker services are not running"

Start the services first:
```bash
docker compose up -d
```

### "Connection refused" or "UnknownHostException"

Make sure:
1. The service is running: `docker compose ps`
2. The service is healthy: Check health status in `docker compose ps`
3. You're using the correct hostname (e.g., `postgres` not `localhost`)

### Tests pass locally but fail in CI

Make sure CI:
1. Starts all required Docker services
2. Waits for services to be healthy
3. Runs tests inside the Docker network using the test runner

### Rebuilding Test Image

If you change dependencies or Dockerfile.test:
```bash
docker compose --profile testing build --no-cache integration-test-runner
```

## CI/CD Integration

To run integration tests in CI:

```yaml
# Example GitHub Actions
- name: Start services
  run: docker compose up -d --wait

- name: Run integration tests
  run: ./run-integration-tests.main.kts

- name: Upload test reports
  if: always()
  uses: actions/upload-artifact@v3
  with:
    name: test-reports
    path: src/*/build/reports/tests/
```

## Benefits of This Approach

1. **Real environment testing** - Tests run against actual databases and services
2. **Network isolation** - Tests run inside Docker network, same as production
3. **Reproducible** - Same environment on all machines and CI
4. **Fast feedback** - No need to mock complex integrations
5. **Catches real issues** - Discovers problems unit tests can't find
