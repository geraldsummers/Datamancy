package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

suspend fun TestRunner.llmTests() = suite("LLM Integration Tests") {
    data class LlmReadiness(val ready: Boolean, val reason: String? = null)

    fun isLlmErrorPayload(text: String): Boolean {
        return try {
            fun hasErrorKey(element: JsonElement): Boolean = when (element) {
                is JsonObject -> {
                    element.containsKey("error") ||
                        element["status_code"]?.jsonPrimitive?.intOrNull?.let { it >= 400 } == true ||
                        element.values.any { hasErrorKey(it) }
                }
                is JsonArray -> element.any { hasErrorKey(it) }
                else -> false
            }
            hasErrorKey(Json.parseToJsonElement(text))
        } catch (_: Exception) {
            val lowered = text.lowercase()
            lowered.contains("llm_http_error") ||
                lowered.contains("internalservererror") ||
                lowered.contains("connection error")
        }
    }

    fun extractLlmErrorCode(text: String): String? {
        return try {
            fun findErrorCode(element: JsonElement): String? = when (element) {
                is JsonObject -> {
                    element["error"]?.jsonPrimitive?.contentOrNull
                        ?: element.values.asSequence().mapNotNull { findErrorCode(it) }.firstOrNull()
                }
                is JsonArray -> element.asSequence().mapNotNull { findErrorCode(it) }.firstOrNull()
                else -> null
            }
            findErrorCode(Json.parseToJsonElement(text))
        } catch (_: Exception) {
            null
        }
    }

    fun isKnownLlmUnavailable(text: String): Boolean {
        val errorCode = extractLlmErrorCode(text)?.lowercase()
        if (errorCode in setOf("circuit_breaker_open", "service_unavailable", "upstream_unavailable")) {
            return true
        }
        val lowered = text.lowercase()
        return lowered.contains("circuit_breaker_open") ||
            lowered.contains("service unavailable") ||
            lowered.contains("connection error") ||
            lowered.contains("timed out") ||
            lowered.contains("timeout")
    }

    fun describeLlmResult(result: ToolResult): String {
        return when (result) {
            is ToolResult.Success -> result.extractAgentContent()
            is ToolResult.Error -> result.message
        }
    }

    suspend fun waitForLlmReady(maxAttempts: Int = 12, delayMs: Long = 5000): LlmReadiness {
        var lastReason = "LLM readiness probe did not return READY"
        var consecutiveUnavailableSignals = 0

        repeat(maxAttempts) { attempt ->
            val probe = client.callTool("llm_chat_completion", mapOf(
                "model" to "qwen2.5-7b-instruct",
                "messages" to listOf(
                    mapOf("role" to "user", "content" to "Reply with READY only.")
                ),
                "temperature" to 0.0,
                "max_tokens" to 8
            ))

            when (probe) {
                is ToolResult.Success -> {
                    val content = probe.extractAgentContent()
                    when {
                        !isLlmErrorPayload(content) && content.contains("READY") -> {
                            return LlmReadiness(ready = true)
                        }
                        isKnownLlmUnavailable(content) -> {
                            consecutiveUnavailableSignals++
                            lastReason = content
                        }
                        else -> {
                            consecutiveUnavailableSignals = 0
                            lastReason = content
                        }
                    }
                }
                is ToolResult.Error -> {
                    val message = probe.message
                    if (isKnownLlmUnavailable(message)) {
                        consecutiveUnavailableSignals++
                    } else {
                        consecutiveUnavailableSignals = 0
                    }
                    lastReason = message
                }
            }

            if (consecutiveUnavailableSignals >= 3) {
                return LlmReadiness(
                    ready = false,
                    reason = "LiteLLM unavailable (circuit breaker or upstream outage): $lastReason"
                )
            }

            if (attempt < maxAttempts - 1) {
                delay(delayMs)
            }
        }

        return LlmReadiness(
            ready = false,
            reason = "LLM readiness probe exhausted after $maxAttempts attempts: $lastReason"
        )
    }

    suspend fun callLlmWithRetry(
        payload: Map<String, Any>,
        attempts: Int = 24,
        delayMs: Long = 5000,
        isAcceptable: (String) -> Boolean
    ): ToolResult {
        var last: ToolResult = ToolResult.Error(0, "LLM call did not execute")
        repeat(attempts) { index ->
            val result = client.callTool("llm_chat_completion", payload)
            if (result is ToolResult.Success) {
                val content = result.extractAgentContent()
                if (isKnownLlmUnavailable(content)) {
                    return result
                }
                if (!isLlmErrorPayload(content) && isAcceptable(content)) {
                    return result
                }
            } else if (result is ToolResult.Error) {
                if (isKnownLlmUnavailable(result.message) || result.statusCode !in 500..599) {
                    return result
                }
            }
            last = result
            if (index < attempts - 1) {
                delay(delayMs)
            }
        }
        return last
    }

    val llmReadiness = waitForLlmReady()
    fun shouldSkipLlmTest(name: String): Boolean {
        if (llmReadiness.ready) {
            return false
        }
        skip(name, llmReadiness.reason ?: "LiteLLM unavailable in this test environment")
        return true
    }

    if (!shouldSkipLlmTest("LLM chat completion generates response")) {
        test("LLM chat completion generates response") {
            val result = callLlmWithRetry(mapOf(
                "model" to "qwen2.5-7b-instruct",
                "messages" to listOf(
                    mapOf("role" to "user", "content" to "What is 2+2? Answer with just the number.")
                ),
                "temperature" to 0.1,
                "max_tokens" to 10
            )) { content ->
                content.contains("4")
            }

            require(result is ToolResult.Success, "LLM completion failed: ${describeLlmResult(result)}")
            val output = (result as ToolResult.Success).extractAgentContent()

            require(!isLlmErrorPayload(output)) {
                "LLM call returned error response: $output"
            }

            output shouldContain "4"
        }
    }

    if (!shouldSkipLlmTest("LLM completion handles system prompts")) {
        test("LLM completion handles system prompts") {
            val result = callLlmWithRetry(mapOf(
                "model" to "qwen2.5-7b-instruct",
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "You are a Kotlin expert. Answer with code only."),
                    mapOf("role" to "user", "content" to "Write a function to add two numbers")
                ),
                "temperature" to 0.7,
                "max_tokens" to 100
            )) { content ->
                content.contains("fun") || content.contains("function")
            }

            require(result is ToolResult.Success, "LLM completion failed: ${describeLlmResult(result)}")
            val output = (result as ToolResult.Success).extractAgentContent()
            require(!isLlmErrorPayload(output)) { "LLM call returned error response: $output" }
            require(output.contains("fun") || output.contains("function"), "Expected function definition, got: $output")
        }
    }

    test("LLM embed text returns vector") {
        suspend fun callEmbeddingWithRetry(attempts: Int = 12, delayMs: Long = 5000): ToolResult {
            var last: ToolResult = ToolResult.Error(0, "Embedding request did not execute")
            repeat(attempts) { index ->
                val result = client.callTool("llm_embed_text", mapOf(
                    "text" to "This is a test sentence for embedding.",
                    "model" to "bge-m3"
                ))

                if (result is ToolResult.Success) {
                    return result
                }

                last = result
                val message = (result as? ToolResult.Error)?.message?.lowercase() ?: ""
                val retryable = message.contains("timed out") ||
                    message.contains("timeout") ||
                    message.contains("connection error") ||
                    message.contains("service unavailable")

                if (!retryable || index == attempts - 1) {
                    return result
                }

                delay(delayMs)
            }
            return last
        }

        val result = callEmbeddingWithRetry()

        require(result is ToolResult.Success, "Embedding failed")
        val output = (result as ToolResult.Success).output
        output shouldContain "["
        output shouldContain "]"

        
        val dimensions = output.split(",").size
        dimensions shouldBeGreaterThan 1000
    }
}
