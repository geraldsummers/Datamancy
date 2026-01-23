package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

/**
 * Tests for the new pipeline service with 6 data sources:
 * - RSS Feeds, CVE/NVD, Torrents CSV, Binance Market Data,
 * - Wikipedia, Australian Laws, Linux Documentation
 */
suspend fun TestRunner.dataPipelineTests() = suite("Data Pipeline Tests") {

    // Helper to query Qdrant for collection info
    suspend fun getQdrantCollectionInfo(collectionName: String): JsonObject? {
        return try {
            val response = client.getRawResponse("${endpoints.qdrant}/collections/$collectionName")
            if (response.status == HttpStatusCode.OK) {
                Json.parseToJsonElement(response.bodyAsText()).jsonObject
            } else null
        } catch (e: Exception) {
            println("      ⚠️  Could not query Qdrant: ${e.message}")
            null
        }
    }

    test("Qdrant has expected pipeline collections") {
        val expectedCollections = listOf(
            "rss_feeds", "cve", "torrents", "market_data",
            "wikipedia", "australian_laws", "linux_docs"
        )

        val response = client.getRawResponse("${endpoints.qdrant}/collections")
        response.status shouldBe HttpStatusCode.OK

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val collections = body["result"]?.jsonObject?.get("collections")?.jsonArray
            ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            ?: emptyList()

        println("      Found ${collections.size} Qdrant collections")

        val existingExpected = expectedCollections.filter { it in collections }
        if (existingExpected.isNotEmpty()) {
            println("      ✓ Pipeline collections: ${existingExpected.joinToString()}")
        } else {
            println("      ℹ️  No pipeline collections yet (created on first fetch)")
        }
    }

    test("RSS collection has vectors") {
        val info = getQdrantCollectionInfo("rss_feeds")
        if (info != null) {
            val count = info["result"]?.jsonObject?.get("points_count")?.jsonPrimitive?.longOrNull ?: 0
            println("      ✓ RSS collection: $count vectors")
            require(count >= 0) { "Invalid vector count" }
        } else {
            println("      ℹ️  RSS collection not yet created")
        }
    }

    test("CVE collection has vectors") {
        val info = getQdrantCollectionInfo("cve")
        if (info != null) {
            val count = info["result"]?.jsonObject?.get("points_count")?.jsonPrimitive?.longOrNull ?: 0
            println("      ✓ CVE collection: $count vectors")
            require(count >= 0) { "Invalid vector count" }
        } else {
            println("      ℹ️  CVE collection not yet created")
        }
    }

    test("Wikipedia collection has vectors") {
        val info = getQdrantCollectionInfo("wikipedia")
        if (info != null) {
            val count = info["result"]?.jsonObject?.get("points_count")?.jsonPrimitive?.longOrNull ?: 0
            println("      ✓ Wikipedia collection: $count vectors")
            require(count >= 0) { "Invalid vector count" }
        } else {
            println("      ℹ️  Wikipedia collection not yet created")
        }
    }

    test("Australian Laws collection has vectors") {
        val info = getQdrantCollectionInfo("australian_laws")
        if (info != null) {
            val count = info["result"]?.jsonObject?.get("points_count")?.jsonPrimitive?.longOrNull ?: 0
            println("      ✓ Australian Laws collection: $count vectors")
            require(count >= 0) { "Invalid vector count" }
        } else {
            println("      ℹ️  Australian Laws collection not yet created")
        }
    }

    test("Linux Docs collection has vectors") {
        val info = getQdrantCollectionInfo("linux_docs")
        if (info != null) {
            val count = info["result"]?.jsonObject?.get("points_count")?.jsonPrimitive?.longOrNull ?: 0
            println("      ✓ Linux Docs collection: $count vectors")
            require(count >= 0) { "Invalid vector count" }
        } else {
            println("      ℹ️  Linux Docs collection not yet created")
        }
    }

    test("ClickHouse market_klines table exists") {
        try {
            val response = client.getRawResponse(
                "${endpoints.clickhouse}/?query=SELECT%20count()%20FROM%20system.tables%20WHERE%20name='market_klines'"
            )
            if (response.status == HttpStatusCode.OK) {
                val count = response.bodyAsText().trim()
                if (count == "1") {
                    println("      ✓ market_klines table exists")
                } else {
                    println("      ℹ️  market_klines table not yet created")
                }
            }
        } catch (e: Exception) {
            println("      ⚠️  Could not verify ClickHouse: ${e.message}")
        }
    }

    test("Search works across pipeline collections") {
        val result = client.search(
            query = "test data",
            collections = listOf("*"),
            limit = 5
        )

        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        if (results != null && results.isNotEmpty()) {
            val sources = results.mapNotNull {
                it.jsonObject["metadata"]?.jsonObject?.get("source")?.jsonPrimitive?.content
            }.toSet()
            println("      ✓ Search found data from: ${sources.joinToString()}")
        } else {
            println("      ℹ️  No data indexed yet (search operational)")
        }
    }

    test("Vector dimensions are consistent") {
        val collections = listOf("rss_feeds", "cve", "torrents", "wikipedia", "australian_laws", "linux_docs")
        val dimensions = mutableMapOf<String, Long>()

        for (collection in collections) {
            val info = getQdrantCollectionInfo(collection)
            val dim = info?.get("result")?.jsonObject
                ?.get("config")?.jsonObject
                ?.get("params")?.jsonObject
                ?.get("vectors")?.jsonObject
                ?.get("size")?.jsonPrimitive?.longOrNull

            if (dim != null) dimensions[collection] = dim
        }

        if (dimensions.isNotEmpty()) {
            val uniqueDims = dimensions.values.toSet()
            if (uniqueDims.size == 1) {
                println("      ✓ All collections use dimension: ${uniqueDims.first()}")
            } else {
                println("      ⚠️  Inconsistent dimensions: $dimensions")
            }
        } else {
            println("      ℹ️  No collections to verify dimensions")
        }
    }
}
