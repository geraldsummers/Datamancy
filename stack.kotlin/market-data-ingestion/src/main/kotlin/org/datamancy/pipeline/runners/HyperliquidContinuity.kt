package org.datamancy.pipeline.runners

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidMarketData
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToLong

private val continuityLogger = KotlinLogging.logger {}

internal const val HYPERLIQUID_MAINNET_INFO_URL = "https://api.hyperliquid.xyz/info"
internal const val HYPERLIQUID_TESTNET_INFO_URL = "https://api.hyperliquid-testnet.xyz/info"
internal const val DEFAULT_HYPERLIQUID_FRESHNESS_CHECK_INTERVAL_MS = 15_000L
internal const val MIN_HYPERLIQUID_FRESHNESS_CHECK_INTERVAL_MS = 1_000L
internal const val DEFAULT_HYPERLIQUID_CHANNEL_ACTIVITY_TIMEOUT_MS = 60_000L
internal const val MIN_HYPERLIQUID_CHANNEL_ACTIVITY_TIMEOUT_MS = 5_000L
internal const val DEFAULT_HYPERLIQUID_CANDLE_STALE_MULTIPLIER = 2.5
internal const val MIN_HYPERLIQUID_CANDLE_STALE_MULTIPLIER = 1.25
internal const val DEFAULT_HYPERLIQUID_BACKFILL_LOOKBACK_HOURS = 168L
internal const val MIN_HYPERLIQUID_BACKFILL_LOOKBACK_HOURS = 1L
internal const val DEFAULT_HYPERLIQUID_BACKFILL_MAX_BARS = 5_000
internal const val MIN_HYPERLIQUID_BACKFILL_MAX_BARS = 2
internal const val DEFAULT_HYPERLIQUID_BACKFILL_OVERLAP_BARS = 2
internal const val DEFAULT_HYPERLIQUID_RECENT_REPAIR_LOOKBACK_HOURS = 24L

internal fun resolveHyperliquidInfoUrl(explicitUrl: String?, mainnet: Boolean): String {
    val url = explicitUrl?.trim()
    if (!url.isNullOrEmpty()) return url
    return if (mainnet) HYPERLIQUID_MAINNET_INFO_URL else HYPERLIQUID_TESTNET_INFO_URL
}

internal fun resolveHyperliquidFreshnessCheckIntervalMs(explicitIntervalMs: Long?): Long {
    val intervalMs = explicitIntervalMs ?: DEFAULT_HYPERLIQUID_FRESHNESS_CHECK_INTERVAL_MS
    return intervalMs.coerceAtLeast(MIN_HYPERLIQUID_FRESHNESS_CHECK_INTERVAL_MS)
}

internal fun resolveHyperliquidChannelActivityTimeoutMs(explicitTimeoutMs: Long?): Long {
    val timeoutMs = explicitTimeoutMs ?: DEFAULT_HYPERLIQUID_CHANNEL_ACTIVITY_TIMEOUT_MS
    return timeoutMs.coerceAtLeast(MIN_HYPERLIQUID_CHANNEL_ACTIVITY_TIMEOUT_MS)
}

internal fun resolveHyperliquidCandleStaleMultiplier(explicitMultiplier: Double?): Double {
    val multiplier = explicitMultiplier ?: DEFAULT_HYPERLIQUID_CANDLE_STALE_MULTIPLIER
    return multiplier.coerceAtLeast(MIN_HYPERLIQUID_CANDLE_STALE_MULTIPLIER)
}

internal fun resolveHyperliquidBackfillLookbackHours(explicitLookbackHours: Long?): Long {
    val lookbackHours = explicitLookbackHours ?: DEFAULT_HYPERLIQUID_BACKFILL_LOOKBACK_HOURS
    return lookbackHours.coerceAtLeast(MIN_HYPERLIQUID_BACKFILL_LOOKBACK_HOURS)
}

internal fun resolveHyperliquidBackfillMaxBars(explicitMaxBars: Int?): Int {
    val maxBars = explicitMaxBars ?: DEFAULT_HYPERLIQUID_BACKFILL_MAX_BARS
    return maxBars.coerceAtLeast(MIN_HYPERLIQUID_BACKFILL_MAX_BARS)
}

internal fun resolveHyperliquidBackfillOverlapBars(explicitOverlapBars: Int?): Int {
    val overlapBars = explicitOverlapBars ?: DEFAULT_HYPERLIQUID_BACKFILL_OVERLAP_BARS
    return overlapBars.coerceAtLeast(0)
}

private fun normalizedBackfillOverlapBars(maxBars: Int, overlapBars: Int): Int {
    val normalizedMaxBars = resolveHyperliquidBackfillMaxBars(maxBars)
    return resolveHyperliquidBackfillOverlapBars(overlapBars).coerceAtMost(normalizedMaxBars - 1)
}

internal fun candleIntervalToMillis(interval: String): Long {
    val normalized = interval.trim()
    return when {
        normalized == "1M" -> 30L * 24L * 60L * 60L * 1000L
        normalized.endsWith("m") -> normalized.removeSuffix("m").toLong() * 60L * 1000L
        normalized.endsWith("h") -> normalized.removeSuffix("h").toLong() * 60L * 60L * 1000L
        normalized.endsWith("d") -> normalized.removeSuffix("d").toLong() * 24L * 60L * 60L * 1000L
        normalized.endsWith("w") -> normalized.removeSuffix("w").toLong() * 7L * 24L * 60L * 60L * 1000L
        else -> throw IllegalArgumentException("Unsupported Hyperliquid candle interval: $interval")
    }
}

internal fun alignDownToIntervalBoundary(instant: Instant, intervalMs: Long): Instant {
    val epochMs = instant.toEpochMilli()
    val alignedEpochMs = epochMs - Math.floorMod(epochMs, intervalMs)
    return Instant.ofEpochMilli(alignedEpochMs)
}

internal data class CandleBackfillWindow(
    val symbol: String,
    val interval: String,
    val intervalMs: Long,
    val requestedBars: Int,
    val startTime: Instant,
    val endTime: Instant
)

internal fun planCandleBackfillWindow(
    symbol: String,
    interval: String,
    now: Instant,
    lookbackHours: Long,
    maxBars: Int,
    overlapBars: Int
): CandleBackfillWindow {
    val intervalMs = candleIntervalToMillis(interval)
    val requestedLookbackMs = resolveHyperliquidBackfillLookbackHours(lookbackHours) * 60L * 60L * 1000L
    val lookbackBars = ceil(requestedLookbackMs.toDouble() / intervalMs.toDouble()).toInt()
    val requestedBars = (lookbackBars + normalizedBackfillOverlapBars(maxBars, overlapBars))
        .coerceAtLeast(MIN_HYPERLIQUID_BACKFILL_MAX_BARS)
        .coerceAtMost(resolveHyperliquidBackfillMaxBars(maxBars))
    val latestBoundary = alignDownToIntervalBoundary(now, intervalMs)
    return CandleBackfillWindow(
        symbol = symbol,
        interval = interval,
        intervalMs = intervalMs,
        requestedBars = requestedBars,
        startTime = latestBoundary.minusMillis((requestedBars - 1L) * intervalMs),
        endTime = now
    )
}

internal fun planCandleBackfillWindows(
    symbol: String,
    interval: String,
    now: Instant,
    lookbackHours: Long,
    maxBars: Int,
    overlapBars: Int
): List<CandleBackfillWindow> {
    val intervalMs = candleIntervalToMillis(interval)
    val normalizedMaxBars = resolveHyperliquidBackfillMaxBars(maxBars)
    val normalizedOverlapBars = normalizedBackfillOverlapBars(normalizedMaxBars, overlapBars)
    val requestedLookbackMs = resolveHyperliquidBackfillLookbackHours(lookbackHours) * 60L * 60L * 1000L
    val totalBars = ceil(requestedLookbackMs.toDouble() / intervalMs.toDouble()).toInt().coerceAtLeast(1)
    val latestBoundary = alignDownToIntervalBoundary(now, intervalMs)
    val oldestBoundary = latestBoundary.minusMillis((totalBars - 1L) * intervalMs)
    val overlapOffsetMs = (normalizedOverlapBars.toLong() - 1L) * intervalMs
    val newestWindow = planCandleBackfillWindow(
        symbol = symbol,
        interval = interval,
        now = now,
        lookbackHours = lookbackHours,
        maxBars = maxBars,
        overlapBars = overlapBars
    )

    val windows = mutableListOf(newestWindow)
    var currentStart = newestWindow.startTime
    while (currentStart.isAfter(oldestBoundary)) {
        val nextEndBoundary = currentStart.plusMillis(overlapOffsetMs)
        val nextStartBoundary = maxOf(
            oldestBoundary,
            nextEndBoundary.minusMillis((normalizedMaxBars - 1L) * intervalMs)
        )
        val requestedBars = (((nextEndBoundary.toEpochMilli() - nextStartBoundary.toEpochMilli()) / intervalMs) + 1L)
            .toInt()
            .coerceAtLeast(1)
        val nextEndTime = minOf(
            now,
            nextEndBoundary.plusMillis(intervalMs).minusMillis(1L)
        )
        windows += CandleBackfillWindow(
            symbol = symbol,
            interval = interval,
            intervalMs = intervalMs,
            requestedBars = requestedBars,
            startTime = nextStartBoundary,
            endTime = nextEndTime
        )
        currentStart = nextStartBoundary
    }

    return windows.distinctBy { window ->
        listOf(
            window.symbol,
            window.interval,
            window.startTime.toEpochMilli(),
            window.endTime.toEpochMilli(),
            window.requestedBars
        ).joinToString("|")
    }
}

internal class HyperliquidContinuityException(message: String) : IllegalStateException(message)

internal data class StaleCandleStream(
    val symbol: String,
    val interval: String,
    val candleMarketTime: Instant?,
    val candleAgeMs: Long?,
    val receiveAgeMs: Long?,
    val tradeActivityAgeMs: Long,
    val reason: String
)

internal class HyperliquidContinuityWatchdog(
    symbols: Collection<String>,
    candleIntervals: Collection<String>,
    private val activityTimeoutMs: Long,
    private val candleStaleMultiplier: Double,
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    private val trackedSymbols = symbols.map(String::trim).filter(String::isNotEmpty).distinct()
    private val trackedIntervals = candleIntervals.map(String::trim).filter(String::isNotEmpty).distinct()

    private var initialCandleRepairCompletedAt: Instant? = null
    private val lastTradeActivityAt = mutableMapOf<String, Instant>()
    private val lastCandleMarketTime = mutableMapOf<String, Instant>()
    private val lastCandleReceivedAt = mutableMapOf<String, Instant>()

    fun record(data: HyperliquidMarketData, receivedAt: Instant = nowProvider()) {
        when (data) {
            is HyperliquidMarketData.Trades -> {
                data.trades.map { it.symbol }.distinct().forEach { symbol ->
                    updateTradeActivity(symbol, receivedAt)
                }
            }
            is HyperliquidMarketData.Orderbook -> Unit
            is HyperliquidMarketData.AssetContext -> Unit
            is HyperliquidMarketData.Candle -> recordCandle(data.candle, receivedAt)
        }
    }

    fun seedBackfilledCandles(candles: Iterable<HyperliquidCandle>, receivedAt: Instant = nowProvider()) {
        candles.forEach { candle ->
            recordCandle(candle, receivedAt)
        }
    }

    fun markInitialCandleRepairComplete(completedAt: Instant = nowProvider()) {
        if (initialCandleRepairCompletedAt == null || completedAt.isAfter(initialCandleRepairCompletedAt)) {
            initialCandleRepairCompletedAt = completedAt
        }
    }

    fun assertHealthy(now: Instant = nowProvider()) {
        if (initialCandleRepairCompletedAt == null) {
            return
        }
        val issues = staleCandleStreams(now).map { it.reason }
        if (issues.isNotEmpty()) {
            throw HyperliquidContinuityException(issues.joinToString(separator = "; "))
        }
    }

    fun staleCandleStreams(now: Instant = nowProvider()): List<StaleCandleStream> {
        return collectStaleCandleStreams(now)
    }

    private fun updateTradeActivity(symbol: String, receivedAt: Instant) {
        if (symbol !in trackedSymbols) return
        val existing = lastTradeActivityAt[symbol]
        if (existing == null || receivedAt.isAfter(existing)) {
            lastTradeActivityAt[symbol] = receivedAt
        }
    }

    private fun recordCandle(candle: HyperliquidCandle, receivedAt: Instant) {
        if (candle.symbol !in trackedSymbols || candle.interval !in trackedIntervals) return
        val key = candleKey(candle.symbol, candle.interval)
        val existingMarketTime = lastCandleMarketTime[key]
        if (existingMarketTime == null || !candle.time.isBefore(existingMarketTime)) {
            lastCandleMarketTime[key] = candle.time
            lastCandleReceivedAt[key] = receivedAt
        }
    }

    private fun collectStaleCandleStreams(now: Instant): List<StaleCandleStream> {
        val repairCompletedAt = initialCandleRepairCompletedAt ?: return emptyList()
        val issues = mutableListOf<StaleCandleStream>()

        trackedSymbols.forEach { symbol ->
            val tradeActivityAt = lastTradeActivityAt[symbol] ?: return@forEach
            val tradeActivityAgeMs = ageMs(tradeActivityAt, now)
            if (tradeActivityAgeMs > activityTimeoutMs) {
                return@forEach
            }

            trackedIntervals.forEach { interval ->
                val intervalMs = candleIntervalToMillis(interval)
                val allowedLagMs = maxOf(
                    activityTimeoutMs,
                    (intervalMs.toDouble() * candleStaleMultiplier).roundToLong()
                )
                val key = candleKey(symbol, interval)
                val candleMarketTime = lastCandleMarketTime[key]

                if (candleMarketTime == null) {
                    if (ageMs(repairCompletedAt, now) > allowedLagMs) {
                        issues += StaleCandleStream(
                            symbol = symbol,
                            interval = interval,
                            candleMarketTime = null,
                            candleAgeMs = null,
                            receiveAgeMs = null,
                            tradeActivityAgeMs = tradeActivityAgeMs,
                            reason = "$symbol/$interval never produced a candle while trades remained active"
                        )
                    }
                    return@forEach
                }

                val candleAgeMs = ageMs(candleMarketTime, now)
                val receiveAgeMs = lastCandleReceivedAt[key]?.let { ageMs(it, now) } ?: Long.MAX_VALUE
                if (candleAgeMs > allowedLagMs && receiveAgeMs > allowedLagMs) {
                    issues += StaleCandleStream(
                        symbol = symbol,
                        interval = interval,
                        candleMarketTime = candleMarketTime,
                        candleAgeMs = candleAgeMs,
                        receiveAgeMs = receiveAgeMs,
                        tradeActivityAgeMs = tradeActivityAgeMs,
                        reason = buildString {
                        append("$symbol/$interval stale")
                        append(" last_bar=")
                        append(candleMarketTime)
                        append(" candle_age_ms=")
                        append(candleAgeMs)
                        append(" receive_age_ms=")
                        append(receiveAgeMs)
                        append(" trade_activity_age_ms=")
                        append(tradeActivityAgeMs)
                    }
                    )
                }
            }
        }

        return issues
    }

    private fun candleKey(symbol: String, interval: String): String = "$symbol|$interval"

    private fun ageMs(from: Instant, to: Instant): Long = (to.toEpochMilli() - from.toEpochMilli()).coerceAtLeast(0L)
}

internal class HyperliquidCandleBackfillClient(
    private val infoUrl: String,
    private val lookbackHours: Long,
    private val maxBarsPerRequest: Int,
    private val overlapBars: Int,
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO)

    suspend fun fetchRecentCandles(
        symbol: String,
        interval: String,
        now: Instant = nowProvider(),
        lookbackHoursOverride: Long? = null,
        maxBarsOverride: Int? = null
    ): List<HyperliquidCandle> {
        val window = planCandleBackfillWindow(
            symbol = symbol,
            interval = interval,
            now = now,
            lookbackHours = lookbackHoursOverride ?: lookbackHours,
            maxBars = maxBarsOverride ?: maxBarsPerRequest,
            overlapBars = overlapBars
        )
        return fetchWindow(window)
    }

    suspend fun fetchHistoricalCandles(symbol: String, interval: String, now: Instant = nowProvider()): List<HyperliquidCandle> {
        return planCandleBackfillWindows(
            symbol = symbol,
            interval = interval,
            now = now,
            lookbackHours = lookbackHours,
            maxBars = maxBarsPerRequest,
            overlapBars = overlapBars
        )
            .flatMap { fetchWindow(it) }
            .distinctBy { candle ->
                listOf(candle.symbol, candle.interval, candle.time.toEpochMilli()).joinToString("|")
            }
            .sortedBy { it.time }
    }

    fun close() {
        client.close()
    }

    private suspend fun fetchWindow(window: CandleBackfillWindow): List<HyperliquidCandle> {
        val payload = buildJsonObject {
            put("type", "candleSnapshot")
            put("req", buildJsonObject {
                put("coin", window.symbol)
                put("interval", window.interval)
                put("startTime", window.startTime.toEpochMilli())
                put("endTime", window.endTime.toEpochMilli())
            })
        }

        val response = client.post(infoUrl) {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                "Hyperliquid candle backfill failed for ${window.symbol}/${window.interval}: " +
                    "HTTP ${response.status.value} ${response.status.description} ${responseText.take(200)}"
            )
        }

        return try {
            json.parseToJsonElement(responseText).jsonArray
                .mapNotNull { parseCandleSnapshot(it) }
                .sortedBy { it.time }
        } catch (e: Exception) {
            continuityLogger.error(e) {
                "Failed to parse candleSnapshot response for ${window.symbol}/${window.interval}"
            }
            throw IllegalStateException(
                "Hyperliquid candle backfill returned an unexpected payload for " +
                    "${window.symbol}/${window.interval}: ${responseText.take(200)}",
                e
            )
        }
    }

    private fun parseCandleSnapshot(element: JsonElement): HyperliquidCandle? {
        val obj = element.jsonObject
        val symbol = obj["s"]?.jsonPrimitive?.contentOrNull ?: return null
        val interval = obj["i"]?.jsonPrimitive?.contentOrNull ?: return null
        val time = obj["t"]?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochMilli) ?: return null
        val open = obj["o"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val high = obj["h"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val low = obj["l"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val close = obj["c"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val volume = obj["v"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val numTrades = obj["n"]?.jsonPrimitive?.intOrNull ?: 0

        return HyperliquidCandle(
            time = time,
            symbol = symbol,
            interval = interval,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            numTrades = numTrades
        )
    }
}
