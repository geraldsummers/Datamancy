#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")
@file:DependsOn("com.google.code.gson:gson:2.10.1")

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

val baseUrl = System.getenv("BASE_URL") ?: "http://latium.local"
val client = OkHttpClient.Builder()
    .connectTimeout(120, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()
val gson = Gson()

fun get(url: String): String {
    val request = Request.Builder().url(url).build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: ${response.body?.string()}")
        return response.body?.string() ?: ""
    }
}

fun post(url: String, body: String = "{}"): String {
    val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: ${response.body?.string()}")
        return response.body?.string() ?: ""
    }
}

println("=== AU Law RAG Pipeline Test ===\n")

// Step 1: Check BookStack collections
println("1. Checking existing BookStack collections...")
val collections = get("$baseUrl:10350/api/indexer/collections")
println("Collections: $collections\n")

// Step 2: Check if we have AU law data
println("2. Checking for AU law data...")
val hasAuLaw = collections.contains("australian_legislation") || collections.contains("legislation")
if (!hasAuLaw) {
    println("⚠️  No AU law collection found. Need to fetch data first.\n")
    println("To fetch AU law data, run on the server:")
    println("  docker exec -it data-fetcher /bin/sh")
    println("  # Then run fetcher manually or trigger via API\n")
} else {
    println("✅ AU law collection exists\n")
}

// Step 3: Check Qdrant collections
println("3. Checking Qdrant vector collections...")
try {
    val qdrantCollections = get("$baseUrl:10360/search/collections")
    println("Qdrant collections: $qdrantCollections\n")
} catch (e: Exception) {
    println("⚠️  Could not query Qdrant: ${e.message}\n")
}

// Step 4: Test RAG search if data exists
if (hasAuLaw) {
    println("4. Testing RAG search...")
    val query = "privacy rights data protection"
    println("Query: \"$query\"\n")

    try {
        val searchBody = gson.toJson(mapOf(
            "query" to query,
            "collections" to listOf("*"),
            "mode" to "hybrid",
            "limit" to 5
        ))

        val results = post("$baseUrl:10360/search", searchBody)
        println("Search results:")
        println(results)
    } catch (e: Exception) {
        println("⚠️  Search failed: ${e.message}")
    }
}

println("\n=== Test Complete ===")
