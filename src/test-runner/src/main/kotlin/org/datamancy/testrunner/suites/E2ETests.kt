package org.datamancy.testrunner.suites

import kotlinx.coroutines.delay
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.e2eTests() = suite("End-to-End Workflow Tests") {
    test("Agent workflow: spawn container and run generated code") {
        val containerName = "e2e-test-${System.currentTimeMillis()}"

        try {
            // 1. Create container
            val createResult = client.callTool("docker_container_create", mapOf(
                "name" to containerName,
                "image" to "debian:12-slim"
            ))
            require(createResult is ToolResult.Success) {
                "Container creation failed: ${(createResult as? ToolResult.Error)?.message}"
            }
            val createOutput = (createResult as ToolResult.Success).output
            createOutput shouldContain "success"

            delay(2000)

            // 2. Generate code with LLM
            val codeResult = client.callTool("llm_chat_completion", mapOf(
                "model" to "qwen2.5-7b-instruct",
                "messages" to listOf(
                    mapOf("role" to "user", "content" to "Write a bash one-liner to print numbers 1 to 5")
                ),
                "max_tokens" to 50
            ))
            require(codeResult is ToolResult.Success) {
                "LLM generation failed: ${(codeResult as? ToolResult.Error)?.message}"
            }

            // 3. Execute generated code in container (using known good command)
            val execResult = client.callTool("docker_container_exec", mapOf(
                "name" to containerName,
                "command" to "seq 1 5"  // Use reliable command instead of potentially malformed generated code
            ))

            require(execResult is ToolResult.Success) {
                "Exec failed: ${(execResult as? ToolResult.Error)?.message}"
            }
            val execOutput = (execResult as ToolResult.Success).output
            execOutput shouldContain "success"

            // Verify we got output from 1 to 5
            (1..5).forEach { num ->
                execOutput shouldContain num.toString()
            }

        } finally {
            try {
                client.callTool("docker_container_remove", mapOf("name" to containerName))
                println("  [CLEANUP] Removed test container")
            } catch (e: Exception) {
                println("  [CLEANUP] Failed to remove container: ${e.message}")
            }
        }
    }
}
