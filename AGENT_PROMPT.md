# Agent Task: Replace Stack Tests Code Generation with Dynamic Testing

## Context

The stack-tests module currently uses KotlinPoet to generate 32 test files from discovered endpoints. This is overcomplicated and causing 480+ compilation errors.

**The fix is simple**: Replace the entire generation system with ONE test class that uses JUnit's `@TestFactory` to create tests dynamically at runtime by reading the discovered-endpoints.json.

## Current State

**What works:**
- ✅ Discovery system (80 endpoints discovered across 32 services)
- ✅ KtorRouteScanner (parses Kotlin code for routes)
- ✅ ExternalServiceRegistry (28 external service healthchecks)
- ✅ JSON output: `build/discovered-endpoints.json`

**What's broken:**
- ❌ TestGenerator.kt (300+ lines using KotlinPoet)
- ❌ 32 generated test files with 480+ compilation errors
- ❌ Comment wrapping issues
- ❌ Import problems
- ❌ Over-engineered solution

## Your Mission

Replace code generation with dynamic testing:

### 1. Create ONE Dynamic Test Class

**File**: `src/stack-tests/src/test/kotlin/org/datamancy/stacktests/StackEndpointTests.kt`

**Requirements:**
- Use JUnit's `@TestFactory` to create dynamic tests
- Read `build/discovered-endpoints.json` at test time
- Create one `DynamicTest` per endpoint (80 total)
- Use Ktor HttpClient to test each endpoint
- Assert status codes are in 200-499 range
- Handle GET, POST, PUT, DELETE methods
- Replace path parameters like `{id}` with test values like `test-id`
- Use `@IntegrationTest` annotation (copy from control-panel if needed)

**Test naming:** `"service-name: METHOD /path"` (e.g., "grafana: GET /api/health")

### 2. Clean Up Build Configuration

**File**: `src/stack-tests/build.gradle.kts`

**Actions:**
- Remove KotlinPoet dependency: `implementation("com.squareup:kotlinpoet:1.15.3")`
- Delete `generateTests` task
- Delete `fullStackTest` task
- Keep only `discoverEndpoints` task
- Keep all existing dependencies except KotlinPoet

### 3. Delete Unnecessary Code

**Delete these files/directories:**
- `src/stack-tests/src/main/kotlin/org/datamancy/stacktests/generator/` (entire directory)
- `src/stack-tests/src/test/kotlin/org/datamancy/stacktests/generated/` (all 32 generated test files)

**Keep these (DO NOT DELETE):**
- `src/stack-tests/src/main/kotlin/org/datamancy/stacktests/discovery/` (all discovery code)
- `src/stack-tests/src/main/kotlin/org/datamancy/stacktests/models/` (all model classes)
- `src/stack-tests/README.md`

### 4. Copy Integration Test Infrastructure

If not already present, copy these files from control-panel:
```bash
src/control-panel/src/test/kotlin/org/datamancy/controlpanel/IntegrationTest.kt
src/control-panel/src/test/kotlin/org/datamancy/controlpanel/IntegrationTestExtension.kt
```

To:
```bash
src/stack-tests/src/test/kotlin/org/datamancy/controlpanel/IntegrationTest.kt
src/stack-tests/src/test/kotlin/org/datamancy/controlpanel/IntegrationTestExtension.kt
```

### 5. Test Your Solution

```bash
# Discover endpoints
./gradlew :stack-tests:discoverEndpoints

# Should succeed and create build/discovered-endpoints.json with 80 endpoints

# Compile tests
./gradlew :stack-tests:compileTestKotlin

# Should succeed with ZERO errors

# Run tests (optional - requires Docker stack)
./gradlew :stack-tests:test

# Should create 80 dynamic tests, one per endpoint
```

## Key Implementation Details

### Dynamic Test Creation Pattern

```kotlin
@TestFactory
fun `test all discovered endpoints`(): Collection<DynamicTest> {
    val registry = loadEndpointsFromJson()

    return registry.services.flatMap { service ->
        service.endpoints.map { endpoint ->
            DynamicTest.dynamicTest("${service.name}: ${endpoint.method} ${endpoint.path}") {
                runBlocking {
                    val response = testEndpoint(service, endpoint)
                    assertTrue(response.status.value in 200..499)
                }
            }
        }
    }
}
```

### HTTP Client Setup

```kotlin
private lateinit var client: HttpClient

@BeforeEach
fun setup() {
    client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }
}

@AfterEach
fun teardown() {
    client.close()
}
```

### Method Handling

```kotlin
when (endpoint.method) {
    HttpMethod.GET -> client.get(url)
    HttpMethod.POST -> client.post(url) {
        contentType(ContentType.Application.Json)
        setBody("{}")
    }
    // etc...
}
```

## Success Criteria

✅ One test class file created (~100 lines)
✅ Zero compilation errors
✅ 80 dynamic tests created at runtime
✅ All generator code deleted
✅ KotlinPoet dependency removed
✅ Build is faster (no generation step)
✅ Tests can be run: `./gradlew :stack-tests:test`

## Expected Results

**Before:**
- 300+ lines of generation code
- 32 generated test files
- 2,400+ lines of generated test code
- 480+ compilation errors
- Complex build pipeline

**After:**
- 1 test file (~100 lines)
- 0 compilation errors
- Simple build: discover → test
- Easy to maintain and debug

## Files You'll Modify

1. **CREATE**: `src/stack-tests/src/test/kotlin/org/datamancy/stacktests/StackEndpointTests.kt`
2. **MODIFY**: `src/stack-tests/build.gradle.kts` (remove generation tasks)
3. **DELETE**: `src/stack-tests/src/main/kotlin/org/datamancy/stacktests/generator/`
4. **DELETE**: `src/stack-tests/src/test/kotlin/org/datamancy/stacktests/generated/`
5. **COPY** (if needed): IntegrationTest files from control-panel

## Common Pitfalls to Avoid

❌ Don't try to fix the generated test files - delete them
❌ Don't try to fix KotlinPoet issues - remove it entirely
❌ Don't keep the generation tasks - delete them
❌ Don't overthink it - it's just reading JSON and making HTTP requests

## Questions?

The complete solution is documented in:
- `STACK_TESTS_NO_GENERATION.md` (detailed implementation)
- `STACK_TESTS_SIMPLE_FIX.md` (why templating was wrong)
- `STACK_TESTS_FIX_PLAN.md` (original KotlinPoet fix attempt)

Read these if you need more context, but the task is straightforward:
**Replace code generation with JUnit @TestFactory dynamic tests.**

---

Good luck! This should take about 15 minutes and will eliminate 480+ errors instantly.
