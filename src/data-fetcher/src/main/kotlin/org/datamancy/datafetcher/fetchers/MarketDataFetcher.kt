package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.datamancy.datafetcher.config.MarketDataConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ClickHouseStore
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private val logger = KotlinLogging.logger {}
private val gson = Gson()

data class InstrumentMapping(val symbol: String, val provider: String, val providerId: String, val instrumentType: String)

class MarketDataFetcher(private val config: MarketDataConfig) : Fetcher {
    private val clickHouseStore = ClickHouseStore()
    private val pgStore = PostgresStore()
    private val maxRetries = 5
    private val baseDelayMs = 1000L
    private val maxDelayMs = 60000L

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("market_data", version = "2.0.0") { ctx ->
            logger.info { "Fetching market data for ${config.symbols.size} symbols..." }

            // Build/refresh instrument registry
            val instrumentRegistry = buildInstrumentRegistry(ctx)

            // Fetch data for each instrument
            instrumentRegistry.forEach { instrument ->
                ctx.markAttempted()
                try {
                    when (instrument.provider) {
                        "coingecko" -> fetchCryptoFromCoinGeckoWithRetry(ctx, instrument)
                        else -> {
                            ctx.markSkipped()
                            logger.warn { "Unknown provider: ${instrument.provider}" }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch ${instrument.symbol}" }
                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", instrument.symbol)
                }
            }

            "Processed ${ctx.metrics.attempted} instruments: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped"
        }
    }

    private suspend fun fetchCryptoFromCoinGeckoWithRetry(ctx: FetchExecutionContext, instrument: InstrumentMapping) {
        var lastException: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                fetchCryptoFromCoinGecko(ctx, instrument)
                return // Success
            } catch (e: Exception) {
                lastException = e

                // Check if it's a rate limit error (429)
                if (e.message?.contains("429") == true) {
                    val delayMs = calculateBackoffDelay(attempt)
                    logger.warn { "Rate limited fetching ${instrument.symbol}, retrying in ${delayMs}ms (attempt ${attempt + 1}/$maxRetries)" }
                    delay(delayMs)
                } else {
                    // For other errors, throw immediately (no retry)
                    throw e
                }
            }
        }

        // All retries exhausted
        throw lastException ?: Exception("Failed after $maxRetries retries")
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        // Exponential backoff: baseDelay * 2^attempt
        val exponentialDelay = (baseDelayMs * 2.0.pow(attempt)).toLong()

        // Cap at maxDelayMs
        val cappedDelay = min(exponentialDelay, maxDelayMs)

        // Add jitter: random value between 0 and 25% of delay
        val jitter = (cappedDelay * 0.25 * Random.nextDouble()).toLong()

        return cappedDelay + jitter
    }

    private suspend fun buildInstrumentRegistry(ctx: FetchExecutionContext): List<InstrumentMapping> {
        val registry = mutableListOf<InstrumentMapping>()

        // Map symbols to providers (simple heuristic for MVP)
        config.symbols.forEach { symbol ->
            val instrumentType = when {
                symbol.length <= 5 && symbol.all { it.isUpperCase() || it.isDigit() } -> "crypto"
                else -> "unknown"
            }

            when (instrumentType) {
                "crypto" -> {
                    // Check cache for CoinGecko ID mapping
                    val cacheKey = "coingecko_id_$symbol"
                    val coinGeckoId = ctx.checkpoint.get(cacheKey) ?: symbol.lowercase()

                    // For MVP, assume symbol.lowercase() == coinGeckoId
                    // Production would query CoinGecko /coins/list endpoint
                    ctx.checkpoint.set(cacheKey, coinGeckoId)

                    registry.add(InstrumentMapping(
                        symbol = symbol,
                        provider = "coingecko",
                        providerId = coinGeckoId,
                        instrumentType = "crypto"
                    ))
                }
            }
        }

        return registry
    }

    private suspend fun fetchCryptoFromCoinGecko(ctx: FetchExecutionContext, instrument: InstrumentMapping) {
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=${instrument.providerId}&vs_currencies=usd&include_24hr_vol=true&include_last_updated_at=true"

        val response = ctx.http.get(url)
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }

        val body = response.body?.string()
        response.close()

        if (body == null || body == "{}") {
            throw Exception("Empty or invalid response")
        }

        val json = JsonParser.parseString(body).asJsonObject

        if (!json.has(instrument.providerId)) {
            throw Exception("Instrument not found: ${instrument.providerId}")
        }

        val data = json.getAsJsonObject(instrument.providerId)
        val price = data.get("usd")?.asDouble ?: 0.0
        val volume = data.get("usd_24h_vol")?.asDouble
        val lastUpdated = data.get("last_updated_at")?.asLong?.let { Instant.fromEpochSeconds(it) }
            ?: Clock.System.now()

        // Normalize market data
        val marketData = mapOf(
            "symbol" to instrument.symbol,
            "price" to price,
            "volume" to (volume ?: 0.0),
            "timestamp" to lastUpdated.toString(),
            "source" to "coingecko",
            "instrumentType" to instrument.instrumentType
        )

        val dataJson = gson.toJson(marketData)
        val contentHash = ContentHasher.hashJson(dataJson)

        // Dedupe by instrument + timestamp (minute precision)
        val timestampKey = lastUpdated.toString().take(16) // YYYY-MM-DDTHH:MM
        val itemId = "${instrument.symbol}_$timestampKey"

        when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
            DedupeResult.NEW -> {
                // Store in ClickHouse time-series
                clickHouseStore.storeMarketData(
                    symbol = instrument.symbol,
                    price = price,
                    volume = volume,
                    timestamp = lastUpdated,
                    source = "coingecko",
                    metadata = mapOf(
                        "instrumentType" to instrument.instrumentType,
                        "providerId" to instrument.providerId
                    )
                )

                // Store raw JSON
                ctx.storage.storeRawText(itemId, body, "json")

                pgStore.storeFetchMetadata(
                    source = "market_data",
                    category = instrument.instrumentType,
                    itemCount = 1,
                    fetchedAt = Clock.System.now(),
                    metadata = mapOf(
                        "symbol" to instrument.symbol,
                        "price" to price
                    )
                )

                ctx.markNew()
                ctx.markFetched()
                logger.info { "Market: ${instrument.symbol} = $$price (vol: $volume)" }
            }
            DedupeResult.UPDATED -> {
                ctx.storage.storeRawText(itemId, body, "json")
                ctx.markUpdated()
                ctx.markFetched()
            }
            DedupeResult.UNCHANGED -> {
                ctx.markSkipped()
            }
        }
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying market data sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check filesystem directories
        checks.add(DryRunUtils.checkDirectory("/app/data/market_data/crypto", "Market data directory"))

        // Check CoinGecko API (no auth required)
        checks.add(
            DryRunUtils.checkApiEndpoint(
                "https://api.coingecko.com/api/v3/ping",
                null,
                "CoinGecko API"
            )
        )

        // Verify at least one symbol configured
        checks.add(
            DryRunUtils.checkConfig(
                if (config.symbols.isEmpty()) null else config.symbols.joinToString(","),
                "Market symbols",
                required = true
            )
        )

        // Check ClickHouse connection
        val chHost = System.getenv("CLICKHOUSE_HOST") ?: "clickhouse"
        val chPort = System.getenv("CLICKHOUSE_PORT")?.toIntOrNull() ?: 8123
        checks.add(
            DryRunUtils.checkUrl(
                "http://$chHost:$chPort/ping",
                "ClickHouse (time-series)"
            )
        )

        return DryRunResult(checks)
    }
}
