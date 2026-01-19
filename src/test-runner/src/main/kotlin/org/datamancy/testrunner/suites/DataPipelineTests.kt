package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.dataPipelineTests() = suite("Data Pipeline Tests") {
    test("Validate legal docs source URLs (dry-run)") {
        val result = client.dryRunFetch("legal_docs")
        result.success shouldBe true

        // Parse JSON response to check details
        val json = Json.parseToJsonElement(result.message).jsonObject
        val checksArray = json["checks"]?.jsonArray ?: emptyList()

        // Count passed checks
        val passedCount = checksArray.count {
            it.jsonObject["passed"]?.jsonPrimitive?.boolean == true
        }
        val totalCount = checksArray.size

        println("\n      Dry-run: $passedCount/$totalCount URL checks passed")

        // At least 80% should pass (allows for minor issues like SA 403)
        require(passedCount >= (totalCount * 0.8).toInt()) {
            "Too many URL validation failures: $passedCount/$totalCount passed"
        }
    }

    test("Search executes without errors") {
        // Test search functionality (independent of fetch/index)
        val result = client.search(
            query = "test query",
            collections = listOf("*"),
            limit = 5
        )

        result.success shouldBe true
        println("      ✓ Search service operational")
    }
}
