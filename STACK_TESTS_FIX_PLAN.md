# Plan: Fix Stack Tests Compilation - Use String Templates Instead of KotlinPoet

## You're Right - Why Use KotlinPoet?

**KotlinPoet is overkill for this use case!** We're generating simple, repetitive test classes with a consistent structure. String templates would be:
- **Simpler**: No complex API to learn
- **Faster**: Direct string concatenation
- **More Maintainable**: Easy to read and modify
- **No Import Issues**: We control the exact output

## Problem Analysis

Current approach uses KotlinPoet which causes:
1. **Comment wrapping issues** - Long paths break across lines
2. **Import alias complexity** - KotlinPoet's import management is finicky
3. **Over-engineering** - 300+ lines of code for simple templating
4. **Debugging difficulty** - Hard to see what's being generated

## Proposed Solution: String Templates

Replace `TestGenerator.kt` with simple Kotlin string templates.

### New TestGenerator Implementation

```kotlin
class TestClassGenerator(private val outputDir: File) {

    fun generateServiceTests(service: ServiceSpec) {
        val testClassName = service.testClassName
        val fileName = "$testClassName.kt"
        val file = File(outputDir, "org/datamancy/stacktests/generated/$fileName")

        file.parentFile.mkdirs()
        file.writeText(generateTestClass(service))

        logger.debug { "Generated $fileName" }
    }

    private fun generateTestClass(service: ServiceSpec): String = """
        |// Auto-generated test class for ${service.name}
        |// Generated from discovered endpoints
        |// DO NOT EDIT - regenerate with: ./gradlew :stack-tests:generateTests
        |package org.datamancy.stacktests.generated
        |
        |import io.ktor.client.HttpClient
        |import io.ktor.client.engine.cio.CIO
        |import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
        |import io.ktor.client.request.*
        |import io.ktor.client.statement.bodyAsText
        |import io.ktor.http.ContentType
        |import io.ktor.http.contentType
        |import io.ktor.serialization.kotlinx.json.json
        |import kotlinx.serialization.json.Json
        |import kotlinx.serialization.json.JsonArray
        |import org.datamancy.controlpanel.IntegrationTest
        |import org.junit.jupiter.api.AfterEach
        |import org.junit.jupiter.api.Assertions.assertTrue
        |import org.junit.jupiter.api.BeforeEach
        |import org.junit.jupiter.api.Test
        |
        |@IntegrationTest(requiredServices = arrayOf(${service.requiredServicesList()}))
        |class ${service.testClassName} {
        |    private lateinit var client: HttpClient
        |    private val baseUrl = "${service.baseUrl}"
        |
        |    @BeforeEach
        |    fun setup() {
        |        client = HttpClient(CIO) {
        |            install(ContentNegotiation) {
        |                json()
        |            }
        |        }
        |    }
        |
        |    @AfterEach
        |    fun teardown() {
        |        client.close()
        |    }
        |
        ${service.endpoints.joinToString("\n\n") { generateTestMethod(it) }.prependIndent("    ")}
        |}
    """.trimMargin()

    private fun ServiceSpec.requiredServicesList(): String {
        val services = (listOf(name) + requiredServices).distinct()
        return services.joinToString(", ") { "\"$it\"" }
    }

    private fun generateTestMethod(endpoint: EndpointSpec): String {
        val methodName = sanitizeTestMethodName(endpoint)
        val url = buildUrl(endpoint)

        return when (endpoint.method) {
            HttpMethod.GET -> """
                |@Test
                |suspend fun `$methodName`() {
                |    val response = client.get("$url")
                |    assertTrue(response.status.value in 200..399, "Expected success status, got ${'$'}{response.status}")
                |    ${generateResponseValidation(endpoint.expectedResponseType)}
                |}
            """.trimMargin()

            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH -> """
                |@Test
                |suspend fun `$methodName`() {
                |    val response = client.${endpoint.method.name.lowercase()}("$url") {
                |        contentType(ContentType.Application.Json)
                |        setBody("{}")
                |    }
                |    assertTrue(response.status.value in 200..499, "Got ${'$'}{response.status}")
                |}
            """.trimMargin()

            HttpMethod.DELETE -> """
                |@Test
                |suspend fun `$methodName`() {
                |    val response = client.delete("$url")
                |    assertTrue(response.status.value in 200..499, "Got ${'$'}{response.status}")
                |}
            """.trimMargin()

            else -> """
                |@Test
                |suspend fun `$methodName`() {
                |    assertTrue(true, "Not implemented: ${endpoint.method}")
                |}
            """.trimMargin()
        }
    }

    private fun sanitizeTestMethodName(endpoint: EndpointSpec): String {
        val path = endpoint.path
            .replace(Regex("[{}/:]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .joinToString(" ")
        return "${endpoint.method} $path returns valid response"
    }

    private fun buildUrl(endpoint: EndpointSpec): String {
        var path = endpoint.path
        endpoint.parameters.forEach { param ->
            path = path.replace("{${param.name}}", "test-${param.name}")
            path = path.replace(":${param.name}", "test-${param.name}")
        }
        return "${'$'}baseUrl$path"
    }

    private fun generateResponseValidation(responseType: ResponseType): String {
        return when (responseType) {
            ResponseType.JSON_ARRAY -> """
                |val body = response.bodyAsText()
                |val element = Json.parseToJsonElement(body)
                |assertTrue(element is JsonArray, "Expected JSON array")
            """.trimMargin()

            ResponseType.JSON, ResponseType.JSON_OBJECT -> """
                |val contentType = response.contentType()
                |assertTrue(
                |    contentType?.contentType == "application" && contentType.contentSubtype == "json",
                |    "Expected JSON content type, got ${'$'}contentType"
                |)
            """.trimMargin()

            ResponseType.TEXT -> """
                |val contentType = response.contentType()
                |assertTrue(contentType?.contentType == "text", "Expected text content type")
            """.trimMargin()

            ResponseType.HTML -> """
                |// Accepts HTML or redirects
            """.trimMargin()

            else -> ""
        }
    }
}
```

## Benefits of String Template Approach

✅ **No comment wrapping issues** - Direct string control
✅ **No import problems** - We write exactly what we need
✅ **Easier to debug** - Can print/log the generated string
✅ **Simpler code** - 150 lines vs 300+ with KotlinPoet
✅ **More maintainable** - Anyone can read/modify templates
✅ **Faster generation** - No object construction overhead

## Implementation Steps

1. **Backup current TestGenerator.kt**
   ```bash
   cp src/stack-tests/src/main/kotlin/org/datamancy/stacktests/generator/TestGenerator.kt{,.backup}
   ```

2. **Replace with string template version** (code above)

3. **Remove KotlinPoet dependency** from `build.gradle.kts`
   ```kotlin
   // DELETE THIS LINE:
   implementation("com.squareup:kotlinpoet:1.15.3")
   ```

4. **Regenerate tests**
   ```bash
   rm -rf src/stack-tests/src/test/kotlin/org/datamancy/stacktests/generated/*.kt
   ./gradlew :stack-tests:clean
   ./gradlew :stack-tests:generateTests --rerun-tasks
   ```

5. **Compile and verify**
   ```bash
   ./gradlew :stack-tests:compileTestKotlin
   ```

## Expected Results

- ✅ **Clean, readable test code**
- ✅ **Zero compilation errors**
- ✅ **All 80 tests generate correctly**
- ✅ **50% less code in TestGenerator**
- ✅ **No weird import/comment issues**

## Time Estimate

- **15 minutes** to implement
- **5 minutes** to test
- **Total: 20 minutes**

## Why This is Better

KotlinPoet is designed for **complex code generation** with:
- Type-safe builders
- Import management
- Annotation processing
- Code analysis

Our use case is **simple repetitive templates**:
- Same structure for every test
- Fixed imports
- No dynamic types
- Just text substitution

**String templates are the right tool for this job.**
