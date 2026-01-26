# Test Runner Module

Comprehensive integration testing framework for the Datamancy stack.

## Overview

The test-runner module provides end-to-end testing for all Datamancy services, including:
- Service connectivity and health checks
- Database operations
- CI/CD pipeline validation
- Labware socket integration
- Registry operations

## Test Suites

### Infrastructure Tests
- **LabwareDockerTestsTest** - Labware socket connectivity and isolation
- **InfrastructureTestsTest** - Core infrastructure services (Caddy, Authelia, etc.)

### CI/CD Tests
- **CICDPipelineTestsTest** - Full CI/CD workflow validation
  - Image building on labware
  - Registry push/pull operations
  - Container deployments
  - Resource limits and isolation

### Application Tests
- **DatabaseTestsTest** - PostgreSQL, MariaDB, ClickHouse
- **ProductivityTestsTest** - Forgejo, JupyterHub, Planka
- **UserInterfaceTestsTest** - Homepage dashboard
- **SearchServiceTestsTest** - Qdrant, search API
- **DataPipelineTestsTest** - Data fetching and processing

## Running Tests

### Quick Start

```bash
# From project root
./scripts/test-cicd.sh all
```

### Via Gradle

```bash
# All tests
./gradlew test-runner:test

# Specific suite
./gradlew test-runner:test --tests "CICDPipelineTestsTest"

# Single test
./gradlew test-runner:test --tests "CICDPipelineTestsTest.should build Docker image*"

# With verbose output
./gradlew test-runner:test --tests "*CICD*" --info
```

### Via Docker

```bash
# Run in test-runner container
docker-compose run --rm test-runner ./gradlew test

# Specific tests
docker-compose run --rm test-runner \
  ./gradlew test --tests "*Labware*,*CICD*"
```

## Test Categories

### CI/CD Tests (Using Labware Socket)

Tests the complete CI/CD pipeline with actual Docker operations:

```kotlin
@Test
fun `should build Docker image on labware socket`()
@Test
fun `should push image to registry from labware`()
@Test
fun `should deploy container on labware from registry`()
@Test
fun `should isolate labware deployments from production`()
```

**Requirements:**
- Labware socket at `/run/labware-docker.sock`
- Registry accessible at `registry:5000`
- Docker installed

### Labware Socket Tests

Validates labware Docker socket for isolated CI/CD:

```kotlin
@Test
fun `labware socket file should exist`()
@Test
fun `labware socket should be connectable`()
@Test
fun `labware docker daemon should respond to ping`()
@Test
fun `labware docker should be isolated from production`()
```

**What they test:**
- Socket presence and permissions
- Docker daemon connectivity
- Container isolation
- Forgejo runner access

## Writing New Tests

### Test Structure

```kotlin
package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.TestSuite
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class MyNewTestsTest : TestSuite(
    name = "My Test Suite",
    description = "Tests for my feature",
    tags = setOf("feature", "integration")
) {

    @Test
    fun `my test case`() = test(
        name = "Descriptive test name",
        description = "What this test validates"
    ) {
        // Arrange
        val expected = "value"

        // Act
        val actual = doSomething()

        // Assert
        assertTrue(
            actual = actual == expected,
            message = "Should match expected value"
        )

        log("✓ Test passed with result: $actual")
    }
}
```

### Best Practices

1. **Use descriptive test names** - Backtick syntax for readable names
2. **Test real operations** - No mocks, test actual Docker/services
3. **Clean up resources** - Use try/finally for cleanup
4. **Verify isolation** - Ensure labware tests don't affect production
5. **Log meaningful messages** - Help debugging when tests fail

### Testing Docker Operations

Use the labware socket helper:

```kotlin
companion object {
    const val LABWARE_SOCKET = "/run/labware-docker.sock"

    fun execLabwareDocker(vararg args: String): Pair<Int, String> {
        val command = listOf("docker", "-H", "unix://$LABWARE_SOCKET") + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return exitCode to output
    }
}

@Test
fun `my docker test`() = test(...) {
    val (exitCode, output) = execLabwareDocker("ps")
    assertTrue(exitCode == 0, "Docker command should succeed")
}
```

## Test Output

### Successful Run

```
Datamancy CI/CD Pipeline Test Suite

▸ Checking labware socket...
✓ Labware socket exists
▸ Testing socket connectivity...
✓ Labware Docker socket is accessible

═══════════════════════════════════════════════════════════════
  Running Test Suites
═══════════════════════════════════════════════════════════════

CICDPipelineTestsTest
  ✓ should build Docker image on labware socket
  ✓ should push image to registry from labware
  ✓ should deploy container on labware from registry
  ✓ should isolate labware deployments from production
  ✓ should support multi-stage build on labware
  ✓ should build and test Datamancy service image
  ✓ should support docker-compose deployment on labware
  ✓ should enforce resource limits on labware
  ✓ should support network isolation on labware

✓ All tests passed!
```

### Failed Test

```
CICDPipelineTestsTest > should push image to registry from labware FAILED
    java.lang.AssertionError: Push to registry should succeed. Output: [error]
        at CICDPipelineTestsTest.should push image to registry from labware
```

**When tests fail:**
1. Check labware socket: `test -S /run/labware-docker.sock`
2. Test manually: `docker -H unix:///run/labware-docker.sock ps`
3. Check registry: `curl http://registry:5000/v2/`
4. View full logs: `./gradlew test-runner:test --info`

## CI Integration

### Forgejo Actions

Add to `.forgejo/workflows/ci.yml`:

```yaml
- name: Run Integration Tests
  run: |
    cd /path/to/datamancy
    ./scripts/test-cicd.sh all
```

### Pre-deployment Validation

```bash
# Before promoting to production
./scripts/test-cicd.sh cicd || {
  echo "CI/CD tests failed - aborting deployment"
  exit 1
}
```

## Troubleshooting

### "Labware socket not found"

```bash
# Check mount
ls -la /run/labware-docker.sock

# See setup guide
cat docs/LABWARE_SOCKET.md
```

### "Cannot connect to registry"

```bash
# Check registry is running
docker ps | grep registry

# Test connectivity
curl http://registry:5000/v2/
```

### "Tests timeout"

```bash
# Check labware VM
ssh root@labware-vm "docker ps"

# Check resources
ssh root@labware-vm "docker system df"
```

### "Permission denied on socket"

```bash
# Check permissions
stat /run/labware-docker.sock

# Ensure readable
sudo chmod 666 /run/labware-docker.sock
```

## Development

### Build Module

```bash
./gradlew test-runner:build
```

### Run in Watch Mode

```bash
./gradlew test-runner:test --continuous
```

### Generate Test Reports

```bash
./gradlew test-runner:test
open test-runner/build/reports/tests/test/index.html
```

## Dependencies

- **JUnit 5** - Test framework
- **Kotlin Test** - Assertions
- **Docker CLI** - For labware tests
- **Curl** - For HTTP tests

## Related Docs

- [CICD_TESTING.md](../docs/CICD_TESTING.md) - Testing guide
- [LABWARE_SOCKET.md](../docs/LABWARE_SOCKET.md) - Socket setup
- [CI_CD_ARCHITECTURE.md](../docs/CI_CD_ARCHITECTURE.md) - Architecture

---

**Test Coverage: Infrastructure ✓ CI/CD ✓ Services ✓ Isolation ✓**
