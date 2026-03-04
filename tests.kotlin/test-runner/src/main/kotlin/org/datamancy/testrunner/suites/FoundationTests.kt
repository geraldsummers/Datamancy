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
}
