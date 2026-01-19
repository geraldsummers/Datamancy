package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*

suspend fun TestRunner.llmTests() = suite("LLM Integration Tests") {
    test("LLM chat completion generates response") {
        val result = client.callTool("llm_chat_completion", mapOf(
            "model" to "qwen2.5-7b-instruct",
            "messages" to listOf(
                mapOf("role" to "user", "content" to "What is 2+2? Answer with just the number.")
            ),
            "temperature" to 0.1,
            "max_tokens" to 10
        ))

        require(result is ToolResult.Success, "LLM completion failed: ${(result as? ToolResult.Error)?.message}")
        val output = (result as ToolResult.Success).output

        // Check for error responses (prevent false positives from "401" containing "4")
        require(!output.contains("\"error\"", ignoreCase = false)) {
            "LLM call returned error response: $output"
        }

        output shouldContain "4"
    }

    test("LLM completion handles system prompts") {
        val result = client.callTool("llm_chat_completion", mapOf(
            "model" to "qwen2.5-7b-instruct",
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a Kotlin expert. Answer with code only."),
                mapOf("role" to "user", "content" to "Write a function to add two numbers")
            ),
            "temperature" to 0.7,
            "max_tokens" to 100
        ))

        require(result is ToolResult.Success, "LLM completion failed")
        val output = (result as ToolResult.Success).output
        require(output.contains("fun") || output.contains("function"), "Expected function definition, got: $output")
    }

    test("LLM embed text returns vector") {
        val result = client.callTool("llm_embed_text", mapOf(
            "text" to "This is a test sentence for embedding.",
            "model" to "bge-base-en-v1.5"
        ))

        require(result is ToolResult.Success, "Embedding failed")
        val output = (result as ToolResult.Success).output
        output shouldContain "["
        output shouldContain "]"

        // Vector should have ~768 dimensions for bge-base-en-v1.5
        val dimensions = output.split(",").size
        dimensions shouldBeGreaterThan 700
    }
}
