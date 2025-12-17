package org.datamancy.unifiedindexer

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Adapter for BookStack as a data source.
 */
class BookStackAdapter(
    private val bookstackUrl: String,
    private val bookstackToken: String,
    private val bookstackSecret: String
) : SourceAdapter {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun getPages(collection: String): List<PageInfo> {
        // Extract jurisdiction from collection name (e.g., "legislation_federal" -> "federal")
        val jurisdiction = collection.removePrefix("legislation_")

        val request = Request.Builder()
            .url("$bookstackUrl/api/search?query=type:page jurisdiction:$jurisdiction&count=1000")
            .get()
            .header("Authorization", "Token $bookstackToken:$bookstackSecret")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to get BookStack pages: ${response.code}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val results = json.getAsJsonArray("data")
            val pages = mutableListOf<PageInfo>()

            results?.forEach { result ->
                val obj = result.asJsonObject
                if (obj.get("type")?.asString == "page") {
                    pages.add(PageInfo(
                        id = obj.get("id").asInt,
                        name = obj.get("name").asString,
                        url = obj.get("url")?.asString ?: ""
                    ))
                }
            }

            return pages
        }
    }

    override suspend fun exportPage(pageId: Int): String {
        val request = Request.Builder()
            .url("$bookstackUrl/api/pages/$pageId/export/plain-text")
            .get()
            .header("Authorization", "Token $bookstackToken:$bookstackSecret")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to export page: ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }
}
