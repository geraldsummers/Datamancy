package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.datamancy.datafetcher.config.EconomicConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ClickHouseStore
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore

private val logger = KotlinLogging.logger {}
private val gson = Gson()

data class EconomicSeries(
    val source: String,
    val seriesId: String,
    val name: String,
    val frequency: String = "monthly"
)

class EconomicDataFetcher(private val config: EconomicConfig) : Fetcher {
    private val pgStore = PostgresStore()
    private val clickHouseStore = ClickHouseStore()

    // MVP: Curated series for demonstration
    private val curatedSeries = listOf(
        EconomicSeries("fred", "GDP", "US GDP", "quarterly"),
        EconomicSeries("fred", "UNRATE", "US Unemployment Rate", "monthly"),
        EconomicSeries("fred", "CPIAUCSL", "US CPI All Urban Consumers", "monthly")
    )

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("economic_data", version = "2.0.0") { ctx ->
            logger.info { "Fetching economic data series..." }

            // For MVP, only fetch FRED if API key available
            val fredEnabled = config.fredApiKey.isNotBlank()

            if (!fredEnabled) {
                logger.warn { "FRED API key not configured - skipping economic data" }
                return@execute "FRED API key not configured"
            }

            curatedSeries.forEach { series ->
                ctx.markAttempted()
                try {
                    when (series.source) {
                        "fred" -> fetchFredSeries(ctx, series)
                        else -> {
                            ctx.markSkipped()
                            logger.warn { "Unknown source: ${series.source}" }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch ${series.seriesId}" }
                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", series.seriesId)
                }
            }

            "Processed ${ctx.metrics.attempted} series: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped"
        }
    }

    private suspend fun fetchFredSeries(ctx: FetchExecutionContext, series: EconomicSeries) {
        // Fetch series metadata
        val metadataUrl = "https://api.stlouisfed.org/fred/series?series_id=${series.seriesId}&api_key=${config.fredApiKey}&file_type=json"
        val metadataResponse = ctx.http.get(metadataUrl)

        if (!metadataResponse.isSuccessful) {
            metadataResponse.close()
            throw Exception("HTTP ${metadataResponse.code}")
        }

        val metadataBody = metadataResponse.body?.string()
        metadataResponse.close()

        // Get checkpoint for last observation date
        val lastObsDate = ctx.checkpoint.get("${series.seriesId}_last_date")

        // Fetch observations
        val obsUrl = "https://api.stlouisfed.org/fred/series/observations?series_id=${series.seriesId}&api_key=${config.fredApiKey}&file_type=json&limit=10"
        val obsResponse = ctx.http.get(obsUrl)

        if (!obsResponse.isSuccessful) {
            obsResponse.close()
            throw Exception("HTTP ${obsResponse.code}")
        }

        val obsBody = obsResponse.body?.string()
        obsResponse.close()

        if (obsBody == null) {
            throw Exception("Empty response")
        }

        val json = JsonParser.parseString(obsBody).asJsonObject
        val observations = json.getAsJsonArray("observations")

        if (observations == null || observations.size() == 0) {
            ctx.markSkipped()
            return
        }

        var newestDate = lastObsDate

        // Process each observation
        observations.forEach { obsElement ->
            val obs = obsElement.asJsonObject
            val date = obs.get("date")?.asString ?: return@forEach
            val value = obs.get("value")?.asString ?: return@forEach

            if (value == ".") {
                // Missing value
                return@forEach
            }

            val numericValue = value.toDoubleOrNull() ?: return@forEach

            // Create normalized observation
            val observation = mapOf(
                "series_id" to series.seriesId,
                "date" to date,
                "value" to numericValue,
                "source" to "fred"
            )

            val obsJson = gson.toJson(observation)
            val contentHash = ContentHasher.hashJson(obsJson)

            val itemId = "${series.seriesId}_$date"

            when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                DedupeResult.NEW -> {
                    // Store in time-series database
                    try {
                        val timestamp = Instant.parse("${date}T00:00:00Z")
                        clickHouseStore.storeMarketData(
                            symbol = series.seriesId,
                            price = numericValue,
                            volume = null,
                            timestamp = timestamp,
                            source = "fred",
                            metadata = mapOf(
                                "seriesName" to series.name,
                                "frequency" to series.frequency
                            )
                        )
                    } catch (e: Exception) {
                        logger.warn { "Failed to parse date: $date" }
                    }

                    // Store raw observation
                    ctx.storage.storeRawText(itemId, obsJson, "json")

                    ctx.markNew()
                    ctx.markFetched()

                    if (newestDate == null || date > newestDate) {
                        newestDate = date
                    }
                }
                DedupeResult.UPDATED -> {
                    ctx.storage.storeRawText(itemId, obsJson, "json")
                    ctx.markUpdated()
                    ctx.markFetched()
                }
                DedupeResult.UNCHANGED -> {
                    ctx.markSkipped()
                }
            }
        }

        // Update checkpoint with newest observation date
        if (newestDate != null) {
            ctx.checkpoint.set("${series.seriesId}_last_date", newestDate)
        }

        pgStore.storeFetchMetadata(
            source = "economic",
            category = "fred",
            itemCount = observations.size(),
            fetchedAt = Clock.System.now(),
            metadata = mapOf(
                "seriesId" to series.seriesId,
                "seriesName" to series.name
            )
        )

        logger.info { "Economic: ${series.seriesId} (${series.name})" }
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying economic data sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check FRED API key if configured
        if (config.fredApiKey.isNotBlank()) {
            checks.add(DryRunUtils.checkApiKey(config.fredApiKey, "FRED API"))
            checks.add(
                DryRunUtils.checkApiEndpoint(
                    "https://api.stlouisfed.org/fred/series?series_id=GDP&api_key=${config.fredApiKey}&file_type=json",
                    null,
                    "FRED API"
                )
            )
        } else {
            checks.add(
                DryRunCheck(
                    name = "API Key: FRED",
                    passed = false,
                    message = "FRED API key not configured (optional)",
                    details = emptyMap()
                )
            )
        }

        // Check other economic data sources (no auth required)
        checks.add(DryRunUtils.checkUrl("https://www.worldbank.org/", "World Bank"))
        checks.add(DryRunUtils.checkUrl("https://www.imf.org/", "IMF"))
        checks.add(DryRunUtils.checkUrl("https://data.oecd.org/", "OECD"))

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/economic", "Economic data directory"))

        return DryRunResult(checks)
    }
}
