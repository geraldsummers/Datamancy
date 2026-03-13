package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

suspend fun TestRunner.llmTests() = suite("LLM Integration Tests") {
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

    suspend fun waitForLlmReady(maxAttempts: Int = 24, delayMs: Long = 5000) {
        repeat(maxAttempts) { attempt ->
            val health = client.getRawResponse("${env.endpoints.liteLLM}/health")
            val models = client.getRawResponse("${env.endpoints.liteLLM}/v1/models")
            val modelsBody = models.bodyAsText()
            val healthy = health.status.value in 200..299
            val modelAvailable = models.status.value in 200..299 && modelsBody.contains("qwen2.5-7b-instruct")

            if (healthy && modelAvailable) {
                return
            }

            if (attempt < maxAttempts - 1) {
                delay(delayMs)
            }
        }
    }

    suspend fun callLlmWithRetry(payload: Map<String, Any>, attempts: Int = 24, delayMs: Long = 5000): ToolResult {
        var last: ToolResult = ToolResult.Error(0, "LLM call did not execute")
        repeat(attempts) { index ->
            val result = client.callTool("llm_chat_completion", payload)
            if (result is ToolResult.Success) {
                val content = result.extractAgentContent()
                if (!isLlmErrorPayload(content)) {
                    return result
                }
            } else if (result is ToolResult.Error && result.statusCode !in 500..599) {
                return result
            }
            last = result
            if (index < attempts - 1) {
                delay(delayMs)
            }
        }
        return last
    }

    test("LLM chat completion generates response") {
        waitForLlmReady()
        val result = callLlmWithRetry(mapOf(
            "model" to "qwen2.5-7b-instruct",
            "messages" to listOf(
                mapOf("role" to "user", "content" to "What is 2+2? Answer with just the number.")
            ),
            "temperature" to 0.1,
            "max_tokens" to 10
        ))

        require(result is ToolResult.Success, "LLM completion failed: ${(result as? ToolResult.Error)?.message}")
        val output = (result as ToolResult.Success).extractAgentContent()


        require(!isLlmErrorPayload(output)) {
            "LLM call returned error response: $output"
        }

        output shouldContain "4"
    }

    test("LLM completion handles system prompts") {
        waitForLlmReady()
        val result = callLlmWithRetry(mapOf(
            "model" to "qwen2.5-7b-instruct",
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a Kotlin expert. Answer with code only."),
                mapOf("role" to "user", "content" to "Write a function to add two numbers")
            ),
            "temperature" to 0.7,
            "max_tokens" to 100
        ))

        require(result is ToolResult.Success, "LLM completion failed")
        val output = (result as ToolResult.Success).extractAgentContent()
        require(!isLlmErrorPayload(output)) { "LLM call returned error response: $output" }
        require(output.contains("fun") || output.contains("function"), "Expected function definition, got: $output")
    }

    test("LLM embed text returns vector") {
        val result = client.callTool("llm_embed_text", mapOf(
            "text" to "This is a test sentence for embedding.",
            "model" to "bge-m3"
        ))

        require(result is ToolResult.Success, "Embedding failed")
        val output = (result as ToolResult.Success).output
        output shouldContain "["
        output shouldContain "]"

        
        val dimensions = output.split(",").size
        dimensions shouldBeGreaterThan 1000
    }
}
