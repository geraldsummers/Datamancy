package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.foundationTests() = suite("Foundation Tests") {
    test("Agent tool server is healthy") {
        val health = client.healthCheck("agent-tool-server")
        health.healthy shouldBe true
    }

    test("Agent tool server lists available tools") {
        val response = client.getRawResponse("${env.endpoints.agentToolServer}/tools")
        val body = response.bodyAsText()
        body shouldContain "tools"
        body shouldContain "docker_container_create"
        body shouldContain "llm_chat_completion"
    }

    test("Agent tool server OpenWebUI schema is valid") {
        val response = client.getRawResponse("${env.endpoints.agentToolServer}/tools.json")
        val body = response.bodyAsText()
        body shouldContain "\"format\""
        body shouldContain "\"tools\""
        body shouldContain "\"name\""
        body shouldContain "\"description\""
    }

    test("Search service is healthy") {
        val health = client.healthCheck("search-service")
        health.healthy shouldBe true
    }

    test("caddy-ca-export init container completed successfully") {
        val result = DockerCli.run(
            "inspect", "-f", "{{.State.Status}}|{{.State.ExitCode}}", "caddy-ca-export"
        )
        require(result.exitCode == 0) {
            "Unable to inspect caddy-ca-export container: ${result.output}"
        }

        val parts = result.output.split("|")
        val status = parts.getOrNull(0)?.trim().orEmpty()
        val exitCode = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
        require((status == "exited" || status == "running") && exitCode == 0) {
            "caddy-ca-export should succeed (exited 0), got status='$status' exitCode=$exitCode"
        }
        println("      ✓ caddy-ca-export completed successfully")
    }
}
