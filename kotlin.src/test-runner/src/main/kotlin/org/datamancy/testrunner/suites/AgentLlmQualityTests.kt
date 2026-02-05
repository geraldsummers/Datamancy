package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*
import kotlin.system.measureTimeMillis

/**
 * LLM quality and core tool capability tests
 *
 * Tests LLM completion quality, tool selection, and comprehensive CoreTools functionality.
 */
suspend fun TestRunner.agentLlmQualityTests() {
    val probRunner = ProbabilisticTestRunner(environment, client, httpClient)

    println("\n▶ Agent LLM Quality & Core Capability Tests")

    // ===== LLM COMPLETION QUALITY TESTS =====

    probRunner.probabilisticTest(
        name = "LLM: Generates coherent responses to simple questions",
        trials = 20,
        acceptableFailureRate = 0.2  // 80% success rate
    ) {
        val questions = listOf(
            "What is 2+2?",
            "What color is the sky?",
            "Is water wet?",
            "How many days in a week?"
        )

        val question = questions.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[{"role":"user","content":"$question"}],
                        "temperature":0.3,
                        "max_tokens":50
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            // Should contain some reasonable response
            body.length > 10 && !body.contains("error", ignoreCase = true)
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "LLM: Handles conversation context correctly",
        trials = 15,
        acceptableFailureRate = 0.3  // 70% success rate
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"user","content":"My name is Alice"},
                            {"role":"assistant","content":"Hello Alice! Nice to meet you."},
                            {"role":"user","content":"What is my name?"}
                        ],
                        "temperature":0.2,
                        "max_tokens":50
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            // Should reference "Alice" in response
            body.contains("Alice", ignoreCase = true)
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "LLM: Respects temperature parameter for determinism",
        trials = 10,
        acceptableFailureRate = 0.3
    ) {
        // With temperature=0, responses should be more consistent
        val prompt = "Say exactly: 'Hello World'"

        val responses = mutableSetOf<String>()
        repeat(3) {
            val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tool":"llm_chat_completion",
                        "input":{
                            "messages":[{"role":"user","content":"$prompt"}],
                            "temperature":0.0,
                            "max_tokens":20
                        }
                    }
                """.trimIndent())
            }
            if (response.status == HttpStatusCode.OK) {
                responses.add(response.bodyAsText())
            }
        }

        // Low temperature should produce similar outputs
        responses.size <= 2  // Allow minor variation
    }

    probRunner.latencyTest(
        name = "LLM: Completion latency for short prompts",
        trials = 30,
        maxMedianLatency = 5000,   // 5 seconds median
        maxP95Latency = 15000      // 15 seconds p95
    ) {
        measureTimeMillis {
            httpClient.post("${endpoints.agentToolServer}/call-tool") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tool":"llm_chat_completion",
                        "input":{
                            "messages":[{"role":"user","content":"Hello"}],
                            "max_tokens":50
                        }
                    }
                """.trimIndent())
            }
        }
    }

    probRunner.probabilisticTest(
        name = "LLM: Handles max_tokens limit correctly",
        trials = 15,
        acceptableFailureRate = 0.2
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[{"role":"user","content":"Write a long story"}],
                        "max_tokens":10
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            // Should be truncated/short due to low token limit
            body.length < 500  // Rough check
        } else {
            false
        }
    }

    // ===== CORE TOOLS: TEXT PROCESSING =====

    probRunner.probabilisticTest(
        name = "CoreTools: Text processing functions work correctly",
        trials = 30,
        acceptableFailureRate = 0.1
    ) {
        val testCases = listOf(
            Triple("normalize_whitespace", """{"text":"  hello   world  "}""", "hello world"),
            Triple("slugify", """{"text":"Hello World!"}""", "hello-world"),
            Triple("tokenize_words", """{"text":"Hello, world!"}""", "Hello"),
            Triple("truncate_text", """{"text":"Long text here","maxChars":5}""", "Long")
        )

        val (tool, input, expectedSubstring) = testCases.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"$tool","input":$input}""")
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains(expectedSubstring, ignoreCase = true)
    }

    probRunner.probabilisticTest(
        name = "CoreTools: String similarity functions produce valid results",
        trials = 25,
        acceptableFailureRate = 0.1
    ) {
        val testCases = listOf(
            Triple("levenshtein_distance", """{"str1":"hello","str2":"hello"}""", "0"),
            Triple("jaccard_similarity", """{"str1":"hello world","str2":"hello world"}""", "1.0"),
            Triple("compare_strings_fuzzy", """{"str1":"test","str2":"test"}""", "1.0")
        )

        val (tool, input, expected) = testCases.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"$tool","input":$input}""")
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains(expected)
    }

    // ===== CORE TOOLS: MATH & VECTOR OPERATIONS =====

    probRunner.probabilisticTest(
        name = "CoreTools: Vector operations compute correctly",
        trials = 25,
        acceptableFailureRate = 0.1
    ) {
        val testCases = listOf(
            Triple("vector_dot", """{"vec1":[1.0,2.0,3.0],"vec2":[1.0,2.0,3.0]}""", "14"),
            Triple("cosine_similarity", """{"vec1":[1.0,0.0],"vec2":[0.0,1.0]}""", "0.0"),
            Triple("mean", """{"values":[1.0,2.0,3.0,4.0,5.0]}""", "3.0")
        )

        val (tool, input, expected) = testCases.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"$tool","input":$input}""")
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains(expected)
    }

    probRunner.probabilisticTest(
        name = "CoreTools: Math functions handle edge cases gracefully",
        trials = 30,
        acceptableFailureRate = 0.15
    ) {
        val edgeCases = listOf(
            Triple("mean", """{"values":[]}""", "NaN"),  // Empty list
            Triple("argmax", """{"values":[]}""", "-1"),  // Empty list
            Triple("clamp_value", """{"value":100.0,"min":0.0,"max":50.0}""", "50"),  // Clamped
            Triple("map_range", """{"value":5.0,"fromMin":0.0,"fromMax":10.0,"toMin":0.0,"toMax":100.0}""", "50")
        )

        val (tool, input, expected) = edgeCases.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"$tool","input":$input}""")
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains(expected)
    }

    // ===== CORE TOOLS: LIST OPERATIONS =====

    probRunner.probabilisticTest(
        name = "CoreTools: List operations work correctly",
        trials = 25,
        acceptableFailureRate = 0.1
    ) {
        val testCases = listOf(
            Triple("chunk_list", """{"items":[1,2,3,4,5],"size":2}""", "[[1,2]"),
            Triple("flatten_list", """{"items":[[1,2],[3,4]]}""", "[1,2,3,4]"),
            Triple("sliding_window", """{"items":[1,2,3,4],"size":2,"step":1}""", "[[1,2]")
        )

        val (tool, input, expected) = testCases.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"$tool","input":$input}""")
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains(expected, ignoreCase = true)
    }

    // ===== CORE TOOLS: DATE/TIME OPERATIONS =====

    probRunner.probabilisticTest(
        name = "CoreTools: Date/time functions produce valid ISO timestamps",
        trials = 20,
        acceptableFailureRate = 0.1
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"now_utc_iso","input":{}}""")
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            // Should match ISO 8601 format
            body.matches(Regex(".*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z.*"))
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "CoreTools: Date calculations work correctly",
        trials = 20,
        acceptableFailureRate = 0.1
    ) {
        val testCases = listOf(
            Triple("days_between", """{"start":"2024-01-01T00:00:00Z","end":"2024-01-11T00:00:00Z"}""", "10"),
            Triple("hours_between", """{"start":"2024-01-01T00:00:00Z","end":"2024-01-01T05:00:00Z"}""", "5")
        )

        val (tool, input, expected) = testCases.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"$tool","input":$input}""")
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains(expected)
    }

    // ===== CORE TOOLS: JSON OPERATIONS =====

    probRunner.probabilisticTest(
        name = "CoreTools: JSON operations handle nested data correctly",
        trials = 20,
        acceptableFailureRate = 0.1
    ) {
        val jsonData = """{"user":{"name":"Alice","age":30}}"""
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"json_get_path","input":{"json":$jsonData,"path":"user.name"}}""")
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains("Alice")
    }

    // ===== UTILITY TESTS =====

    probRunner.probabilisticTest(
        name = "CoreTools: UUID generation produces valid UUIDs",
        trials = 30,
        acceptableFailureRate = 0.0
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"uuid_generate","input":{}}""")
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            // Check UUID format
            body.matches(Regex(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*"))
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "CoreTools: Safe parsing returns defaults on invalid input",
        trials = 25,
        acceptableFailureRate = 0.1
    ) {
        val testCases = listOf(
            Triple("safe_parse_int", """{"text":"invalid","default":99}""", "99"),
            Triple("safe_parse_double", """{"text":"not_a_number","default":1.5}""", "1.5")
        )

        val (tool, input, expected) = testCases.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"$tool","input":$input}""")
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains(expected)
    }

    // Print summary
    val summary = probRunner.summary()
    println("\n" + "=".repeat(80))
    println("LLM QUALITY & CORE CAPABILITY TEST SUMMARY")
    println("=".repeat(80))
    println("Total Tests: ${summary.total}")
    println("  ✓ Passed: ${summary.passed}")
    println("  ✗ Failed: ${summary.failed}")

    if (summary.failed > 0) {
        println("\n❌ Failed Tests:")
        summary.results.filter { !it.passed }.forEach { result ->
            when (result) {
                is ProbabilisticTestResultSuccess -> {
                    println("  • ${result.name}")
                    println("    Success rate: ${result.successCount}/${result.trials} (${(result.actualFailureRate * 100).toInt()}% fail rate)")
                }
                is LatencyTestResult -> {
                    println("  • ${result.name}")
                    println("    Median: ${result.medianMs}ms (max ${result.maxMedianLatency}ms), P95: ${result.p95Ms}ms (max ${result.maxP95Latency}ms)")
                }
                else -> {}
            }
        }
    }

    println("=".repeat(80))

    if (summary.failed > 0) {
        throw AssertionError("${summary.failed} LLM quality/capability test(s) failed")
    }
}
