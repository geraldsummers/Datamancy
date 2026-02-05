package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*


suspend fun TestRunner.microserviceTests() = suite("Pipeline Tests") {

    test("Pipeline: Health check") {
        val response = client.getRawResponse("http://pipeline:8090/health")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val status = json["status"]?.jsonPrimitive?.content
        val message = json["message"]?.jsonPrimitive?.content

        status shouldBe "ok"
        message?.shouldContain("Pipeline")
        println("      ✓ Pipeline service healthy: $status - $message")
    }

    test("Pipeline: List data sources") {
        val response = client.getRawResponse("http://pipeline:8090/sources")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val sources = json["sources"]?.jsonArray

        require(sources != null) { "sources array missing" }
        require(sources.size == 8) { "Expected 8 sources, got ${sources.size}" }

        val sourceNames = sources.map { it.jsonObject["id"]?.jsonPrimitive?.content }
        val expectedSources = listOf("rss", "cve", "torrents", "wikipedia", "australian_laws", "linux_docs", "debian_wiki", "arch_wiki")

        expectedSources.forEach { expected ->
            require(expected in sourceNames) { "Missing source: $expected" }
        }

        println("      ✓ Found ${sources.size} data sources: ${sourceNames.joinToString()}")
    }

    test("Pipeline: Check scheduler status") {
        val response = client.getRawResponse("http://pipeline:8090/status")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val uptime = json["uptime"]?.jsonPrimitive?.longOrNull
        val sources = json["sources"]?.jsonArray

        require(uptime != null) { "uptime missing" }
        require(sources != null) { "sources array missing" }
        require(sources.size == 8) { "Expected 8 sources in status" }

        
        sources.forEach { sourceElement ->
            val source = sourceElement.jsonObject
            require(source["source"]?.jsonPrimitive?.content != null) { "source name missing" }
            require(source["enabled"]?.jsonPrimitive?.booleanOrNull != null) { "enabled field missing" }
            require(source["totalProcessed"]?.jsonPrimitive?.longOrNull != null) { "totalProcessed missing" }
            require(source["status"]?.jsonPrimitive?.content != null) { "status missing" }
        }

        println("      ✓ Pipeline uptime: ${uptime}s, ${sources.size} sources monitored")

        
        sources.forEach { sourceElement ->
            val source = sourceElement.jsonObject
            val name = source["source"]?.jsonPrimitive?.content
            val processed = source["totalProcessed"]?.jsonPrimitive?.long
            val status = source["status"]?.jsonPrimitive?.content
            println("         - $name: $processed processed, status: $status")
        }
    }
}