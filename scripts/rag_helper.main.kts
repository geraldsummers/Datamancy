#!/usr/bin/env kotlin

/**
 * RAG Helper - Kotlin Script
 * Simple script to ingest and query documents in Qdrant
 */

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.system.exitProcess

// Simple JSON helpers (avoiding external deps for script simplicity)
fun String.toJsonString() = "\"${this.replace("\"", "\\\"")}\""
fun List<Double>.toJsonArray() = "[${this.joinToString(",")}]"

class RAGHelper(
    private val embeddingUrl: String = "http://embedding-service:8080",
    private val qdrantUrl: String = "http://qdrant:6333"
) {
    private val client = HttpClient.newBuilder().build()

    fun embedText(text: String): List<Double> {
        val requestBody = """{"inputs": ${text.toJsonString()}}"""

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$embeddingUrl/embed"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw Exception("Embedding failed: ${response.statusCode()} - ${response.body()}")
        }

        // Parse JSON manually (simple array extraction)
        val body = response.body()
        val numbers = body.substring(2, body.length - 2) // Remove [[ and ]]
            .split(",")
            .map { it.trim().toDouble() }

        return numbers
    }

    fun ingestDocument(
        text: String,
        collection: String = "rss_aggregation",
        metadata: Map<String, String> = emptyMap()
    ): String {
        val docId = UUID.randomUUID().toString()
        val vector = embedText(text)

        val payloadParts = mutableListOf("\"text\": ${text.toJsonString()}")
        metadata.forEach { (key, value) ->
            payloadParts.add("\"$key\": ${value.toJsonString()}")
        }
        val payload = "{${payloadParts.joinToString(", ")}}"

        val requestBody = """
        {
            "points": [{
                "id": ${docId.toJsonString()},
                "vector": ${vector.toJsonArray()},
                "payload": $payload
            }]
        }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$qdrantUrl/collections/$collection/points?wait=true"))
            .header("Content-Type", "application/json")
            .method("PUT", HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw Exception("Ingestion failed: ${response.statusCode()} - ${response.body()}")
        }

        return docId
    }

    fun search(
        query: String,
        collection: String = "rss_aggregation",
        limit: Int = 5
    ): String {
        val queryVector = embedText(query)

        val requestBody = """
        {
            "vector": ${queryVector.toJsonArray()},
            "limit": $limit,
            "with_payload": true
        }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$qdrantUrl/collections/$collection/points/search"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw Exception("Search failed: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    fun getCollectionInfo(collection: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$qdrantUrl/collections/$collection"))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw Exception("Failed to get info: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }
}

// Helper function to parse search results and display them nicely
fun displaySearchResults(jsonResponse: String) {
    // Extract results array from JSON (simple parsing)
    val resultsStart = jsonResponse.indexOf("\"result\":[") + 10
    val resultsEnd = jsonResponse.indexOf("]", resultsStart) + 1

    if (resultsStart < 10 || resultsEnd <= resultsStart) {
        println("No results found")
        return
    }

    // Count results by counting score occurrences
    val scoreCount = jsonResponse.substring(resultsStart, resultsEnd).split("\"score\":").size - 1
    println("\nFound $scoreCount results:\n")

    var index = 1
    var searchPos = resultsStart

    while (searchPos < resultsEnd) {
        val scoreIdx = jsonResponse.indexOf("\"score\":", searchPos)
        if (scoreIdx == -1 || scoreIdx > resultsEnd) break

        val scoreStart = scoreIdx + 8
        val scoreEnd = jsonResponse.indexOf(",", scoreStart)
        val score = jsonResponse.substring(scoreStart, scoreEnd).trim().toDoubleOrNull() ?: 0.0

        val textIdx = jsonResponse.indexOf("\"text\":", scoreEnd)
        if (textIdx != -1 && textIdx < resultsEnd) {
            val textStart = jsonResponse.indexOf("\"", textIdx + 7) + 1
            val textEnd = jsonResponse.indexOf("\"", textStart)
            val text = jsonResponse.substring(textStart, textEnd)
                .replace("\\\"", "\"")
                .replace("\\n", " ")

            println("$index. Score: %.4f".format(score))
            val displayText = if (text.length > 100) text.substring(0, 100) + "..." else text
            println("   Text: $displayText")
            println()

            index++
        }

        searchPos = if (textIdx != -1) textIdx + 10 else resultsEnd
    }
}

// Main script logic
if (args.isEmpty()) {
    println("Usage:")
    println("  Ingest: kotlin rag_helper.main.kts ingest <text> [collection]")
    println("  Search: kotlin rag_helper.main.kts search <query> [collection] [limit]")
    println("  Info:   kotlin rag_helper.main.kts info [collection]")
    exitProcess(1)
}

val rag = RAGHelper()
val command = args[0]

when (command) {
    "ingest" -> {
        if (args.size < 2) {
            println("Error: Text required for ingestion")
            exitProcess(1)
        }

        val text = args[1]
        val collection = if (args.size > 2) args[2] else "rss_aggregation"

        println("Ingesting document into '$collection'...")
        val docId = rag.ingestDocument(text, collection, mapOf("source" to "cli"))
        println("✓ Document ingested with ID: $docId")
    }

    "search" -> {
        if (args.size < 2) {
            println("Error: Query required for search")
            exitProcess(1)
        }

        val query = args[1]
        val collection = if (args.size > 2) args[2] else "rss_aggregation"
        val limit = if (args.size > 3) args[3].toInt() else 5

        println("Searching '$collection' for: $query")
        val results = rag.search(query, collection, limit)
        displaySearchResults(results)
    }

    "info" -> {
        val collection = if (args.size > 1) args[1] else "rss_aggregation"

        val info = rag.getCollectionInfo(collection)

        // Extract key information
        val vectorsCount = info.substringAfter("\"vectors_count\":").substringBefore(",").trim()

        println("Collection: $collection")
        println("Vectors: $vectorsCount")

        // Extract vector size
        if (info.contains("\"size\":")) {
            val size = info.substringAfter("\"size\":").substringBefore(",").trim()
            println("Vector size: $size")
        }
    }

    else -> {
        println("Error: Unknown command '$command'")
        exitProcess(1)
    }
}
