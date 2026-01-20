# Search Service Integration Tests

Comprehensive integration test suite for the Search Service RAG Provider.

## Running Tests

```bash
# Run only search-service tests
./gradlew :test-runner:shadowJar
java -jar src/test-runner/build/libs/test-runner-*-all.jar --suite search-service

# Run with verbose output
java -jar src/test-runner/build/libs/test-runner-*-all.jar --suite search-service --verbose

# Run from inside a container
docker exec -it test-runner java -jar /app/test-runner.jar --suite search-service --env container
```

## Test Coverage

### Core Functionality (5 tests)
- ✅ Search service health check
- ✅ List available collections
- ✅ Search returns results with content type
- ✅ Search returns results with capabilities
- ✅ Search UI page is served at root

### Audience Filtering (2 tests)
- ✅ Human audience filter works
- ✅ Agent audience filter works

### Content Type Capabilities (4 tests)
- ✅ BookStack content has correct capabilities
- ✅ Market data has correct capabilities
- ✅ CVE content has correct capabilities
- ✅ Weather data has correct capabilities

### Search Modes (3 tests)
- ✅ Vector search mode works
- ✅ BM25 search mode works
- ✅ Hybrid search mode works (default)

### Query Features (3 tests)
- ✅ Search respects limit parameter
- ✅ Search with specific collection works
- ✅ Empty query returns error or empty results

### Integration Features (3 tests)
- ✅ Results include all required fields
- ✅ Interactive content can be chatted with (OpenWebUI ready)
- ✅ Time series content can be graphed (Grafana ready)

**Total: 20 comprehensive integration tests**

## What These Tests Verify

### RAG Provider Capabilities
- Content type inference (bookstack, article, market, weather, cve, wikipedia, docs)
- Capability flags (humanFriendly, agentFriendly, hasTimeSeries, hasRichContent, isInteractive, isStructured)
- Audience-aware filtering (human, agent, both)

### Search Modes
- Vector search (semantic similarity via Qdrant)
- BM25 search (keyword matching via ClickHouse)
- Hybrid search (RRF-based reranking)

### UI Integration
- HTML interface served at root
- Search input and mode toggles
- Type-specific result rendering

### Service Integrations
- OpenWebUI compatibility (interactive content with rich text)
- Grafana compatibility (time-series data with structured fields)
- Agent tool compatibility (structured data for LLM tool calling)

## Test Architecture

```
SearchServiceTests.kt
├── Health & Discovery Tests
│   ├── Service health
│   └── Collection listing
│
├── Core Search Tests
│   ├── Content type detection
│   ├── Capability inference
│   └── UI serving
│
├── Audience Filtering Tests
│   ├── Human-only results
│   └── Agent-only results
│
├── Content Type Tests
│   ├── BookStack capabilities
│   ├── Market data capabilities
│   ├── CVE capabilities
│   └── Weather capabilities
│
├── Search Mode Tests
│   ├── Vector mode
│   ├── BM25 mode
│   └── Hybrid mode
│
└── Integration Tests
    ├── OpenWebUI readiness
    └── Grafana readiness
```

## Example Test Output

```
╔═══════════════════════════════════════════════════════════════════════════╗
║  Datamancy Integration Test Runner (Kotlin 1.9.22)                        ║
╚═══════════════════════════════════════════════════════════════════════════╝

Environment: container
Suite: search-service
Verbose: false

▶ Search Service RAG Provider
  [TEST] Search service is healthy ... ✓ OK (234ms)
  [TEST] Can list available collections ... ✓ OK (156ms)
  [TEST] Search returns results with content type ... ✓ OK (423ms)
  [TEST] Search returns results with capabilities ... ✓ OK (389ms)
  [TEST] Human audience filter works ... ✓ OK (512ms)
  [TEST] Agent audience filter works ... ✓ OK (498ms)
  [TEST] BookStack content has correct capabilities ... ✓ OK (445ms)
  [TEST] Market data has correct capabilities ... ✓ OK (467ms)
  [TEST] CVE content has correct capabilities ... ✓ OK (421ms)
  [TEST] Weather data has correct capabilities ... ✓ OK (434ms)
  [TEST] Vector search mode works ... ✓ OK (378ms)
  [TEST] BM25 search mode works ... ✓ OK (312ms)
  [TEST] Hybrid search mode works (default) ... ✓ OK (401ms)
  [TEST] Search respects limit parameter ... ✓ OK (289ms)
  [TEST] Search with specific collection works ... ✓ OK (367ms)
  [TEST] Search UI page is served at root ... ✓ OK (145ms)
  [TEST] Results include all required fields ... ✓ OK (298ms)
  [TEST] Empty query returns error or empty results ... ✓ OK (178ms)
  [TEST] Interactive content can be chatted with (OpenWebUI ready) ... ✓ OK (456ms)
  [TEST] Time series content can be graphed (Grafana ready) ... ✓ OK (412ms)

================================================================================
TEST SUMMARY
================================================================================
Total Tests: 20
  ✓ Passed:  20
  ✗ Failed:  0
  ⊘ Skipped: 0
  Duration:  7234ms (7.234s)
================================================================================

✅ All tests passed!
```

## Prerequisites

For tests to pass, ensure:
1. Search service is running and healthy
2. Qdrant has indexed collections
3. ClickHouse has data tables
4. Embedding service is available
5. At least one data source has been indexed (BookStack, market data, CVE, etc.)

## CI/CD Integration

```yaml
# .github/workflows/test.yml
- name: Run Search Service Tests
  run: |
    java -jar test-runner-all.jar --suite search-service --env container
```

## Debugging Failed Tests

```bash
# Verbose mode shows HTTP requests/responses
java -jar test-runner-all.jar --suite search-service --verbose

# Check service health manually
curl http://search-service:8098/health

# Check collections
curl http://search-service:8098/collections

# Manual search test
curl -X POST http://search-service:8098/search \
  -H "Content-Type: application/json" \
  -d '{"query": "test", "mode": "hybrid", "audience": "both", "limit": 5}'
```

## Contributing

When adding new search features:
1. Add corresponding test in `SearchServiceTests.kt`
2. Update this README with test description
3. Ensure test follows existing patterns (arrange/act/assert)
4. Run full test suite before committing

---

**These tests verify that the search-service functions as a unified RAG provider for both human users and AI agents.** 🚀
