# Datamancy Testing Guide

## Overview

This document describes the testing strategy and how to run tests for the Datamancy stack.

## Test Structure

### 1. Unit Tests

Unit tests verify individual components in isolation using mocks where necessary.

**Location:** `src/*/src/test/kotlin/`

**Run:**
```bash
./test-unit.sh
```

Or individual modules:
```bash
./gradlew :data-fetcher:test
./gradlew :unified-indexer:test
./gradlew :search-service:test
./gradlew :agent-tool-server:test
```

**Coverage:**
- Storage components (CheckpointStore, DedupeStore, ContentHasher)
- Converters (HtmlToMarkdownConverter)
- API endpoints (Health, Status, Trigger, DryRun)
- Business logic (Fetchers, Adapters, Search Gateway)

### 2. Integration Tests

Integration tests verify components working together with real databases.

**Prerequisites:**
- Docker and docker-compose installed
- Test databases available (see `docker-compose.test.yml`)

**Run:**
```bash
./test-integration.sh
```

**What's tested:**
- Database connectivity and schema initialization
- Data persistence and retrieval
- Service integration with Postgres, ClickHouse, Qdrant
- API endpoints with real backend services

### 3. API Contract Tests

Contract tests verify HTTP API endpoints conform to their specifications.

**Location:** `src/*/src/test/kotlin/org/*/api/`

**Run:**
```bash
./gradlew test --tests "*EndpointTest"
```

**What's tested:**
- HTTP status codes
- Response content types
- Request/response schemas
- Error handling

### 4. End-to-End Pipeline Tests

E2E tests verify the complete data pipeline from ingestion to search.

**Run:**
```bash
./test-e2e-pipeline.sh
```

**Pipeline flow:**
1. Data fetcher retrieves data from sources
2. Unified indexer processes and indexes data
3. Search service returns query results
4. Verify data consistency across pipeline

### 5. Stack Health Tests

Health tests verify all Docker services are running and healthy.

**Run:**
```bash
./test-stack-health.sh
```

**What's checked:**
- Docker container status
- Container health checks
- HTTP endpoint availability
- Service dependencies

### 6. Complete Test Suite

Run all tests in sequence:

**Run:**
```bash
./test-all.sh
```

## Test Databases

The `docker-compose.test.yml` file defines minimal test databases:

- **postgres-test:** PostgreSQL for metadata (port 5433)
- **clickhouse-test:** ClickHouse for analytics (port 8124)
- **qdrant-test:** Qdrant for vector search (port 6334)
- **data-fetcher-test:** Test instance of data-fetcher

**Note:** Test databases use `tmpfs` for in-memory storage (no persistence).

## Writing Tests

### Unit Test Example

```kotlin
package org.datamancy.datafetcher.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentHasherTest {
    @Test
    fun `hashJson produces consistent SHA-256 hash`() {
        val json = """{"title":"Test"}"""
        val hash1 = ContentHasher.hashJson(json)
        val hash2 = ContentHasher.hashJson(json)

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }
}
```

### API Contract Test Example

```kotlin
package org.datamancy.datafetcher.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HealthEndpointTest {
    @Test
    fun `health endpoint returns OK`() = testApplication {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
```

### Integration Test Example

```kotlin
package org.datamancy.datafetcher.storage

import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer

class CheckpointStoreIntegrationTest {
    @Test
    fun `can store and retrieve checkpoints`() {
        // Use testcontainers for real database
        PostgreSQLContainer("postgres:16").use { postgres ->
            postgres.start()
            val store = CheckpointStore(
                host = postgres.host,
                port = postgres.firstMappedPort
            )
            store.ensureSchema()
            store.set("test", "key", "value")
            assertEquals("value", store.get("test", "key"))
        }
    }
}
```

## Continuous Integration

### GitHub Actions Example

```yaml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v3

    - name: Set up JDK 21
      uses: actions/setup-java@v3
      with:
        java-version: '21'
        distribution: 'temurin'

    - name: Run unit tests
      run: ./test-unit.sh

    - name: Run integration tests
      run: ./test-integration.sh

    - name: Generate test report
      uses: dorny/test-reporter@v1
      if: always()
      with:
        name: Test Results
        path: '**/build/test-results/test/*.xml'
        reporter: java-junit
```

## Test Coverage

Generate coverage reports:

```bash
./gradlew test jacocoTestReport
```

View reports:
```bash
open build/reports/jacoco/test/html/index.html
```

**Target coverage:** 70%+ per module

## Common Issues

### Test Database Connection Failures

**Symptom:** Tests fail with connection refused errors

**Solution:**
```bash
docker-compose -f docker-compose.test.yml down
docker-compose -f docker-compose.test.yml up -d
sleep 15  # Wait for databases to initialize
./test-integration.sh
```

### Port Conflicts

**Symptom:** Test databases fail to start (port already in use)

**Solution:**
```bash
# Stop production stack first
docker-compose down

# Or modify docker-compose.test.yml to use different ports
```

### Out of Memory

**Symptom:** Tests fail with OutOfMemoryError

**Solution:**
```bash
# Increase Gradle heap size
export GRADLE_OPTS="-Xmx4g"
./gradlew test
```

## Best Practices

1. **Keep tests fast:** Unit tests should run in < 5 seconds
2. **Use mocks wisely:** Mock external dependencies, not internal logic
3. **Test edge cases:** Empty inputs, null values, large datasets
4. **Descriptive names:** Use backticks for readable test names
5. **Arrange-Act-Assert:** Structure tests clearly
6. **Clean up resources:** Use `use {}` for auto-closing resources
7. **Avoid test interdependence:** Each test should be independent
8. **Use testcontainers:** For integration tests requiring real services

## Future Improvements

- [ ] Add performance/load tests with k6 or Gatling
- [ ] Add mutation testing with Pitest
- [ ] Add contract testing with Pact
- [ ] Add UI tests with Playwright (test-framework module)
- [ ] Add chaos engineering tests
- [ ] Add security scanning tests
- [ ] Add database migration tests
- [ ] Set up test data fixtures
- [ ] Add snapshot testing for API responses
- [ ] Implement parallel test execution

## Resources

- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [MockK Documentation](https://mockk.io/)
- [Ktor Testing](https://ktor.io/docs/testing.html)
- [Testcontainers](https://www.testcontainers.org/)
- [Gradle Testing](https://docs.gradle.org/current/userguide/java_testing.html)
