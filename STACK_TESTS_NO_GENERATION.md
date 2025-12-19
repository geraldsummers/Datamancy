# Stack Tests - Why Are We Generating Anything?

## The Real Question

Why are we **generating test files at all**?

We could just write **one test class** that reads the endpoint registry JSON and **dynamically creates tests**.

## The Simple Approach

Instead of:
1. Discover endpoints → JSON
2. Read JSON → Generate 32 .kt files
3. Compile 32 generated files
4. Run tests

Just do:
1. Discover endpoints → JSON
2. **One test class reads JSON and runs all tests dynamically**

## Implementation

```kotlin
package org.datamancy.stacktests

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.datamancy.controlpanel.IntegrationTest
import org.junit.jupiter.api.*
import java.io.File

@IntegrationTest(requiredServices = arrayOf()) // Start all services
class DynamicStackTests {
    private lateinit var client: HttpClient
    private lateinit var registry: StackEndpointsRegistry

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadEndpoints() {
            // Load the discovered endpoints JSON
            val jsonFile = File("build/discovered-endpoints.json")
            val json = Json { ignoreUnknownKeys = true }
            registry = json.decodeFromString(jsonFile.readText())
        }
    }

    @BeforeEach
    fun setup() {
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
    }

    @AfterEach
    fun teardown() {
        client.close()
    }

    @TestFactory
    fun allEndpointTests(): Collection<DynamicTest> {
        return registry.services.flatMap { service ->
            service.endpoints.map { endpoint ->
                val testName = "${service.name}: ${endpoint.method} ${endpoint.path}"

                DynamicTest.dynamicTest(testName) {
                    runBlocking {
                        val url = "${service.baseUrl}${endpoint.path}"

                        val response = when (endpoint.method) {
                            HttpMethod.GET -> client.get(url)
                            HttpMethod.POST -> client.post(url) {
                                contentType(ContentType.Application.Json)
                                setBody("{}")
                            }
                            HttpMethod.PUT -> client.put(url) {
                                contentType(ContentType.Application.Json)
                                setBody("{}")
                            }
                            HttpMethod.DELETE -> client.delete(url)
                            else -> return@runBlocking
                        }

                        Assertions.assertTrue(response.status.value in 200..499) {
                            "Service: ${service.name}, Endpoint: ${endpoint.method} ${endpoint.path}, Status: ${response.status}"
                        }
                    }
                }
            }
        }
    }
}
```

## Benefits

✅ **ONE test file** instead of 32
✅ **NO code generation** at all
✅ **NO compilation issues** - it's already compiled
✅ **Easy to modify** - just edit one file
✅ **Dynamic** - add endpoints, they're automatically tested
✅ **Simpler build** - no generation step needed
✅ **JUnit's @TestFactory** creates tests at runtime

## Workflow

```bash
# 1. Discover endpoints
./gradlew :stack-tests:discoverEndpoints

# 2. Run tests (that's it!)
./gradlew :stack-tests:test
```

No generation step needed!

## Why Is This Better?

**Current approach:**
- Discovery system ✓
- Generation system (unnecessary)
- 32 generated files to compile
- Import/syntax issues
- Complex debugging

**Dynamic approach:**
- Discovery system ✓
- One test file that reads JSON
- Runtime test creation
- No generation bugs possible
- Simple and maintainable

## The Answer

We don't need to generate anything. JUnit's `@TestFactory` with `DynamicTest` does exactly what we need - **create tests at runtime from data**.

The entire test generator can be **deleted** and replaced with one ~80 line test class.
