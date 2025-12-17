package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.SearchConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore
import java.net.URLEncoder

private val logger = KotlinLogging.logger {}
private val gson = Gson()

class SearchFetcher(private val config: SearchConfig) : Fetcher {
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("search", version = "2.0.0") { ctx ->
            logger.info { "Fetching search results for ${config.queries.size} queries..." }

            if (config.apiKey.isBlank()) {
                logger.warn { "SerpAPI key not configured - skipping search" }
                return@execute "SerpAPI key not configured"
            }

            if (config.queries.isEmpty()) {
                logger.warn { "No search queries configured" }
                return@execute "No queries configured"
            }

            config.queries.forEach { query ->
                ctx.markAttempted()
                try {
                    fetchSearchResults(ctx, query)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch search results for: $query" }
                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", query)
                }
            }

            "Processed ${ctx.metrics.attempted} queries: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped"
        }
    }

    private suspend fun fetchSearchResults(ctx: FetchExecutionContext, query: String) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://serpapi.com/search.json?q=$encodedQuery&api_key=${config.apiKey}&num=10"

        val response = ctx.http.get(url)
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }

        val body = response.body?.string()
        response.close()

        if (body == null) {
            throw Exception("Empty response")
        }

        val json = JsonParser.parseString(body).asJsonObject
        val results = json.getAsJsonArray("organic_results")

        if (results == null || results.size() == 0) {
            ctx.markSkipped()
            return
        }

        // Create SERP snapshot
        val timestamp = Clock.System.now()
        val snapshotId = "${query.hashCode()}_${timestamp.toString().take(13)}" // Hour precision

        val serpSnapshot = mutableListOf<Map<String, Any>>()

        results.forEachIndexed { index, resultElement ->
            val result = resultElement.asJsonObject
            val title = result.get("title")?.asString ?: ""
            val link = result.get("link")?.asString ?: ""
            val snippet = result.get("snippet")?.asString ?: ""
            val displayedLink = result.get("displayed_link")?.asString ?: ""

            val resultData = mapOf(
                "rank" to (index + 1),
                "title" to title,
                "link" to link,
                "snippet" to snippet,
                "displayedLink" to displayedLink,
                "query" to query
            )

            serpSnapshot.add(resultData)

            // Store individual result for rank tracking
            val resultId = "${query.hashCode()}_${link.hashCode()}"
            val resultJson = gson.toJson(resultData)
            val resultHash = ContentHasher.hashJson(resultJson)

            when (ctx.dedupe.shouldUpsert(resultId, resultHash)) {
                DedupeResult.NEW -> ctx.markNew()
                DedupeResult.UPDATED -> ctx.markUpdated()
                DedupeResult.UNCHANGED -> {}
            }
        }

        // Create snapshot with all results
        val snapshotData = mapOf(
            "query" to query,
            "timestamp" to timestamp.toString(),
            "results" to serpSnapshot
        )

        val snapshotJson = gson.toJson(snapshotData)
        val snapshotHash = ContentHasher.hashJson(snapshotJson)

        // Dedupe snapshot
        when (ctx.dedupe.shouldUpsert(snapshotId, snapshotHash)) {
            DedupeResult.NEW -> {
                ctx.storage.storeRawText(snapshotId, body, "json")
                ctx.markFetched()
            }
            DedupeResult.UPDATED -> {
                ctx.storage.storeRawText(snapshotId, body, "json")
                ctx.markFetched()
            }
            DedupeResult.UNCHANGED -> {
                ctx.markSkipped()
            }
        }

        pgStore.storeFetchMetadata(
            source = "search",
            category = "serp",
            itemCount = results.size(),
            fetchedAt = Clock.System.now(),
            metadata = mapOf(
                "query" to query,
                "resultCount" to results.size()
            )
        )

        logger.info { "Search: '$query' - ${results.size()} results" }
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying search configuration..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check API key
        checks.add(DryRunUtils.checkApiKey(config.apiKey, "SerpAPI / Google Search"))

        // If API key configured, test SerpAPI
        if (config.apiKey.isNotBlank()) {
            checks.add(
                DryRunUtils.checkApiEndpoint(
                    "https://serpapi.com/search.json?q=test&api_key=${config.apiKey}",
                    null,
                    "SerpAPI"
                )
            )
        }

        // Check if queries are configured
        checks.add(
            DryRunUtils.checkConfig(
                if (config.queries.isEmpty()) null else config.queries.joinToString("; "),
                "Search queries",
                required = false
            )
        )

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/search", "Search results directory"))

        return DryRunResult(checks)
    }
}
