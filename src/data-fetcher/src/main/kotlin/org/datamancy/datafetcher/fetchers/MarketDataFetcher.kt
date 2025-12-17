package org.datamancy.datafetcher.fetchers

import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.datamancy.datafetcher.config.MarketDataConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ClickHouseStore
import org.datamancy.datafetcher.storage.FileSystemStore
import org.datamancy.datafetcher.storage.PostgresStore
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class MarketDataFetcher(private val config: MarketDataConfig) : Fetcher {
    private val clickHouseStore = ClickHouseStore()
    private val fsStore = FileSystemStore()
    private val pgStore = PostgresStore()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(): FetchResult {
        logger.info { "Fetching market data for ${config.symbols.size} symbols..." }
        var totalFetched = 0
        val errors = mutableListOf<String>()

        // Fetch crypto data from CoinGecko (free tier)
        config.symbols.filter { it.length <= 5 && it.all { c -> c.isUpperCase() } }.forEach { symbol ->
            try {
                fetchCryptoFromCoinGecko(symbol.lowercase())
                totalFetched++
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch crypto data for $symbol" }
                errors.add("$symbol: ${e.message}")
            }
        }

        // Store fetch metadata
        pgStore.storeFetchMetadata(
            source = "market_data",
            category = "crypto",
            itemCount = totalFetched,
            fetchedAt = Clock.System.now()
        )

        return if (errors.isEmpty()) {
            FetchResult.Success("Fetched market data for $totalFetched symbols", totalFetched)
        } else {
            FetchResult.Error("Fetched $totalFetched with ${errors.size} errors: ${errors.joinToString("; ")}")
        }
    }

    private fun fetchCryptoFromCoinGecko(coinId: String) {
        // CoinGecko free API - no key required for basic endpoints
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=$coinId&vs_currencies=usd&include_24hr_vol=true"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return
                val json = JsonParser.parseString(body).asJsonObject

                if (json.has(coinId)) {
                    val data = json.getAsJsonObject(coinId)
                    val price = data.get("usd")?.asDouble ?: 0.0
                    val volume = data.get("usd_24h_vol")?.asDouble

                    // Store in ClickHouse
                    clickHouseStore.storeMarketData(
                        symbol = coinId.uppercase(),
                        price = price,
                        volume = volume,
                        timestamp = Clock.System.now(),
                        source = "coingecko"
                    )

                    // Also store raw JSON
                    val filename = "${coinId}_${Clock.System.now().epochSeconds}.json"
                    fsStore.storeRawText("market_data/crypto", filename, body)

                    logger.info { "Fetched crypto data: $coinId = $$price" }
                }
            } else {
                throw Exception("HTTP ${response.code}: ${response.message}")
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
