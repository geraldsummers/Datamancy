# Integration Testing Guide

This project uses a consolidated test-runner container for all integration and e2e tests that require Docker container access.

## Architecture

```
src/test-runner/
├── build.gradle.kts                    # Gradle build file
└── src/main/kotlin/org/datamancy/testrunner/
    ├── Main.kt                          # Entry point
    ├── framework/
    │   ├── TestEnvironment.kt          # Environment configuration
    │   ├── ServiceClient.kt            # Service interaction layer
    │   └── TestRunner.kt               # Test execution framework
    └── suites/
        ├── FoundationTests.kt          # Health checks
        ├── DockerTests.kt              # Container lifecycle
        ├── LlmTests.kt                 # AI/LLM integration
        ├── KnowledgeBaseTests.kt       # Search & databases
        ├── DataPipelineTests.kt        # Data flow (fetch→index→search)
        └── E2ETests.kt                 # End-to-end workflows
```

## Running Tests

### From Docker Compose (Recommended)

```bash
# Build test runner image
docker build -f Dockerfile.test-runner -t datamancy/test-runner .

# Run all tests
docker compose --profile testing run --rm integration-test-runner

# Run specific suite
docker compose --profile testing run --rm integration-test-runner foundation
docker compose --profile testing run --rm integration-test-runner docker
docker compose --profile testing run --rm integration-test-runner llm
docker compose --profile testing run --rm integration-test-runner knowledge-base
docker compose --profile testing run --rm integration-test-runner data-pipeline
docker compose --profile testing run --rm integration-test-runner e2e

# Interactive debugging
docker compose --profile testing run --rm integration-test-runner bash
```

### From Gradle (Local Development)

```bash
# Unit tests only (no Docker required)
./gradlew test

# Build test runner
./gradlew :test-runner:build

# Run test-runner locally (requires stack running with test ports exposed)
./gradlew :test-runner:run --args="--env localhost --suite foundation"
```

## Test Suites

### Foundation Tests
- Service health checks
- API availability
- OpenWebUI schema validation

### Docker Tests
- Container creation with SSH
- Command execution
- SSH key retrieval
- Cleanup

### LLM Tests
- Chat completion
- System prompts
- Text embeddings

### Knowledge Base Tests
- PostgreSQL queries (with shadow accounts)
- Security validation (blocks DROP/DELETE)
- Semantic search

### Data Pipeline Tests
- Fetch legislation to BookStack
- Verify content storage
- Index to Qdrant/ClickHouse
- Search indexed content

### E2E Tests
- Spawn container → Generate code with LLM → Execute in container

## Test Configuration

### Environment Variables

The test runner auto-detects its environment but can be overridden:

**Container Environment** (inside Docker network):
```bash
AGENT_TOOL_SERVER_URL=http://agent-tool-server:8081
DATA_FETCHER_URL=http://data-fetcher:8095
UNIFIED_INDEXER_URL=http://data-transformer:8096
SEARCH_SERVICE_URL=http://search-service:8098
POSTGRES_HOST=postgres
CLICKHOUSE_HOST=clickhouse
TEST_USER_CONTEXT=test-agent-user
```

**Localhost Environment** (via exposed test ports):
```bash
# Automatically uses localhost:18xxx ports
TEST_ENV=localhost
```

### Optional: BookStack API Credentials

For data pipeline tests:

```bash
export BOOKSTACK_API_TOKEN_ID="your-token-id"
export BOOKSTACK_API_TOKEN_SECRET="your-token-secret"
```

Generate credentials:
```bash
./configs.templates/applications/bookstack/generate-api-token.main.kts
```

## Network Access

⚠️ **Security Note**: The test container has access to ALL Docker networks for integration testing. This intentionally violates zero-trust networking principles but is essential for comprehensive testing.

Networks accessed:
- ai, ai-gateway
- postgres, mariadb, clickhouse, qdrant
- ldap, valkey
- backend, frontend, monitoring

The test container:
- Only runs when `--profile testing` is specified
- Uses `restart: "no"` to prevent accidental startup
- Has no persistent volumes
- Is ephemeral (destroyed after each run)

## Adding New Tests

1. Create new test file in `src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/`
2. Define test suite function:

```kotlin
suspend fun TestRunner.myNewTests() = suite("My New Tests") {
    test("my test name") {
        val result = client.healthCheck("my-service")
        result.healthy shouldBe true
    }
}
```

3. Register in `Main.kt`:

```kotlin
when (suite) {
    // ...existing suites...
    "my-new-suite" -> runner.myNewTests()
}
```

## Migration from Old Tests

**Deprecated (removed)**:
- ❌ `src/stack-tests/` - Empty module
- ❌ `src/stack-test-runner/` - Old container definition
- ❌ `scripts/stack-health/test-agent-stack.main.kts` - Monolithic script
- ❌ `configs.templates/applications/bookstack/test-pipeline.sh` - Bash script
- ❌ `compose.templates/docker-compose.test-runner.yml` - Old compose overlay
- ❌ `compose.templates/stack-test-runner.yml` - Old compose definition

**New (use these)**:
- ✅ `src/test-runner/` - Modular Kotlin test framework
- ✅ `compose.templates/testing.yml` - Consolidated test container
- ✅ `Dockerfile.test-runner` - Updated for Kotlin 2.0.21

## Troubleshooting

### Tests can't connect to services

1. Ensure stack is running:
   ```bash
   docker compose ps
   ```

2. Check test container is on correct networks:
   ```bash
   docker compose --profile testing run --rm integration-test-runner bash
   # Inside container:
   curl http://agent-tool-server:8081/healthz
   ```

### Shadow account not provisioned

If PostgreSQL tests fail:

```bash
./scripts/security/create-shadow-agent-account.main.kts test-agent-user
```

### Tests timeout

- Increase `requestTimeout` in `ServiceClient.kt`
- Check service logs: `docker compose logs <service>`

## CI/CD Integration

Example GitHub Actions:

```yaml
- name: Build test runner
  run: docker build -f Dockerfile.test-runner -t datamancy/test-runner .

- name: Run integration tests
  run: |
    docker compose up -d
    docker compose --profile testing run --rm integration-test-runner
```
