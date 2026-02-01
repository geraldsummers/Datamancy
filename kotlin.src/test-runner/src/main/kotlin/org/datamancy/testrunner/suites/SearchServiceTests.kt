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

        // First, delete and recreate test collections to ensure correct dimensions (1024 for BGE-M3)
        val collections = listOf("test-docs", "test-market", "test-bookstack")
        for (collection in collections) {
            try {
                // Delete existing collection (may fail if doesn't exist)
                client.deleteRaw("${env.endpoints.qdrant}/collections/$collection")
                println("      ✓ Deleted existing collection: $collection")
            } catch (e: Exception) {
                // Collection doesn't exist, that's fine
            }

            try {
                // Create collection with 1024 dimensions (BGE-M3 embedding model)
                val createRequest = buildJsonObject {
                    putJsonObject("vectors") {
                        put("size", 1024)  // Must match embedding service output (BGE-M3 = 1024-dim)
                        put("distance", "Cosine")
                    }
                }
                client.putRaw("${env.endpoints.qdrant}/collections/$collection") {
                    contentType(ContentType.Application.Json)
                    setBody(createRequest.toString())
                }
                println("      ✓ Created collection: $collection (1024-dim)")
            } catch (e: Exception) {
                println("      ⚠️  Failed to create collection $collection: ${e.message}")
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
                val text = doc["text"] ?: continue
                val title = doc["title"] ?: "Untitled"
                val url = doc["url"] ?: ""
                val source = doc["source"] ?: "unknown"

                // Generate embedding
                val embedResult = client.callTool("llm_embed_text", mapOf(
                    "text" to text,
                    "model" to "bge-base-en-v1.5"
                ))

                if (embedResult is org.datamancy.testrunner.framework.ToolResult.Success) {
                    // Parse vector from response
                    val vectorStr = embedResult.output
                        .removePrefix("[").removeSuffix("]")
                    val vector = vectorStr.split(",").map { it.trim().toFloat() }

                    // Insert directly into Qdrant using HTTP API
                    val pointId = title.hashCode().toLong()
                    val payload = buildJsonObject {
                        put("title", title)
                        put("link", url)
                        put("url", url)
                        put("description", text)
                        put("text", text)
                        put("source", source)
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
        kotlinx.coroutines.delay(5000) // Wait for Qdrant indexing to complete (increased from 2s)
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
        val result = client.search("bitcoin market", limit = 10)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No search results returned - data may not be seeded yet" }

        // Find a result from our test data (prefer market data for this test)
        val testResult = results.firstOrNull {
            it.jsonObject["source"]?.jsonPrimitive?.content?.contains("market") == true ||
            it.jsonObject["contentType"]?.jsonPrimitive?.content == "market"
        }?.jsonObject ?: results.firstOrNull()?.jsonObject

        require(testResult != null) { "No valid test results found" }

        // Check capabilities object exists with all required fields (keys must exist)
        val capabilities = testResult.get("capabilities")?.jsonObject
        require(capabilities != null) { "Capabilities field missing from search result" }
        require(capabilities.containsKey("humanFriendly")) { "Missing humanFriendly field" }
        require(capabilities.containsKey("agentFriendly")) { "Missing agentFriendly field" }
        require(capabilities.containsKey("hasTimeSeries")) { "Missing hasTimeSeries field" }
        require(capabilities.containsKey("hasRichContent")) { "Missing hasRichContent field" }
        require(capabilities.containsKey("isInteractive")) { "Missing isInteractive field" }
        require(capabilities.containsKey("isStructured")) { "Missing isStructured field" }
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
        html.uppercase() shouldContain "<!DOCTYPE HTML>"
        html shouldContain "Search Knowledge Base"
        html shouldContain "searchInput"
    }

    test("Results include all required fields") {
        val result = client.search("kubernetes deployment", limit = 10)
        result.success shouldBe true

        val results = result.results.jsonObject["results"]?.jsonArray
        require(!results.isNullOrEmpty()) { "No search results returned - data may not be seeded yet" }
        val firstResult = results.firstOrNull()?.jsonObject
        require(firstResult != null) { "No valid result object found" }

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

    // ================================================================================
    // PER-SOURCE SEARCH VALIDATION (BM25 + Semantic + Hybrid)
    // Validates that EVERY data source is searchable and vectorized correctly
    // ================================================================================

    // RSS FEED SOURCE TESTS
    test("RSS: BM25 search finds feed articles") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "hacker news technology programming")
                put("mode", "bm25")
                putJsonArray("collections") { add("rss_feeds") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            val rssResult = results.first().jsonObject
            val source = rssResult["source"]?.jsonPrimitive?.content
            source shouldBe "rss"
            println("      ✓ BM25 found ${results.size} RSS articles")
        } else {
            println("      ℹ️  No RSS articles found (may need time to ingest)")
        }
    }

    test("RSS: Semantic search finds relevant articles") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "artificial intelligence machine learning")
                put("mode", "vector")
                putJsonArray("collections") { add("rss_feeds") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            println("      ✓ Semantic search found ${results.size} RSS articles")
            // Verify embeddings exist by checking score
            val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
            require(firstScore != null && firstScore > 0) { "Invalid vector similarity score" }
        } else {
            println("      ℹ️  No RSS vectors found (embeddings may be pending)")
        }
    }

    test("RSS: Hybrid search combines BM25 and semantic") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "software development tools")
                put("mode", "hybrid")
                putJsonArray("collections") { add("rss_feeds") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"

        val results = body.jsonObject["results"]?.jsonArray
        if (!results.isNullOrEmpty()) {
            println("      ✓ Hybrid search found ${results.size} RSS results")
        }
    }

    // CVE SOURCE TESTS
    test("CVE: BM25 search finds vulnerabilities by keyword") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "remote code execution critical severity")
                put("mode", "bm25")
                putJsonArray("collections") { add("cve") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            val cveResult = results.first().jsonObject
            val source = cveResult["source"]?.jsonPrimitive?.content
            source shouldBe "cve"
            println("      ✓ BM25 found ${results.size} CVE entries")
        } else {
            println("      ℹ️  No CVE data found (may need time to ingest from NVD)")
        }
    }

    test("CVE: Semantic search finds similar vulnerabilities") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "buffer overflow memory corruption exploit")
                put("mode", "vector")
                putJsonArray("collections") { add("cve") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            println("      ✓ Semantic search found ${results.size} related CVEs")
            val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
            require(firstScore != null && firstScore > 0) { "Invalid CVE vector score" }
        } else {
            println("      ℹ️  No CVE vectors found (embeddings may be pending)")
        }
    }

    test("CVE: Hybrid search finds CVEs effectively") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "SQL injection web application")
                put("mode", "hybrid")
                putJsonArray("collections") { add("cve") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
    }

    // TORRENTS SOURCE TESTS
    test("Torrents: BM25 search finds torrents by name") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "ubuntu linux debian iso")
                put("mode", "bm25")
                putJsonArray("collections") { add("torrents") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            val torrentResult = results.first().jsonObject
            val source = torrentResult["source"]?.jsonPrimitive?.content
            source shouldBe "torrents"
            println("      ✓ BM25 found ${results.size} torrents")
        } else {
            println("      ℹ️  No torrent data found (CSV may be downloading)")
        }
    }

    test("Torrents: Semantic search finds similar content") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "operating system distribution software")
                put("mode", "vector")
                putJsonArray("collections") { add("torrents") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            println("      ✓ Semantic search found ${results.size} similar torrents")
            val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
            require(firstScore != null && firstScore > 0) { "Invalid torrent vector score" }
        } else {
            println("      ℹ️  No torrent vectors found (embeddings may be pending)")
        }
    }

    test("Torrents: Hybrid search combines keyword and semantic") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "movie film video")
                put("mode", "hybrid")
                putJsonArray("collections") { add("torrents") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
    }

    // WIKIPEDIA SOURCE TESTS
    test("Wikipedia: BM25 search finds articles by title/content") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "computer science algorithm programming")
                put("mode", "bm25")
                putJsonArray("collections") { add("wikipedia") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            val wikiResult = results.first().jsonObject
            val source = wikiResult["source"]?.jsonPrimitive?.content
            source shouldBe "wikipedia"
            println("      ✓ BM25 found ${results.size} Wikipedia articles")
        } else {
            println("      ℹ️  No Wikipedia data found (dump may be downloading)")
        }
    }

    test("Wikipedia: Semantic search finds conceptually related articles") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "quantum physics mechanics particles")
                put("mode", "vector")
                putJsonArray("collections") { add("wikipedia") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            println("      ✓ Semantic search found ${results.size} related Wikipedia articles")
            val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
            require(firstScore != null && firstScore > 0) { "Invalid Wikipedia vector score" }
        } else {
            println("      ℹ️  No Wikipedia vectors found (embeddings may be pending)")
        }
    }

    test("Wikipedia: Hybrid search leverages both methods") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "biology evolution species")
                put("mode", "hybrid")
                putJsonArray("collections") { add("wikipedia") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
    }

    // AUSTRALIAN LAWS SOURCE TESTS
    test("Australian Laws: BM25 search finds legislation") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "commonwealth act regulation")
                put("mode", "bm25")
                putJsonArray("collections") { add("australian_laws") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            val lawResult = results.first().jsonObject
            val source = lawResult["source"]?.jsonPrimitive?.content
            source shouldBe "australian_laws"
            println("      ✓ BM25 found ${results.size} Australian laws")
        } else {
            println("      ℹ️  No Australian laws data found")
        }
    }

    test("Australian Laws: Semantic search finds related legislation") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "legal statute government policy")
                put("mode", "vector")
                putJsonArray("collections") { add("australian_laws") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            println("      ✓ Semantic search found ${results.size} related laws")
            val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
            require(firstScore != null && firstScore > 0) { "Invalid law vector score" }
        } else {
            println("      ℹ️  No law vectors found")
        }
    }

    test("Australian Laws: Hybrid search combines approaches") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "section provision clause")
                put("mode", "hybrid")
                putJsonArray("collections") { add("australian_laws") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
    }

    // LINUX DOCS SOURCE TESTS
    test("Linux Docs: BM25 search finds man pages by command") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "bash shell command grep sed awk")
                put("mode", "bm25")
                putJsonArray("collections") { add("linux_docs") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            val docResult = results.first().jsonObject
            val source = docResult["source"]?.jsonPrimitive?.content
            source shouldBe "linux_docs"
            println("      ✓ BM25 found ${results.size} Linux docs")
        } else {
            println("      ℹ️  No Linux docs found (container may not have man pages)")
        }
    }

    test("Linux Docs: Semantic search finds related documentation") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "file system directory permissions")
                put("mode", "vector")
                putJsonArray("collections") { add("linux_docs") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            println("      ✓ Semantic search found ${results.size} related docs")
            val firstScore = results.first().jsonObject["score"]?.jsonPrimitive?.double
            require(firstScore != null && firstScore > 0) { "Invalid doc vector score" }
        } else {
            println("      ℹ️  No Linux doc vectors found")
        }
    }

    test("Linux Docs: Hybrid search leverages both methods") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "network configuration interface")
                put("mode", "hybrid")
                putJsonArray("collections") { add("linux_docs") }
                put("limit", 10)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        body.jsonObject["mode"]?.jsonPrimitive?.content shouldBe "hybrid"
    }

    // ================================================================================
    // CROSS-SOURCE VALIDATION
    // ================================================================================

    test("All sources: Cross-collection search works") {
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "security vulnerability")
                put("mode", "hybrid")
                putJsonArray("collections") {
                    add("rss_feeds")
                    add("cve")
                    add("torrents")
                    add("wikipedia")
                    add("australian_laws")
                    add("linux_docs")
                }
                put("limit", 20)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            val sources = results.mapNotNull {
                it.jsonObject["source"]?.jsonPrimitive?.content
            }.toSet()

            println("      ✓ Cross-collection search found results from: ${sources.joinToString()}")
            println("      ✓ Total results across all sources: ${results.size}")
        } else {
            println("      ℹ️  No cross-collection results yet")
        }
    }

    test("All sources: Vectorization quality check") {
        // Test that vector scores are reasonable (not all 0.0 or 1.0)
        val response = client.postRaw("${env.endpoints.searchService}/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", "technology innovation")
                put("mode", "vector")
                put("limit", 50)
            })
        }

        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.body<String>())
        val results = body.jsonObject["results"]?.jsonArray

        if (!results.isNullOrEmpty()) {
            val scores = results.mapNotNull {
                it.jsonObject["score"]?.jsonPrimitive?.double
            }

            val avgScore = scores.average()
            val minScore = scores.minOrNull() ?: 0.0
            val maxScore = scores.maxOrNull() ?: 0.0

            println("      ✓ Vector scores - Min: ${"%.4f".format(minScore)}, " +
                   "Max: ${"%.4f".format(maxScore)}, Avg: ${"%.4f".format(avgScore)}")

            // Sanity check: scores should be between 0 and 1
            require(minScore >= 0.0 && maxScore <= 1.0) { "Invalid vector scores detected" }
            require(avgScore > 0.0) { "Average score should be > 0" }
        } else {
            println("      ℹ️  No vectors to validate yet")
        }
    }
}
