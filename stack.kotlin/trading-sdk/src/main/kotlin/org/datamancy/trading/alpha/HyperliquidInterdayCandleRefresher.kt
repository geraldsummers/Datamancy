package org.datamancy.trading.alpha

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.pow

private const val DEFAULT_HYPERLIQUID_INFO_URL = "https://api.hyperliquid.xyz/info"
private val HYPERLIQUID_JSON_MEDIA_TYPE = "application/json".toMediaType()

class HyperliquidInterdayCandleRefresher(
    private val dataSource: DataSource,
    private val infoUrl: String = DEFAULT_HYPERLIQUID_INFO_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build(),
    private val concurrency: Int = 1,
    private val requestSpacingMs: Long = 5_000,
    private val maxRetries: Int = 6,
    private val baseRetryDelayMs: Long = 5_000
) {
    private val requestGate = Mutex()
    private var nextRequestEarliestMs: Long = 0

    suspend fun refresh(request: AlphaDatasetRefreshRequest): AlphaDatasetRefreshResponse = withContext(Dispatchers.IO) {
        require(request.signalBarMinutes in setOf(240, 1_440)) {
            "Interday candle refresh supports signalBarMinutes=240 or 1440 only"
        }
        require(request.lookbackHours > 0) { "lookbackHours must be positive" }

        val startedAt = Instant.now()
        val exchange = request.exchange?.ifBlank { null } ?: "hyperliquid_mainnet"
        val endTime = alignedWindowEnd(startedAt, request.signalBarMinutes)
        val startTime = endTime.minus(Duration.ofHours(request.lookbackHours.toLong()))
        val universe = fetchUniverse()
        require(universe.isNotEmpty()) { "Hyperliquid public universe resolution returned no tradable symbols" }

        val candleDataType = interdayCandleDataType(request.signalBarMinutes)
        val interval = interdayCandleInterval(request.signalBarMinutes)
        val storedBars = coroutineScope {
            val semaphore = Semaphore(concurrency.coerceAtLeast(1))
            universe.map { symbol ->
                async {
                    semaphore.withPermit {
                        val candles = fetchCandles(
                            symbol = symbol,
                            signalBarMinutes = request.signalBarMinutes,
                            startTime = startTime,
                            endTime = endTime
                        )
                        storeCandles(
                            exchange = exchange,
                            symbol = symbol,
                            candleDataType = candleDataType,
                            candles = candles
                        )
                    }
                }
            }.awaitAll()
        }.sum()

        AlphaDatasetRefreshResponse(
            startedAt = startedAt,
            completedAt = Instant.now(),
            exchange = exchange,
            signalBarMinutes = request.signalBarMinutes,
            storedDataType = candleDataType,
            lookbackHours = request.lookbackHours,
            refreshedSymbols = universe.size,
            storedBars = storedBars,
            notes = listOf(
                "Interday candles were refreshed from Hyperliquid public candleSnapshot and stored locally as $candleDataType.",
                "Signal research now reads stored $interval candles from market-postgres instead of pulling venue candles during discovery."
            )
        )
    }

    private suspend fun fetchUniverse(): List<String> {
        val body = post("""{"type":"meta"}""")
        val root = JsonParser.parseString(body).asJsonObject
        return root.getAsJsonArray("universe")
            ?.mapNotNull { element ->
                val obj = element.asJsonObject
                val symbol = obj["name"]?.asString?.trim().orEmpty()
                val delisted = obj["isDelisted"]?.asBoolean == true || obj["delisted"]?.asBoolean == true
                symbol.takeIf { it.isNotEmpty() && !delisted }
            }
            ?.distinct()
            ?.sorted()
            .orEmpty()
    }

    private suspend fun fetchCandles(
        symbol: String,
        signalBarMinutes: Int,
        startTime: Instant,
        endTime: Instant
    ): List<StoredInterdayCandle> {
        val interval = interdayCandleInterval(signalBarMinutes)
        val payload = """
            {
              "type":"candleSnapshot",
              "req":{
                "coin":"$symbol",
                "interval":"$interval",
                "startTime":${startTime.toEpochMilli()},
                "endTime":${endTime.toEpochMilli()}
              }
            }
        """.trimIndent()
        val body = post(payload)
        val array = JsonParser.parseString(body).asJsonArray
        return array.mapNotNull { element ->
            val obj = element.asJsonObject
            val time = obj["t"]?.asLong?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
            StoredInterdayCandle(
                time = time,
                open = obj["o"]?.asString?.toDoubleOrNull() ?: return@mapNotNull null,
                high = obj["h"]?.asString?.toDoubleOrNull() ?: return@mapNotNull null,
                low = obj["l"]?.asString?.toDoubleOrNull() ?: return@mapNotNull null,
                close = obj["c"]?.asString?.toDoubleOrNull() ?: return@mapNotNull null,
                volume = obj["v"]?.asString?.toDoubleOrNull() ?: 0.0
            )
        }.sortedBy { it.time }
    }

    private fun storeCandles(
        exchange: String,
        symbol: String,
        candleDataType: String,
        candles: List<StoredInterdayCandle>
    ): Int {
        if (candles.isEmpty()) return 0
        val sql = """
            INSERT INTO market_data (time, symbol, exchange, data_type, open, high, low, close, volume, num_trades)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, exchange, data_type) WHERE data_type LIKE 'candle_%' DO UPDATE SET
                open = EXCLUDED.open,
                high = EXCLUDED.high,
                low = EXCLUDED.low,
                close = EXCLUDED.close,
                volume = EXCLUDED.volume,
                num_trades = EXCLUDED.num_trades
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(sql).use { statement ->
                    candles.forEach { candle ->
                        statement.setTimestamp(1, Timestamp.from(candle.time))
                        statement.setString(2, symbol)
                        statement.setString(3, exchange)
                        statement.setString(4, candleDataType)
                        statement.setDouble(5, candle.open)
                        statement.setDouble(6, candle.high)
                        statement.setDouble(7, candle.low)
                        statement.setDouble(8, candle.close)
                        statement.setDouble(9, candle.volume)
                        statement.setInt(10, 0)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
        return candles.size
    }

    private suspend fun post(jsonBody: String): String {
        repeat(maxRetries.coerceAtLeast(1)) { attempt ->
            awaitRequestSlot()
            val request = Request.Builder()
                .url(infoUrl)
                .post(jsonBody.toRequestBody(HYPERLIQUID_JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return response.body?.string().orEmpty()
                }
                if (response.code == 429 || response.code in 500..599) {
                    if (attempt == maxRetries - 1) {
                        error("Hyperliquid interday refresh failed after ${attempt + 1} attempts: HTTP ${response.code}")
                    }
                    delay(retryDelayMs(attempt, response.header("Retry-After")))
                    return@repeat
                }
                error("Hyperliquid interday refresh failed: HTTP ${response.code}")
            }
        }
        error("Hyperliquid interday refresh exhausted retries")
    }

    private suspend fun awaitRequestSlot() {
        if (requestSpacingMs <= 0) return
        requestGate.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (nextRequestEarliestMs - now).coerceAtLeast(0)
            if (waitMs > 0) {
                delay(waitMs)
            }
            nextRequestEarliestMs = maxOf(System.currentTimeMillis(), nextRequestEarliestMs) + requestSpacingMs
        }
    }

    private fun retryDelayMs(attempt: Int, retryAfterHeader: String?): Long {
        val retryAfterMs = retryAfterHeader?.trim()?.toLongOrNull()?.times(1_000)
        if (retryAfterMs != null && retryAfterMs > 0) {
            return retryAfterMs
        }
        val multiplier = 2.0.pow(attempt.toDouble()).toLong()
        return (baseRetryDelayMs * multiplier).coerceAtMost(30_000)
    }

    private data class StoredInterdayCandle(
        val time: Instant,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double
    )
}

internal fun interdayCandleInterval(signalBarMinutes: Int): String = when (signalBarMinutes) {
    240 -> "4h"
    1_440 -> "1d"
    else -> error("unsupported signalBarMinutes=$signalBarMinutes")
}

private fun alignedWindowEnd(now: Instant, signalBarMinutes: Int): Instant {
    val utc = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
    return when (signalBarMinutes) {
        240 -> utc
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .withHour((utc.hour / 4) * 4)
            .toInstant()
        1_440 -> utc
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
        else -> error("unsupported signalBarMinutes=$signalBarMinutes")
    }
}
