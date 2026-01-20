package org.datamancy.testrunner.suites

import kotlinx.coroutines.delay
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.dockerTests() = suite("Docker-in-Docker Tests") {
    val testContainerName = "test-${System.currentTimeMillis()}"

    try {
        test("Create Debian container with SSH") {
            val result = client.callTool("docker_container_create", mapOf(
                "name" to testContainerName,
                "image" to "debian:12-slim"
            ))

            require(result is ToolResult.Success) {
                "Container creation failed: ${(result as? ToolResult.Error)?.message}"
            }
            val output = (result as ToolResult.Success).output
            output shouldContain "success"
            output shouldContain "ssh_host"
            output shouldContain "ssh_key_path"
        }

        delay(2000) // Wait for container to be ready

        test("List containers includes new container") {
            val result = client.callTool("docker_container_list", emptyMap())
            require(result is ToolResult.Success) {
                "Container list failed: ${(result as? ToolResult.Error)?.message}"
            }
            val output = (result as ToolResult.Success).output
            output shouldContain testContainerName
        }

        test("Execute command in container") {
            val result = client.callTool("docker_container_exec", mapOf(
                "name" to testContainerName,
                "command" to "echo 'Hello from container' && uname -s"
            ))

            require(result is ToolResult.Success) {
                "Exec failed: ${(result as? ToolResult.Error)?.message}"
            }
            val output = (result as ToolResult.Success).output
            output shouldContain "success"
            output shouldContain "Hello from container"
            output shouldContain "Linux"
        }

        test("Retrieve SSH key for container") {
            val result = client.callTool("docker_ssh_key_get", mapOf(
                "containerName" to testContainerName
            ))

            require(result is ToolResult.Success, "SSH key retrieval failed")
            val output = (result as ToolResult.Success).output
            output shouldContain "success"
            output shouldContain "BEGIN OPENSSH PRIVATE KEY"
        }

    } finally {
        // Cleanup
        try {
            client.callTool("docker_container_remove", mapOf("name" to testContainerName))
            println("  [CLEANUP] Removed test container")
        } catch (e: Exception) {
            println("  [CLEANUP] Failed to remove container: ${e.message}")
        }
    }
}
