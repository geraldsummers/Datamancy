#!/usr/bin/env kotlin


/**
 * RAG Query - Kotlin Script
 * Answer questions using retrieved context and LLM
 */

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

// Simple JSON helpers
fun String.toJsonString() = "\"${this.replace("\"", "\\\"").replace("\n", "\\n")}\""
fun List<Double>.toJsonArray() = "[${this.joinToString(",")}]"

class RAGQuery(
    private val embeddingUrl: String = "http://embedding-service:8080",
    private val qdrantUrl: String = "http://qdrant:6333",
    private val llmUrl: String = "http://litellm:4000/v1",
    private val llmApiKey: String = "sk-8a95f0e47897d7734a7d76e1fbe0b9d2c719979c992e5d116bdeb158436da770"
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
            throw Exception("Embedding failed: ${response.statusCode()}")
        }

        val body = response.body()
        val numbers = body.substring(2, body.length - 2)
            .split(",")
            .map { it.trim().toDouble() }

        return numbers
    }

    data class SearchResult(
        val score: Double,
        val text: String
    )

    fun search(
        query: String,
        collection: String = "rss_aggregation",
        limit: Int = 3
    ): List<SearchResult> {
        val queryVector = embedText(query)

        val requestBody = """
        {
            "vector": ${queryVector.toJsonArray()},
            "limit": $limit,
            "with_payload": true,
            "score_threshold": 0.0
        }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$qdrantUrl/collections/$collection/points/search"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw Exception("Search failed: ${response.statusCode()}")
        }

        // Parse results manually
        val results = mutableListOf<SearchResult>()
        val body = response.body()

        var searchPos = 0
        while (true) {
            val scoreIdx = body.indexOf("\"score\":", searchPos)
            if (scoreIdx == -1) break

            val scoreStart = scoreIdx + 8
            val scoreEnd = body.indexOf(",", scoreStart)
            val score = body.substring(scoreStart, scoreEnd).trim().toDoubleOrNull() ?: 0.0

            val textIdx = body.indexOf("\"text\":", scoreEnd)
            if (textIdx != -1) {
                val textStart = body.indexOf("\"", textIdx + 7) + 1
                val textEnd = body.indexOf("\"", textStart)
                val text = body.substring(textStart, textEnd)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")

                results.add(SearchResult(score, text))
                searchPos = textEnd
            } else {
                break
            }
        }

        return results
    }

    fun askLLM(
        question: String,
        context: String,
        model: String = "hermes-2-pro-mistral-7b"
    ): String {
        val prompt = """You are a helpful assistant. Use the following context to answer the question.

Context:
$context

Question: $question

Answer:"""

        val systemMsg = "You are a helpful assistant that answers questions based on the provided context."

        val requestBody = """
        {
            "model": "$model",
            "messages": [
                {"role": "system", "content": ${systemMsg.toJsonString()}},
                {"role": "user", "content": ${prompt.toJsonString()}}
            ],
            "max_tokens": 500,
            "temperature": 0.7
        }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$llmUrl/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $llmApiKey")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw Exception("LLM request failed: ${response.statusCode()} - ${response.body()}")
        }

        // Extract content from response
        val body = response.body()
        val contentIdx = body.indexOf("\"content\":")
        if (contentIdx == -1) {
            throw Exception("No content in LLM response")
        }

        val contentStart = body.indexOf("\"", contentIdx + 10) + 1
        val contentEnd = body.indexOf("\"", contentStart)
        val content = body.substring(contentStart, contentEnd)
            .replace("\\n", "\n")
            .replace("\\\"", "\"")

        return content
    }

    fun query(
        question: String,
        collection: String = "rss_aggregation",
        topK: Int = 3,
        verbose: Boolean = true
    ): String {
        // Step 1: Retrieve relevant documents
        if (verbose) {
            println("🔍 Searching for relevant context...")
        }

        val results = search(question, collection, topK)

        if (results.isEmpty()) {
            return "No relevant context found in the knowledge base."
        }

        // Step 2: Build context
        if (verbose) {
            println("✓ Found ${results.size} relevant documents:\n")
        }

        val contextParts = results.mapIndexed { index, result ->
            if (verbose) {
                println("${index + 1}. Score: %.4f".format(result.score))
                val displayText = if (result.text.length > 100) {
                    result.text.substring(0, 100) + "..."
                } else {
                    result.text
                }
                println("   $displayText\n")
            }

            "Document ${index + 1}: ${result.text}"
        }

        val context = contextParts.joinToString("\n\n")

        // Step 3: Generate answer
        if (verbose) {
            println("🤖 Generating answer using LLM...\n")
        }

        val answer = askLLM(question, context)

        return answer
    }
}

// Main script logic
if (args.isEmpty()) {
    println("Usage: kotlin rag_query.main.kts <question> [collection] [top_k]")
    println("\nExample:")
    println("  kotlin rag_query.main.kts \"How does AI help with software development?\"")
    exitProcess(1)
}

val question = args[0]
val collection = if (args.size > 1) args[1] else "rss_aggregation"
val topK = if (args.size > 2) args[2].toInt() else 3

val rag = RAGQuery()

println("=".repeat(70))
println("Question: $question")
println("=".repeat(70))
println()

val answer = rag.query(question, collection, topK, verbose = true)

println("=".repeat(70))
println("ANSWER:")
println("=".repeat(70))
println(answer)
println()
