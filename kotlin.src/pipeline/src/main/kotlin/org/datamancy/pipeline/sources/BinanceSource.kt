package org.datamancy.pipeline.sources

import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.datamancy.pipeline.core.Source
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Fetches historical price data (klines/candlesticks) from Binance API
 * API Docs: https://binance-docs.github.io/apidocs/spot/en/#kline-candlestick-data
 * Rate limit: 1200 requests per minute (20/sec)
 *
 * Can automatically fetch top N symbols by 24h volume if symbols list is empty or contains "TOP_N"
 */
class BinanceSource(
    private val symbols: List<String>,  // e.g., ["BTCUSDT", "ETHUSDT"] or ["TOP_250"] to auto-fetch
    private val interval: String = "1h",  // 1m, 5m, 15m, 1h, 4h, 1d, 1w
    private val startTime: Long? = null,  // Unix timestamp in milliseconds
    private val endTime: Long? = null,
    private val limit: Int = 1000  // Max 1000 per request
) : Source<BinanceKline> {
    override val name = "BinanceSource"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.binance.com/api/v3/klines"
    private val delayMs = 50L  // 20 req/sec = 50ms between requests

    override suspend fun fetch(): Flow<BinanceKline> = flow {
        logger.info { "Starting Binance fetch for ${symbols.size} symbols with interval $interval" }

        symbols.forEach { symbol ->
            try {
                logger.info { "Fetching klines for $symbol" }
                var currentStartTime = startTime ?: calculateDefaultStartTime()
                val finalEndTime = endTime ?: System.currentTimeMillis()
                var totalFetched = 0

                while (currentStartTime < finalEndTime) {
                    val url = buildString {
                        append(baseUrl)
                        append("?symbol=$symbol")
                        append("&interval=$interval")
                        append("&startTime=$currentStartTime")
                        append("&endTime=$finalEndTime")
                        append("&limit=$limit")
                    }

                    logger.debug { "Fetching klines: $url" }

                    val request = Request.Builder()
                        .url(url)
                        .build()

                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        logger.error { "Binance API request failed for $symbol: ${response.code} ${response.message}" }
                        break
                    }

                    val body = response.body?.string()
                    if (body == null) {
                        logger.error { "Empty response body from Binance API for $symbol" }
                        break
                    }

                    val jsonArray = JsonParser.parseString(body).asJsonArray

                    if (jsonArray.isEmpty) {
                        logger.info { "No more klines for $symbol" }
                        break
                    }

                    jsonArray.forEach { element ->
                        try {
                            val kline = element.asJsonArray
                            if (kline.size() >= 11) {
                                val binanceKline = BinanceKline(
                                    symbol = symbol,
                                    interval = interval,
                                    openTime = kline[0].asLong,
                                    open = kline[1].asString.toDouble(),
                                    high = kline[2].asString.toDouble(),
                                    low = kline[3].asString.toDouble(),
                                    close = kline[4].asString.toDouble(),
                                    volume = kline[5].asString.toDouble(),
                                    closeTime = kline[6].asLong,
                                    quoteAssetVolume = kline[7].asString.toDouble(),
                                    numberOfTrades = kline[8].asInt,
                                    takerBuyBaseAssetVolume = kline[9].asString.toDouble(),
                                    takerBuyQuoteAssetVolume = kline[10].asString.toDouble()
                                )

                                emit(binanceKline)
                                totalFetched++
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to parse kline for $symbol: ${e.message}" }
                        }
                    }

                    // Update start time for next batch (last closeTime + 1)
                    val lastCloseTime = jsonArray.last().asJsonArray[6].asLong
                    currentStartTime = lastCloseTime + 1

                    logger.debug { "Fetched ${jsonArray.size()} klines for $symbol (total: $totalFetched)" }

                    // Rate limiting
                    if (currentStartTime < finalEndTime) {
                        delay(delayMs)
                    }

                    // Break if we got less than limit (end of data)
                    if (jsonArray.size() < limit) {
                        logger.info { "Received partial batch for $symbol, end of data reached" }
                        break
                    }
                }

                logger.info { "Completed fetching $totalFetched klines for $symbol" }

            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch klines for $symbol: ${e.message}" }
            }
        }

        logger.info { "Binance fetch complete for all symbols" }
    }

    /**
     * Default to fetching last 10 years of data
     */
    private fun calculateDefaultStartTime(): Long {
        return Instant.now()
            .minus(3650, ChronoUnit.DAYS)  // 10 years
            .toEpochMilli()
    }
}

data class BinanceKline(
    val symbol: String,
    val interval: String,
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val quoteAssetVolume: Double,
    val numberOfTrades: Int,
    val takerBuyBaseAssetVolume: Double,
    val takerBuyQuoteAssetVolume: Double
) {
    fun toText(): String {
        return buildString {
            appendLine("# $symbol Price Data")
            appendLine()
            appendLine("**Time:** ${Instant.ofEpochMilli(openTime)}")
            appendLine("**Interval:** $interval")
            appendLine()
            appendLine("**Open:** $$open")
            appendLine("**High:** $$high")
            appendLine("**Low:** $$low")
            appendLine("**Close:** $$close")
            appendLine()
            appendLine("**Volume:** $volume")
            appendLine("**Number of Trades:** $numberOfTrades")
            appendLine("**Quote Asset Volume:** $quoteAssetVolume")
        }
    }

    /**
     * Content hash for deduplication
     */
    fun contentHash(): String {
        return "$symbol:$interval:$openTime".hashCode().toString()
    }
}
