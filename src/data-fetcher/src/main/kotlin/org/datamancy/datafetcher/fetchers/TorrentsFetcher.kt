package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.TorrentsConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore
import java.net.URLEncoder

private val logger = KotlinLogging.logger {}
private val gson = Gson()

data class TorrentMetadata(
    val infoHash: String,
    val name: String,
    val magnetLink: String,
    val seeders: Int,
    val leechers: Int,
    val size: Long,
    val createdAt: String,
    val category: String
)

class TorrentsFetcher(private val config: TorrentsConfig) : Fetcher {
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("torrents", version = "2.0.0") { ctx ->
            // For MVP: Use curated search queries for demonstration
            val searchQueries = listOf(
                "debian",
                "ubuntu",
                "linux kernel",
                "open source"
            )

            logger.info { "Fetching torrent metadata for ${searchQueries.size} queries..." }

            // SAFETY: Metadata-only mode by default
            if (config.autoDownload) {
                logger.warn { "Auto-download is enabled - this is a safety risk. Metadata-only mode recommended." }
            }

            searchQueries.forEach { query ->
                ctx.markAttempted()
                try {
                    searchTorrentMetadata(ctx, query)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to search torrents for: $query" }
                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", query)
                }
            }

            pgStore.storeFetchMetadata(
                source = "torrents",
                category = "metadata",
                itemCount = ctx.metrics.new + ctx.metrics.updated,
                fetchedAt = Clock.System.now(),
                metadata = mapOf(
                    "queries" to searchQueries.size,
                    "new" to ctx.metrics.new,
                    "updated" to ctx.metrics.updated,
                    "autoDownload" to config.autoDownload
                )
            )

            "Processed ${ctx.metrics.attempted} queries: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped"
        }
    }

    private suspend fun searchTorrentMetadata(ctx: FetchExecutionContext, query: String) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://torrents-csv.com/service/search?q=$encodedQuery&size=10&type=torrent"

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
        val torrents = json.getAsJsonArray("torrents")

        if (torrents == null || torrents.size() == 0) {
            ctx.markSkipped()
            return
        }

        torrents.forEach { torrentElement ->
            val torrent = torrentElement.asJsonObject
            val infoHash = torrent.get("infohash")?.asString ?: return@forEach
            val name = torrent.get("name")?.asString ?: ""
            val seeders = torrent.get("seeders")?.asInt ?: 0
            val leechers = torrent.get("leechers")?.asInt ?: 0
            val size = torrent.get("size_bytes")?.asLong ?: 0L
            val createdAt = torrent.get("created_unix")?.asString ?: ""

            // Construct magnet link (metadata-only, safe)
            val magnetLink = "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(name, "UTF-8")}"

            val metadata = TorrentMetadata(
                infoHash = infoHash,
                name = name,
                magnetLink = magnetLink,
                seeders = seeders,
                leechers = leechers,
                size = size,
                createdAt = createdAt,
                category = query
            )

            val metadataJson = gson.toJson(metadata)
            val contentHash = ContentHasher.hashJson(metadataJson)

            // Use infoHash as deterministic ID
            val itemId = "torrent_$infoHash"

            when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                DedupeResult.NEW -> {
                    ctx.storage.storeRawText(itemId, metadataJson, "json")
                    ctx.markNew()
                    ctx.markFetched()
                }
                DedupeResult.UPDATED -> {
                    // Seeder/leecher counts may have changed
                    ctx.storage.storeRawText(itemId, metadataJson, "json")
                    ctx.markUpdated()
                    ctx.markFetched()
                }
                DedupeResult.UNCHANGED -> {
                    ctx.markSkipped()
                }
            }
        }

        logger.info { "Torrents: '$query' - ${torrents.size()} results" }
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying torrents sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check torrents-csv.com API
        checks.add(
            DryRunUtils.checkUrl(
                "https://torrents-csv.com/service/search?q=test&size=1",
                "Torrents CSV API"
            )
        )

        // Check qBittorrent if auto-download enabled
        if (config.autoDownload) {
            checks.add(
                DryRunUtils.checkUrl(
                    config.qbittorrentUrl,
                    "qBittorrent Web UI"
                )
            )
        }

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/torrents", "Torrents metadata directory"))

        return DryRunResult(checks)
    }
}
