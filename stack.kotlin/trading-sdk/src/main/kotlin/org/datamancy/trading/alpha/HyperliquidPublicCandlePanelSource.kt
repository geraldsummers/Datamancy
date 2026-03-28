package org.datamancy.trading.alpha

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private const val DEFAULT_HYPERLIQUID_INFO_URL = "https://api.hyperliquid.xyz/info"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

class HyperliquidPublicCandlePanelSource(
    private val dataSource: DataSource? = null,
    private val infoUrl: String = DEFAULT_HYPERLIQUID_INFO_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build(),
    private val concurrency: Int = 12
) : InterdayPanelSource {
    override suspend fun load(request: InterdayPanelRequest): InterdayPanel = withContext(Dispatchers.IO) {
        require(request.signalBarMinutes in setOf(240, 1_440)) {
            "Hyperliquid public interday source supports signalBarMinutes=240 or 1440 only"
        }
        val universe = fetchUniverse()
        require(universe.isNotEmpty()) { "Hyperliquid public universe resolution returned no tradable symbols" }

        val carryBySymbol = loadCarrySeries(request)
        val series = coroutineScope {
            val semaphore = Semaphore(concurrency.coerceAtLeast(1))
            universe.map { symbol ->
                async {
                    semaphore.withPermit {
                        fetchSeries(symbol = symbol, request = request, carryByTime = carryBySymbol[symbol].orEmpty())
                    }
                }
            }.awaitAll()
        }.filter { it.bars.any { bar -> bar != null } }

        require(series.isNotEmpty()) {
            "No public Hyperliquid candle history returned for exchange=${request.exchange} " +
                "signalBarMinutes=${request.signalBarMinutes} start=${request.startTime} end=${request.endTime}"
        }

        val ranked = series
            .map { candidate ->
                candidate to candidate.bars.filterNotNull().averageOfOrZero { it.close * it.volume }
            }
            .sortedByDescending { it.second }

        val selected = if (request.maxSymbols > 0) {
            ranked.take(request.maxSymbols).map { it.first }
        } else {
            ranked.map { it.first }
        }

        val timeline = selected
            .flatMap { seriesCandidate -> seriesCandidate.bars.filterNotNull().map { it.time } }
            .distinct()
            .sorted()
        require(timeline.isNotEmpty()) {
            "Selected universe had no usable public candle bars for signalBarMinutes=${request.signalBarMinutes}"
        }
        val timeIndex = timeline.withIndex().associate { it.value to it.index }
        val alignedSeries = selected.map { candidate ->
            val aligned = MutableList<InterdayBar?>(timeline.size) { null }
            candidate.bars.filterNotNull().forEach { bar ->
                timeIndex[bar.time]?.let { aligned[it] = bar }
            }
            InterdaySymbolSeries(candidate.symbol, aligned)
        }
        InterdayPanel(
            exchange = request.exchange,
            signalBarMinutes = request.signalBarMinutes,
            timeline = timeline,
            series = alignedSeries
        )
    }

    private suspend fun fetchSeries(
        symbol: String,
        request: InterdayPanelRequest,
        carryByTime: Map<Instant, CarryBar>
    ): SymbolBars {
        val candles = fetchCandles(symbol = symbol, request = request)
        if (candles.isEmpty()) {
            return SymbolBars(symbol = symbol, bars = emptyList())
        }
        return SymbolBars(
            symbol = symbol,
            bars = candles.map { candle ->
                val carry = carryByTime[candle.time]
                InterdayBar(
                    time = candle.time,
                    open = candle.open,
                    high = candle.high,
                    low = candle.low,
                    close = candle.close,
                    volume = candle.volume,
                    tradeVolume = candle.volume,
                    buyVolume = 0.0,
                    sellVolume = 0.0,
                    spreadBps = null,
                    depthUsd = null,
                    fundingRate = carry?.fundingRate,
                    openInterest = carry?.openInterest,
                    tradeObservedRatio = 0.0,
                    orderbookObservedRatio = 0.0,
                    assetContextObservedRatio = if (carry != null) 1.0 else 0.0
                )
            }
        )
    }

    private fun fetchUniverse(): List<String> {
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

    private fun fetchCandles(symbol: String, request: InterdayPanelRequest): List<PublicCandle> {
        val interval = when (request.signalBarMinutes) {
            240 -> "4h"
            1_440 -> "1d"
            else -> error("unsupported signalBarMinutes=${request.signalBarMinutes}")
        }
        val payload = """
            {
              "type":"candleSnapshot",
              "req":{
                "coin":"$symbol",
                "interval":"$interval",
                "startTime":${request.startTime.toEpochMilli()},
                "endTime":${request.endTime.toEpochMilli()}
              }
            }
        """.trimIndent()
        val body = post(payload)
        val array = JsonParser.parseString(body).asJsonArray
        return array.mapNotNull { element ->
            val obj = element.asJsonObject
            val time = obj["t"]?.asLong?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
            PublicCandle(
                time = time,
                open = obj["o"]?.asString?.toDoubleOrNull() ?: return@mapNotNull null,
                high = obj["h"]?.asString?.toDoubleOrNull() ?: return@mapNotNull null,
                low = obj["l"]?.asString?.toDoubleOrNull() ?: return@mapNotNull null,
                close = obj["c"]?.asString?.toDoubleOrNull() ?: return@mapNotNull null,
                volume = obj["v"]?.asString?.toDoubleOrNull() ?: 0.0
            )
        }.sortedBy { it.time }
    }

    private fun post(jsonBody: String): String {
        val request = Request.Builder()
            .url(infoUrl)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Hyperliquid public candle request failed: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun loadCarrySeries(request: InterdayPanelRequest): Map<String, Map<Instant, CarryBar>> {
        val source = dataSource ?: return emptyMap()
        val sql = """
            WITH aggregated AS (
                SELECT
                    time_bucket((? || ' minutes')::interval, time) AS bucket,
                    symbol,
                    AVG(funding_rate) FILTER (WHERE data_type = 'funding') AS funding_rate,
                    AVG(open_interest) FILTER (WHERE data_type = 'open_interest') AS open_interest
                FROM market_data
                WHERE exchange = ?
                  AND data_type IN ('funding', 'open_interest')
                  AND time >= ?
                  AND time <= ?
                GROUP BY 1, 2
            )
            SELECT bucket, symbol, funding_rate, open_interest
            FROM aggregated
        """.trimIndent()
        val rows = mutableMapOf<String, MutableMap<Instant, CarryBar>>()
        source.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, request.signalBarMinutes)
                statement.setString(2, request.exchange)
                statement.setTimestamp(3, Timestamp.from(request.startTime))
                statement.setTimestamp(4, Timestamp.from(request.endTime))
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val bucket = rs.getTimestamp("bucket").toInstant().truncatedTo(ChronoUnit.MINUTES)
                        val symbol = rs.getString("symbol")
                        val fundingRate = rs.getDouble("funding_rate")
                        val funding = fundingRate.takeUnless { rs.wasNull() }
                        val openInterestValue = rs.getDouble("open_interest")
                        val openInterest = openInterestValue.takeUnless { rs.wasNull() }
                        rows.getOrPut(symbol) { linkedMapOf() }[bucket] = CarryBar(
                            fundingRate = funding,
                            openInterest = openInterest
                        )
                    }
                }
            }
        }
        return rows
    }

    private data class PublicCandle(
        val time: Instant,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double
    )

    private data class CarryBar(
        val fundingRate: Double?,
        val openInterest: Double?
    )

    private data class SymbolBars(
        val symbol: String,
        val bars: List<InterdayBar?>
    )
}

private fun <T> Iterable<T>.averageOfOrZero(selector: (T) -> Double): Double {
    var total = 0.0
    var count = 0
    for (item in this) {
        total += selector(item)
        count += 1
    }
    return if (count == 0) 0.0 else total / count.toDouble()
}
