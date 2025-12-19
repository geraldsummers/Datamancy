# Stack Tests - Comprehensive Endpoint Testing

## Overview

Automated testing system that discovers and validates HTTP endpoints across the entire Datamancy stack.

Tests run inside Docker containers on the backend/database networks to access services by hostname (e.g., `http://control-panel:8097`, `postgres:5432`).

## Architecture

### Build-on-Host, Run-in-Container

1. **Host**: Compiles classes and discovers endpoints (`./gradlew :stack-tests:testClasses :stack-tests:discoverEndpoints`)
2. **Host**: Ensures Docker stack is running (`./stack-controller.main.kts up`)
3. **Container**: Runs tests with access to Docker networks via mounted build directories
4. **Host**: Collects test results from mounted volumes

This approach provides:
- Fast builds (no Docker image compilation)
- Clean separation of concerns (build vs runtime)
- Simple volume mounts (no complex Dockerfiles)

### Discovery System
- **KtorRouteScanner**: Parses Kotlin source code to find HTTP route definitions
- **ExternalServiceRegistry**: Curated registry of external service healthcheck endpoints
- **Smart Filtering**: Excludes false positives, auth-required endpoints, and parameterized paths

## Discovered Endpoints

### Kotlin/Ktor Services (4 services)

1. **control-panel** - Dashboard, configuration, fetcher/indexer APIs, storage, logs, system
2. **unified-indexer** - Collection indexing, job management, ingestion control
3. **data-fetcher** - Health, status, trigger endpoints, dry-run, markdown conversion
4. **search-service** - Health, search, collections

### External Services (28+ services)

#### AI/ML Services
- **vLLM**, **Embedding Service**, **LiteLLM**

#### Databases
- **CouchDB**, **ClickHouse**, **PostgreSQL**, **Qdrant**

#### Authentication
- **Authelia**, **LDAP Account Manager**

#### Monitoring & Infrastructure
- **Grafana**, **Benthos**, **Docker Proxy**

#### Applications
- **Open-WebUI**, **Vaultwarden**, **Planka**, **BookStack**, **Seafile**, **OnlyOffice**, **Radicale**, **Roundcube**, **Forgejo**, **qBittorrent**, **Synapse**, **Element**, **Mastodon**, **JupyterHub**, **Homepage**, **Home Assistant**

## Usage

### Run Complete Test Suite

```bash
# Complete workflow: build → discover → start stack → run tests
./gradlew :stack-tests:stackTest
```

This single command:
1. Compiles test classes
2. Discovers endpoints
3. Starts Docker stack (if not running)
4. Runs tests inside container
5. Collects results

### Individual Steps

```bash
# Discover endpoints only
./gradlew :stack-tests:discoverEndpoints

# Run tests manually (requires stack running)
docker compose run --rm stack-test-runner

# Or run tests on host (won't be able to reach services)
./gradlew :stack-tests:test
```

## Test Structure

Tests use JUnit 5 dynamic test generation (`@TestFactory`):

```kotlin
class StackEndpointTests {
    @TestFactory
    fun `test all discovered endpoints`(): Collection<DynamicTest> {
        return registry.services.flatMap { service ->
            service.endpoints
                .filter { shouldTestEndpoint(it) }
                .map { endpoint ->
                    DynamicTest.dynamicTest("${service.name}: ${endpoint.method} ${endpoint.path}") {
                        val response = testEndpoint(service, endpoint)
                        assertTrue(response.status.value in 200..399)
                    }
                }
        }
    }
}
```

Smart filtering excludes:
- Endpoints with path parameters (`{id}`, `{uuid}`)
- Auth-required services (litellm)
- External URLs discovered as paths
- Services not reliably running (seafile, radicale)

## Features

✅ **Automatic Discovery**: Scans Kotlin code + external service registry
✅ **Smart Filtering**: Excludes problematic endpoints automatically
✅ **Build-on-Host**: Fast compilation, no Docker build steps
✅ **Run-in-Container**: Access Docker networks seamlessly
✅ **Single Command**: `./gradlew :stack-tests:stackTest` does everything
✅ **Clear Results**: Test reports in `build/test-results/`

## Output Location

- Test results: `build/test-results/test/`
- Test reports: `build/reports/tests/test/`
- Discovered endpoints: `build/discovered-endpoints.json`

## Requirements

- JDK 21
- Docker and Docker Compose
- Stack services accessible (handled automatically by `stackTest` task)

## Maintenance

Update endpoints after code changes:
```bash
./gradlew :stack-tests:discoverEndpoints --rerun-tasks
./gradlew :stack-tests:stackTest
```

Add new external services:
1. Edit `ExternalServiceRegistry.kt`
2. Add service with healthcheck endpoint
3. Rediscover: `./gradlew :stack-tests:discoverEndpoints`

---

**Architecture**: Build-on-host, run-in-container
**Test Runner**: JUnit 5 with dynamic test generation
**Execution**: Inside Docker on backend/database networks
