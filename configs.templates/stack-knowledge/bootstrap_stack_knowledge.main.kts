#!/usr/bin/env kotlin

@file:DependsOn("io.ktor:ktor-client-core:2.3.7")
@file:DependsOn("io.ktor:ktor-client-cio:2.3.7")
@file:DependsOn("io.ktor:ktor-client-content-negotiation:2.3.7")
@file:DependsOn("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

// ============================================================================
// Configuration
// ============================================================================

val QDRANT_URL = System.getenv("QDRANT_URL") ?: "http://qdrant:6333"
val EMBEDDING_URL = System.getenv("EMBEDDING_URL") ?: "http://embedding-service:8080"
val COLLECTION_NAME = "stack_knowledge"
val VECTOR_SIZE = 1024  // bge-m3 embeddings
val KNOWLEDGE_DIR = "/configs/stack-knowledge"

// ============================================================================
// Data Classes
// ============================================================================

@Serializable
data class VectorParams(
    val size: Int,
    val distance: String = "Cosine"
)

@Serializable
data class CreateCollectionRequest(
    val vectors: VectorParams
)

@Serializable
data class Point(
    val id: String,
    val vector: List<Float>,
    val payload: JsonObject
)

@Serializable
data class UpsertRequest(
    val points: List<Point>
)

@Serializable
data class EmbedRequest(
    val text: String,
    val model: String = "bge-m3"
)

@Serializable
data class EmbedResponse(
    val embedding: List<Float>
)

// ============================================================================
// Functions
// ============================================================================

suspend fun createHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }
}

suspend fun createCollection(client: HttpClient) {
    println("Creating collection: $COLLECTION_NAME")

    // Delete existing collection if it exists
    try {
        val deleteResponse = client.delete("$QDRANT_URL/collections/$COLLECTION_NAME")
        println("Deleted existing collection")
    } catch (e: Exception) {
        println("No existing collection to delete")
    }

    // Create new collection
    val createResponse = client.put("$QDRANT_URL/collections/$COLLECTION_NAME") {
        contentType(ContentType.Application.Json)
        setBody(CreateCollectionRequest(vectors = VectorParams(size = VECTOR_SIZE)))
    }

    if (createResponse.status.isSuccess()) {
        println("✓ Collection created successfully")
    } else {
        println("✗ Failed to create collection: ${createResponse.bodyAsText()}")
        throw Exception("Collection creation failed")
    }
}

suspend fun generateEmbedding(client: HttpClient, text: String): List<Float> {
    val response = client.post("$EMBEDDING_URL/embed") {
        contentType(ContentType.Application.Json)
        setBody(EmbedRequest(text = text, model = "bge-m3"))
    }

    if (!response.status.isSuccess()) {
        println("✗ Embedding failed: ${response.bodyAsText()}")
        throw Exception("Embedding generation failed")
    }

    val embedResponse: EmbedResponse = response.body()
    return embedResponse.embedding
}

data class DocumentChunk(
    val title: String,
    val category: String,
    val section: String,
    val content: String,
    val filepath: String,
    val chunkIndex: Int
)

fun parseMarkdownFile(file: File): List<DocumentChunk> {
    val content = file.readText()
    val filename = file.name
    val category = filename.substringBefore("-").padStart(2, '0')
    val title = filename.substringAfter("-").removeSuffix(".md")
        .replace("-", " ")
        .split(" ")
        .joinToString(" ") { it.capitalize() }

    val chunks = mutableListOf<DocumentChunk>()
    val lines = content.lines()

    var currentSection = ""
    var currentContent = StringBuilder()
    var chunkIndex = 0

    for (line in lines) {
        when {
            line.startsWith("# ") -> {
                // Main title - save previous chunk if exists
                if (currentContent.isNotEmpty()) {
                    chunks.add(
                        DocumentChunk(
                            title = title,
                            category = category,
                            section = currentSection,
                            content = currentContent.toString().trim(),
                            filepath = file.name,
                            chunkIndex = chunkIndex++
                        )
                    )
                    currentContent = StringBuilder()
                }
                currentSection = line.removePrefix("# ").trim()
            }
            line.startsWith("## ") || line.startsWith("### ") -> {
                // Section header - save previous chunk if exists
                if (currentContent.length > 500) {  // Only chunk if substantial content
                    chunks.add(
                        DocumentChunk(
                            title = title,
                            category = category,
                            section = currentSection,
                            content = currentContent.toString().trim(),
                            filepath = file.name,
                            chunkIndex = chunkIndex++
                        )
                    )
                    currentContent = StringBuilder()
                }
                currentSection = line.removePrefix("###").removePrefix("##").trim()
                currentContent.append(line).append("\n")
            }
            else -> {
                currentContent.append(line).append("\n")

                // Create chunk if content is large enough
                if (currentContent.length > 2000) {
                    chunks.add(
                        DocumentChunk(
                            title = title,
                            category = category,
                            section = currentSection,
                            content = currentContent.toString().trim(),
                            filepath = file.name,
                            chunkIndex = chunkIndex++
                        )
                    )
                    currentContent = StringBuilder()
                }
            }
        }
    }

    // Add final chunk
    if (currentContent.isNotEmpty()) {
        chunks.add(
            DocumentChunk(
                title = title,
                category = category,
                section = currentSection,
                content = currentContent.toString().trim(),
                filepath = file.name,
                chunkIndex = chunkIndex
            )
        )
    }

    return chunks
}

suspend fun ingestDocuments(client: HttpClient) {
    val knowledgeDir = File(KNOWLEDGE_DIR)
    if (!knowledgeDir.exists()) {
        println("✗ Knowledge directory not found: $KNOWLEDGE_DIR")
        return
    }

    val markdownFiles = knowledgeDir.listFiles { file ->
        file.extension == "md" && file.name.matches(Regex("\\d+-.*\\.md"))
    }?.sortedBy { it.name } ?: emptyList()

    if (markdownFiles.isEmpty()) {
        println("✗ No markdown files found in $KNOWLEDGE_DIR")
        return
    }

    println("\nFound ${markdownFiles.size} knowledge documents")

    var totalChunks = 0
    val allPoints = mutableListOf<Point>()

    for (file in markdownFiles) {
        println("\nProcessing: ${file.name}")
        val chunks = parseMarkdownFile(file)
        println("  Extracted ${chunks.size} chunks")

        for (chunk in chunks) {
            // Generate embedding
            val text = "${chunk.title} - ${chunk.section}\n\n${chunk.content}"
            val embedding = generateEmbedding(client, text)

            // Create point
            val payload = buildJsonObject {
                put("title", chunk.title)
                put("category", chunk.category)
                put("section", chunk.section)
                put("content", chunk.content)
                put("filepath", chunk.filepath)
                put("chunk_index", chunk.chunkIndex)
            }

            allPoints.add(
                Point(
                    id = UUID.randomUUID().toString(),
                    vector = embedding,
                    payload = payload
                )
            )

            totalChunks++

            if (totalChunks % 10 == 0) {
                print(".")
            }
        }

        println("\n  ✓ Processed ${chunks.size} chunks from ${file.name}")
    }

    // Upsert all points in batches
    println("\nUpserting $totalChunks points to Qdrant...")

    val batchSize = 50
    allPoints.chunked(batchSize).forEachIndexed { index, batch ->
        val upsertResponse = client.put("$QDRANT_URL/collections/$COLLECTION_NAME/points") {
            contentType(ContentType.Application.Json)
            setBody(UpsertRequest(points = batch))
        }

        if (upsertResponse.status.isSuccess()) {
            println("  ✓ Batch ${index + 1}/${(totalChunks + batchSize - 1) / batchSize} uploaded")
        } else {
            println("  ✗ Batch ${index + 1} failed: ${upsertResponse.bodyAsText()}")
        }
    }

    println("\n✓ Successfully ingested $totalChunks knowledge chunks")
}

suspend fun verifyCollection(client: HttpClient) {
    println("\nVerifying collection...")

    val response = client.get("$QDRANT_URL/collections/$COLLECTION_NAME")

    if (response.status.isSuccess()) {
        val info = response.bodyAsText()
        println("✓ Collection info:")
        println(info)
    } else {
        println("✗ Failed to get collection info")
    }
}

// ============================================================================
// Main
// ============================================================================

runBlocking {
    val client = createHttpClient()

    try {
        println("╔════════════════════════════════════════╗")
        println("║  Stack Knowledge Bootstrap             ║")
        println("║  Vectorizing documentation for AI      ║")
        println("╚════════════════════════════════════════╝\n")

        println("Configuration:")
        println("  Qdrant: $QDRANT_URL")
        println("  Embedding: $EMBEDDING_URL")
        println("  Collection: $COLLECTION_NAME")
        println("  Knowledge Dir: $KNOWLEDGE_DIR")
        println()

        // Step 1: Create collection
        createCollection(client)

        // Step 2: Ingest documents
        ingestDocuments(client)

        // Step 3: Verify
        verifyCollection(client)

        println("\n✓ Stack knowledge base ready for AI context retrieval!")

    } catch (e: Exception) {
        println("\n✗ Error: ${e.message}")
        e.printStackTrace()
    } finally {
        client.close()
    }
}
