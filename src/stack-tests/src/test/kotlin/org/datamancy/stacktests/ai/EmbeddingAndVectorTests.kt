package org.datamancy.stacktests.ai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.datamancy.stacktests.base.BaseStackTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for embedding service and vector database operations.
 *
 * Tests cover:
 * - Embedding service model loading and health
 * - Document embedding generation
 * - Qdrant collection management
 * - Vector insertion and retrieval
 * - Similarity search operations
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EmbeddingAndVectorTests : BaseStackTest() {

    private val testCollectionName = "test_embeddings_collection"
    private val embeddingServiceUrl = localhostPorts.httpUrl(localhostPorts.embeddingService)
    private val qdrantUrl = localhostPorts.qdrantUrl()

    @Test
    @Order(1)
    fun `embedding service is healthy and ready`() = runBlocking {
        val response = client.get("$embeddingServiceUrl/health")

        assertEquals(HttpStatusCode.OK, response.status,
            "Embedding service should be healthy")

        println("✓ Embedding service is healthy")
    }

    @Test
    @Order(2)
    fun `embedding service can generate embeddings`() = runBlocking {
        val testText = "This is a test document for embedding generation"

        val response = client.post("$embeddingServiceUrl/embed") {
            contentType(ContentType.Application.Json)
            setBody("""{"inputs": "$testText"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Embedding generation should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText())
        val embeddings = json.jsonArray[0].jsonArray

        assertEquals(384, embeddings.size,
            "Embedding should be 384 dimensions (all-MiniLM-L6-v2)")

        // Verify embeddings are valid floats
        val firstValue = embeddings[0].jsonPrimitive.float
        assertTrue(firstValue.isFinite(), "Embedding values should be finite")

        println("✓ Embedding service generated 384-dimensional vector")
    }

    @Test
    @Order(3)
    fun `embedding service handles batch requests`() = runBlocking {
        val testTexts = listOf(
            "First document about machine learning",
            "Second document about data science",
            "Third document about artificial intelligence"
        )

        val response = client.post("$embeddingServiceUrl/embed") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("inputs", JsonArray(testTexts.map { JsonPrimitive(it) }))
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Batch embedding should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray

        assertEquals(3, json.size,
            "Should generate 3 embeddings for 3 inputs")

        json.forEach { embedding ->
            assertEquals(384, embedding.jsonArray.size,
                "Each embedding should be 384 dimensions")
        }

        println("✓ Embedding service processed batch of 3 documents")
    }

    @Test
    @Order(4)
    fun `Qdrant can create a collection`() = runBlocking {
        // Delete if exists
        client.delete("$qdrantUrl/collections/$testCollectionName")

        // Create new collection
        val response = client.put("$qdrantUrl/collections/$testCollectionName") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "vectors": {
                        "size": 384,
                        "distance": "Cosine"
                    }
                }
            """.trimIndent())
        }

        assertTrue(response.status.value in 200..299,
            "Collection creation should succeed (got ${response.status})")

        println("✓ Qdrant collection '$testCollectionName' created")
    }

    @Test
    @Order(5)
    fun `Qdrant collection info can be retrieved`() = runBlocking {
        val response = client.get("$qdrantUrl/collections/$testCollectionName")

        assertEquals(HttpStatusCode.OK, response.status,
            "Collection info retrieval should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result = json["result"]?.jsonObject

        assertNotNull(result, "Response should contain result")

        // Verify collection exists
        assertTrue(result!!.isNotEmpty(), "Result should not be empty")

        val vectorsConfig = result["config"]?.jsonObject?.get("params")?.jsonObject
            ?.get("vectors")?.jsonObject
        assertNotNull(vectorsConfig, "Vectors config should exist")
        assertEquals(384, vectorsConfig!!["size"]?.jsonPrimitive?.int,
            "Vector size should be 384")

        println("✓ Qdrant collection info retrieved successfully")
    }

    @Test
    @Order(6)
    fun `can insert vectors into Qdrant collection`() = runBlocking {
        // Generate embeddings for test documents
        val testDocs = listOf(
            "Machine learning is a subset of artificial intelligence",
            "Data science involves statistics and programming",
            "Neural networks are inspired by biological neurons"
        )

        val embedResponse = client.post("$embeddingServiceUrl/embed") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("inputs", JsonArray(testDocs.map { JsonPrimitive(it) }))
            }.toString())
        }

        val embeddings = Json.parseToJsonElement(embedResponse.bodyAsText()).jsonArray

        // Insert vectors into Qdrant
        val points = buildJsonArray {
            testDocs.forEachIndexed { index, doc ->
                add(buildJsonObject {
                    put("id", index + 1)
                    put("vector", embeddings[index].jsonArray)
                    put("payload", buildJsonObject {
                        put("text", doc)
                        put("index", index)
                    })
                })
            }
        }

        val insertResponse = client.put("$qdrantUrl/collections/$testCollectionName/points") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("points", points)
            }.toString())
        }

        assertTrue(insertResponse.status.value in 200..299,
            "Vector insertion should succeed (got ${insertResponse.status})")

        println("✓ Inserted 3 vectors into Qdrant collection")
    }

    @Test
    @Order(7)
    fun `can perform similarity search in Qdrant`() = runBlocking {
        // Wait for indexing
        kotlinx.coroutines.delay(1000)

        // Generate query embedding
        val queryText = "artificial intelligence and machine learning"
        val embedResponse = client.post("$embeddingServiceUrl/embed") {
            contentType(ContentType.Application.Json)
            setBody("""{"inputs": "$queryText"}""")
        }

        val queryVector = Json.parseToJsonElement(embedResponse.bodyAsText())
            .jsonArray[0].jsonArray

        // Search for similar vectors
        val searchResponse = client.post("$qdrantUrl/collections/$testCollectionName/points/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("vector", queryVector)
                put("limit", 3)
                put("with_payload", true)
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, searchResponse.status,
            "Search should succeed")

        val results = Json.parseToJsonElement(searchResponse.bodyAsText())
            .jsonObject["result"]?.jsonArray

        assertNotNull(results, "Should have search results")
        assertTrue(results!!.size > 0, "Should find at least one similar vector")

        // Verify first result has expected structure
        val firstResult = results[0].jsonObject
        assertTrue(firstResult.containsKey("id"), "Result should have id")
        assertTrue(firstResult.containsKey("score"), "Result should have score")
        assertTrue(firstResult.containsKey("payload"), "Result should have payload")

        val score = firstResult["score"]?.jsonPrimitive?.float ?: 0f
        assertTrue(score > 0.5, "Top result should have high similarity score (got $score)")

        println("✓ Similarity search found ${results.size} similar vectors")
        println("  Top match score: $score")
    }

    @Test
    @Order(8)
    fun `can filter vectors by payload in Qdrant`() = runBlocking {
        val response = client.post("$qdrantUrl/collections/$testCollectionName/points/scroll") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("limit", 10)
                put("with_payload", true)
                put("with_vector", false)
                put("filter", buildJsonObject {
                    put("must", buildJsonArray {
                        add(buildJsonObject {
                            put("key", "index")
                            put("match", buildJsonObject {
                                put("value", 0)
                            })
                        })
                    })
                })
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Filtered scroll should succeed")

        val results = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["result"]?.jsonObject?.get("points")?.jsonArray

        assertNotNull(results, "Should have filtered results")
        assertEquals(1, results!!.size, "Should find exactly 1 document with index=0")

        val payload = results[0].jsonObject["payload"]?.jsonObject
        assertEquals(0, payload?.get("index")?.jsonPrimitive?.int,
            "Filtered document should have index=0")

        println("✓ Payload filtering working correctly")
    }

    @Test
    @Order(9)
    fun `can retrieve vector by ID from Qdrant`() = runBlocking {
        val response = client.get("$qdrantUrl/collections/$testCollectionName/points/1")

        assertEquals(HttpStatusCode.OK, response.status,
            "Point retrieval should succeed")

        val point = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["result"]?.jsonObject

        assertNotNull(point, "Should retrieve point")
        assertEquals(1, point!!["id"]?.jsonPrimitive?.int, "ID should match")

        val vector = point["vector"]?.jsonArray
        assertEquals(384, vector?.size, "Retrieved vector should be 384 dimensions")

        println("✓ Successfully retrieved vector by ID")
    }

    @Test
    @Order(10)
    fun `can count vectors in Qdrant collection`() = runBlocking {
        val response = client.get("$qdrantUrl/collections/$testCollectionName")

        val collectionInfo = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["result"]?.jsonObject

        val pointsCount = collectionInfo?.get("points_count")?.jsonPrimitive?.long

        assertEquals(3, pointsCount?.toInt(),
            "Collection should contain 3 vectors")

        println("✓ Collection contains $pointsCount vectors")
    }

    @Test
    @Order(11)
    fun `cleanup - delete test collection`() = runBlocking {
        val response = client.delete("$qdrantUrl/collections/$testCollectionName")

        assertTrue(response.status.value in 200..299,
            "Collection deletion should succeed")

        // Verify deletion
        val verifyResponse = client.get("$qdrantUrl/collections/$testCollectionName")
        assertEquals(HttpStatusCode.NotFound, verifyResponse.status,
            "Collection should no longer exist")

        println("✓ Test collection cleaned up")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║     Embedding Service & Vector Database Tests     ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }
    }
}
