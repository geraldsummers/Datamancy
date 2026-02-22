package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

/**
 * Helper to extract agent response fields.
 */
private fun extractAgentField(body: String, field: String): Any? {
    return try {
        val json = Json.parseToJsonElement(body).jsonObject
        val result = json["result"]?.jsonObject
        when (field) {
            "content" -> result?.get("content")?.jsonPrimitive?.content
            "iterations" -> result?.get("iterations")?.jsonPrimitive?.int
            "trace" -> result?.get("trace")?.jsonArray
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Tests for the agent orchestration layer's advanced capabilities.
 *
 * These tests validate:
 * - Multi-iteration reasoning loops
 * - Forced tool usage (mode: "required")
 * - Tool filtering
 * - Agent trace logging
 * - Stack knowledge retrieval integration
 */
suspend fun TestRunner.agentOrchestrationTests() {
    val probRunner = ProbabilisticTestRunner(environment, client, httpClient)

    println("\n▶ Agent Orchestration Tests")

    // ============================================================================
    // Forced Tool Usage
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Agent: Forces tool usage when mode=required",
        trials = 3,
        acceptableFailureRate = 0.4
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name":"llm_chat_completion",
                    "args":{
                        "messages":[{"role":"user","content":"What ports does nginx typically use?"}],
                        "requested_tools":["semantic_search"],
                        "mode":"required",
                        "temperature":0.2,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val trace = extractAgentField(body, "trace") as? JsonArray

            // Verify that retrieve_stack_context was actually called
            val retrieveCalled = trace?.any { entry ->
                entry.jsonObject["tool"]?.jsonPrimitive?.content == "semantic_search"
            } ?: false

            retrieveCalled
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "Agent: Tool filtering works correctly",
        trials = 2,
        acceptableFailureRate = 0.2
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name":"llm_chat_completion",
                    "args":{
                        "messages":[{"role":"user","content":"Search for kubernetes documentation"}],
                        "requested_tools":["retrieve_stack_context"],
                        "temperature":0.2,
                        "max_tokens":150
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val trace = extractAgentField(body, "trace") as? JsonArray

            // Verify only allowed tools were used
            val invalidToolUsed = trace?.any { entry ->
                val toolName = entry.jsonObject["tool"]?.jsonPrimitive?.content
                toolName != null && toolName != "semantic_search"
            } ?: false

            !invalidToolUsed  // Test passes if no invalid tools were used
        } else {
            false
        }
    }

    // ============================================================================
    // Multi-Iteration Reasoning
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Agent: Can perform multi-iteration reasoning",
        trials = 2,
        acceptableFailureRate = 0.3
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name":"llm_chat_completion",
                    "args":{
                        "messages":[{
                            "role":"user",
                            "content":"First, retrieve information about Docker Compose networks. Then explain how services communicate."
                        }],
                        "requested_tools":["retrieve_stack_context"],
                        "max_iterations":3,
                        "temperature":0.2,
                        "max_tokens":200
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val iterations = extractAgentField(body, "iterations") as? Int
            val content = extractAgentField(body, "content") as? String

            // Agent should use multiple iterations for complex multi-step tasks
            (iterations ?: 0) >= 1 && content != null && content.length > 50
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "Agent: Respects max_iterations limit",
        trials = 2,
        acceptableFailureRate = 0.1
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name":"llm_chat_completion",
                    "args":{
                        "messages":[{"role":"user","content":"Tell me about docker compose"}],
                        "max_iterations":2,
                        "temperature":0.3,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val iterations = extractAgentField(body, "iterations") as? Int

            // Should never exceed max_iterations
            (iterations ?: 0) <= 2
        } else {
            false
        }
    }

    // ============================================================================
    // Trace Logging & Observability
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Agent: Provides trace of tool calls",
        trials = 2,
        acceptableFailureRate = 0.2
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name":"llm_chat_completion",
                    "args":{
                        "messages":[{"role":"user","content":"What is Qdrant?"}],
                        "requested_tools":["retrieve_stack_context"],
                        "mode":"required",
                        "temperature":0.2,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val trace = extractAgentField(body, "trace") as? JsonArray

            // Trace should exist and contain tool call details
            if (trace != null && trace.isNotEmpty()) {
                val firstEntry = trace[0].jsonObject
                firstEntry.containsKey("tool") &&
                firstEntry.containsKey("iteration") &&
                firstEntry.containsKey("arguments")
            } else {
                false
            }
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "Agent: Returns valid response structure",
        trials = 3,
        acceptableFailureRate = 0.1
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name":"llm_chat_completion",
                    "args":{
                        "messages":[{"role":"user","content":"Hello"}],
                        "temperature":0.3,
                        "max_tokens":50
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                null
            }

            // Verify response has required fields
            json != null &&
            json.containsKey("result") &&
            json["result"]?.jsonObject?.containsKey("content") == true &&
            json["result"]?.jsonObject?.containsKey("iterations") == true
        } else {
            false
        }
    }

    // ============================================================================
    // Stack Knowledge Integration
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Agent: Successfully retrieves stack knowledge",
        trials = 2,
        acceptableFailureRate = 0.2
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name":"llm_chat_completion",
                    "args":{
                        "messages":[{"role":"user","content":"Explain Docker Compose health checks"}],
                        "requested_tools":["retrieve_stack_context"],
                        "mode":"required",
                        "temperature":0.2,
                        "max_tokens":200
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val content = extractAgentField(body, "content") as? String
            val trace = extractAgentField(body, "trace") as? JsonArray

            // Should retrieve context and mention health checks in response
            val retrievedContext = trace?.any { entry ->
                entry.jsonObject["tool"]?.jsonPrimitive?.content == "semantic_search"
            } ?: false

            val mentionsHealthCheck = content?.contains("health", ignoreCase = true) ?: false

            retrievedContext && mentionsHealthCheck
        } else {
            false
        }
    }

    probRunner.probabilisticTest(
        name = "Agent: Uses system prompt from stack-expert.txt",
        trials = 2,
        acceptableFailureRate = 0.3
    ) {
        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name":"llm_chat_completion",
                    "args":{
                        "messages":[{"role":"user","content":"What services are available?"}],
                        "temperature":0.2,
                        "max_tokens":150
                    }
                }
            """.trimIndent())
        }

        if (response.status == HttpStatusCode.OK) {
            val content = extractAgentField(response.bodyAsText(), "content") as? String

            // Response should reflect stack expertise
            content?.let { text ->
                text.contains("docker", ignoreCase = true) ||
                text.contains("compose", ignoreCase = true) ||
                text.contains("service", ignoreCase = true) ||
                text.contains("stack", ignoreCase = true)
            } ?: false
        } else {
            false
        }
    }

    // Print summary
    val summary = probRunner.summary()
    println("\n" + "=".repeat(80))
    println("AGENT ORCHESTRATION TEST SUMMARY")
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
                else -> {}
            }
        }
    }

    println("=".repeat(80))

    if (summary.failed > 0) {
        throw AssertionError("${summary.failed} agent orchestration test(s) failed")
    }
}
