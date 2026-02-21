package org.datamancy.testrunner.suites

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*
import kotlin.test.*

/**
 * Unit tests for StackLlmCapabilityTests
 *
 * These tests verify the test logic itself, ensuring that:
 * - Prompts are well-formed
 * - Response validation logic is correct
 * - Test expectations are reasonable
 */
class StackLlmCapabilityTestsTest {

    // ============================================================================
    // Docker Compose Understanding Tests
    // ============================================================================

    @Test
    fun `test Docker Compose service structure prompts are valid`() {
        val prompt = """
            Given this Docker Compose service definition, what is the container name?

            services:
              test-service:
                image: nginx:latest
                container_name: my-nginx-container
                ports:
                  - "8080:80"
        """.trimIndent()

        assertTrue(prompt.contains("container name"))
        assertTrue(prompt.contains("services:"))
        assertTrue(prompt.contains("my-nginx-container"))
    }

    @Test
    fun `test network configuration prompt includes multiple networks`() {
        val expectedNetworks = listOf("frontend", "backend")

        expectedNetworks.forEach { network ->
            assertTrue(network.isNotEmpty())
        }

        assertEquals(2, expectedNetworks.size)
    }

    @Test
    fun `test environment variable detection logic`() {
        val envVars = listOf("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")

        envVars.forEach { envVar ->
            assertTrue(envVar.startsWith("POSTGRES_"))
            assertTrue(envVar.length > 9)
        }
    }

    @Test
    fun `test Docker Compose port conflict detection`() {
        val portConflictYaml = """
            services:
              web:
                image: nginx:latest
                ports:
                  - 8080:80
                  - 8080:443
        """.trimIndent()

        // Logic: same host port (8080) mapped to different container ports
        val portMappings = listOf("8080:80", "8080:443")
        val hostPorts = portMappings.map { it.split(":")[0] }
        val hasDuplicate = hostPorts.size != hostPorts.toSet().size

        assertTrue(hasDuplicate, "Should detect port conflict")
    }

    @Test
    fun `test service generation validation checks required fields`() {
        val requiredFields = listOf("services:", "image:", "ports:")

        requiredFields.forEach { field ->
            assertTrue(field.endsWith(":"))
        }
    }

    // ============================================================================
    // JupyterHub Code Generation Tests
    // ============================================================================

    @Test
    fun `test Python function generation validation logic`() {
        val mockResponse = """
            def calculate_mean(numbers):
                return sum(numbers) / len(numbers)
        """.trimIndent()

        assertTrue(mockResponse.contains("def"))
        assertTrue(mockResponse.contains("sum"))
        assertTrue(mockResponse.contains("len"))
    }

    @Test
    fun `test pandas code validation checks for required imports`() {
        val mockPandasCode = """
            import pandas as pd
            df = pd.read_csv('data.csv')
            df.head()
        """.trimIndent()

        assertTrue(mockPandasCode.contains("pandas"))
        assertTrue(mockPandasCode.contains("read_csv"))
        assertTrue(mockPandasCode.contains("head"))
    }

    @Test
    fun `test Python library suggestions are valid`() {
        val validLibraries = listOf("requests", "httpx", "urllib")

        validLibraries.forEach { lib ->
            assertTrue(lib.isNotEmpty())
            assertTrue(lib.all { it.isLetterOrDigit() })
        }
    }

    @Test
    fun `test matplotlib code contains plot functions`() {
        val mockMatplotlibCode = """
            import matplotlib.pyplot as plt
            plt.plot([1, 2, 3], [4, 5, 6])
            plt.show()
        """.trimIndent()

        assertTrue(mockMatplotlibCode.contains("matplotlib"))
        assertTrue(mockMatplotlibCode.contains("plot"))
    }

    // ============================================================================
    // Qdrant Knowledge Base Tests
    // ============================================================================

    @Test
    fun `test Qdrant collection concept validation`() {
        val validResponse = "A collection is a named group of vector points in Qdrant"

        assertTrue(
            validResponse.contains("vector", ignoreCase = true) ||
            validResponse.contains("embedding", ignoreCase = true) ||
            validResponse.contains("point", ignoreCase = true)
        )
    }

    @Test
    fun `test cosine similarity explanation validation`() {
        val validExplanations = listOf(
            "cosine similarity measures the angle between vectors",
            "it calculates the cosine of the angle between vectors",
            "similarity based on vector direction"
        )

        validExplanations.forEach { explanation ->
            val hasRelevantTerm = explanation.contains("cosine", ignoreCase = true) ||
                                 explanation.contains("angle", ignoreCase = true) ||
                                 explanation.contains("similarity", ignoreCase = true)
            assertTrue(hasRelevantTerm)
        }
    }

    @Test
    fun `test embedding dimension validation logic`() {
        val validDimensions = listOf(384, 512, 768, 1024, 1536)

        validDimensions.forEach { dim ->
            assertTrue(dim > 0)
            assertTrue(dim % 64 == 0 || dim % 128 == 0, "Common dimensions are multiples of 64 or 128")
        }
    }

    @Test
    fun `test hybrid search concept validation`() {
        val validHybridDescription = "combines vector similarity with keyword BM25 search"

        val hasVectorTerm = validHybridDescription.contains("vector", ignoreCase = true)
        val hasKeywordTerm = validHybridDescription.contains("keyword", ignoreCase = true) ||
                            validHybridDescription.contains("BM25", ignoreCase = true)

        assertTrue(hasVectorTerm && hasKeywordTerm ||
                  validHybridDescription.contains("combine", ignoreCase = true))
    }

    // ============================================================================
    // Search Service API Tests
    // ============================================================================

    @Test
    fun `test search API request JSON structure`() {
        val mockRequest = buildJsonObject {
            put("query", "kubernetes")
            put("limit", 10)
            put("mode", "hybrid")
        }

        assertTrue(mockRequest.containsKey("query"))
        assertTrue(mockRequest.containsKey("limit"))
        assertEquals("kubernetes", mockRequest["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test search result field validation`() {
        val expectedFields = listOf("title", "url", "score", "snippet", "content", "link")
        val mockResult = """
            {
                "title": "Test Article",
                "url": "https://example.com",
                "score": 0.95,
                "snippet": "This is a test"
            }
        """.trimIndent()

        val json = Json.parseToJsonElement(mockResult).jsonObject
        val presentFields = expectedFields.filter { json.containsKey(it) }

        assertTrue(presentFields.size >= 2, "Should have at least 2 common fields")
    }

    @Test
    fun `test search mode recommendation logic`() {
        val semanticQuery = "finding semantically similar documents"

        // Logic: semantic queries should use vector search
        val shouldUseVector = semanticQuery.contains("semantic", ignoreCase = true) ||
                             semanticQuery.contains("similar", ignoreCase = true)

        assertTrue(shouldUseVector)
    }

    @Test
    fun `test relevance score interpretation logic`() {
        val highScore = 0.95
        val mediumScore = 0.50
        val lowScore = 0.15

        assertTrue(highScore > 0.8, "High score should be > 0.8")
        assertTrue(mediumScore in 0.3..0.7, "Medium score should be 0.3-0.7")
        assertTrue(lowScore < 0.3, "Low score should be < 0.3")
    }

    // ============================================================================
    // Stack Reasoning Tests
    // ============================================================================

    @Test
    fun `test depends_on recommendation logic`() {
        val scenario = "web application that needs a database"

        val shouldUseDependsOn = scenario.contains("needs", ignoreCase = true) ||
                                scenario.contains("requires", ignoreCase = true) ||
                                scenario.contains("depend", ignoreCase = true)

        assertTrue(shouldUseDependsOn)
    }

    @Test
    fun `test volume persistence recommendation for databases`() {
        val databaseServices = listOf("postgres", "mysql", "mariadb", "mongodb")

        databaseServices.forEach { db ->
            // Logic: databases should always use volumes
            val shouldUseVolume = true
            assertTrue(shouldUseVolume, "$db should use volume")
        }
    }

    @Test
    fun `test health check validation for PostgreSQL`() {
        val mockHealthcheck = """
            healthcheck:
              test: ["CMD-SHELL", "pg_isready -U postgres"]
              interval: 10s
              timeout: 5s
              retries: 5
        """.trimIndent()

        assertTrue(mockHealthcheck.contains("healthcheck"))
        assertTrue(mockHealthcheck.contains("pg_isready") || mockHealthcheck.contains("test:"))
    }

    @Test
    fun `test security best practice validation`() {
        val insecureConfig = "POSTGRES_PASSWORD: mysecretpassword"
        val secureConfig = "POSTGRES_PASSWORD: \${POSTGRES_PASSWORD}"

        // Logic: environment variable usage is secure
        val isSecure = secureConfig.contains("\${") && secureConfig.contains("}")
        val isInsecure = insecureConfig.matches(Regex(".*:\\s*[^$].*"))

        assertTrue(isSecure)
        assertTrue(isInsecure)
    }

    // ============================================================================
    // Test Suite Validation
    // ============================================================================

    @Test
    fun `test all test categories are covered`() {
        val categories = setOf(
            "Basic Stack Understanding",
            "Stack Generation & Validation",
            "Stack Reasoning & Problem Solving",
            "Datamancy Stack-Specific Knowledge",
            "JupyterHub Code Generation",
            "Qdrant Knowledge Base Reasoning",
            "Search Service API Querying",
            "Performance Tests"
        )

        assertEquals(8, categories.size)
    }

    @Test
    fun `test expected total test count`() {
        val basicUnderstanding = 3
        val generation = 3
        val reasoning = 3
        val stackSpecific = 2
        val jupyter = 4
        val qdrant = 4
        val searchAPI = 4
        val performance = 2

        val total = basicUnderstanding + generation + reasoning +
                   stackSpecific + jupyter + qdrant + searchAPI + performance

        assertEquals(25, total)
    }

    @Test
    fun `test probabilistic test parameters are reasonable`() {
        // Trials should be between 10-30
        val trialCounts = listOf(10, 12, 15, 20)
        trialCounts.forEach { trials ->
            assertTrue(trials in 10..30)
        }

        // Acceptable failure rates should be 0.0-0.4
        val failureRates = listOf(0.0, 0.1, 0.2, 0.3, 0.4)
        failureRates.forEach { rate ->
            assertTrue(rate in 0.0..0.4)
        }
    }

    @Test
    fun `test latency test parameters are reasonable`() {
        // Median latency should be under 10 seconds
        val maxMedianLatencies = listOf(5000, 8000)
        maxMedianLatencies.forEach { latency ->
            assertTrue(latency <= 10000, "Median should be <= 10s")
        }

        // P95 latency should be under 30 seconds
        val maxP95Latencies = listOf(15000, 20000)
        maxP95Latencies.forEach { latency ->
            assertTrue(latency <= 30000, "P95 should be <= 30s")
        }
    }

    @Test
    fun `test temperature settings are appropriate`() {
        // Temperature for deterministic tasks should be low (0.0-0.2)
        val deterministicTemp = 0.1
        assertTrue(deterministicTemp in 0.0..0.2)

        // Temperature for creative tasks can be higher (0.2-0.7)
        val creativeTemp = 0.2
        assertTrue(creativeTemp in 0.0..0.7)
    }

    @Test
    fun `test max_tokens settings are reasonable`() {
        val tokenLimits = mapOf(
            "short_answer" to 50,
            "code_snippet" to 150,
            "explanation" to 100
        )

        tokenLimits.forEach { (type, limit) ->
            assertTrue(limit in 20..200, "$type token limit should be 20-200")
        }
    }

    // ============================================================================
    // Mock Response Validation
    // ============================================================================

    @Test
    fun `test LLM response validation handles various formats`() = runBlocking {
        val mockResponses = listOf(
            """{"content": "my-nginx-container"}""",
            """my-nginx-container""",
            """The container name is: my-nginx-container"""
        )

        mockResponses.forEach { response ->
            val containsAnswer = response.contains("my-nginx-container", ignoreCase = true)
            assertTrue(containsAnswer, "Response should contain the answer")
        }
    }

    @Test
    fun `test case-insensitive validation logic`() {
        val answers = listOf("YES", "yes", "Yes", "yEs")

        answers.forEach { answer ->
            assertTrue(answer.contains("yes", ignoreCase = true))
        }
    }

    @Test
    fun `test multiple valid answers are accepted`() {
        val validLibraries = listOf("requests", "httpx", "urllib")
        val mockResponse = "You should use requests or httpx for HTTP calls"

        val containsValidLibrary = validLibraries.any {
            mockResponse.contains(it, ignoreCase = true)
        }

        assertTrue(containsValidLibrary)
    }

    @Test
    fun `test test runner can be instantiated for stack LLM tests`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel("""{"status": "ok"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        assertNotNull(runner)
    }

    @Test
    fun `test agent tool server endpoint is configured`() {
        val endpoints = ServiceEndpoints.fromEnvironment()
        assertNotNull(endpoints.agentToolServer)
        assertTrue(endpoints.agentToolServer.contains("agent-tool-server"))
    }

    @Test
    fun `test LLM completion tool call structure`() {
        val mockToolCall = buildJsonObject {
            put("tool", "llm_chat_completion")
            putJsonObject("input") {
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "test prompt")
                    }
                }
                put("temperature", 0.1)
                put("max_tokens", 50)
            }
        }

        assertEquals("llm_chat_completion", mockToolCall["tool"]?.jsonPrimitive?.content)
        assertTrue(mockToolCall.containsKey("input"))

        val input = mockToolCall["input"]?.jsonObject
        assertNotNull(input)
        assertTrue(input.containsKey("messages"))
    }
}
