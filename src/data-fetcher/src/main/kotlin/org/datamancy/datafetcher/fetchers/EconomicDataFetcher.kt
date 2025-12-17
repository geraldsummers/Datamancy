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

    // Curated series for demonstration
    private val curatedSeries = listOf(
        // FRED
        EconomicSeries("fred", "GDP", "US GDP", "quarterly"),
        EconomicSeries("fred", "UNRATE", "US Unemployment Rate", "monthly"),
        EconomicSeries("fred", "CPIAUCSL", "US CPI All Urban Consumers", "monthly"),
        // IMF
        EconomicSeries("imf", "NGDP_R", "Real GDP", "annual"),
        EconomicSeries("imf", "PCPIPCH", "Inflation Rate", "annual"),
        // World Bank
        EconomicSeries("worldbank", "NY.GDP.MKTP.CD", "GDP (current US$)", "annual"),
        EconomicSeries("worldbank", "SP.POP.TOTL", "Population, total", "annual"),
        // OECD
        EconomicSeries("oecd", "GDP", "Gross domestic product", "quarterly"),
        EconomicSeries("oecd", "CPI", "Consumer price index", "monthly")
    )

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("economic_data", version = "2.0.0") { ctx ->
            logger.info { "Fetching economic data series..." }

            curatedSeries.forEach { series ->
                ctx.markAttempted()
                try {
                    when (series.source) {
                        "fred" -> {
                            if (config.fredApiKey.isBlank()) {
                                logger.warn { "FRED API key not configured - skipping ${series.seriesId}" }
                                ctx.markSkipped()
                            } else {
                                fetchFredSeries(ctx, series)
                            }
                        }
                        "imf" -> fetchImfSeries(ctx, series)
                        "worldbank" -> fetchWorldBankSeries(ctx, series)
                        "oecd" -> fetchOecdSeries(ctx, series)
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

    private suspend fun fetchImfSeries(ctx: FetchExecutionContext, series: EconomicSeries) {
        // IMF Data API - using JSON format
        // Documentation: https://datahelp.imf.org/knowledgebase/articles/667681-using-json-restful-web-service
        val url = "http://dataservices.imf.org/REST/SDMX_JSON.svc/CompactData/IFS/A.US.${series.seriesId}"

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
        val dataSets = json.getAsJsonObject("CompactData")?.getAsJsonObject("DataSet")?.getAsJsonArray("Series")

        if (dataSets == null || dataSets.size() == 0) {
            ctx.markSkipped()
            return
        }

        val series0 = dataSets.get(0).asJsonObject
        val observations = series0.getAsJsonArray("Obs") ?: return

        var newestDate: String? = ctx.checkpoint.get("${series.seriesId}_imf_last_date")

        observations.forEach { obsElement ->
            val obs = obsElement.asJsonObject
            val timePeriod = obs.get("@TIME_PERIOD")?.asString ?: return@forEach
            val obsValue = obs.get("@OBS_VALUE")?.asString ?: return@forEach

            val numericValue = obsValue.toDoubleOrNull() ?: return@forEach

            val observation = mapOf(
                "series_id" to series.seriesId,
                "date" to timePeriod,
                "value" to numericValue,
                "source" to "imf"
            )

            val obsJson = gson.toJson(observation)
            val contentHash = ContentHasher.hashJson(obsJson)
            val itemId = "${series.seriesId}_imf_$timePeriod"

            when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                DedupeResult.NEW -> {
                    try {
                        val timestamp = Instant.parse("${timePeriod}-01-01T00:00:00Z")
                        clickHouseStore.storeMarketData(
                            symbol = series.seriesId,
                            price = numericValue,
                            volume = null,
                            timestamp = timestamp,
                            source = "imf",
                            metadata = mapOf(
                                "seriesName" to series.name,
                                "frequency" to series.frequency
                            )
                        )
                    } catch (e: Exception) {
                        logger.warn { "Failed to parse date: $timePeriod" }
                    }

                    ctx.storage.storeRawText(itemId, obsJson, "json")
                    ctx.markNew()
                    ctx.markFetched()

                    if (newestDate == null || timePeriod > newestDate) {
                        newestDate = timePeriod
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

        if (newestDate != null) {
            ctx.checkpoint.set("${series.seriesId}_imf_last_date", newestDate)
        }

        pgStore.storeFetchMetadata(
            source = "economic",
            category = "imf",
            itemCount = observations.size(),
            fetchedAt = Clock.System.now(),
            metadata = mapOf(
                "seriesId" to series.seriesId,
                "seriesName" to series.name
            )
        )

        logger.info { "Economic IMF: ${series.seriesId} (${series.name})" }
    }

    private suspend fun fetchWorldBankSeries(ctx: FetchExecutionContext, series: EconomicSeries) {
        // World Bank API v2
        // Documentation: https://datahelpdesk.worldbank.org/knowledgebase/articles/889392-about-the-indicators-api-documentation
        val url = "https://api.worldbank.org/v2/country/USA/indicator/${series.seriesId}?format=json&per_page=100"

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

        val json = JsonParser.parseString(body).asJsonArray
        if (json.size() < 2) {
            ctx.markSkipped()
            return
        }

        val dataArray = json.get(1).asJsonArray
        if (dataArray.size() == 0) {
            ctx.markSkipped()
            return
        }

        var newestDate: String? = ctx.checkpoint.get("${series.seriesId}_wb_last_date")

        dataArray.forEach { dataElement ->
            val dataPoint = dataElement.asJsonObject
            val date = dataPoint.get("date")?.asString ?: return@forEach
            val value = dataPoint.get("value")?.asDouble ?: return@forEach

            val observation = mapOf(
                "series_id" to series.seriesId,
                "date" to date,
                "value" to value,
                "source" to "worldbank",
                "country" to "USA"
            )

            val obsJson = gson.toJson(observation)
            val contentHash = ContentHasher.hashJson(obsJson)
            val itemId = "${series.seriesId}_wb_$date"

            when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                DedupeResult.NEW -> {
                    try {
                        val timestamp = Instant.parse("${date}-01-01T00:00:00Z")
                        clickHouseStore.storeMarketData(
                            symbol = series.seriesId,
                            price = value,
                            volume = null,
                            timestamp = timestamp,
                            source = "worldbank",
                            metadata = mapOf(
                                "seriesName" to series.name,
                                "frequency" to series.frequency,
                                "country" to "USA"
                            )
                        )
                    } catch (e: Exception) {
                        logger.warn { "Failed to parse date: $date" }
                    }

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

        if (newestDate != null) {
            ctx.checkpoint.set("${series.seriesId}_wb_last_date", newestDate)
        }

        pgStore.storeFetchMetadata(
            source = "economic",
            category = "worldbank",
            itemCount = dataArray.size(),
            fetchedAt = Clock.System.now(),
            metadata = mapOf(
                "seriesId" to series.seriesId,
                "seriesName" to series.name
            )
        )

        logger.info { "Economic World Bank: ${series.seriesId} (${series.name})" }
    }

    private suspend fun fetchOecdSeries(ctx: FetchExecutionContext, series: EconomicSeries) {
        // OECD SDMX JSON API
        // Documentation: https://data.oecd.org/api/sdmx-json-documentation/
        val url = "https://stats.oecd.org/SDMX-JSON/data/${series.seriesId}/USA/OECD?startTime=2010"

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
        val dataSets = json.getAsJsonArray("dataSets")
        if (dataSets == null || dataSets.size() == 0) {
            ctx.markSkipped()
            return
        }

        val dataset = dataSets.get(0).asJsonObject
        val observations = dataset.getAsJsonObject("observations")
        if (observations == null || observations.size() == 0) {
            ctx.markSkipped()
            return
        }

        val structure = json.getAsJsonObject("structure")
        val dimensions = structure.getAsJsonObject("dimensions")?.getAsJsonArray("observation")
        val timeDimension = dimensions?.get(0)?.asJsonObject?.getAsJsonArray("values")

        var newestDate: String? = ctx.checkpoint.get("${series.seriesId}_oecd_last_date")
        var processedCount = 0

        observations.keySet().forEach { key ->
            val obsArray = observations.getAsJsonArray(key)
            if (obsArray == null || obsArray.size() == 0) return@forEach

            val value = obsArray.get(0).asDouble
            val timeIndex = key.split(":").getOrNull(0)?.toIntOrNull() ?: return@forEach
            val timePeriod = timeDimension?.get(timeIndex)?.asJsonObject?.get("id")?.asString ?: return@forEach

            val observation = mapOf(
                "series_id" to series.seriesId,
                "date" to timePeriod,
                "value" to value,
                "source" to "oecd"
            )

            val obsJson = gson.toJson(observation)
            val contentHash = ContentHasher.hashJson(obsJson)
            val itemId = "${series.seriesId}_oecd_$timePeriod"

            when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                DedupeResult.NEW -> {
                    try {
                        val timestamp = Instant.parse("${timePeriod}-01-01T00:00:00Z")
                        clickHouseStore.storeMarketData(
                            symbol = series.seriesId,
                            price = value,
                            volume = null,
                            timestamp = timestamp,
                            source = "oecd",
                            metadata = mapOf(
                                "seriesName" to series.name,
                                "frequency" to series.frequency
                            )
                        )
                    } catch (e: Exception) {
                        logger.warn { "Failed to parse date: $timePeriod" }
                    }

                    ctx.storage.storeRawText(itemId, obsJson, "json")
                    ctx.markNew()
                    ctx.markFetched()
                    processedCount++

                    if (newestDate == null || timePeriod > newestDate) {
                        newestDate = timePeriod
                    }
                }
                DedupeResult.UPDATED -> {
                    ctx.storage.storeRawText(itemId, obsJson, "json")
                    ctx.markUpdated()
                    ctx.markFetched()
                    processedCount++
                }
                DedupeResult.UNCHANGED -> {
                    ctx.markSkipped()
                }
            }
        }

        if (newestDate != null) {
            ctx.checkpoint.set("${series.seriesId}_oecd_last_date", newestDate)
        }

        pgStore.storeFetchMetadata(
            source = "economic",
            category = "oecd",
            itemCount = processedCount,
            fetchedAt = Clock.System.now(),
            metadata = mapOf(
                "seriesId" to series.seriesId,
                "seriesName" to series.name
            )
        )

        logger.info { "Economic OECD: ${series.seriesId} (${series.name})" }
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
