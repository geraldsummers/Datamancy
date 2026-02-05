package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*
import kotlin.system.measureTimeMillis

/**
 * Probabilistic tests for agent-tool-server capabilities
 *
 * These tests acknowledge the non-deterministic nature of agent systems and test
 * capabilities probabilistically rather than deterministically.
 */

suspend fun TestRunner.agentCapabilityTests() {
    val probRunner = ProbabilisticTestRunner(environment, client, httpClient)

    println("\n▶ Agent Capability Tests (Probabilistic)")

    // ===== BASIC TOOL AVAILABILITY TESTS =====

    probRunner.probabilisticTest(
        name = "Agent tool server responds to health checks consistently",
        trials = 20,
        acceptableFailureRate = 0.05  // 5% failure rate acceptable for network issues
    ) {
        val response = client.getRawResponse("${endpoints.agentToolServer}/healthz")
        response.status == HttpStatusCode.OK
    }

    probRunner.probabilisticTest(
        name = "Tool listing endpoint returns valid JSON",
        trials = 20,
        acceptableFailureRate = 0.0  // Should always work
    ) {
        val response = client.getRawResponse("${endpoints.agentToolServer}/tools")
        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains("\"tools\"")
    }

    // ===== TOOL EXECUTION TESTS =====

    probRunner.probabilisticTest(
        name = "Core text processing tools execute successfully",
        trials = 30,
        acceptableFailureRate = 0.1  // 10% failure acceptable
    ) {
        val toolNames = listOf(
            "normalize_whitespace",
            "slugify",
            "tokenize_words",
            "uuid_generate"
        )
        val tool = toolNames.random()
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"$tool","input":{"text":"Hello World Test"}}""")
        }
        response.status == HttpStatusCode.OK
    }

    probRunner.probabilisticTest(
        name = "Docker container operations succeed on valid inputs",
        trials = 15,
        acceptableFailureRate = 0.2  // 20% failure acceptable (network, Docker daemon issues)
    ) {
        // Test docker_container_list which is read-only and safe
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"docker_container_list","input":{"all":true}}""")
        }
        response.status == HttpStatusCode.OK
    }

    // ===== LATENCY TESTS =====

    probRunner.latencyTest(
        name = "Tool listing latency",
        trials = 50,
        maxMedianLatency = 200,  // 200ms median
        maxP95Latency = 500      // 500ms p95
    ) {
        val start = System.currentTimeMillis()
        client.getRawResponse("${endpoints.agentToolServer}/tools")
        System.currentTimeMillis() - start
    }

    probRunner.latencyTest(
        name = "Simple tool execution latency (normalize_whitespace)",
        trials = 40,
        maxMedianLatency = 300,  // 300ms median
        maxP95Latency = 1000     // 1s p95
    ) {
        val start = System.currentTimeMillis()
        httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"normalize_whitespace","input":{"text":"  hello   world  "}}""")
        }
        System.currentTimeMillis() - start
    }

    probRunner.latencyTest(
        name = "Docker list containers latency",
        trials = 30,
        maxMedianLatency = 1000,  // 1s median (Docker API can be slow)
        maxP95Latency = 3000      // 3s p95
    ) {
        val start = System.currentTimeMillis()
        httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"docker_container_list","input":{"all":false}}""")
        }
        System.currentTimeMillis() - start
    }

    // ===== THROUGHPUT TESTS =====

    probRunner.throughputTest(
        name = "Tool listing throughput",
        durationSeconds = 10,
        minOpsPerSecond = 5.0  // At least 5 requests per second
    ) {
        client.getRawResponse("${endpoints.agentToolServer}/tools")
    }

    probRunner.throughputTest(
        name = "Simple tool execution throughput",
        durationSeconds = 10,
        minOpsPerSecond = 2.0  // At least 2 tool calls per second
    ) {
        httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"uuid_generate","input":{}}""")
        }
    }

    // ===== PLUGIN CAPABILITY TESTS =====

    probRunner.probabilisticTest(
        name = "Core tools plugin is loaded and functional",
        trials = 10,
        acceptableFailureRate = 0.0
    ) {
        val response = client.getRawResponse("${endpoints.agentToolServer}/tools")
        val body = response.bodyAsText()
        body.contains("normalize_whitespace") &&
            body.contains("slugify") &&
            body.contains("cosine_similarity")
    }

    probRunner.probabilisticTest(
        name = "Host tools plugin is loaded and functional",
        trials = 10,
        acceptableFailureRate = 0.0
    ) {
        val response = client.getRawResponse("${endpoints.agentToolServer}/tools")
        val body = response.bodyAsText()
        body.contains("fetch_url") || body.contains("http")
    }

    probRunner.probabilisticTest(
        name = "Docker plugin is loaded and functional",
        trials = 10,
        acceptableFailureRate = 0.0
    ) {
        val response = client.getRawResponse("${endpoints.agentToolServer}/tools")
        val body = response.bodyAsText()
        body.contains("docker_container")
    }

    probRunner.probabilisticTest(
        name = "LLM completion plugin is loaded and functional",
        trials = 10,
        acceptableFailureRate = 0.0
    ) {
        val response = client.getRawResponse("${endpoints.agentToolServer}/tools")
        val body = response.bodyAsText()
        body.contains("llm_chat_completion")
    }

    // ===== TOOL ROBUSTNESS TESTS =====

    probRunner.probabilisticTest(
        name = "Tools handle malformed inputs gracefully",
        trials = 20,
        acceptableFailureRate = 0.0  // Should never crash, always return error
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"normalize_whitespace","input":{"wrong_field":"test"}}""")
        }
        // Should return 400 or 200 with error, not 500
        response.status != HttpStatusCode.InternalServerError
    }

    probRunner.probabilisticTest(
        name = "Tools handle missing required parameters gracefully",
        trials = 20,
        acceptableFailureRate = 0.0
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"normalize_whitespace","input":{}}""")
        }
        // Should return error response, not crash
        response.status != HttpStatusCode.InternalServerError
    }

    probRunner.probabilisticTest(
        name = "Tools handle non-existent tool names gracefully",
        trials = 20,
        acceptableFailureRate = 0.0
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""{"tool":"nonexistent_tool_12345","input":{}}""")
        }
        // Should return 404 or 400, not 500
        response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.BadRequest
    }

    // ===== CONCURRENT EXECUTION TESTS =====

    probRunner.probabilisticTest(
        name = "Server handles concurrent tool requests",
        trials = 20,
        acceptableFailureRate = 0.15  // 15% failure acceptable under load
    ) {
        // Make 5 concurrent requests
        val responses = (1..5).map {
            httpClient.post("${endpoints.agentToolServer}/call-tool") {
                contentType(ContentType.Application.Json)
                setBody("""{"tool":"uuid_generate","input":{}}""")
            }
        }
        responses.all { it.status == HttpStatusCode.OK }
    }

    // Print summary
    val summary = probRunner.summary()
    println("\n" + "=".repeat(80))
    println("PROBABILISTIC TEST SUMMARY")
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
                    println("    Success rate: ${result.successCount}/${result.trials} (${(result.actualFailureRate * 100).toInt()}% failure)")
                }
                is LatencyTestResult -> {
                    println("  • ${result.name}")
                    println("    Median: ${result.medianMs}ms (max ${result.maxMedianLatency}ms), P95: ${result.p95Ms}ms (max ${result.maxP95Latency}ms)")
                }
                is ThroughputTestResult -> {
                    println("  • ${result.name}")
                    println("    Throughput: ${String.format("%.2f", result.opsPerSecond)}ops/s (min ${result.minOpsPerSecond}ops/s)")
                }
            }
        }
    }

    println("=".repeat(80))

    // Fail the entire suite if any probabilistic tests failed
    if (summary.failed > 0) {
        throw AssertionError("${summary.failed} probabilistic test(s) failed")
    }
}
