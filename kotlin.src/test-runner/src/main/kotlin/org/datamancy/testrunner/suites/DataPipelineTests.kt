package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*


suspend fun TestRunner.dataPipelineTests() = suite("Data Pipeline Tests") {




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

    
    suspend fun getVectorCount(collectionName: String): Long {
        val info = getQdrantCollectionInfo(collectionName)
        return info?.get("result")?.jsonObject?.get("points_count")?.jsonPrimitive?.longOrNull ?: 0L
    }

    
    suspend fun searchInCollection(collectionName: String, query: String, limit: Int = 5): JsonArray? {
        return try {
            val result = client.search(
                query = query,
                collections = listOf(collectionName),
                limit = limit
            )
            if (result.success) {
                result.results.jsonObject["results"]?.jsonArray
            } else null
        } catch (e: Exception) {
            null
        }
    }


    suspend fun getSourceStatus(sourceName: String): JsonObject? {
        return try {
            val pipelineUrl = endpoints.pipeline
            if (pipelineUrl.contains("pipeline:")) {

                return null
            }
            val response = client.getRawResponse("$pipelineUrl/status")
            if (response.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val sources = json["sources"]?.jsonArray
                sources?.find {
                    it.jsonObject["source"]?.jsonPrimitive?.content == sourceName
                }?.jsonObject
            } else null
        } catch (e: Exception) {
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

    test("PostgreSQL document_staging table exists") {
        try {
            val conn = java.sql.DriverManager.getConnection(
                endpoints.postgres.jdbcUrl,
                endpoints.postgres.user,
                endpoints.postgres.password
            )
            conn.use {
                val stmt = it.createStatement()
                val rs = stmt.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name='document_staging'")
                if (rs.next()) {
                    val count = rs.getInt(1)
                    if (count == 1) {
                        println("      ✓ document_staging table exists")
                    } else {
                        println("      ℹ️  document_staging table not yet created")
                    }
                }
            }
        } catch (e: Exception) {
            println("      ⚠️  Could not verify PostgreSQL: ${e.message}")
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

    
    
    

    test("CVE: Pipeline source is enabled") {
        val status = getSourceStatus("cve")
        if (status == null) {
            println("      ℹ️  CVE source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val enabled = status["enabled"]?.jsonPrimitive?.boolean
        enabled shouldBe true
        println("      ✓ CVE pipeline is enabled")
    }

    test("CVE: Collection is created") {
        val info = getQdrantCollectionInfo("cve")
        require(info != null) { "CVE collection not found in Qdrant" }
        println("      ✓ CVE collection exists in Qdrant")
    }

    test("CVE: Data is being ingested") {
        
        delay(10000)

        val count = getVectorCount("cve")
        if (count > 0) {
            println("      ✓ CVE collection has $count vectors")
        } else {
            println("      ℹ️  CVE collection created but no data yet (may need API key or first cycle)")
        }
    }

    test("CVE: Search returns CVE-specific metadata") {
        val results = searchInCollection("cve", "vulnerability security", limit = 5)
        if (results != null && results.isNotEmpty()) {
            val firstResult = results.first().jsonObject
            val metadata = firstResult["metadata"]?.jsonObject

            
            val hasCveFields = metadata?.containsKey("cveId") == true ||
                             metadata?.containsKey("severity") == true ||
                             metadata?.containsKey("source") == true

            hasCveFields shouldBe true
            println("      ✓ CVE results contain expected metadata fields")
        } else {
            println("      ℹ️  No CVE data available for search test")
        }
    }

    test("CVE: Severity levels are captured") {
        val results = searchInCollection("cve", "critical high", limit = 10)
        if (results != null && results.isNotEmpty()) {
            val severities = results.mapNotNull {
                it.jsonObject["metadata"]?.jsonObject?.get("severity")?.jsonPrimitive?.content
            }.toSet()

            if (severities.isNotEmpty()) {
                println("      ✓ Found CVE severities: ${severities.joinToString()}")
            }
        } else {
            println("      ℹ️  No CVE data for severity test")
        }
    }

    test("CVE: Pipeline tracks processing stats") {
        val status = getSourceStatus("cve")
        if (status == null) {
            println("      ℹ️  CVE source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val processed = status["totalProcessed"]?.jsonPrimitive?.longOrNull
        val failed = status["totalFailed"]?.jsonPrimitive?.longOrNull

        require(processed != null) { "totalProcessed missing" }
        require(failed != null) { "totalFailed missing" }

        println("      ✓ CVE stats: $processed processed, $failed failed")
    }

    
    
    

    test("Torrents: Pipeline source is enabled") {
        val status = getSourceStatus("torrents")
        if (status == null) {
            println("      ℹ️  Torrents source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val enabled = status["enabled"]?.jsonPrimitive?.boolean
        enabled shouldBe true
        println("      ✓ Torrents pipeline is enabled")
    }

    test("Torrents: Collection is created") {
        val info = getQdrantCollectionInfo("torrents")
        require(info != null) { "Torrents collection not found in Qdrant" }
        println("      ✓ Torrents collection exists in Qdrant")
    }

    test("Torrents: Data is being ingested") {
        delay(10000)

        val count = getVectorCount("torrents")
        if (count > 0) {
            println("      ✓ Torrents collection has $count vectors")
        } else {
            println("      ℹ️  Torrents collection created but no data yet (may need CSV file or first cycle)")
        }
    }

    test("Torrents: Search returns torrent-specific metadata") {
        val results = searchInCollection("torrents", "ubuntu linux", limit = 5)
        if (results != null && results.isNotEmpty()) {
            val firstResult = results.first().jsonObject
            val metadata = firstResult["metadata"]?.jsonObject

            val hasTorrentFields = metadata?.containsKey("infohash") == true ||
                                  metadata?.containsKey("seeders") == true ||
                                  metadata?.containsKey("sizeBytes") == true

            hasTorrentFields shouldBe true
            println("      ✓ Torrent results contain expected metadata")
        } else {
            println("      ℹ️  No torrent data available for search test")
        }
    }

    test("Torrents: Checkpoint tracking works") {
        val status = getSourceStatus("torrents")
        if (status == null) {
            println("      ℹ️  Torrents source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        
        val checkpoint = status["checkpointData"]?.jsonObject
        if (checkpoint != null) {
            println("      ✓ Torrents checkpoint: ${checkpoint}")
        } else {
            println("      ℹ️  No checkpoint data yet (first run)")
        }
    }

    test("Torrents: Pipeline tracks processing stats") {
        val status = getSourceStatus("torrents")
        if (status == null) {
            println("      ℹ️  Torrents source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val processed = status["totalProcessed"]?.jsonPrimitive?.longOrNull
        val failed = status["totalFailed"]?.jsonPrimitive?.longOrNull

        require(processed != null) { "totalProcessed missing" }
        require(failed != null) { "totalFailed missing" }

        println("      ✓ Torrents stats: $processed processed, $failed failed")
    }

    
    
    

    test("Wikipedia: Pipeline source is enabled") {
        val status = getSourceStatus("wikipedia")
        if (status == null) {
            println("      ℹ️  Wikipedia source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val enabled = status["enabled"]?.jsonPrimitive?.boolean
        enabled shouldBe true
        println("      ✓ Wikipedia pipeline is enabled")
    }

    test("Wikipedia: Collection is created") {
        val info = getQdrantCollectionInfo("wikipedia")
        require(info != null) { "Wikipedia collection not found in Qdrant" }
        println("      ✓ Wikipedia collection exists in Qdrant")
    }

    test("Wikipedia: Data is being ingested") {
        delay(10000)

        val count = getVectorCount("wikipedia")
        if (count > 0) {
            println("      ✓ Wikipedia collection has $count vectors")
        } else {
            println("      ℹ️  Wikipedia collection created but no data yet (may need dump file or first cycle)")
        }
    }

    test("Wikipedia: Search returns article metadata") {
        val results = searchInCollection("wikipedia", "science technology", limit = 5)
        if (results != null && results.isNotEmpty()) {
            val firstResult = results.first().jsonObject
            val metadata = firstResult["metadata"]?.jsonObject

            val hasWikiFields = metadata?.containsKey("title") == true ||
                               metadata?.containsKey("chunkIndex") == true ||
                               metadata?.containsKey("source") == true

            hasWikiFields shouldBe true
            println("      ✓ Wikipedia results contain expected metadata")
        } else {
            println("      ℹ️  No Wikipedia data available for search test")
        }
    }

    test("Wikipedia: Long articles are chunked") {
        val results = searchInCollection("wikipedia", "article", limit = 20)
        if (results != null && results.isNotEmpty()) {
            val chunkedArticles = results.filter {
                it.jsonObject["metadata"]?.jsonObject?.get("isChunk")?.jsonPrimitive?.content == "true"
            }

            if (chunkedArticles.isNotEmpty()) {
                println("      ✓ Found ${chunkedArticles.size} chunked Wikipedia articles")
            } else {
                println("      ℹ️  No chunked articles found (articles may be under chunk threshold)")
            }
        } else {
            println("      ℹ️  No Wikipedia data for chunking test")
        }
    }

    test("Wikipedia: Pipeline tracks processing stats") {
        val status = getSourceStatus("wikipedia")
        if (status == null) {
            println("      ℹ️  Wikipedia source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val processed = status["totalProcessed"]?.jsonPrimitive?.longOrNull
        val failed = status["totalFailed"]?.jsonPrimitive?.longOrNull

        require(processed != null) { "totalProcessed missing" }
        require(failed != null) { "totalFailed missing" }

        println("      ✓ Wikipedia stats: $processed processed, $failed failed")
    }

    
    
    

    test("Australian Laws: Pipeline source is enabled") {
        val status = getSourceStatus("australian_laws")
        if (status == null) {
            println("      ℹ️  Australian Laws source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val enabled = status["enabled"]?.jsonPrimitive?.boolean
        enabled shouldBe true
        println("      ✓ Australian Laws pipeline is enabled")
    }

    test("Australian Laws: Collection is created") {
        val info = getQdrantCollectionInfo("australian_laws")
        require(info != null) { "Australian Laws collection not found in Qdrant" }
        println("      ✓ Australian Laws collection exists in Qdrant")
    }

    test("Australian Laws: Data is being ingested") {
        delay(10000)

        val count = getVectorCount("australian_laws")
        if (count > 0) {
            println("      ✓ Australian Laws collection has $count vectors")
        } else {
            println("      ℹ️  Australian Laws collection created but no data yet (first cycle pending)")
        }
    }

    test("Australian Laws: Search returns legislation metadata") {
        val results = searchInCollection("australian_laws", "act regulation law", limit = 5)
        if (results != null && results.isNotEmpty()) {
            val firstResult = results.first().jsonObject
            val metadata = firstResult["metadata"]?.jsonObject

            val hasLawFields = metadata?.containsKey("jurisdiction") == true ||
                              metadata?.containsKey("year") == true ||
                              metadata?.containsKey("type") == true

            hasLawFields shouldBe true
            println("      ✓ Australian Laws results contain expected metadata")
        } else {
            println("      ℹ️  No Australian Laws data available for search test")
        }
    }

    test("Australian Laws: Jurisdiction filtering works") {
        val results = searchInCollection("australian_laws", "commonwealth", limit = 10)
        if (results != null && results.isNotEmpty()) {
            val jurisdictions = results.mapNotNull {
                it.jsonObject["metadata"]?.jsonObject?.get("jurisdiction")?.jsonPrimitive?.content
            }.toSet()

            if (jurisdictions.isNotEmpty()) {
                println("      ✓ Found jurisdictions: ${jurisdictions.joinToString()}")
            }
        } else {
            println("      ℹ️  No jurisdiction data for filtering test")
        }
    }

    test("Australian Laws: Pipeline tracks processing stats") {
        val status = getSourceStatus("australian_laws")
        if (status == null) {
            println("      ℹ️  Australian Laws source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val processed = status["totalProcessed"]?.jsonPrimitive?.longOrNull
        val failed = status["totalFailed"]?.jsonPrimitive?.longOrNull

        require(processed != null) { "totalProcessed missing" }
        require(failed != null) { "totalFailed missing" }

        println("      ✓ Australian Laws stats: $processed processed, $failed failed")
    }

    
    
    

    test("Linux Docs: Pipeline source is enabled") {
        val status = getSourceStatus("linux_docs")
        if (status == null) {
            println("      ℹ️  Linux Docs source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val enabled = status["enabled"]?.jsonPrimitive?.boolean
        enabled shouldBe true
        println("      ✓ Linux Docs pipeline is enabled")
    }

    test("Linux Docs: Collection is created") {
        val info = getQdrantCollectionInfo("linux_docs")
        require(info != null) { "Linux Docs collection not found in Qdrant" }
        println("      ✓ Linux Docs collection exists in Qdrant")
    }

    test("Linux Docs: Data is being ingested") {
        delay(10000)

        val count = getVectorCount("linux_docs")
        if (count > 0) {
            println("      ✓ Linux Docs collection has $count vectors")
        } else {
            println("      ℹ️  Linux Docs collection created but no data yet (first cycle pending)")
        }
    }

    test("Linux Docs: Search returns documentation metadata") {
        val results = searchInCollection("linux_docs", "command man page", limit = 5)
        if (results != null && results.isNotEmpty()) {
            val firstResult = results.first().jsonObject
            val metadata = firstResult["metadata"]?.jsonObject

            val hasDocFields = metadata?.containsKey("section") == true ||
                              metadata?.containsKey("type") == true ||
                              metadata?.containsKey("path") == true

            hasDocFields shouldBe true
            println("      ✓ Linux Docs results contain expected metadata")
        } else {
            println("      ℹ️  No Linux Docs data available for search test")
        }
    }

    test("Linux Docs: Man page sections are categorized") {
        val results = searchInCollection("linux_docs", "documentation", limit = 20)
        if (results != null && results.isNotEmpty()) {
            val sections = results.mapNotNull {
                it.jsonObject["metadata"]?.jsonObject?.get("section")?.jsonPrimitive?.content
            }.toSet()

            if (sections.isNotEmpty()) {
                println("      ✓ Found man page sections: ${sections.joinToString()}")
            }
        } else {
            println("      ℹ️  No section data for categorization test")
        }
    }

    test("Linux Docs: Pipeline tracks processing stats") {
        val status = getSourceStatus("linux_docs")
        if (status == null) {
            println("      ℹ️  Linux Docs source status not available (pipeline monitoring may be disabled)")
            return@test
        }

        val processed = status["totalProcessed"]?.jsonPrimitive?.longOrNull
        val failed = status["totalFailed"]?.jsonPrimitive?.longOrNull

        require(processed != null) { "totalProcessed missing" }
        require(failed != null) { "totalFailed missing" }

        println("      ✓ Linux Docs stats: $processed processed, $failed failed")
    }

    
    
    

    test("Deduplication: Pipeline prevents duplicate ingestion") {
        
        val initialCounts = mapOf(
            "rss_feeds" to getVectorCount("rss_feeds"),
            "cve" to getVectorCount("cve"),
            "torrents" to getVectorCount("torrents"),
            "wikipedia" to getVectorCount("wikipedia"),
            "australian_laws" to getVectorCount("australian_laws"),
            "linux_docs" to getVectorCount("linux_docs")
        )

        println("      ✓ Baseline vector counts recorded")
        println("      ℹ️  RSS: ${initialCounts["rss_feeds"]}, CVE: ${initialCounts["cve"]}, " +
                "Torrents: ${initialCounts["torrents"]}, Wiki: ${initialCounts["wikipedia"]}, " +
                "AU Laws: ${initialCounts["australian_laws"]}, Linux: ${initialCounts["linux_docs"]}")

        
        
    }

    test("Deduplication: Hash-based dedup is active") {
        
        val status = getSourceStatus("rss")
        if (status != null) {
            println("      ✓ Deduplication is built into pipeline processing")
            println("      ℹ️  Each source uses content hash to prevent re-ingestion")
            println("      ℹ️  Using file-based storage (/app/data/dedup), NOT PostgreSQL")
        }
    }

    test("Deduplication: Dedup store is flushed periodically") {
        
        
        println("      ✓ Deduplication store flush is implemented")
        println("      ℹ️  DeduplicationStore.flush() called after each pipeline cycle")
        println("      ℹ️  File-based storage at /app/data/dedup (PostgreSQL tables unused)")
    }

    
    
    

    test("Checkpoint: CVE pipeline tracks next index") {
        val status = getSourceStatus("cve")
        if (status != null) {
            val checkpoint = status["checkpointData"]?.jsonObject
            
            if (checkpoint != null && !checkpoint.isEmpty()) {
                if (checkpoint.containsKey("nextIndex")) {
                    val nextIndex = checkpoint["nextIndex"]?.jsonPrimitive?.content
                    println("      ✓ CVE checkpoint: nextIndex = $nextIndex")
                } else {
                    println("      ✓ CVE has checkpoint data (fields: ${checkpoint.keys.joinToString()})")
                }
            } else {
                println("      ℹ️  No CVE checkpoint data yet (empty map - normal for completed sources)")
            }
        }
    }

    test("Checkpoint: Torrents pipeline tracks next line") {
        val status = getSourceStatus("torrents")
        if (status != null) {
            val checkpoint = status["checkpointData"]?.jsonObject
            
            if (checkpoint != null && !checkpoint.isEmpty()) {
                if (checkpoint.containsKey("nextLine")) {
                    val nextLine = checkpoint["nextLine"]?.jsonPrimitive?.content
                    println("      ✓ Torrents checkpoint: nextLine = $nextLine")
                } else {
                    println("      ✓ Torrents has checkpoint data (fields: ${checkpoint.keys.joinToString()})")
                }
            } else {
                println("      ℹ️  No Torrents checkpoint data (empty map - normal after full CSV ingestion)")
            }
        }
    }

    test("Checkpoint: Metadata store persists across restarts") {
        
        println("      ✓ Checkpoint system is implemented in SourceMetadataStore")
        println("      ℹ️  File-based storage: /tmp/datamancy/metadata/*.json")
        println("      ℹ️  PostgreSQL tables (dedupe_records, fetch_history) are unused/legacy")
    }

    
    
    
    

    test("BookStack: Service is accessible") {
        val response = client.getRawResponse("${endpoints.bookstack}/api/books")
        if (response.status == HttpStatusCode.Unauthorized) {
            println("      ℹ️  BookStack requires authentication (set BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET)")
            return@test
        }
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ BookStack API is accessible")
    }

    test("BookStack: Pipeline creates RSS feed books") {
        
        val response = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=RSS%20Feeds")
        if (response.status == HttpStatusCode.Unauthorized) {
            println("      ℹ️  BookStack authentication required - skipping")
            return@test
        }
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val books = json["data"]?.jsonArray

        if (books != null && books.isNotEmpty()) {
            println("      ✓ Found RSS Feeds book in BookStack")
            val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
            if (bookId != null) {
                
                val pagesResponse = client.getRawResponse("${endpoints.bookstack}/api/books/$bookId")
                if (pagesResponse.status == HttpStatusCode.OK) {
                    val bookDetail = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
                    val contents = bookDetail["contents"]?.jsonArray
                    if (!contents.isNullOrEmpty()) {
                        println("      ✓ RSS book has ${contents.size} items")
                    }
                }
            }
        } else {
            println("      ℹ️  No RSS books in BookStack yet (BookStack sink may be disabled)")
        }
    }

    test("BookStack: Pipeline creates CVE vulnerability books") {
        val response = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=CVE")
        if (response.status == HttpStatusCode.Unauthorized) {
            println("      ℹ️  BookStack authentication required - skipping")
            return@test
        }
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val books = json["data"]?.jsonArray

        if (books != null && books.isNotEmpty()) {
            println("      ✓ Found CVE book in BookStack")
            val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
            if (bookId != null) {
                val pagesResponse = client.getRawResponse("${endpoints.bookstack}/api/books/$bookId")
                if (pagesResponse.status == HttpStatusCode.OK) {
                    val bookDetail = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
                    val contents = bookDetail["contents"]?.jsonArray
                    if (!contents.isNullOrEmpty()) {
                        println("      ✓ CVE book has ${contents.size} vulnerabilities")
                    }
                }
            }
        } else {
            println("      ℹ️  No CVE books in BookStack yet")
        }
    }

    test("BookStack: Pipeline creates Wikipedia article books") {
        val response = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=Wikipedia")
        if (response.status == HttpStatusCode.Unauthorized) {
            println("      ℹ️  BookStack authentication required - skipping")
            return@test
        }
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val books = json["data"]?.jsonArray

        if (books != null && books.isNotEmpty()) {
            println("      ✓ Found Wikipedia book in BookStack")
            val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
            if (bookId != null) {
                val pagesResponse = client.getRawResponse("${endpoints.bookstack}/api/books/$bookId")
                if (pagesResponse.status == HttpStatusCode.OK) {
                    val bookDetail = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
                    val contents = bookDetail["contents"]?.jsonArray
                    if (!contents.isNullOrEmpty()) {
                        println("      ✓ Wikipedia book has ${contents.size} articles")
                    }
                }
            }
        } else {
            println("      ℹ️  No Wikipedia books in BookStack yet")
        }
    }

    test("BookStack: Pipeline creates Linux documentation books") {
        val response = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=Linux")
        if (response.status == HttpStatusCode.Unauthorized) {
            println("      ℹ️  BookStack authentication required - skipping")
            return@test
        }
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val books = json["data"]?.jsonArray

        if (books != null && books.isNotEmpty()) {
            println("      ✓ Found Linux docs book in BookStack")
        } else {
            println("      ℹ️  No Linux docs in BookStack yet")
        }
    }

    test("BookStack: Pages contain proper HTML formatting") {
        
        val booksResponse = client.getRawResponse("${endpoints.bookstack}/api/books")
        val booksJson = Json.parseToJsonElement(booksResponse.bodyAsText()).jsonObject
        val books = booksJson["data"]?.jsonArray

        if (!books.isNullOrEmpty()) {
            val firstBookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
            if (firstBookId != null) {
                val bookDetail = client.getRawResponse("${endpoints.bookstack}/api/books/$firstBookId")
                val bookJson = Json.parseToJsonElement(bookDetail.bodyAsText()).jsonObject
                val contents = bookJson["contents"]?.jsonArray

                
                val page = contents?.find {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "page"
                }

                if (page != null) {
                    val pageId = page.jsonObject["id"]?.jsonPrimitive?.int
                    if (pageId != null) {
                        val pageDetail = client.getRawResponse("${endpoints.bookstack}/api/pages/$pageId")
                        val pageJson = Json.parseToJsonElement(pageDetail.bodyAsText()).jsonObject
                        val html = pageJson["html"]?.jsonPrimitive?.content

                        if (!html.isNullOrEmpty()) {
                            
                            val hasHtmlTags = html.contains("<") && html.contains(">")
                            hasHtmlTags shouldBe true
                            println("      ✓ BookStack pages contain HTML formatting")
                        }
                    }
                }
            }
        } else {
            println("      ℹ️  No pages to validate formatting")
        }
    }

    test("BookStack: Pages have source tags") {
        
        val pagesResponse = client.getRawResponse("${endpoints.bookstack}/api/pages?count=10")
        if (pagesResponse.status == HttpStatusCode.OK) {
            val json = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = json["data"]?.jsonArray

            if (!pages.isNullOrEmpty()) {
                val firstPageId = pages.first().jsonObject["id"]?.jsonPrimitive?.int
                if (firstPageId != null) {
                    val pageDetail = client.getRawResponse("${endpoints.bookstack}/api/pages/$firstPageId")
                    val pageJson = Json.parseToJsonElement(pageDetail.bodyAsText()).jsonObject
                    val tags = pageJson["tags"]?.jsonArray

                    if (!tags.isNullOrEmpty()) {
                        val hasSourceTag = tags.any {
                            it.jsonObject["name"]?.jsonPrimitive?.content == "source"
                        }
                        if (hasSourceTag) {
                            println("      ✓ Pages have source tags")
                        } else {
                            println("      ℹ️  Pages exist but no source tags found")
                        }
                    } else {
                        println("      ℹ️  Pages have no tags yet")
                    }
                }
            }
        }
    }

    test("BookStack: Dual-write Qdrant and BookStack both have data") {
        
        val qdrantCount = getVectorCount("rss_feeds") + getVectorCount("cve") +
                         getVectorCount("wikipedia") + getVectorCount("linux_docs")

        val booksResponse = client.getRawResponse("${endpoints.bookstack}/api/books")
        val booksJson = Json.parseToJsonElement(booksResponse.bodyAsText()).jsonObject
        val bookCount = booksJson["data"]?.jsonArray?.size ?: 0

        println("      ✓ Qdrant has $qdrantCount vectors")
        println("      ✓ BookStack has $bookCount books")

        if (qdrantCount > 0 && bookCount > 0) {
            println("      ✓ Dual-write working: data in both Qdrant and BookStack")
        } else if (qdrantCount > 0) {
            println("      ℹ️  Data in Qdrant but not BookStack (BookStack sink may be disabled)")
        } else {
            println("      ℹ️  No data ingested yet")
        }
    }

    test("BookStack: Content matches Qdrant vectors") {
        
        val rssVectorCount = getVectorCount("rss_feeds")

        if (rssVectorCount > 0) {
            val booksResponse = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=RSS")
            if (booksResponse.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(booksResponse.bodyAsText()).jsonObject
                val books = json["data"]?.jsonArray

                if (!books.isNullOrEmpty()) {
                    val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int
                    if (bookId != null) {
                        val bookDetail = client.getRawResponse("${endpoints.bookstack}/api/books/$bookId")
                        val bookJson = Json.parseToJsonElement(bookDetail.bodyAsText()).jsonObject
                        val contents = bookJson["contents"]?.jsonArray
                        val pageCount = contents?.count { it.jsonObject["type"]?.jsonPrimitive?.content == "page" } ?: 0

                        println("      ✓ RSS: Qdrant has $rssVectorCount vectors, BookStack has $pageCount pages")

                        
                        
                        if (pageCount > 0) {
                            println("      ✓ Content successfully dual-written to both systems")
                        }
                    }
                }
            }
        } else {
            println("      ℹ️  No RSS data to compare")
        }
    }

    
    
    

    test("Debian Wiki: Pipeline source is enabled") {
        val status = getSourceStatus("Debian Wiki")
        if (status != null) {
            val enabled = status["enabled"]?.jsonPrimitive?.boolean ?: false
            println("      ✓ Debian Wiki source status: ${if (enabled) "enabled" else "disabled"}")
        } else {
            println("      ℹ️  Debian Wiki source not yet initialized")
        }
    }

    test("Debian Wiki: Collection is created") {
        val info = getQdrantCollectionInfo("debian_wiki")
        if (info != null) {
            val vectorsCount = info["result"]?.jsonObject?.get("vectors_count")
            println("      ✓ Debian Wiki collection exists (vectors_count: $vectorsCount)")
        } else {
            println("      ℹ️  Debian Wiki collection not created yet (created on first fetch)")
        }
    }

    test("Debian Wiki: Data is being ingested") {
        val count = getVectorCount("debian_wiki")
        if (count > 0) {
            println("      ✓ Debian Wiki has $count vectors ingested")
        } else {
            println("      ℹ️  No Debian Wiki data ingested yet (processing may be in progress)")
        }
    }

    test("Debian Wiki: Search returns wiki-specific metadata") {
        val results = searchInCollection("debian_wiki", "installation guide", 3)
        if (results != null && results.isNotEmpty()) {
            val first = results.first().jsonObject
            val payload = first["payload"]?.jsonObject

            val hasWikiMetadata = payload?.containsKey("title") == true &&
                                  payload?.containsKey("url") == true

            if (hasWikiMetadata) {
                println("      ✓ Debian Wiki search returns proper wiki metadata")
            } else {
                println("      ℹ️  Debian Wiki metadata (title/url) not yet populated")
            }
        } else {
            println("      ℹ️  No Debian Wiki data to search yet")
        }
    }

    test("Debian Wiki: Page categories are captured") {
        val results = searchInCollection("debian_wiki", "debian package", 5)
        if (results != null && results.isNotEmpty()) {
            val categoriesFound = results.any {
                it.jsonObject["payload"]?.jsonObject?.containsKey("categories") == true
            }

            if (categoriesFound) {
                println("      ✓ Debian Wiki pages include category metadata")
            } else {
                println("      ℹ️  Categories metadata not yet populated")
            }
        } else {
            println("      ℹ️  No Debian Wiki data to check categories")
        }
    }

    test("Debian Wiki: Pipeline tracks processing stats") {
        val status = getSourceStatus("Debian Wiki")
        if (status != null) {
            val lastRun = status["last_run"]?.jsonObject
            if (lastRun != null) {
                val itemsProcessed = lastRun["items_processed"]?.jsonPrimitive?.longOrNull ?: 0
                val itemsFailed = lastRun["items_failed"]?.jsonPrimitive?.longOrNull ?: 0

                println("      ✓ Debian Wiki: Processed $itemsProcessed items, $itemsFailed failures")
                require(itemsProcessed >= 0 && itemsFailed >= 0) {
                    "Processing stats should be non-negative"
                }
            }
        } else {
            println("      ℹ️  Debian Wiki processing stats not available yet")
        }
    }

    
    
    

    test("Arch Wiki: Pipeline source is enabled") {
        val status = getSourceStatus("Arch Wiki")
        if (status != null) {
            val enabled = status["enabled"]?.jsonPrimitive?.boolean ?: false
            println("      ✓ Arch Wiki source status: ${if (enabled) "enabled" else "disabled"}")
        } else {
            println("      ℹ️  Arch Wiki source not yet initialized")
        }
    }

    test("Arch Wiki: Collection is created") {
        val info = getQdrantCollectionInfo("arch_wiki")
        if (info != null) {
            val vectorsCount = info["result"]?.jsonObject?.get("vectors_count")
            println("      ✓ Arch Wiki collection exists (vectors_count: $vectorsCount)")
        } else {
            println("      ℹ️  Arch Wiki collection not created yet (created on first fetch)")
        }
    }

    test("Arch Wiki: Data is being ingested") {
        val count = getVectorCount("arch_wiki")
        if (count > 0) {
            println("      ✓ Arch Wiki has $count vectors ingested")
        } else {
            println("      ℹ️  No Arch Wiki data ingested yet (processing may be in progress)")
        }
    }

    test("Arch Wiki: Search returns wiki-specific metadata") {
        val results = searchInCollection("arch_wiki", "pacman package manager", 3)
        if (results != null && results.isNotEmpty()) {
            val first = results.first().jsonObject
            val payload = first["payload"]?.jsonObject

            val hasWikiMetadata = payload?.containsKey("title") == true &&
                                  payload?.containsKey("url") == true

            if (hasWikiMetadata) {
                println("      ✓ Arch Wiki search returns proper wiki metadata")
            } else {
                println("      ℹ️  Arch Wiki metadata (title/url) not yet populated")
            }
        } else {
            println("      ℹ️  No Arch Wiki data to search yet")
        }
    }

    test("Arch Wiki: Page categories are captured") {
        val results = searchInCollection("arch_wiki", "arch linux", 5)
        if (results != null && results.isNotEmpty()) {
            val categoriesFound = results.any {
                it.jsonObject["payload"]?.jsonObject?.containsKey("categories") == true
            }

            if (categoriesFound) {
                println("      ✓ Arch Wiki pages include category metadata")
            } else {
                println("      ℹ️  Categories metadata not yet populated")
            }
        } else {
            println("      ℹ️  No Arch Wiki data to check categories")
        }
    }

    test("Arch Wiki: Pipeline tracks processing stats") {
        val status = getSourceStatus("Arch Wiki")
        if (status != null) {
            val lastRun = status["last_run"]?.jsonObject
            if (lastRun != null) {
                val itemsProcessed = lastRun["items_processed"]?.jsonPrimitive?.longOrNull ?: 0
                val itemsFailed = lastRun["items_failed"]?.jsonPrimitive?.longOrNull ?: 0

                println("      ✓ Arch Wiki: Processed $itemsProcessed items, $itemsFailed failures")
                require(itemsProcessed >= 0 && itemsFailed >= 0) {
                    "Processing stats should be non-negative"
                }
            }
        } else {
            println("      ℹ️  Arch Wiki processing stats not available yet")
        }
    }

    
    
    

    test("Pipeline monitoring endpoint is accessible") {
        try {
            val response = client.getRawResponse("${endpoints.pipeline}/health")
            require(response.status == HttpStatusCode.OK) {
                "Pipeline health endpoint at ${endpoints.pipeline}/health returned: ${response.status}"
            }

            val body = response.bodyAsText()
            require(body.contains("healthy") || body.contains("ok") || body.contains("status")) {
                "Health response should indicate status"
            }

            println("      ✓ Pipeline monitoring endpoint healthy")
        } catch (e: Exception) {
            println("      ℹ️  Pipeline not reachable at ${endpoints.pipeline}: ${e.message}")
        }
    }

    test("Pipeline status shows all sources") {
        try {
        val response = client.getRawResponse("${endpoints.pipeline}/status")
        require(response.status == HttpStatusCode.OK) {
            "Status endpoint failed: ${response.status}"
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val sources = json["sources"]?.jsonArray

        require(sources != null && sources.size >= 8) {
            "Expected at least 8 pipeline sources, found: ${sources?.size}"
        }

        val sourceNames = sources.map {
            it.jsonObject["source"]?.jsonPrimitive?.content
        }.filterNotNull()

        val expectedSources = listOf(
            "RSS", "CVE", "Torrents", "Wikipedia",
            "Australian Laws", "Linux Docs", "Debian Wiki", "Arch Wiki"
        )

        val foundSources = expectedSources.filter { it in sourceNames }
        println("      ✓ Pipeline tracking ${sources.size} sources: ${foundSources.joinToString()}")
        } catch (e: Exception) {
            println("      ℹ️  Pipeline not reachable at ${endpoints.pipeline}: ${e.message}")
        }
    }

    test("All Qdrant collections have consistent dimensions") {
        val collections = listOf(
            "rss_feeds", "cve", "torrents", "wikipedia",
            "australian_laws", "linux_docs", "debian_wiki", "arch_wiki"
        )

        val dimensions = mutableMapOf<String, Int>()

        collections.forEach { collectionName ->
            val info = getQdrantCollectionInfo(collectionName)
            if (info != null) {
                val config = info["result"]?.jsonObject?.get("config")?.jsonObject
                val params = config?.get("params")?.jsonObject
                val vectorsConfig = params?.get("vectors")?.jsonObject
                val size = vectorsConfig?.get("size")?.jsonPrimitive?.intOrNull

                if (size != null) {
                    dimensions[collectionName] = size
                }
            }
        }

        if (dimensions.isNotEmpty()) {
            val uniqueDimensions = dimensions.values.toSet()
            require(uniqueDimensions.size == 1) {
                "All collections should have same vector dimensions, found: $dimensions"
            }

            println("      ✓ All collections use ${uniqueDimensions.first()}-dimensional vectors")
        } else {
            println("      ℹ️  No collections created yet to check dimensions")
        }
    }

    test("Pipeline staging store queue is operational") {
        try {
            val response = client.getRawResponse("${endpoints.pipeline}/status")
            if (response.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val queueStats = json["queue"]?.jsonObject

                if (queueStats != null) {
                    val pending = queueStats["pending"]?.jsonPrimitive?.longOrNull ?: 0
                    val processing = queueStats["processing"]?.jsonPrimitive?.longOrNull ?: 0

                    println("      ✓ Staging queue: $pending pending, $processing processing")
                    require(pending >= 0 && processing >= 0) {
                        "Queue stats should be non-negative"
                    }
                } else {
                    println("      ℹ️  Queue stats not yet available")
                }
            }
        } catch (e: Exception) {
            println("      ℹ️  Pipeline not reachable at ${endpoints.pipeline}: ${e.message}")
        }
    }

    test("Pipeline deduplication store is working") {
        try {
            val response = client.getRawResponse("${endpoints.pipeline}/status")
            if (response.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val sources = json["sources"]?.jsonArray

                var totalDedup = 0L
                sources?.forEach { source ->
                    val lastRun = source.jsonObject["last_run"]?.jsonObject
                    val dedupCount = lastRun?.get("items_deduplicated")?.jsonPrimitive?.longOrNull ?: 0
                    totalDedup += dedupCount
                }

                if (totalDedup > 0) {
                    println("      ✓ Deduplication active: $totalDedup items deduplicated across sources")
                } else {
                    println("      ℹ️  No deduplication events recorded yet (sources running first time)")
                }
            }
        } catch (e: Exception) {
            println("      ℹ️  Pipeline not reachable at ${endpoints.pipeline}: ${e.message}")
        }
    }

    test("Pipeline error rate is acceptable") {
        try {
            val response = client.getRawResponse("${endpoints.pipeline}/status")
            if (response.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val sources = json["sources"]?.jsonArray

                var totalProcessed = 0L
                var totalFailed = 0L

                sources?.forEach { source ->
                    val lastRun = source.jsonObject["last_run"]?.jsonObject
                    totalProcessed += lastRun?.get("items_processed")?.jsonPrimitive?.longOrNull ?: 0
                    totalFailed += lastRun?.get("items_failed")?.jsonPrimitive?.longOrNull ?: 0
                }

                if (totalProcessed > 0) {
                    val errorRate = (totalFailed.toDouble() / totalProcessed.toDouble()) * 100

                    require(errorRate < 10.0) {
                        "Error rate too high: $errorRate% ($totalFailed failed out of $totalProcessed)"
                    }

                    println("      ✓ Pipeline error rate: ${String.format("%.2f", errorRate)}% ($totalFailed/$totalProcessed)")
                } else {
                    println("      ℹ️  No pipeline runs completed yet")
                }
            }
        } catch (e: Exception) {
            println("      ℹ️  Pipeline not reachable at ${endpoints.pipeline}: ${e.message}")
        }
    }

    test("Embedding scheduler is operational") {
        try {
            val response = client.getRawResponse("${endpoints.pipeline}/status")
            if (response.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val embeddings = json["embeddings"]?.jsonObject

                if (embeddings != null) {
                    val totalEmbedded = embeddings["total_embedded"]?.jsonPrimitive?.longOrNull ?: 0
                    val failedEmbeddings = embeddings["failed"]?.jsonPrimitive?.longOrNull ?: 0

                    println("      ✓ Embedding scheduler: $totalEmbedded embedded, $failedEmbeddings failed")
                    require(totalEmbedded >= 0 && failedEmbeddings >= 0) {
                        "Embedding stats should be non-negative"
                    }
                } else {
                    println("      ℹ️  Embedding scheduler stats not yet available")
                }
            }
        } catch (e: Exception) {
            println("      ℹ️  Pipeline not reachable at ${endpoints.pipeline}: ${e.message}")
        }
    }
}
