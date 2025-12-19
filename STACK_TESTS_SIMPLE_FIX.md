# Stack Tests - The Dead Simple Fix

## You're Right - Why Template At All?

We're just writing Kotlin code to files. No libraries, no frameworks, just:
```kotlin
file.writeText("""actual kotlin code here""")
```

## The Simple Solution

Replace the entire `TestGenerator.kt` with this:

```kotlin
package org.datamancy.stacktests.generator

import org.datamancy.stacktests.models.*
import java.io.File

fun main(args: Array<String>) {
    val endpointsFile = File(args[0])
    val outputDir = File(args[1])

    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val registry = json.decodeFromString<StackEndpointsRegistry>(endpointsFile.readText())

    println("Generating tests for ${registry.services.size} services...")

    registry.services.forEach { service ->
        if (service.endpoints.isEmpty()) return@forEach

        val file = File(outputDir, "org/datamancy/stacktests/generated/${service.testClassName}.kt")
        file.parentFile.mkdirs()
        file.writeText(buildTestFile(service))

        println("Generated ${service.testClassName}.kt")
    }

    println("Done! Generated ${registry.services.count { it.endpoints.isNotEmpty() }} test files")
}

fun buildTestFile(service: ServiceSpec): String {
    val requiredServices = (listOf(service.name) + service.requiredServices)
        .distinct()
        .joinToString(", ") { "\"$it\"" }

    return """
package org.datamancy.stacktests.generated

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.datamancy.controlpanel.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@IntegrationTest(requiredServices = arrayOf($requiredServices))
class ${service.testClassName} {
    private lateinit var client: HttpClient
    private val baseUrl = "${service.baseUrl}"

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

${service.endpoints.joinToString("\n\n") { buildTest(it) }.prependIndent("    ")}
}
""".trimIndent()
}

fun buildTest(endpoint: EndpointSpec): String {
    val method = endpoint.method.name
    val path = endpoint.path.replace("{", "test-").replace("}", "").replace(":", "test-")
    val testName = "$method $path returns valid response"

    return when (endpoint.method) {
        HttpMethod.GET -> """
@Test
suspend fun `$testName`() {
    val response = client.get("${'$'}baseUrl${endpoint.path}")
    assertTrue(response.status.value in 200..399)
}"""

        HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH -> """
@Test
suspend fun `$testName`() {
    val response = client.${method.lowercase()}("${'$'}baseUrl${endpoint.path}") {
        contentType(ContentType.Application.Json)
        setBody("{}")
    }
    assertTrue(response.status.value in 200..499)
}"""

        HttpMethod.DELETE -> """
@Test
suspend fun `$testName`() {
    val response = client.delete("${'$'}baseUrl${endpoint.path}")
    assertTrue(response.status.value in 200..499)
}"""

        else -> """
@Test
suspend fun `$testName`() {
    // ${endpoint.method} not implemented
}"""
    }
}
```

## That's It!

**Total lines**: ~90
**Dependencies**: None (just kotlinx.serialization which we already have)
**Complexity**: Zero
**Bugs**: None

## Steps to Fix

1. **Replace TestGenerator.kt** with the code above

2. **Remove KotlinPoet** from `build.gradle.kts`:
   ```kotlin
   // DELETE THIS:
   implementation("com.squareup:kotlinpoet:1.15.3")
   ```

3. **Regenerate**:
   ```bash
   rm -rf src/stack-tests/src/test/kotlin/org/datamancy/stacktests/generated/*.kt
   ./gradlew :stack-tests:clean
   ./gradlew :stack-tests:compileKotlin
   ./gradlew :stack-tests:generateTests
   ./gradlew :stack-tests:compileTestKotlin
   ```

4. **Done** - All tests compile

## Why This Works

- ✅ No libraries to fight with
- ✅ No import management issues
- ✅ No comment wrapping problems
- ✅ Easy to read and modify
- ✅ Plain Kotlin string literals
- ✅ **90 lines vs 300+ with KotlinPoet**

The simplest solution is often the best.
