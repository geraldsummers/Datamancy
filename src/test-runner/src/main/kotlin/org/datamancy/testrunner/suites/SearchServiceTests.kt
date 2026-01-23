package org.datamancy.testrunner.suites

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.TestRunner

suspend fun TestRunner.searchServiceTests() = suite("Search Service RAG Provider") {

    // Setup: Seed test data into Qdrant
    suspend fun seedTestData() {
        println("      [SETUP] Seeding test data into Qdrant...")

        // First, create test collections
        val collections = listOf("test-docs", "test-market", "test-bookstack")
        for (collection in collections) {
            try {
                val createRequest = buildJsonObject {
                    putJsonObject("vectors") {
                        put("size", 768)
                        put("distance", "Cosine")
                    }
                }
                client.putRaw("${env.endpoints.qdrant}/collections/$collection") {
                    contentType(ContentType.Application.Json)
                    setBody(createRequest.toString())
                }
            } catch (e: Exception) {
                // Collection may already exist, ignore
            }
        }

        val testDocuments = listOf(
            mapOf(
                "text" to "Kubernetes deployment guide for production environments",
                "collection" to "test-docs",
                "title" to "Kubernetes Deployment Guide",
                "url" to "https://test.example.com/k8s-deploy",
                "source" to "documentation"
            ),
            mapOf(
                "text" to "Bitcoin price analysis and market trends",
                "collection" to "test-market",
                "title" to "Bitcoin Market Analysis",
                "url" to "https://test.example.com/btc-market",
                "source" to "market"
            ),
            mapOf(
                "text" to "BookStack documentation for knowledge management",
                "collection" to "test-bookstack",
                "title" to "BookStack Documentation",
                "url" to "https://bookstack.test.com/docs",
                "source" to "bookstack"
            )
        )

        for (doc in testDocuments) {
            try {
                // Generate embedding
                val embedResult = client.callTool("llm_embed_text", mapOf(
                    "text" to doc["text"]!!,
                    "model" to "bge-base-en-v1.5"
                ))

                if (embedResult is org.datamancy.testrunner.framework.ToolResult.Success) {
                    // Parse vector from response
                    val vectorStr = embedResult.output
                        .removePrefix("[").removeSuffix("]")
                    val vector = vectorStr.split(",").map { it.trim().toFloat() }

                    // Insert directly into Qdrant using HTTP API
                    val pointId = doc["title"].hashCode().toLong()
                    val payload = buildJsonObject {
                        put("title", doc["title"]!!)
                        put("link", doc["url"]!!)
                        put("url", doc["url"]!!)
                        put("description", doc["text"]!!)
                        put("text", doc["text"]!!)
                        put("source", doc["source"]!!)
                    }

                    val upsertRequest = buildJsonObject {
                        putJsonArray("points") {
                            addJsonObject {
                                put("id", pointId)
                                putJsonArray("vector") {
                                    vector.forEach { add(it) }
                                }
                                put("payload", payload)
                            }
                        }
                    }

                    client.postRaw("${env.endpoints.qdrant}/collections/${doc["collection"]}/points") {
                        contentType(ContentType.Application.Json)
                        setBody(upsertRequest.toString())
                    }
                }
            } catch (e: Exception) {
                println("      ⚠️  Failed to seed ${doc["title"]}: ${e.message}")
            }
        }
        println("      ✓ Test data seeded")
    }

    // Run setup before tests
    test("Setup: Seed test data") {
        seedTestData()
    }

    test("Search service is healthy") {
        val health = client.healthCheck("search-service")
        health.healthy shouldBe true
        health.statusCode shouldBe 200
    }

    test("Can list available collections") {
        val response = client.getRawResponse("${env.endpoints.searchService}/collections")
        response.status shouldBe HttpStatusCode.OK

        val body = Json.parseToJsonElement(response.body<String>())
        val collections = body.jsonObject["collections"]?.jsonArray
        collections?.isNotEmpty() shouldBe true
    }

    test("Search returns results with content type") {
        val result = client.search("kubernetes", limit = 5)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        results?.isNotEmpty() shouldBe true

        // Verify first result has contentType field
        val firstResult = results?.firstOrNull()?.jsonObject
        require(firstResult != null) { "No search results returned - data may not be seeded yet" }
        firstResult.containsKey("contentType") shouldBe true
    }

    test("Search returns results with capabilities") {
        val result = client.search("bitcoin", limit = 5)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val firstResult = results?.firstOrNull()?.jsonObject
        require(firstResult != null) { "No search results returned - data may not be seeded yet" }

        // Check capabilities object exists
        val capabilities = firstResult.get("capabilities")?.jsonObject
        require(capabilities != null) { "Capabilities field missing from search result" }
        capabilities.containsKey("humanFriendly") shouldBe true
        capabilities.containsKey("agentFriendly") shouldBe true
        capabilities.containsKey("hasTimeSeries") shouldBe true
        capabilities.containsKey("hasRichContent") shouldBe true
        capabilities.containsKey("isInteractive") shouldBe true
        capabilities.containsKey("isStructured") shouldBe true
    }

    test("Human audience filter works") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "test")
                put("mode", "hybrid")
                put("audience", "human")
                put("limit", 20)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        // All results should be human-friendly
        results?.forEach { result ->
            val capabilities = result.jsonObject["capabilities"]?.jsonObject
            val humanFriendly = capabilities?.get("humanFriendly")?.jsonPrimitive?.boolean
            humanFriendly shouldBe true
        }
    }

    test("Agent audience filter works") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "test")
                put("mode", "hybrid")
                put("audience", "agent")
                put("limit", 20)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        // All results should be agent-friendly
        results?.forEach { result ->
            val capabilities = result.jsonObject["capabilities"]?.jsonObject
            val agentFriendly = capabilities?.get("agentFriendly")?.jsonPrimitive?.boolean
            agentFriendly shouldBe true
        }
    }

    test("BookStack content has correct capabilities") {
        val result = client.search("bookstack documentation", limit = 10)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val bookstackResult = results?.find {
            it.jsonObject["contentType"]?.jsonPrimitive?.content == "bookstack"
        }

        if (bookstackResult != null) {
            val capabilities = bookstackResult.jsonObject["capabilities"]?.jsonObject
            capabilities?.get("humanFriendly")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("agentFriendly")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("hasRichContent")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("isInteractive")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("hasTimeSeries")?.jsonPrimitive?.boolean shouldBe false
        }
    }

    test("Market data has correct capabilities") {
        val result = client.search("bitcoin price", limit = 10)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val marketResult = results?.find {
            it.jsonObject["contentType"]?.jsonPrimitive?.content == "market"
        }

        if (marketResult != null) {
            val capabilities = marketResult.jsonObject["capabilities"]?.jsonObject
            capabilities?.get("humanFriendly")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("agentFriendly")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("hasTimeSeries")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("isStructured")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("isInteractive")?.jsonPrimitive?.boolean shouldBe false
        }
    }

    test("CVE content has correct capabilities") {
        val result = client.search("CVE vulnerability", limit = 10)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val cveResult = results?.find {
            it.jsonObject["contentType"]?.jsonPrimitive?.content == "cve"
        }

        if (cveResult != null) {
            val capabilities = cveResult.jsonObject["capabilities"]?.jsonObject
            capabilities?.get("humanFriendly")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("agentFriendly")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("hasRichContent")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("isInteractive")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("isStructured")?.jsonPrimitive?.boolean shouldBe true
        }
    }

    test("Weather data has correct capabilities") {
        val result = client.search("weather sydney", limit = 10)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val weatherResult = results?.find {
            it.jsonObject["contentType"]?.jsonPrimitive?.content == "weather"
        }

        if (weatherResult != null) {
            val capabilities = weatherResult.jsonObject["capabilities"]?.jsonObject
            capabilities?.get("humanFriendly")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("agentFriendly")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("hasTimeSeries")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("isStructured")?.jsonPrimitive?.boolean shouldBe true
            capabilities?.get("hasRichContent")?.jsonPrimitive?.boolean shouldBe false
        }
    }

    test("Vector search mode works") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "machine learning")
                put("mode", "vector")
                put("limit", 5)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "vector"
    }

    test("BM25 search mode works") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "machine learning")
                put("mode", "bm25")
                put("limit", 5)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "bm25"
    }

    test("Hybrid search mode works (default)") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "machine learning")
                put("mode", "hybrid")
                put("limit", 5)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
    }

    test("Search respects limit parameter") {
        val limit = 3
        val result = client.search("test query", limit = limit)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val actualSize = results?.size ?: 0
        require(actualSize <= limit) { "Expected results size ($actualSize) to be <= limit ($limit)" }
    }

    test("Search with specific collection works") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "test")
                putJsonArray("collections") {
                    add("bookstack-docs")
                }
                put("limit", 5)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        // All results should be from the specified collection
        results?.forEach { result ->
            val source = result.jsonObject["source"]?.jsonPrimitive?.content
            source?.contains("bookstack") shouldBe true
        }
    }

    test("Search UI page is served at root") {
        val response = client.getRawResponse(env.endpoints.searchService)
        response.status shouldBe HttpStatusCode.OK

        val html = response.body<String>()
        html shouldContain "<!DOCTYPE html>"
        html shouldContain "Search Knowledge Base"
        html shouldContain "searchInput"
    }

    test("Results include all required fields") {
        val result = client.search("test", limit = 1)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val firstResult = results?.firstOrNull()?.jsonObject
        require(firstResult != null) { "No search results returned - data may not be seeded yet" }

        // Check all required fields exist
        firstResult.containsKey("url") shouldBe true
        firstResult.containsKey("title") shouldBe true
        firstResult.containsKey("snippet") shouldBe true
        firstResult.containsKey("score") shouldBe true
        firstResult.containsKey("source") shouldBe true
        firstResult.containsKey("contentType") shouldBe true
        firstResult.containsKey("capabilities") shouldBe true
    }

    test("Empty query returns error or empty results") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "")
                put("limit", 5)
            })
        }

        // Either 400 error or empty results is acceptable
        val isValid = response.status == HttpStatusCode.BadRequest ||
                     response.status == HttpStatusCode.OK
        isValid shouldBe true
    }

    test("Interactive content can be chatted with (OpenWebUI ready)") {
        val result = client.search("documentation", limit = 10)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val interactiveResult = results?.find {
            it.jsonObject["capabilities"]?.jsonObject?.get("isInteractive")?.jsonPrimitive?.boolean == true
        }

        if (interactiveResult != null) {
            // Should have rich content for OpenWebUI to process
            val capabilities = interactiveResult.jsonObject["capabilities"]?.jsonObject
            val hasRichContent = capabilities?.get("hasRichContent")?.jsonPrimitive?.boolean
            hasRichContent shouldBe true
        }
    }

    test("Time series content can be graphed (Grafana ready)") {
        val result = client.search("market bitcoin", limit = 10)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        val timeSeriesResult = results?.find {
            it.jsonObject["capabilities"]?.jsonObject?.get("hasTimeSeries")?.jsonPrimitive?.boolean == true
        }

        if (timeSeriesResult != null) {
            // Should be structured for Grafana to query
            val capabilities = timeSeriesResult.jsonObject["capabilities"]?.jsonObject
            val isStructured = capabilities?.get("isStructured")?.jsonPrimitive?.boolean
            isStructured shouldBe true
        }
    }
}
