#!/usr/bin/env kotlin
@file:DependsOn("org.yaml:snakeyaml:2.2")

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.yaml.snakeyaml.Yaml

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

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: bootstrap_vectors.main.kts /path/to/collections.yaml")
        kotlin.system.exitProcess(2)
    }

    val yamlPath = args[0]
    val yamlText = File(yamlPath).readText()
    val yaml = Yaml()
    val root = yaml.load<Map<String, Any?>>(yamlText) ?: emptyMap()

    val baseUrl = (getenv("QDRANT_URL", "http://localhost:6333") ?: "http://localhost:6333").trimEnd('/')
    val apiKey = getenv("QDRANT_API_KEY", null)
    val defaultSize = (getenv("VECTOR_SIZE", null)?.toIntOrNull())
        ?: (root["vector_size"] as? Number)?.toInt()
        ?: 384
    val distance = (root["distance"] as? String) ?: "Cosine"

    @Suppress("UNCHECKED_CAST")
    val colList = (root["collections"] as? List<Map<String, Any?>>) ?: emptyList()

    if (colList.isEmpty()) {
        println("[bootstrap_vectors.kts] No collections defined; exiting.")
        return
    }

    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    for (c in colList) {
        val name = c["name"] as? String ?: continue
        val size = (c["vector_size"] as? Number)?.toInt() ?: defaultSize
        val dist = (c["distance"] as? String) ?: distance
        ensureCollection(client, baseUrl, name, size, dist, apiKey)
    }

    println("[bootstrap_vectors.kts] Completed.")
}

main(args)
