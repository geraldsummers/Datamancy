#!/usr/bin/env kotlin

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class CollectionCfg(
    val name: String?,
    val vector_size: Int? = null,
    val distance: String? = null
)

fun getenv(name: String, def: String? = null): String? = System.getenv(name) ?: def

fun ensureCollection(
    client: HttpClient,
    baseUrl: String,
    name: String,
    vectorSize: Int,
    distance: String,
    apiKey: String?
) {
    val headers = mutableListOf<String>()
    if (!apiKey.isNullOrBlank()) {
        headers += listOf("api-key", apiKey)
    }

    // Check if collection exists
    run {
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/collections/$name"))
            .timeout(Duration.ofSeconds(10))
            .GET()
        for (i in headers.indices step 2) {
            reqBuilder.header(headers[i], headers[i + 1])
        }
        val resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding())
        if (resp.statusCode() == 200) {
            println("[bootstrap_vectors.kts] Collection exists: $name")
            return
        }
        if (resp.statusCode() != 404) {
            error("Unexpected response when checking collection $name: ${resp.statusCode()}")
        }
    }

    // Create collection
    val payload = """
        {"vectors":{"size":$vectorSize,"distance":"$distance"}}
    """.trimIndent()
    val reqBuilder = HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl/collections/$name"))
        .timeout(Duration.ofSeconds(10))
        .PUT(HttpRequest.BodyPublishers.ofString(payload))
        .header("Content-Type", "application/json")
    for (i in headers.indices step 2) {
        reqBuilder.header(headers[i], headers[i + 1])
    }
    val resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() !in listOf(200, 201)) {
        error("Failed to create collection $name: ${resp.statusCode()} ${resp.body()}")
    }
    println("[bootstrap_vectors.kts] Created collection: $name")
}

/**
 * Simple YAML parser for collections.yaml (avoids external dependencies)
 * Expected format:
 *   vector_size: 1024  # Global default
 *   distance: Cosine   # Global default
 *   collections:
 *     - name: collection1
 *       vector_size: 1024  # Optional override
 *     - name: collection2
 */
fun parseCollectionsYaml(yamlText: String): Pair<Map<String, String>, List<Map<String, String>>> {
    val globalDefaults = mutableMapOf<String, String>()
    val collections = mutableListOf<Map<String, String>>()
    val lines = yamlText.lines()
    var inCollections = false
    var currentCollection = mutableMapOf<String, String>()

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("collections:") -> {
                inCollections = true
            }
            !inCollections && trimmed.startsWith("vector_size:") -> {
                globalDefaults["vector_size"] = trimmed.removePrefix("vector_size:").trim()
            }
            !inCollections && trimmed.startsWith("distance:") -> {
                globalDefaults["distance"] = trimmed.removePrefix("distance:").trim()
            }
            inCollections && trimmed.startsWith("- name:") -> {
                if (currentCollection.isNotEmpty()) {
                    collections.add(currentCollection)
                }
                currentCollection = mutableMapOf()
                currentCollection["name"] = trimmed.removePrefix("- name:").trim()
            }
            inCollections && trimmed.startsWith("name:") -> {
                currentCollection["name"] = trimmed.removePrefix("name:").trim()
            }
            inCollections && trimmed.startsWith("vector_size:") -> {
                currentCollection["vector_size"] = trimmed.removePrefix("vector_size:").trim()
            }
            inCollections && trimmed.startsWith("distance:") -> {
                currentCollection["distance"] = trimmed.removePrefix("distance:").trim()
            }
        }
    }
    if (currentCollection.isNotEmpty()) {
        collections.add(currentCollection)
    }
    return Pair(globalDefaults, collections)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: bootstrap_vectors.main.kts /path/to/collections.yaml")
        kotlin.system.exitProcess(2)
    }

    val yamlPath = args[0]
    val yamlText = File(yamlPath).readText()
    val (globalDefaults, collections) = parseCollectionsYaml(yamlText)

    val baseUrl = (getenv("QDRANT_URL", "http://localhost:6333") ?: "http://localhost:6333").trimEnd('/')
    val apiKey = getenv("QDRANT_API_KEY", null)

    // Priority: global YAML default > env var > hardcoded fallback
    val defaultSize = globalDefaults["vector_size"]?.toIntOrNull()
                      ?: getenv("VECTOR_SIZE", "1024")?.toIntOrNull()
                      ?: 1024
    val defaultDistance = globalDefaults["distance"] ?: "Cosine"

    if (collections.isEmpty()) {
        println("[bootstrap_vectors.kts] No collections defined; exiting.")
        return
    }

    println("[bootstrap_vectors.kts] Default vector size: $defaultSize")
    println("[bootstrap_vectors.kts] Default distance: $defaultDistance")

    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    for (c in collections) {
        val name = c["name"] ?: continue
        val size = c["vector_size"]?.toIntOrNull() ?: defaultSize
        val distance = c["distance"] ?: defaultDistance
        ensureCollection(client, baseUrl, name, size, distance, apiKey)
    }

    println("[bootstrap_vectors.kts] Completed.")
}

main(args)
