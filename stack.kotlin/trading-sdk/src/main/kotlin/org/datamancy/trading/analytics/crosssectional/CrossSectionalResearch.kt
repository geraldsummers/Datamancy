package org.datamancy.trading.analytics.crosssectional

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

fun env(name: String, default: String = ""): String =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default

fun envInt(name: String, default: Int): Int =
    env(name, default.toString()).toIntOrNull() ?: default

fun envDouble(name: String, default: Double): Double =
    env(name, default.toString()).toDoubleOrNull() ?: default

fun envBoolean(name: String, default: Boolean): Boolean {
    val raw = env(name, if (default) "true" else "false").lowercase()
    return raw in setOf("1", "true", "yes", "on")
}

fun clamp(value: Double, lower: Double, upper: Double): Double =
    max(lower, min(upper, value))

fun direction(value: Double): Double =
    when {
        value > 1e-9 -> 1.0
        value < -1e-9 -> -1.0
        else -> 0.0
    }

fun Double.round(decimals: Int = 4): Double {
    val scale = 10.0.pow(decimals.toDouble())
    return round(this * scale) / scale
}

fun sqlList(values: List<String>): String =
    values.joinToString(",") { "'" + it.replace("'", "''") + "'" }

fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

fun mean(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else values.average()

fun stdev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mu = mean(values)
    val variance = values.sumOf { (it - mu).pow(2.0) } / values.size.toDouble()
    return sqrt(max(variance, 0.0))
}

fun rollingSum(values: List<Double>, endIndex: Int, window: Int): Double {
    if (values.isEmpty() || endIndex < 0) return 0.0
    val start = max(0, endIndex - window + 1)
    return values.subList(start, endIndex + 1).sum()
}

fun rollingMean(values: List<Double>, endIndex: Int, window: Int): Double {
    if (values.isEmpty() || endIndex < 0) return 0.0
    val start = max(0, endIndex - window + 1)
    return mean(values.subList(start, endIndex + 1))
}

fun deterministicJitter(time: Instant, salt: Int): Double {
    val bucket = (time.epochSecond / 60L) + salt.toLong() * 13L
    return abs((bucket % 37L) - 18L).toDouble()
}

fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString

fun JsonObject.bool(name: String): Boolean? =
    get(name)?.takeIf { !it.isJsonNull }?.asBoolean

fun JsonObject.double(name: String): Double? =
    get(name)?.takeIf { !it.isJsonNull }?.asDouble

fun JsonObject.obj(name: String): JsonObject? =
    getAsJsonObject(name)

fun JsonObject.array(name: String): JsonArray? =
    getAsJsonArray(name)

data class ExchangeCapabilitiesSnapshot(
    val paperOrder: Boolean,
    val liveOrder: Boolean,
    val nativeOrderAdapter: Boolean,
    val marketDataIngress: Boolean,
    val bestQuoteDefault: Boolean
)

data class ExchangeCatalogSnapshot(
    val apiName: String,
    val implementationStatus: String,
    val defaultExecutionMode: String,
    val supportedExecutionModes: List<String>,
    val capabilities: ExchangeCapabilitiesSnapshot,
    val notes: String
)

data class ExchangePlan(
    val exchange: String,
    val marketAliases: List<String>
)

@Serializable
data class ResearchConfig(
    val txGatewayUrl: String = env("TX_GATEWAY_URL", "http://tx-gateway:8080"),
    val txAuthToken: String = env("TX_AUTH_TOKEN", ""),
    val marketExchange: String = env("DATAMANCY_CROSS_SECTIONAL_MARKET_EXCHANGE", "hyperliquid_mainnet"),
    val executionExchangeOverride: String = env("DATAMANCY_CROSS_SECTIONAL_EXECUTION_EXCHANGE", ""),
    val barMinutes: Int = envInt("DATAMANCY_CROSS_SECTIONAL_BAR_MINUTES", 60),
    val lookbackHours: Int = envInt("DATAMANCY_CROSS_SECTIONAL_LOOKBACK_HOURS", 1080),
    val forwardHours: Int = envInt("DATAMANCY_CROSS_SECTIONAL_FORWARD_HOURS", 72),
    val betaLookbackBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_BETA_LOOKBACK_BARS", 168),
    val trendLookbackBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TREND_LOOKBACK_BARS", 24),
    val trendSlowBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TREND_SLOW_BARS", 96),
    val reversionLookbackBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_REVERSION_LOOKBACK_BARS", 12),
    val trendHoldBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TREND_HOLD_BARS", 24),
    val reversionHoldBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_REVERSION_HOLD_BARS", 8),
    val topPerSide: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TOP_PER_SIDE", 1),
    val notionalUsd: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_NOTIONAL_USD", 5000.0),
    val maxSymbols: Int = envInt("DATAMANCY_CROSS_SECTIONAL_MAX_SYMBOLS", 8),
    val minBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_MIN_BARS", 360),
    val trendEntryScore: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_TREND_ENTRY_SCORE", 1.05),
    val reversionZEntry: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_REVERSION_Z_ENTRY", 2.15),
    val reversionZExit: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_REVERSION_Z_EXIT", 0.45),
    val maxSpreadBps: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MAX_SPREAD_BPS", 11.0),
    val minDepthMultiple: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_DEPTH_MULTIPLE", 12.0),
    val minFillRatio: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_FILL_RATIO", 0.58),
    val minVolumeRatio: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_VOLUME_RATIO", 0.35),
    val maxVolumeRatio: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MAX_VOLUME_RATIO", 4.5),
    val maxVolRegime: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MAX_VOL_REGIME", 2.35),
    val executionSafetyMarginBps: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_EXECUTION_SAFETY_MARGIN_BPS", 8.0),
    val minExpectedNetEdgeBps: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_EXPECTED_NET_EDGE_BPS", 4.0),
    val trendMinFlowAlignment: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_TREND_MIN_FLOW_ALIGNMENT", 0.08),
    val reversionMaxContinuationPressure: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_REVERSION_MAX_CONTINUATION_PRESSURE", 0.18),
    val calibrationLookbackHours: Int = envInt("DATAMANCY_CROSS_SECTIONAL_CALIBRATION_LOOKBACK_HOURS", 720),
    val minCalibrationSamples: Int = envInt("DATAMANCY_CROSS_SECTIONAL_MIN_CALIBRATION_SAMPLES", 4),
    val strongCalibrationSamples: Int = envInt("DATAMANCY_CROSS_SECTIONAL_STRONG_CALIBRATION_SAMPLES", 12),
    val minCalibrationLowerBoundBps: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_CALIBRATION_LOWER_BOUND_BPS", 0.5),
    val minCalibrationWinRate: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_CALIBRATION_WIN_RATE", 0.51),
    val trendCooldownBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TREND_COOLDOWN_BARS", 8),
    val reversionCooldownBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_REVERSION_COOLDOWN_BARS", 4),
    val persistBacktest: Boolean = envBoolean("DATAMANCY_CROSS_SECTIONAL_PERSIST_BACKTEST", true),
    val persistForward: Boolean = envBoolean("DATAMANCY_CROSS_SECTIONAL_PERSIST_FORWARD", true),
    val enablePaperOrders: Boolean = envBoolean("DATAMANCY_CROSS_SECTIONAL_ENABLE_PAPER_ORDERS", false),
    val paperExecutionMode: String = env("DATAMANCY_CROSS_SECTIONAL_ORDER_MODE", "forward_paper")
)

data class Bar(
    val exchange: String,
    val symbol: String,
    val time: Instant,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val bidDepth10: Double,
    val askDepth10: Double,
    val midPrice: Double
)

data class BasePoint(
    val exchange: String,
    val symbol: String,
    val time: Instant,
    val barIndex: Int,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val spreadBps: Double,
    val bidDepth10: Double,
    val askDepth10: Double,
    val midPrice: Double,
    val depthUsd: Double,
    val ret1m: Double,
    val vol30: Double
)

data class UnrankedFeature(
    val exchange: String,
    val symbol: String,
    val time: Instant,
    val barIndex: Int,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val spreadBps: Double,
    val depthUsd: Double,
    val midPrice: Double,
    val ret1m: Double,
    val vol30: Double,
    val volBps: Double,
    val btcRet1m: Double,
    val ethRet1m: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val residualRet: Double,
    val residualMomFast: Double,
    val residualMomSlow: Double,
    val residualZ: Double,
    val imbalance: Double,
    val volumeRatio: Double,
    val depthRatio: Double,
    val volRegime: Double,
    val flowSignal: Double,
    val rawTrend: Double,
    val trendExpectedGrossEdgeBps: Double,
    val reversionExpectedGrossEdgeBps: Double,
    val liquid: Boolean
)

data class FeatureRow(
    val exchange: String,
    val symbol: String,
    val time: Instant,
    val barIndex: Int,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val spreadBps: Double,
    val depthUsd: Double,
    val midPrice: Double,
    val ret1m: Double,
    val vol30: Double,
    val volBps: Double,
    val btcRet1m: Double,
    val ethRet1m: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val residualRet: Double,
    val residualMomFast: Double,
    val residualMomSlow: Double,
    val residualZ: Double,
    val imbalance: Double,
    val volumeRatio: Double,
    val depthRatio: Double,
    val volRegime: Double,
    val flowSignal: Double,
    val breadth: Double,
    val rawTrend: Double,
    val trendScore: Double,
    val reversionScore: Double,
    val trendExpectedGrossEdgeBps: Double,
    val reversionExpectedGrossEdgeBps: Double,
    val liquid: Boolean,
    val trendLongRank: Int,
    val trendShortRank: Int,
    val reversionLongRank: Int,
    val reversionShortRank: Int
)

data class SignalSnapshot(
    val exchange: String,
    val symbol: String,
    val time: String,
    val lastPrice: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val residualZ: Double,
    val trendScore: Double,
    val reversionScore: Double,
    val breadth: Double,
    val spreadBps: Double,
    val depthUsd: Double,
    val imbalance: Double,
    val flowSignal: Double,
    val volumeRatio: Double,
    val volRegime: Double,
    val trendExpectedNetEdgeBps: Double,
    val reversionExpectedNetEdgeBps: Double,
    val trendCalibrationSamples: Int,
    val reversionCalibrationSamples: Int,
    val trendCalibrationLowerBoundBps: Double,
    val reversionCalibrationLowerBoundBps: Double,
    val liquid: Boolean,
    val trendAction: String,
    val reversionAction: String
)

data class ExecutionEstimate(
    val fillRatio: Double,
    val feeBps: Double,
    val feeTier: String,
    val feeTierAdjustmentBps: Double,
    val makerFeeBps: Double,
    val takerFeeBps: Double,
    val spreadCostBps: Double,
    val slippageBps: Double,
    val impactBps: Double,
    val adverseSelectionBps: Double,
    val fundingDriftBps: Double,
    val basisDriftBps: Double,
    val totalCostBps: Double,
    val estimatedFeeUsd: Double,
    val estimatedCostUsd: Double
)

data class TradeRecord(
    val strategyName: String,
    val strategyKind: String,
    val exchange: String,
    val symbol: String,
    val side: String,
    val entryTime: Instant,
    val exitTime: Instant,
    val entryPrice: Double,
    val exitPrice: Double,
    val holdBars: Int,
    val grossReturnFraction: Double,
    val netReturnFraction: Double,
    val fillRatio: Double,
    val feeBps: Double,
    val feeTier: String,
    val feeTierAdjustmentBps: Double,
    val makerFeeBps: Double,
    val takerFeeBps: Double,
    val spreadCostBps: Double,
    val slippageBps: Double,
    val impactBps: Double,
    val adverseSelectionBps: Double,
    val fundingDriftBps: Double,
    val basisDriftBps: Double,
    val totalCostBps: Double,
    val edgeAfterCostBps: Double,
    val estimatedFeeUsd: Double,
    val estimatedCostUsd: Double,
    val entryTrendScore: Double,
    val entryResidualZ: Double,
    val expectedGrossEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val expectedNetEdgeBps: Double,
    val calibrationSamples: Int,
    val calibrationWinRate: Double,
    val calibrationLowerBoundBps: Double,
    val calibrationScope: String,
    val entryImbalance: Double,
    val entryFlowSignal: Double,
    val entryVolumeRatio: Double,
    val entryVolRegime: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val decisionLatencyMs: Double,
    val submitToAckMs: Double,
    val submitToFillMs: Double,
    val p50RoundtripMs: Double,
    val p95RoundtripMs: Double,
    val p99RoundtripMs: Double,
    val jitterMs: Double
)

data class StrategySummary(
    val strategyName: String,
    val strategyKind: String,
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val startTime: Instant,
    val endTime: Instant,
    val trades: Int,
    val winRate: Double,
    val netReturnPct: Double,
    val maxDrawdownPct: Double,
    val sharpe: Double,
    val avgEdgeAfterCostBps: Double,
    val avgTotalCostBps: Double,
    val avgSlippageBps: Double,
    val avgFillRatio: Double,
    val avgSubmitToFillMs: Double,
    val notes: String,
    val metricsJson: String
)

enum class StrategyKind {
    TREND,
    REVERSION
}

data class OpenPosition(
    val strategyName: String,
    val strategyKind: StrategyKind,
    val exchange: String,
    val symbol: String,
    val side: Int,
    val entryRow: FeatureRow,
    val entryEstimate: ExecutionEstimate,
    val expectedGrossEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val expectedNetEdgeBps: Double,
    val calibrationSamples: Int,
    val calibrationWinRate: Double,
    val calibrationLowerBoundBps: Double,
    val calibrationScope: String
)

data class EntryCandidate(
    val row: FeatureRow,
    val side: Int,
    val entryEstimate: ExecutionEstimate,
    val expectedGrossEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val expectedNetEdgeBps: Double,
    val calibrationSamples: Int,
    val calibrationWinRate: Double,
    val calibrationLowerBoundBps: Double,
    val calibrationScope: String
)

data class CalibrationKey(
    val strategyKind: StrategyKind,
    val exchange: String,
    val symbol: String,
    val side: Int,
    val regimeBucket: String,
    val signalBucket: String,
    val confirmationBucket: String
)

data class CalibrationExample(
    val key: CalibrationKey,
    val entryTime: Instant,
    val availableAt: Instant,
    val grossEdgeBps: Double,
    val netEdgeBps: Double,
    val totalCostBps: Double,
    val fillRatio: Double
)

data class CalibrationStats(
    val samples: Int,
    val winRate: Double,
    val avgGrossEdgeBps: Double,
    val avgNetEdgeBps: Double,
    val avgTotalCostBps: Double,
    val avgFillRatio: Double,
    val lowerBoundNetEdgeBps: Double,
    val scope: String
)

data class CalibrationAccumulator(
    var samples: Int = 0,
    var wins: Int = 0,
    var sumGrossEdgeBps: Double = 0.0,
    var sumNetEdgeBps: Double = 0.0,
    var sumNetEdgeSqBps: Double = 0.0,
    var sumTotalCostBps: Double = 0.0,
    var sumFillRatio: Double = 0.0
)

data class CalibrationState(
    val scoped: MutableMap<CalibrationKey, CalibrationAccumulator> = mutableMapOf()
)

val gson: Gson = GsonBuilder().setPrettyPrinting().create()
val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private val postgresDriverLoaded: Boolean = run {
    Class.forName("org.postgresql.Driver")
    true
}
val pgHost = env("POSTGRES_HOST", "postgres")
val pgPort = env("POSTGRES_PORT", "5432")
val pgDb = env("POSTGRES_DB", "datamancy")
val pgUser = env("POSTGRES_USER", "pipeline_user")
val pgPassword = env("POSTGRES_PASSWORD", "")
val jdbcUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDb"

fun pgConnection(): Connection =
    DriverManager.getConnection(jdbcUrl, pgUser, pgPassword)

fun twoFactorBetas(window: List<Triple<Double, Double, Double>>): Pair<Double, Double> {
    if (window.size < 30) return 0.0 to 0.0
    val yMean = mean(window.map { it.first })
    val x1Mean = mean(window.map { it.second })
    val x2Mean = mean(window.map { it.third })

    var s11 = 0.0
    var s22 = 0.0
    var s12 = 0.0
    var sy1 = 0.0
    var sy2 = 0.0

    for ((yRaw, x1Raw, x2Raw) in window) {
        val y = yRaw - yMean
        val x1 = x1Raw - x1Mean
        val x2 = x2Raw - x2Mean
        s11 += x1 * x1
        s22 += x2 * x2
        s12 += x1 * x2
        sy1 += y * x1
        sy2 += y * x2
    }

    val determinant = (s11 * s22) - (s12 * s12)
    if (abs(determinant) < 1e-9) {
        val beta1 = if (abs(s11) < 1e-9) 0.0 else sy1 / s11
        val beta2 = if (abs(s22) < 1e-9) 0.0 else sy2 / s22
        return beta1 to beta2
    }

    val betaBtc = ((sy1 * s22) - (sy2 * s12)) / determinant
    val betaEth = ((sy2 * s11) - (sy1 * s12)) / determinant
    return betaBtc to betaEth
}

fun fetchExchangeCatalog(txBase: String): List<ExchangeCatalogSnapshot> =
    runCatching {
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges"))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Exchange catalog returned status ${response.statusCode()}")
        }
        val payload = JsonParser.parseString(response.body()).asJsonObject
        payload.array("exchanges")
            ?.map { element ->
                val obj = element.asJsonObject
                val caps = obj.obj("capabilities") ?: JsonObject()
                ExchangeCatalogSnapshot(
                    apiName = obj.string("apiName") ?: obj.string("name") ?: "unknown",
                    implementationStatus = (obj.string("implementationStatus") ?: "placeholder").uppercase(),
                    defaultExecutionMode = obj.string("defaultExecutionMode") ?: "forward_paper",
                    supportedExecutionModes = obj.array("supportedExecutionModes")
                        ?.map { it.asString }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                    capabilities = ExchangeCapabilitiesSnapshot(
                        paperOrder = caps.bool("paperOrder") ?: false,
                        liveOrder = caps.bool("liveOrder") ?: false,
                        nativeOrderAdapter = caps.bool("nativeOrderAdapter") ?: false,
                        marketDataIngress = caps.bool("marketDataIngress") ?: false,
                        bestQuoteDefault = caps.bool("bestQuoteDefault") ?: false
                    ),
                    notes = obj.string("notes") ?: ""
                )
            }
            ?: emptyList()
    }.getOrElse { ex ->
        println("Exchange catalog unavailable, falling back to Hyperliquid only: ${ex.message}")
        listOf(
            ExchangeCatalogSnapshot(
                apiName = "hyperliquid",
                implementationStatus = "INTEGRATED",
                defaultExecutionMode = "forward_paper",
                supportedExecutionModes = listOf("backtest", "forward_paper", "testnet_live"),
                capabilities = ExchangeCapabilitiesSnapshot(
                    paperOrder = true,
                    liveOrder = true,
                    nativeOrderAdapter = true,
                    marketDataIngress = true,
                    bestQuoteDefault = true
                ),
                notes = "Fallback exchange selection"
            )
        )
    }

fun buildExchangePlans(catalog: List<ExchangeCatalogSnapshot>, config: ResearchConfig): List<ExchangePlan> {
    val overrideExchange = config.executionExchangeOverride.trim().lowercase()
    val selected = if (overrideExchange.isNotEmpty()) {
        listOf(overrideExchange)
    } else {
        catalog
            .filter {
                it.implementationStatus == "INTEGRATED" ||
                    it.capabilities.marketDataIngress ||
                    it.capabilities.bestQuoteDefault
            }
            .map { it.apiName.lowercase() }
            .distinct()
            .ifEmpty { listOf("hyperliquid") }
    }

    return selected.map { exchange ->
        val aliases = when (exchange) {
            "hyperliquid" -> when (config.marketExchange.lowercase()) {
                "hyperliquid_testnet" -> listOf("hyperliquid_testnet")
                "hyperliquid" -> listOf("hyperliquid")
                "hyperliquid_merged", "hyperliquid_mainnet_merged" -> listOf("hyperliquid_mainnet", "hyperliquid")
                "hyperliquid_mainnet" -> listOf("hyperliquid_mainnet")
                else -> listOf("hyperliquid_mainnet")
            }
            else -> listOf(exchange)
        }
        ExchangePlan(exchange = exchange, marketAliases = aliases.distinct())
    }
}

fun discoverSymbols(aliases: List<String>, lookbackHours: Int, maxSymbols: Int, minBars: Int): List<String> {
    val aliasSql = sqlList(aliases)
    val sql = """
        SELECT symbol,
               COUNT(*) AS bars,
               COALESCE(AVG(volume), 0) AS avg_volume
        FROM market_data
        WHERE exchange IN ($aliasSql)
          AND data_type = 'candle_1m'
          AND time >= NOW() - INTERVAL '${lookbackHours} hours'
        GROUP BY symbol
        HAVING COUNT(*) >= $minBars
        ORDER BY bars DESC, avg_volume DESC, symbol ASC
        LIMIT $maxSymbols
    """.trimIndent()

    val discovered = mutableListOf<String>()
    pgConnection().use { conn ->
        conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    discovered += rs.getString("symbol")
                }
            }
        }
    }

    val universe = (listOf("BTC", "ETH") + discovered).distinct()
    return universe
}

fun loadBars(exchange: String, aliases: List<String>, symbols: List<String>, lookbackHours: Int, barMinutes: Int): List<Bar> {
    if (symbols.isEmpty()) return emptyList()
    val aliasSql = sqlList(aliases)
    val symbolSql = sqlList(symbols)
    val preferredAlias = aliases.first()
    val bucketSeconds = max(barMinutes, 1) * 60
    val bucketInterval = "${max(barMinutes, 1)} minutes"
    val sql = """
        WITH candle_rows AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                close,
                COALESCE(volume, 0) AS volume,
                exchange,
                time
            FROM market_data
            WHERE exchange IN ($aliasSql)
              AND data_type = 'candle_1m'
              AND time >= NOW() - INTERVAL '${lookbackHours} hours'
              AND symbol IN ($symbolSql)
        ),
        candles AS (
            SELECT DISTINCT ON (symbol, bucket_time)
                symbol,
                bucket_time,
                close,
                SUM(volume) OVER (PARTITION BY symbol, bucket_time) AS volume
            FROM candle_rows
            ORDER BY
                symbol,
                bucket_time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END,
                time DESC
        )
        SELECT
            c.symbol,
            c.bucket_time,
            c.close,
            c.volume,
            COALESCE(o.spread_pct, 0) AS spread_pct,
            COALESCE(o.bid_depth_10, 0) AS bid_depth_10,
            COALESCE(o.ask_depth_10, 0) AS ask_depth_10,
            COALESCE(NULLIF(o.mid_price, 0), c.close) AS mid_price
        FROM candles c
        LEFT JOIN LATERAL (
            SELECT
                COALESCE(spread_pct, 0) AS spread_pct,
                COALESCE(bid_depth_10, 0) AS bid_depth_10,
                COALESCE(ask_depth_10, 0) AS ask_depth_10,
                COALESCE(NULLIF(mid_price, 0), c.close) AS mid_price
            FROM orderbook_data o
            WHERE o.symbol = c.symbol
              AND o.exchange IN ($aliasSql)
              AND o.time >= c.bucket_time
              AND o.time < c.bucket_time + INTERVAL '$bucketInterval'
            ORDER BY
                CASE WHEN o.exchange = '$preferredAlias' THEN 0 ELSE 1 END,
                o.time DESC
            LIMIT 1
        ) o ON TRUE
        ORDER BY c.bucket_time ASC, c.symbol ASC
    """.trimIndent()

    return buildList {
        pgConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        add(
                            Bar(
                                exchange = exchange,
                                symbol = rs.getString("symbol"),
                                time = rs.getTimestamp("bucket_time").toInstant(),
                                close = rs.getDouble("close"),
                                volume = rs.getDouble("volume"),
                                spreadPct = rs.getDouble("spread_pct"),
                                bidDepth10 = rs.getDouble("bid_depth_10"),
                                askDepth10 = rs.getDouble("ask_depth_10"),
                                midPrice = rs.getDouble("mid_price")
                            )
                        )
                    }
                }
            }
        }
    }
}

fun engineerFeatures(bars: List<Bar>, config: ResearchConfig): List<FeatureRow> {
    if (bars.isEmpty()) return emptyList()

    val seriesByKey = bars.groupBy { it.exchange to it.symbol }
        .mapValues { (_, value) -> value.sortedBy { it.time } }

    val baseByKey = seriesByKey.mapValues { (key, series) ->
        val returns = ArrayDeque<Double>()
        series.mapIndexed { index, bar ->
            val previous = series.getOrNull(index - 1)
            val ret1m = if (previous == null || previous.close <= 0.0) 0.0 else (bar.close / previous.close) - 1.0
            returns.addLast(ret1m)
            if (returns.size > 30) {
                returns.removeFirst()
            }
            val vol30 = stdev(returns.toList())
            val spreadBps = max(bar.spreadPct, 0.0) * 100.0
            val midPrice = max(bar.midPrice, bar.close)
            val depthUsd = max((max(bar.bidDepth10, 0.0) + max(bar.askDepth10, 0.0)) * midPrice, 0.0)
            BasePoint(
                exchange = key.first,
                symbol = key.second,
                time = bar.time,
                barIndex = index,
                close = bar.close,
                volume = bar.volume,
                spreadPct = bar.spreadPct,
                spreadBps = spreadBps,
                bidDepth10 = bar.bidDepth10,
                askDepth10 = bar.askDepth10,
                midPrice = midPrice,
                depthUsd = depthUsd,
                ret1m = ret1m,
                vol30 = vol30
            )
        }
    }

    val baseLookup = mutableMapOf<Triple<String, String, Instant>, BasePoint>()
    baseByKey.values.flatten().forEach { point ->
        baseLookup[Triple(point.exchange, point.symbol, point.time)] = point
    }

    val unranked = mutableListOf<UnrankedFeature>()
    for ((key, series) in baseByKey) {
        val exchange = key.first
        val residuals = mutableListOf<Double>()
        val dislocations = mutableListOf<Double>()
        val volumeSeries = series.map { max(it.volume, 0.0) }
        val depthSeries = series.map { max(it.depthUsd, 0.0) }
        val volSeries = series.map { max(it.vol30, 0.0) }

        for (i in series.indices) {
            val point = series[i]
            val betaWindow = buildList {
                val start = max(0, i - config.betaLookbackBars + 1)
                for (j in start..i) {
                    val row = series[j]
                    val btc = baseLookup[Triple(exchange, "BTC", row.time)] ?: continue
                    val eth = baseLookup[Triple(exchange, "ETH", row.time)] ?: continue
                    add(Triple(row.ret1m, btc.ret1m, eth.ret1m))
                }
            }

            val (betaBtc, betaEth) = twoFactorBetas(betaWindow)
            val btcRet = baseLookup[Triple(exchange, "BTC", point.time)]?.ret1m ?: 0.0
            val ethRet = baseLookup[Triple(exchange, "ETH", point.time)]?.ret1m ?: 0.0
            val residualRet = point.ret1m - (betaBtc * btcRet) - (betaEth * ethRet)
            residuals += residualRet

            val residualMomFast = rollingSum(residuals, residuals.lastIndex, config.trendLookbackBars)
            val residualMomSlow = rollingSum(residuals, residuals.lastIndex, config.trendSlowBars)
            val dislocation = rollingSum(residuals, residuals.lastIndex, config.reversionLookbackBars)
            dislocations += dislocation
            val dislocationWindow = dislocations.subList(max(0, dislocations.size - config.betaLookbackBars), dislocations.size)
            val dislocationMean = mean(dislocationWindow)
            val dislocationStd = stdev(dislocationWindow)
            val residualZ = if (dislocationStd < 1e-9) 0.0 else (dislocation - dislocationMean) / dislocationStd
            val volumeBaseline = max(rollingMean(volumeSeries, i, 60), 1.0)
            val depthBaseline = max(rollingMean(depthSeries, i, 60), config.notionalUsd)
            val volBaseline = max(rollingMean(volSeries, i, 120), 1e-6)
            val volumeRatio = clamp(point.volume / volumeBaseline, 0.0, 8.0)
            val depthRatio = clamp(point.depthUsd / depthBaseline, 0.0, 6.0)
            val imbalance = (max(point.bidDepth10, 0.0) - max(point.askDepth10, 0.0)) /
                max(max(point.bidDepth10, 0.0) + max(point.askDepth10, 0.0), 1e-6)
            val volRegime = clamp(point.vol30 / volBaseline, 0.25, 6.0)
            val normalizedRet = clamp(point.ret1m / max(point.vol30, 1e-6), -4.0, 4.0)
            val flowSignal = clamp(
                (normalizedRet * 0.65) +
                    (imbalance * 0.95) +
                    ((clamp(volumeRatio, 0.0, 4.0) - 1.0) * 0.22),
                -3.5,
                3.5
            )
            val fastScale = max(point.vol30 * sqrt(config.trendLookbackBars.toDouble()), 1e-6)
            val slowScale = max(point.vol30 * sqrt(config.trendSlowBars.toDouble()), 1e-6)
            val rawTrend = ((residualMomFast / fastScale) * 0.7) +
                ((residualMomSlow / slowScale) * 0.3) +
                (flowSignal * 0.12) -
                (point.spreadBps / 55.0)
            val volBps = point.vol30 * 10000.0
            val trendExpectedGrossEdgeBps = clamp(
                (abs(rawTrend) * max(volBps, 4.0) * 0.55 * sqrt(config.trendHoldBars.toDouble() / 15.0)) +
                    (max(0.0, direction(rawTrend) * flowSignal) * 7.0) +
                    (max(0.0, depthRatio - 1.0) * 2.0) +
                    (max(0.0, min(volumeRatio, 3.0) - 1.0) * 2.5) -
                    (max(0.0, abs(residualZ) - 1.0) * 5.0) -
                    (max(0.0, volRegime - 1.6) * 5.0),
                0.0,
                220.0
            )
            val reversionExpectedGrossEdgeBps = clamp(
                (abs(residualZ) * max(volBps, 4.0) * 0.70 * sqrt(config.reversionHoldBars.toDouble() / 10.0)) +
                    (max(0.0, -(direction(residualZ) * flowSignal)) * 8.0) +
                    (max(0.0, depthRatio - 1.0) * 2.0) -
                    (max(0.0, abs(rawTrend) - 0.85) * 6.5) -
                    (max(0.0, volRegime - 1.45) * 6.0),
                0.0,
                220.0
            )
            val tradable = point.symbol !in setOf("BTC", "ETH")
            val liquid = point.spreadBps <= config.maxSpreadBps &&
                point.depthUsd >= config.notionalUsd * config.minDepthMultiple &&
                point.barIndex >= max(config.betaLookbackBars, config.trendSlowBars) &&
                point.volume > 0.0 &&
                volumeRatio >= config.minVolumeRatio &&
                tradable

            unranked += UnrankedFeature(
                exchange = exchange,
                symbol = point.symbol,
                time = point.time,
                barIndex = point.barIndex,
                close = point.close,
                volume = point.volume,
                spreadPct = point.spreadPct,
                spreadBps = point.spreadBps,
                depthUsd = point.depthUsd,
                midPrice = point.midPrice,
                ret1m = point.ret1m,
                vol30 = point.vol30,
                volBps = point.vol30 * 10000.0,
                btcRet1m = btcRet,
                ethRet1m = ethRet,
                betaBtc = betaBtc,
                betaEth = betaEth,
                residualRet = residualRet,
                residualMomFast = residualMomFast,
                residualMomSlow = residualMomSlow,
                residualZ = residualZ,
                imbalance = imbalance,
                volumeRatio = volumeRatio,
                depthRatio = depthRatio,
                volRegime = volRegime,
                flowSignal = flowSignal,
                rawTrend = rawTrend,
                trendExpectedGrossEdgeBps = trendExpectedGrossEdgeBps,
                reversionExpectedGrossEdgeBps = reversionExpectedGrossEdgeBps,
                liquid = liquid
            )
        }
    }

    val grouped = unranked.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val finalRows = mutableListOf<FeatureRow>()

    for (groupKey in orderedKeys) {
        val bucket = grouped[groupKey].orEmpty()
        if (bucket.isEmpty()) continue
        val breadthTilt = mean(bucket.map { clamp(it.rawTrend / 3.0, -1.0, 1.0) })
        val breadth = clamp((breadthTilt + 1.0) / 2.0, 0.0, 1.0)
        val marketStress = mean(bucket.map { it.volRegime })
        val trendScores = bucket.associateWith { row ->
            val flowAlignment = direction(row.rawTrend) * row.flowSignal
            val breadthAlignment = direction(row.rawTrend) * breadthTilt
            row.rawTrend +
                (breadthAlignment * 0.9) +
                (max(0.0, flowAlignment) * 0.65) -
                (max(0.0, -flowAlignment) * 1.0) +
                min(max(0.0, row.depthRatio - 1.0) * 0.35, 0.55) +
                (max(0.0, min(row.volumeRatio, 3.0) - 1.0) * 0.22) -
                (max(0.0, abs(row.residualZ) - 1.0) * 0.45) -
                (max(0.0, row.volRegime - 1.7) * 0.45)
        }
        val reversionScores = bucket.associateWith { row ->
            val continuationPressure = direction(row.residualZ) * row.flowSignal
            val breadthContinuation = direction(row.residualZ) * breadthTilt
            abs(row.residualZ) +
                (max(0.0, -continuationPressure) * 0.95) -
                (max(0.0, continuationPressure) * 1.25) -
                (max(0.0, abs(row.rawTrend) - 0.75) * 0.85) -
                (row.spreadBps / 35.0) -
                (max(0.0, breadthContinuation) * 1.15) -
                (max(0.0, row.volRegime - 1.55) * 0.60)
        }
        val trendExpectedEdges = bucket.associateWith { row ->
            val breadthAlignment = direction(row.rawTrend) * breadthTilt
            clamp(
                row.trendExpectedGrossEdgeBps +
                    (max(0.0, breadthAlignment) * 6.0) -
                    (max(0.0, -breadthAlignment) * 8.0) -
                    (max(0.0, marketStress - 1.6) * 5.0),
                0.0,
                220.0
            )
        }
        val reversionExpectedEdges = bucket.associateWith { row ->
            val breadthContinuation = direction(row.residualZ) * breadthTilt
            clamp(
                row.reversionExpectedGrossEdgeBps +
                    (max(0.0, -breadthContinuation) * 5.0) -
                    (max(0.0, breadthContinuation) * 7.0) -
                    (max(0.0, marketStress - 1.5) * 6.0),
                0.0,
                220.0
            )
        }
        val trendLongRanks = trendScores.entries
            .filter { it.key.liquid && (trendExpectedEdges[it.key] ?: 0.0) > 0.0 }
            .sortedByDescending { it.value }
            .mapIndexed { index, entry -> entry.key to index + 1 }
            .toMap()
        val trendShortRanks = trendScores.entries
            .filter { it.key.liquid && (trendExpectedEdges[it.key] ?: 0.0) > 0.0 }
            .sortedBy { it.value }
            .mapIndexed { index, entry -> entry.key to index + 1 }
            .toMap()
        val reversionLongRanks = bucket
            .filter { it.liquid && it.residualZ < 0.0 && (reversionExpectedEdges[it] ?: 0.0) > 0.0 }
            .sortedBy { it.residualZ }
            .mapIndexed { index, row -> row to index + 1 }
            .toMap()
        val reversionShortRanks = bucket
            .filter { it.liquid && it.residualZ > 0.0 && (reversionExpectedEdges[it] ?: 0.0) > 0.0 }
            .sortedByDescending { it.residualZ }
            .mapIndexed { index, row -> row to index + 1 }
            .toMap()

        bucket.forEach { row ->
            finalRows += FeatureRow(
                exchange = row.exchange,
                symbol = row.symbol,
                time = row.time,
                barIndex = row.barIndex,
                close = row.close,
                volume = row.volume,
                spreadPct = row.spreadPct,
                spreadBps = row.spreadBps,
                depthUsd = row.depthUsd,
                midPrice = row.midPrice,
                ret1m = row.ret1m,
                vol30 = row.vol30,
                volBps = row.volBps,
                btcRet1m = row.btcRet1m,
                ethRet1m = row.ethRet1m,
                betaBtc = row.betaBtc,
                betaEth = row.betaEth,
                residualRet = row.residualRet,
                residualMomFast = row.residualMomFast,
                residualMomSlow = row.residualMomSlow,
                residualZ = row.residualZ,
                imbalance = row.imbalance,
                volumeRatio = row.volumeRatio,
                depthRatio = row.depthRatio,
                volRegime = row.volRegime,
                flowSignal = row.flowSignal,
                breadth = breadth,
                rawTrend = row.rawTrend,
                trendScore = trendScores[row] ?: row.rawTrend,
                reversionScore = reversionScores[row] ?: 0.0,
                trendExpectedGrossEdgeBps = trendExpectedEdges[row] ?: row.trendExpectedGrossEdgeBps,
                reversionExpectedGrossEdgeBps = reversionExpectedEdges[row] ?: row.reversionExpectedGrossEdgeBps,
                liquid = row.liquid,
                trendLongRank = trendLongRanks[row] ?: Int.MAX_VALUE,
                trendShortRank = trendShortRanks[row] ?: Int.MAX_VALUE,
                reversionLongRank = reversionLongRanks[row] ?: Int.MAX_VALUE,
                reversionShortRank = reversionShortRanks[row] ?: Int.MAX_VALUE
            )
        }
    }

    return finalRows.sortedWith(compareBy<FeatureRow> { it.time }.thenBy { it.exchange }.thenBy { it.symbol })
}

fun buildExecutionEstimate(row: FeatureRow, notionalUsd: Double, side: Int, kind: StrategyKind): ExecutionEstimate {
    val spreadHalfBps = row.spreadBps / 2.0
    val depthPressure = notionalUsd / max(row.depthUsd, notionalUsd)
    val volatilityPenalty = min(row.volBps / 70.0, 4.0)
    val imbalanceAgainstTrade = max(0.0, -side.toDouble() * row.imbalance)
    val imbalanceWithTrade = max(0.0, side.toDouble() * row.imbalance)
    val flowAgainstTrade = max(0.0, -side.toDouble() * row.flowSignal)
    val makerFeeBps = 1.0
    val takerFeeBps = 4.0
    val makerShare = clamp(
        (if (kind == StrategyKind.REVERSION) 0.62 else 0.52) -
            (depthPressure * 0.32) -
            (volatilityPenalty * 0.03) -
            (flowAgainstTrade * 0.05) +
            (imbalanceWithTrade * 0.05) -
            (spreadHalfBps / 45.0),
        0.12,
        0.88
    )
    val feeBps = (makerFeeBps * makerShare) + (takerFeeBps * (1.0 - makerShare))
    val fillRatio = clamp(
        0.96 -
            (spreadHalfBps / 30.0) -
            (depthPressure * 2.8) -
            (volatilityPenalty * 0.04) -
            (flowAgainstTrade * 0.05) -
            (imbalanceAgainstTrade * 0.03) +
            (imbalanceWithTrade * 0.02),
        0.25,
        1.0
    )
    val slippageBps = clamp(
        0.20 +
            (spreadHalfBps * 0.18) +
            (depthPressure * 9.0) +
            (volatilityPenalty * 0.70) +
            (flowAgainstTrade * 1.9) +
            (imbalanceAgainstTrade * 0.8) -
            (makerShare * 0.70),
        0.15,
        18.0
    )
    val impactBps = clamp(
        0.15 +
            (depthPressure * 14.0) +
            (max(0.0, row.volumeRatio - 2.0) * 0.9) +
            (max(0.0, row.volRegime - 1.3) * 1.8),
        0.10,
        22.0
    )
    val adverseSelectionBps = clamp(
        (abs(row.residualZ) * 0.35) +
            (flowAgainstTrade * 2.5) +
            (max(0.0, row.volRegime - 1.0) * 2.2) +
            (max(0.0, abs(row.rawTrend) - 1.5) * 1.2),
        0.0,
        10.0
    )
    val totalCostBps = feeBps + spreadHalfBps + slippageBps + impactBps + adverseSelectionBps
    return ExecutionEstimate(
        fillRatio = fillRatio,
        feeBps = feeBps,
        feeTier = if (makerShare >= 0.5) "retail_mixed_maker_bias" else "retail_mixed_taker_bias",
        feeTierAdjustmentBps = (feeBps - takerFeeBps).round(4),
        makerFeeBps = makerFeeBps,
        takerFeeBps = takerFeeBps,
        spreadCostBps = spreadHalfBps,
        slippageBps = slippageBps,
        impactBps = impactBps,
        adverseSelectionBps = adverseSelectionBps,
        fundingDriftBps = 0.0,
        basisDriftBps = 0.0,
        totalCostBps = totalCostBps,
        estimatedFeeUsd = notionalUsd * feeBps / 10000.0,
        estimatedCostUsd = notionalUsd * totalCostBps / 10000.0
    )
}

fun buildExpectedRoundTripCostBps(row: FeatureRow, entryEstimate: ExecutionEstimate): Double {
    val regimeBuffer = max(0.0, row.volRegime - 1.0) * 1.75
    val flowBuffer = max(0.0, abs(row.flowSignal) - 0.8) * 1.40
    return (entryEstimate.totalCostBps * 2.0) + regimeBuffer + flowBuffer
}

fun breadthTilt(row: FeatureRow): Double =
    (row.breadth - 0.5) * 2.0

fun calibrationRegimeBucket(row: FeatureRow): String =
    when {
        row.volRegime < 0.95 -> "calm"
        row.volRegime < 1.45 -> "normal"
        else -> "stress"
    }

fun calibrationSignalBucket(kind: StrategyKind, row: FeatureRow): String =
    when (kind) {
        StrategyKind.TREND -> when {
            abs(row.trendScore) < 1.35 -> "entry"
            abs(row.trendScore) < 1.90 -> "strong"
            else -> "extreme"
        }
        StrategyKind.REVERSION -> when {
            abs(row.residualZ) < 2.60 -> "entry"
            abs(row.residualZ) < 3.30 -> "deep"
            else -> "extreme"
        }
    }

fun calibrationConfirmationBucket(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): String {
    val flowAlignment = side.toDouble() * row.flowSignal
    val fastAlignment = side.toDouble() * direction(row.residualMomFast)
    val slowAlignment = side.toDouble() * direction(row.residualMomSlow)
    val continuationPressure = direction(row.residualZ) * row.flowSignal
    return when (kind) {
        StrategyKind.TREND -> when {
            flowAlignment >= config.trendMinFlowAlignment && fastAlignment > 0.0 && slowAlignment > 0.0 -> "confirmed"
            flowAlignment >= -0.04 && (fastAlignment > 0.0 || slowAlignment > 0.0) -> "mixed"
            else -> "fragile"
        }
        StrategyKind.REVERSION -> when {
            flowAlignment >= 0.08 && fastAlignment > 0.0 -> "confirmed"
            continuationPressure <= (config.reversionMaxContinuationPressure * 0.55) &&
                flowAlignment >= -0.04 &&
                abs(row.rawTrend) < (config.trendEntryScore * 0.95) -> "stall"
            else -> "fragile"
        }
    }
}

fun calibrationBaseKey(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): CalibrationKey =
    CalibrationKey(
        strategyKind = kind,
        exchange = row.exchange,
        symbol = row.symbol,
        side = side,
        regimeBucket = calibrationRegimeBucket(row),
        signalBucket = calibrationSignalBucket(kind, row),
        confirmationBucket = calibrationConfirmationBucket(kind, row, side, config)
    )

fun calibrationScopesForKey(key: CalibrationKey): List<Pair<String, CalibrationKey>> =
    listOf(
        "symbol_regime_signal_confirmation" to key,
        "market_regime_signal_confirmation" to key.copy(symbol = "ALL"),
        "symbol_regime_confirmation" to key.copy(signalBucket = "ALL"),
        "market_regime_confirmation" to key.copy(symbol = "ALL", signalBucket = "ALL"),
        "symbol_confirmation" to key.copy(regimeBucket = "ALL", signalBucket = "ALL"),
        "market_confirmation" to key.copy(symbol = "ALL", regimeBucket = "ALL", signalBucket = "ALL"),
        "symbol_all" to key.copy(regimeBucket = "ALL", signalBucket = "ALL", confirmationBucket = "ALL"),
        "market_all" to key.copy(symbol = "ALL", regimeBucket = "ALL", signalBucket = "ALL", confirmationBucket = "ALL")
    )

fun calibrationScopesForRow(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig
): List<Pair<String, CalibrationKey>> =
    calibrationScopesForKey(calibrationBaseKey(kind, row, side, config))

fun CalibrationAccumulator.applyExample(example: CalibrationExample, multiplier: Int) {
    val factor = multiplier.toDouble()
    samples += multiplier
    wins += if (example.netEdgeBps > 0.0) multiplier else 0
    sumGrossEdgeBps += example.grossEdgeBps * factor
    sumNetEdgeBps += example.netEdgeBps * factor
    sumNetEdgeSqBps += example.netEdgeBps.pow(2.0) * factor
    sumTotalCostBps += example.totalCostBps * factor
    sumFillRatio += example.fillRatio * factor
}

fun CalibrationAccumulator.toStats(scope: String): CalibrationStats? {
    if (samples <= 0) return null
    val sampleCount = samples.toDouble()
    val avgNetEdgeBps = sumNetEdgeBps / sampleCount
    val variance = max((sumNetEdgeSqBps / sampleCount) - avgNetEdgeBps.pow(2.0), 0.0)
    val stderr = sqrt(variance / sampleCount)
    return CalibrationStats(
        samples = samples,
        winRate = wins.toDouble() / sampleCount,
        avgGrossEdgeBps = sumGrossEdgeBps / sampleCount,
        avgNetEdgeBps = avgNetEdgeBps,
        avgTotalCostBps = sumTotalCostBps / sampleCount,
        avgFillRatio = sumFillRatio / sampleCount,
        lowerBoundNetEdgeBps = avgNetEdgeBps - (1.28 * stderr),
        scope = scope
    )
}

fun addCalibrationExample(state: CalibrationState, example: CalibrationExample) {
    calibrationScopesForKey(example.key).forEach { (_, scopedKey) ->
        state.scoped.getOrPut(scopedKey) { CalibrationAccumulator() }.applyExample(example, 1)
    }
}

fun removeCalibrationExample(state: CalibrationState, example: CalibrationExample) {
    calibrationScopesForKey(example.key).forEach { (_, scopedKey) ->
        val accumulator = state.scoped[scopedKey] ?: return@forEach
        accumulator.applyExample(example, -1)
        if (accumulator.samples <= 0) {
            state.scoped.remove(scopedKey)
        }
    }
}

fun buildCalibrationState(examples: List<CalibrationExample>): CalibrationState {
    val state = CalibrationState()
    examples.forEach { addCalibrationExample(state, it) }
    return state
}

fun blendCalibrationStats(primary: CalibrationStats, fallback: CalibrationStats, config: ResearchConfig): CalibrationStats {
    if (primary.scope == fallback.scope) return primary
    val weight = clamp(primary.samples.toDouble() / max(config.strongCalibrationSamples, 1).toDouble(), 0.0, 1.0)
    return CalibrationStats(
        samples = primary.samples,
        winRate = (primary.winRate * weight) + (fallback.winRate * (1.0 - weight)),
        avgGrossEdgeBps = (primary.avgGrossEdgeBps * weight) + (fallback.avgGrossEdgeBps * (1.0 - weight)),
        avgNetEdgeBps = (primary.avgNetEdgeBps * weight) + (fallback.avgNetEdgeBps * (1.0 - weight)),
        avgTotalCostBps = (primary.avgTotalCostBps * weight) + (fallback.avgTotalCostBps * (1.0 - weight)),
        avgFillRatio = (primary.avgFillRatio * weight) + (fallback.avgFillRatio * (1.0 - weight)),
        lowerBoundNetEdgeBps = (primary.lowerBoundNetEdgeBps * weight) + (fallback.lowerBoundNetEdgeBps * (1.0 - weight)),
        scope = primary.scope
    )
}

fun resolveCalibration(
    state: CalibrationState?,
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig
): CalibrationStats? {
    if (state == null) return null
    val scopedStats = calibrationScopesForRow(kind, row, side, config)
        .mapNotNull { (scope, key) -> state.scoped[key]?.toStats(scope) }
    if (scopedStats.isEmpty()) return null
    val fallback = scopedStats.last()
    val primary = scopedStats.firstOrNull { it.samples >= config.minCalibrationSamples }
        ?: fallback.takeIf { it.samples >= config.minCalibrationSamples }
        ?: return null
    return blendCalibrationStats(primary, fallback, config)
}

fun buildStructuralCandidate(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): EntryCandidate? {
    if (!row.liquid) return null
    if (row.volumeRatio < config.minVolumeRatio || row.volumeRatio > config.maxVolumeRatio) return null
    if (row.volRegime > config.maxVolRegime) return null

    val entryEstimate = buildExecutionEstimate(row, config.notionalUsd, side, kind)
    if (entryEstimate.fillRatio < config.minFillRatio) return null

    val expectedGrossEdgeBps = when (kind) {
        StrategyKind.TREND -> row.trendExpectedGrossEdgeBps
        StrategyKind.REVERSION -> row.reversionExpectedGrossEdgeBps
    }
    val expectedRoundTripCostBps = buildExpectedRoundTripCostBps(row, entryEstimate)
    val expectedNetEdgeBps = expectedGrossEdgeBps - expectedRoundTripCostBps
    val rowBreadthTilt = breadthTilt(row)
    val continuationPressure = direction(row.residualZ) * row.flowSignal

    when (kind) {
        StrategyKind.TREND -> {
            val flowAlignment = side.toDouble() * row.flowSignal
            val breadthAlignment = side.toDouble() * rowBreadthTilt
            if ((side.toDouble() * row.trendScore) < config.trendEntryScore) return null
            if ((side.toDouble() * row.rawTrend) <= 0.0) return null
            if (flowAlignment < config.trendMinFlowAlignment) return null
            if (breadthAlignment < -0.05) return null
            if ((side.toDouble() * direction(row.residualMomFast)) <= 0.0) return null
            if ((side.toDouble() * direction(row.residualMomSlow)) <= 0.0) return null
            if (abs(row.residualZ) > config.reversionZEntry * 1.05) return null
        }
        StrategyKind.REVERSION -> {
            if (side > 0 && row.residualZ > -config.reversionZEntry) return null
            if (side < 0 && row.residualZ < config.reversionZEntry) return null
            if (row.reversionScore <= 0.0) return null
            if (abs(row.rawTrend) > config.trendEntryScore * 1.10) return null
            if (continuationPressure > (config.reversionMaxContinuationPressure * 0.75)) return null
            if (direction(row.residualZ) * rowBreadthTilt > 0.15) return null
            if ((side.toDouble() * row.flowSignal) < -0.08) return null
            if ((side.toDouble() * direction(row.residualMomFast)) < 0.0 && abs(row.rawTrend) > (config.trendEntryScore * 0.85)) return null
        }
    }

    return EntryCandidate(
        row = row,
        side = side,
        entryEstimate = entryEstimate,
        expectedGrossEdgeBps = expectedGrossEdgeBps,
        expectedRoundTripCostBps = expectedRoundTripCostBps,
        expectedNetEdgeBps = expectedNetEdgeBps,
        calibrationSamples = 0,
        calibrationWinRate = 0.0,
        calibrationLowerBoundBps = 0.0,
        calibrationScope = "heuristic"
    )
}

fun buildEntryCandidate(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): EntryCandidate? {
    val seed = buildStructuralCandidate(kind, row, side, config) ?: return null
    val safetyMarginBps = config.executionSafetyMarginBps + (max(0.0, row.volRegime - 1.0) * 2.5)

    if (calibrationState == null) {
        if (seed.expectedNetEdgeBps < config.minExpectedNetEdgeBps) return null
        if (seed.expectedGrossEdgeBps < seed.expectedRoundTripCostBps + safetyMarginBps) return null
        return seed
    }

    val calibration = resolveCalibration(calibrationState, kind, row, side, config) ?: return null
    if (calibration.avgFillRatio < config.minFillRatio) return null
    if (calibration.winRate < config.minCalibrationWinRate) return null
    if (calibration.lowerBoundNetEdgeBps < config.minCalibrationLowerBoundBps) return null
    if (calibration.avgNetEdgeBps < config.minExpectedNetEdgeBps) return null

    val calibratedGrossEdgeBps = max(
        calibration.avgGrossEdgeBps,
        seed.expectedRoundTripCostBps + calibration.avgNetEdgeBps
    )
    if (calibratedGrossEdgeBps < seed.expectedRoundTripCostBps + safetyMarginBps) return null

    return seed.copy(
        expectedGrossEdgeBps = calibratedGrossEdgeBps.round(4),
        expectedNetEdgeBps = calibration.avgNetEdgeBps.round(4),
        calibrationSamples = calibration.samples,
        calibrationWinRate = calibration.winRate.round(4),
        calibrationLowerBoundBps = calibration.lowerBoundNetEdgeBps.round(4),
        calibrationScope = calibration.scope
    )
}

fun shouldExitPosition(kind: StrategyKind, position: OpenPosition, current: FeatureRow, config: ResearchConfig): Boolean =
    when (kind) {
        StrategyKind.TREND -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.trendHoldBars ||
                (current.trendScore * position.side.toDouble()) <= 0.12 ||
                (position.side.toDouble() * current.flowSignal) < -0.18 ||
                current.volRegime > (config.maxVolRegime * 1.15)
        }
        StrategyKind.REVERSION -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.reversionHoldBars ||
                abs(current.residualZ) <= config.reversionZExit ||
                (current.residualZ * position.side.toDouble()) >= -0.05 ||
                (direction(position.entryRow.residualZ) * current.flowSignal) > config.reversionMaxContinuationPressure
        }
    }

fun seedCandidateRows(kind: StrategyKind, bucket: List<FeatureRow>, config: ResearchConfig): List<EntryCandidate> =
    when (kind) {
        StrategyKind.TREND -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.trendLongRank <= config.topPerSide &&
                        it.trendScore >= config.trendEntryScore &&
                        abs(it.residualZ) <= config.reversionZEntry * 1.5
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.TREND, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.residualZ) <= config.reversionZEntry * 1.5
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.TREND, it, -1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
        StrategyKind.REVERSION -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.reversionLongRank <= config.topPerSide &&
                        it.residualZ <= -config.reversionZEntry &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.REVERSION, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.residualZ >= config.reversionZEntry &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.REVERSION, it, -1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
    }

fun candidateRows(
    kind: StrategyKind,
    bucket: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<EntryCandidate> =
    when (kind) {
        StrategyKind.TREND -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.trendLongRank <= config.topPerSide &&
                        it.trendScore >= config.trendEntryScore &&
                        abs(it.residualZ) <= config.reversionZEntry * 1.5
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, 1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.residualZ) <= config.reversionZEntry * 1.5
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, -1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
        StrategyKind.REVERSION -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.reversionLongRank <= config.topPerSide &&
                        it.residualZ <= -config.reversionZEntry &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, 1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.residualZ >= config.reversionZEntry &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, -1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
    }

fun latestSignalSnapshots(
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<SignalSnapshot> =
    rows.groupBy { it.exchange to it.symbol }
        .values
        .mapNotNull { series -> series.maxByOrNull { it.time } }
        .map { row ->
            val trendLong = if (
                row.liquid &&
                    row.trendLongRank <= config.topPerSide &&
                    row.trendScore >= config.trendEntryScore
            ) {
                buildEntryCandidate(StrategyKind.TREND, row, 1, config, calibrationState)
            } else {
                null
            }
            val trendShort = if (
                row.liquid &&
                    row.trendShortRank <= config.topPerSide &&
                    row.trendScore <= -config.trendEntryScore
            ) {
                buildEntryCandidate(StrategyKind.TREND, row, -1, config, calibrationState)
            } else {
                null
            }
            val reversionLong = if (
                row.liquid &&
                    row.reversionLongRank <= config.topPerSide &&
                    row.residualZ <= -config.reversionZEntry &&
                    row.reversionScore > 0.0
            ) {
                buildEntryCandidate(StrategyKind.REVERSION, row, 1, config, calibrationState)
            } else {
                null
            }
            val reversionShort = if (
                row.liquid &&
                    row.reversionShortRank <= config.topPerSide &&
                    row.residualZ >= config.reversionZEntry &&
                    row.reversionScore > 0.0
            ) {
                buildEntryCandidate(StrategyKind.REVERSION, row, -1, config, calibrationState)
            } else {
                null
            }
            val trendCandidate = listOfNotNull(trendLong, trendShort).maxByOrNull { it.expectedNetEdgeBps }
            val reversionCandidate = listOfNotNull(reversionLong, reversionShort).maxByOrNull { it.expectedNetEdgeBps }
            SignalSnapshot(
                exchange = row.exchange,
                symbol = row.symbol,
                time = row.time.toString(),
                lastPrice = row.close.round(4),
                betaBtc = row.betaBtc.round(4),
                betaEth = row.betaEth.round(4),
                residualZ = row.residualZ.round(4),
                trendScore = row.trendScore.round(4),
                reversionScore = row.reversionScore.round(4),
                breadth = row.breadth.round(4),
                spreadBps = row.spreadBps.round(2),
                depthUsd = row.depthUsd.round(2),
                imbalance = row.imbalance.round(4),
                flowSignal = row.flowSignal.round(4),
                volumeRatio = row.volumeRatio.round(4),
                volRegime = row.volRegime.round(4),
                trendExpectedNetEdgeBps = (trendCandidate?.expectedNetEdgeBps ?: 0.0).round(2),
                reversionExpectedNetEdgeBps = (reversionCandidate?.expectedNetEdgeBps ?: 0.0).round(2),
                trendCalibrationSamples = trendCandidate?.calibrationSamples ?: 0,
                reversionCalibrationSamples = reversionCandidate?.calibrationSamples ?: 0,
                trendCalibrationLowerBoundBps = (trendCandidate?.calibrationLowerBoundBps ?: 0.0).round(2),
                reversionCalibrationLowerBoundBps = (reversionCandidate?.calibrationLowerBoundBps ?: 0.0).round(2),
                liquid = row.liquid,
                trendAction = when (trendCandidate?.side) {
                    1 -> "LONG"
                    -1 -> "SHORT"
                    else -> "FLAT"
                },
                reversionAction = when (reversionCandidate?.side) {
                    1 -> "LONG"
                    -1 -> "SHORT"
                    else -> "FLAT"
                }
            )
        }
        .sortedWith(
            compareByDescending<SignalSnapshot> { max(it.trendExpectedNetEdgeBps, it.reversionExpectedNetEdgeBps) }
                .thenByDescending { max(it.trendCalibrationLowerBoundBps, it.reversionCalibrationLowerBoundBps) }
                .thenByDescending { abs(it.trendScore) }
                .thenBy { it.exchange }
                .thenBy { it.symbol }
        )

fun buildTradeRecord(position: OpenPosition, current: FeatureRow, config: ResearchConfig): TradeRecord {
    val kind = position.strategyKind
    val exitEstimate = buildExecutionEstimate(current, config.notionalUsd, -position.side, kind)
    val effectiveFill = min(position.entryEstimate.fillRatio, exitEstimate.fillRatio)
    val grossReturn = position.side * ((current.close / position.entryRow.close) - 1.0) * effectiveFill
    val totalCostBps = position.entryEstimate.totalCostBps + exitEstimate.totalCostBps
    val netReturn = grossReturn - (totalCostBps / 10000.0)
    val signalMagnitude = when (kind) {
        StrategyKind.TREND -> abs(position.entryRow.trendScore)
        StrategyKind.REVERSION -> abs(position.entryRow.residualZ)
    }
    val jitter = deterministicJitter(current.time, position.side)
    val decisionLatencyMs = clamp(6.0 + (signalMagnitude * 7.0) + (jitter * 0.6), 4.0, 60.0)
    val submitToAckMs = clamp(
        55.0 +
            (current.spreadBps * 1.1) +
            ((config.notionalUsd / max(current.depthUsd, config.notionalUsd)) * 120.0) +
            (jitter * 1.5),
        20.0,
        900.0
    )
    val submitToFillMs = clamp(submitToAckMs + ((1.0 - effectiveFill) * 260.0) + 18.0, 30.0, 1800.0)
    val p50RoundtripMs = clamp(submitToAckMs + 12.0, 20.0, 1000.0)
    val p95RoundtripMs = clamp(submitToAckMs * 2.0, 25.0, 1800.0)
    val p99RoundtripMs = clamp(submitToAckMs * 3.0, 30.0, 2500.0)

    return TradeRecord(
        strategyName = position.strategyName,
        strategyKind = kind.name.lowercase(),
        exchange = position.exchange,
        symbol = position.symbol,
        side = if (position.side > 0) "BUY" else "SELL",
        entryTime = position.entryRow.time,
        exitTime = current.time,
        entryPrice = position.entryRow.close,
        exitPrice = current.close,
        holdBars = current.barIndex - position.entryRow.barIndex,
        grossReturnFraction = grossReturn,
        netReturnFraction = netReturn,
        fillRatio = effectiveFill,
        feeBps = position.entryEstimate.feeBps + exitEstimate.feeBps,
        feeTier = position.entryEstimate.feeTier,
        feeTierAdjustmentBps = position.entryEstimate.feeTierAdjustmentBps + exitEstimate.feeTierAdjustmentBps,
        makerFeeBps = position.entryEstimate.makerFeeBps + exitEstimate.makerFeeBps,
        takerFeeBps = position.entryEstimate.takerFeeBps + exitEstimate.takerFeeBps,
        spreadCostBps = position.entryEstimate.spreadCostBps + exitEstimate.spreadCostBps,
        slippageBps = position.entryEstimate.slippageBps + exitEstimate.slippageBps,
        impactBps = position.entryEstimate.impactBps + exitEstimate.impactBps,
        adverseSelectionBps = position.entryEstimate.adverseSelectionBps + exitEstimate.adverseSelectionBps,
        fundingDriftBps = 0.0,
        basisDriftBps = 0.0,
        totalCostBps = totalCostBps,
        edgeAfterCostBps = netReturn * 10000.0,
        estimatedFeeUsd = position.entryEstimate.estimatedFeeUsd + exitEstimate.estimatedFeeUsd,
        estimatedCostUsd = position.entryEstimate.estimatedCostUsd + exitEstimate.estimatedCostUsd,
        entryTrendScore = position.entryRow.trendScore,
        entryResidualZ = position.entryRow.residualZ,
        expectedGrossEdgeBps = position.expectedGrossEdgeBps,
        expectedRoundTripCostBps = position.expectedRoundTripCostBps,
        expectedNetEdgeBps = position.expectedNetEdgeBps,
        calibrationSamples = position.calibrationSamples,
        calibrationWinRate = position.calibrationWinRate,
        calibrationLowerBoundBps = position.calibrationLowerBoundBps,
        calibrationScope = position.calibrationScope,
        entryImbalance = position.entryRow.imbalance,
        entryFlowSignal = position.entryRow.flowSignal,
        entryVolumeRatio = position.entryRow.volumeRatio,
        entryVolRegime = position.entryRow.volRegime,
        betaBtc = position.entryRow.betaBtc,
        betaEth = position.entryRow.betaEth,
        decisionLatencyMs = decisionLatencyMs,
        submitToAckMs = submitToAckMs,
        submitToFillMs = submitToFillMs,
        p50RoundtripMs = p50RoundtripMs,
        p95RoundtripMs = p95RoundtripMs,
        p99RoundtripMs = p99RoundtripMs,
        jitterMs = jitter
    )
}

fun simulateIndependentTrade(
    strategyName: String,
    kind: StrategyKind,
    candidate: EntryCandidate,
    series: List<FeatureRow>,
    startIndex: Int,
    config: ResearchConfig
): TradeRecord? {
    if (series.isEmpty() || startIndex !in series.indices) return null
    val position = OpenPosition(
        strategyName = strategyName,
        strategyKind = kind,
        exchange = candidate.row.exchange,
        symbol = candidate.row.symbol,
        side = candidate.side,
        entryRow = candidate.row,
        entryEstimate = candidate.entryEstimate,
        expectedGrossEdgeBps = candidate.expectedGrossEdgeBps,
        expectedRoundTripCostBps = candidate.expectedRoundTripCostBps,
        expectedNetEdgeBps = candidate.expectedNetEdgeBps,
        calibrationSamples = candidate.calibrationSamples,
        calibrationWinRate = candidate.calibrationWinRate,
        calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
        calibrationScope = candidate.calibrationScope
    )
    for (index in (startIndex + 1) until series.size) {
        val current = series[index]
        if (shouldExitPosition(kind, position, current, config)) {
            return buildTradeRecord(position, current, config)
        }
    }
    val last = series.lastOrNull() ?: return null
    return if (last.time == candidate.row.time) null else buildTradeRecord(position, last, config)
}

fun buildCalibrationExamples(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig
): List<CalibrationExample> {
    if (rows.isEmpty()) return emptyList()
    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val seriesByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.sortedBy { it.time } }
    val indexLookup = mutableMapOf<Triple<String, String, Instant>, Int>()
    seriesByExchangeSymbol.forEach { (key, series) ->
        series.forEachIndexed { index, row ->
            indexLookup[Triple(key.first, key.second, row.time)] = index
        }
    }

    val examples = mutableListOf<CalibrationExample>()
    for (key in orderedKeys) {
        val bucket = grouped[key].orEmpty()
        seedCandidateRows(kind, bucket, config).forEach { candidate ->
            val series = seriesByExchangeSymbol[candidate.row.exchange to candidate.row.symbol] ?: return@forEach
            val startIndex = indexLookup[Triple(candidate.row.exchange, candidate.row.symbol, candidate.row.time)] ?: return@forEach
            val trade = simulateIndependentTrade(strategyName, kind, candidate, series, startIndex, config) ?: return@forEach
            examples += CalibrationExample(
                key = calibrationBaseKey(kind, candidate.row, candidate.side, config),
                entryTime = candidate.row.time,
                availableAt = trade.exitTime,
                grossEdgeBps = trade.grossReturnFraction * 10000.0,
                netEdgeBps = trade.edgeAfterCostBps,
                totalCostBps = trade.totalCostBps,
                fillRatio = trade.fillRatio
            )
        }
    }
    return examples.sortedBy { it.availableAt }
}

fun simulateStrategy(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<TradeRecord> {
    if (rows.isEmpty()) return emptyList()
    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val positions = mutableMapOf<String, OpenPosition>()
    val cooldownUntilBar = mutableMapOf<String, Int>()
    val trades = mutableListOf<TradeRecord>()

    for (key in orderedKeys) {
        val exchange = key.first
        val bucket = grouped[key].orEmpty()
        val rowBySymbol = bucket.associateBy { it.symbol }

        for ((positionKey, position) in positions.toMap()) {
            if (position.exchange != exchange) continue
            val current = rowBySymbol[position.symbol] ?: continue
            if (!shouldExitPosition(kind, position, current, config)) continue
            trades += buildTradeRecord(position, current, config)
            positions.remove(positionKey)
            cooldownUntilBar[positionKey] = current.barIndex + when (kind) {
                StrategyKind.TREND -> config.trendCooldownBars
                StrategyKind.REVERSION -> config.reversionCooldownBars
            }
        }

        candidateRows(kind, bucket, config, calibrationState).forEach { candidate ->
            val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
            if (positions.containsKey(positionKey)) return@forEach
            if ((cooldownUntilBar[positionKey] ?: Int.MIN_VALUE) > candidate.row.barIndex) return@forEach
            positions[positionKey] = OpenPosition(
                strategyName = strategyName,
                strategyKind = kind,
                exchange = candidate.row.exchange,
                symbol = candidate.row.symbol,
                side = candidate.side,
                entryRow = candidate.row,
                entryEstimate = candidate.entryEstimate,
                expectedGrossEdgeBps = candidate.expectedGrossEdgeBps,
                expectedRoundTripCostBps = candidate.expectedRoundTripCostBps,
                expectedNetEdgeBps = candidate.expectedNetEdgeBps,
                calibrationSamples = candidate.calibrationSamples,
                calibrationWinRate = candidate.calibrationWinRate,
                calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
                calibrationScope = candidate.calibrationScope
            )
        }
    }

    val latestByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.maxByOrNull { it.time } }

    for ((positionKey, position) in positions.toMap()) {
        val current = latestByExchangeSymbol[position.exchange to position.symbol] ?: continue
        if (current.time == position.entryRow.time) continue
        trades += buildTradeRecord(position, current, config)
        positions.remove(positionKey)
    }

    return trades.sortedBy { it.entryTime }
}

fun simulateStrategyWalkForward(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig
): List<TradeRecord> {
    if (rows.isEmpty()) return emptyList()
    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val positions = mutableMapOf<String, OpenPosition>()
    val cooldownUntilBar = mutableMapOf<String, Int>()
    val trades = mutableListOf<TradeRecord>()
    val calibrationExamples = buildCalibrationExamples(strategyName, kind, rows, config)
    val calibrationState = CalibrationState()
    val activeExamples = ArrayDeque<CalibrationExample>()
    var exampleIndex = 0

    for (key in orderedKeys) {
        val currentTime = key.second
        while (exampleIndex < calibrationExamples.size && calibrationExamples[exampleIndex].availableAt.isBefore(currentTime)) {
            val example = calibrationExamples[exampleIndex]
            activeExamples.addLast(example)
            addCalibrationExample(calibrationState, example)
            exampleIndex += 1
        }
        val cutoff = currentTime.minus(config.calibrationLookbackHours.toLong(), ChronoUnit.HOURS)
        while (activeExamples.isNotEmpty() && activeExamples.first().availableAt.isBefore(cutoff)) {
            removeCalibrationExample(calibrationState, activeExamples.removeFirst())
        }

        val exchange = key.first
        val bucket = grouped[key].orEmpty()
        val rowBySymbol = bucket.associateBy { it.symbol }

        for ((positionKey, position) in positions.toMap()) {
            if (position.exchange != exchange) continue
            val current = rowBySymbol[position.symbol] ?: continue
            if (!shouldExitPosition(kind, position, current, config)) continue
            trades += buildTradeRecord(position, current, config)
            positions.remove(positionKey)
            cooldownUntilBar[positionKey] = current.barIndex + when (kind) {
                StrategyKind.TREND -> config.trendCooldownBars
                StrategyKind.REVERSION -> config.reversionCooldownBars
            }
        }

        candidateRows(kind, bucket, config, calibrationState).forEach { candidate ->
            val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
            if (positions.containsKey(positionKey)) return@forEach
            if ((cooldownUntilBar[positionKey] ?: Int.MIN_VALUE) > candidate.row.barIndex) return@forEach
            positions[positionKey] = OpenPosition(
                strategyName = strategyName,
                strategyKind = kind,
                exchange = candidate.row.exchange,
                symbol = candidate.row.symbol,
                side = candidate.side,
                entryRow = candidate.row,
                entryEstimate = candidate.entryEstimate,
                expectedGrossEdgeBps = candidate.expectedGrossEdgeBps,
                expectedRoundTripCostBps = candidate.expectedRoundTripCostBps,
                expectedNetEdgeBps = candidate.expectedNetEdgeBps,
                calibrationSamples = candidate.calibrationSamples,
                calibrationWinRate = candidate.calibrationWinRate,
                calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
                calibrationScope = candidate.calibrationScope
            )
        }
    }

    val latestByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.maxByOrNull { it.time } }

    for ((positionKey, position) in positions.toMap()) {
        val current = latestByExchangeSymbol[position.exchange to position.symbol] ?: continue
        if (current.time == position.entryRow.time) continue
        trades += buildTradeRecord(position, current, config)
        positions.remove(positionKey)
    }

    return trades.sortedBy { it.entryTime }
}

fun buildStrategySummaries(
    config: ResearchConfig,
    strategyName: String,
    strategyKind: StrategyKind,
    trades: List<TradeRecord>,
    timeframe: String,
    notes: String
): List<StrategySummary> {
    if (trades.isEmpty()) return emptyList()

    fun summarize(exchange: String, symbol: String, bucket: List<TradeRecord>): StrategySummary {
        val sorted = bucket.sortedBy { it.entryTime }
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        val returns = mutableListOf<Double>()
        sorted.forEach { trade ->
            equity *= (1.0 + trade.netReturnFraction)
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, 1.0 - (equity / peak))
            returns += trade.netReturnFraction
        }
        val netReturnPct = ((equity - 1.0) * 100.0).round(4)
        val winRate = sorted.count { it.netReturnFraction > 0.0 }.toDouble() / sorted.size.toDouble()
        val sharpe = run {
            val sigma = stdev(returns)
            if (sigma < 1e-9) 0.0 else (mean(returns) / sigma) * sqrt(sorted.size.toDouble())
        }
        val avgEdgeAfterCostBps = mean(sorted.map { it.edgeAfterCostBps })
        val avgTotalCostBps = mean(sorted.map { it.totalCostBps })
        val avgSlippageBps = mean(sorted.map { it.slippageBps })
        val avgFillRatio = mean(sorted.map { it.fillRatio })
        val avgSubmitToFillMs = mean(sorted.map { it.submitToFillMs })
        val avgBetaBtc = mean(sorted.map { it.betaBtc })
        val avgBetaEth = mean(sorted.map { it.betaEth })
        val avgExpectedGrossEdgeBps = mean(sorted.map { it.expectedGrossEdgeBps })
        val avgExpectedNetEdgeBps = mean(sorted.map { it.expectedNetEdgeBps })
        val edgePredictionErrorBps = avgEdgeAfterCostBps - avgExpectedNetEdgeBps
        val avgCalibrationSamples = mean(sorted.map { it.calibrationSamples.toDouble() })
        val avgCalibrationWinRate = mean(sorted.map { it.calibrationWinRate })
        val avgCalibrationLowerBoundBps = mean(sorted.map { it.calibrationLowerBoundBps })
        val dominantCalibrationScope = sorted.groupingBy { it.calibrationScope }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "heuristic"
        val avgEntryImbalance = mean(sorted.map { it.entryImbalance })
        val avgEntryFlowSignal = mean(sorted.map { it.entryFlowSignal })
        val avgEntryVolumeRatio = mean(sorted.map { it.entryVolumeRatio })
        val avgEntryVolRegime = mean(sorted.map { it.entryVolRegime })
        val metricsJson = gson.toJson(
            mapOf(
                "exchange" to exchange,
                "strategyKind" to strategyKind.name.lowercase(),
                "bar_minutes" to config.barMinutes,
                "avg_edge_after_cost_bps" to avgEdgeAfterCostBps.round(4),
                "avg_expected_gross_edge_bps" to avgExpectedGrossEdgeBps.round(4),
                "avg_expected_net_edge_bps" to avgExpectedNetEdgeBps.round(4),
                "edge_prediction_error_bps" to edgePredictionErrorBps.round(4),
                "avg_total_cost_bps" to avgTotalCostBps.round(4),
                "avg_slippage_bps" to avgSlippageBps.round(4),
                "avg_fill_ratio" to avgFillRatio.round(4),
                "avg_submit_to_fill_ms" to avgSubmitToFillMs.round(4),
                "avg_beta_btc" to avgBetaBtc.round(4),
                "avg_beta_eth" to avgBetaEth.round(4),
                "avg_calibration_samples" to avgCalibrationSamples.round(4),
                "avg_calibration_win_rate" to avgCalibrationWinRate.round(4),
                "avg_calibration_lower_bound_bps" to avgCalibrationLowerBoundBps.round(4),
                "dominant_calibration_scope" to dominantCalibrationScope,
                "avg_entry_imbalance" to avgEntryImbalance.round(4),
                "avg_entry_flow_signal" to avgEntryFlowSignal.round(4),
                "avg_entry_volume_ratio" to avgEntryVolumeRatio.round(4),
                "avg_entry_vol_regime" to avgEntryVolRegime.round(4),
                "source" to "cross-sectional-beta-kotlin"
            )
        )
        return StrategySummary(
            strategyName = strategyName,
            strategyKind = strategyKind.name.lowercase(),
            exchange = exchange,
            symbol = symbol,
            timeframe = timeframe,
            startTime = sorted.first().entryTime,
            endTime = sorted.last().exitTime,
            trades = sorted.size,
            winRate = winRate,
            netReturnPct = netReturnPct,
            maxDrawdownPct = (maxDrawdown * 100.0).round(4),
            sharpe = sharpe.round(4),
            avgEdgeAfterCostBps = avgEdgeAfterCostBps.round(4),
            avgTotalCostBps = avgTotalCostBps.round(4),
            avgSlippageBps = avgSlippageBps.round(4),
            avgFillRatio = avgFillRatio.round(4),
            avgSubmitToFillMs = avgSubmitToFillMs.round(4),
            notes = notes,
            metricsJson = metricsJson
        )
    }

    val perSymbol = trades.groupBy { it.exchange to it.symbol }
        .map { (key, bucket) -> summarize(key.first, key.second, bucket) }
    val perExchange = trades.groupBy { it.exchange }
        .map { (exchange, bucket) -> summarize(exchange, "ALL", bucket) }
    return perSymbol + perExchange
}

fun ensureAnalyticsTables(conn: Connection) {
    conn.createStatement().use { stmt ->
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_backtest_runs (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                start_time TIMESTAMPTZ NOT NULL,
                end_time TIMESTAMPTZ NOT NULL,
                trades INTEGER NOT NULL DEFAULT 0,
                win_rate DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                sharpe DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                notes TEXT,
                metrics JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_backtest_run_at ON strategy_backtest_runs (run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_backtest_symbol_time ON strategy_backtest_runs (symbol, timeframe, run_at DESC)")
        stmt.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_strategy_backtest_dedupe
            ON strategy_backtest_runs (strategy_name, symbol, timeframe, start_time, end_time)
            """.trimIndent()
        )
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_latency_metrics (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                decision_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
                submit_to_ack_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
                submit_to_fill_ms DOUBLE PRECISION,
                p50_roundtrip_ms DOUBLE PRECISION,
                p95_roundtrip_ms DOUBLE PRECISION,
                p99_roundtrip_ms DOUBLE PRECISION,
                jitter_ms DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_latency_time ON strategy_latency_metrics (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_latency_strategy_time ON strategy_latency_metrics (strategy_name, observed_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_execution_costs (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                fee_tier TEXT NOT NULL DEFAULT 'retail',
                fee_tier_adjustment_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                maker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                taker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                spread_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                slippage_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                impact_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                adverse_selection_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                funding_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                basis_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                total_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                edge_after_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                estimated_fee_usd DOUBLE PRECISION,
                estimated_cost_usd DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_time ON strategy_execution_costs (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_strategy_time ON strategy_execution_costs (strategy_name, observed_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_live_backtest_drift (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                live_edge_bps DOUBLE PRECISION,
                backtest_edge_bps DOUBLE PRECISION,
                fill_quality_delta_bps DOUBLE PRECISION,
                slippage_drift_bps DOUBLE PRECISION,
                latency_drift_ms DOUBLE PRECISION,
                drift_score DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_drift_time ON strategy_live_backtest_drift (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_drift_strategy_time ON strategy_live_backtest_drift (strategy_name, observed_at DESC)")
    }
}

fun persistBacktestSummaries(summaries: List<StrategySummary>) {
    if (summaries.isEmpty()) return
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.prepareStatement(
            """
            INSERT INTO strategy_backtest_runs (
                strategy_name, symbol, timeframe, start_time, end_time,
                trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (strategy_name, symbol, timeframe, start_time, end_time)
            DO UPDATE SET
                trades = EXCLUDED.trades,
                win_rate = EXCLUDED.win_rate,
                net_return_pct = EXCLUDED.net_return_pct,
                max_drawdown_pct = EXCLUDED.max_drawdown_pct,
                sharpe = EXCLUDED.sharpe,
                notes = EXCLUDED.notes,
                metrics = EXCLUDED.metrics,
                run_at = NOW()
            """.trimIndent()
        ).use { stmt ->
            summaries.forEach { summary ->
                stmt.setString(1, summary.strategyName)
                stmt.setString(2, summary.symbol)
                stmt.setString(3, summary.timeframe)
                stmt.setTimestamp(4, Timestamp.from(summary.startTime))
                stmt.setTimestamp(5, Timestamp.from(summary.endTime))
                stmt.setInt(6, summary.trades)
                stmt.setDouble(7, summary.winRate)
                stmt.setDouble(8, summary.netReturnPct)
                stmt.setDouble(9, summary.maxDrawdownPct)
                stmt.setDouble(10, summary.sharpe)
                stmt.setString(11, summary.notes)
                stmt.setString(12, summary.metricsJson)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }
}

fun persistForwardTelemetry(
    config: ResearchConfig,
    trades: List<TradeRecord>,
    baselines: Map<Triple<String, String, String>, StrategySummary>,
    source: String
) {
    if (trades.isEmpty()) return
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.autoCommit = false

        val grouped = trades.groupBy { Triple(it.strategyName, it.exchange, it.symbol) }
        grouped.forEach { (scope, bucket) ->
            val firstObservedAt = bucket.minOf { it.entryTime }
            val lastObservedAt = bucket.maxOf { it.entryTime }
            conn.prepareStatement(
                """
                DELETE FROM strategy_execution_costs
                WHERE strategy_name = ? AND exchange = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.second)
                stmt.setString(3, scope.third)
                stmt.setTimestamp(4, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(5, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                """
                DELETE FROM strategy_latency_metrics
                WHERE strategy_name = ? AND exchange = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.second)
                stmt.setString(3, scope.third)
                stmt.setTimestamp(4, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(5, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                """
                DELETE FROM strategy_live_backtest_drift
                WHERE strategy_name = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.third)
                stmt.setTimestamp(3, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(4, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
        }

        conn.prepareStatement(
            """
            INSERT INTO strategy_latency_metrics (
                observed_at, strategy_name, exchange, symbol,
                decision_latency_ms, submit_to_ack_ms, submit_to_fill_ms,
                p50_roundtrip_ms, p95_roundtrip_ms, p99_roundtrip_ms,
                jitter_ms, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { latencyStmt ->
            conn.prepareStatement(
                """
                INSERT INTO strategy_execution_costs (
                    observed_at, strategy_name, exchange, symbol, side,
                    fee_bps, fee_tier, fee_tier_adjustment_bps,
                    maker_fee_bps, taker_fee_bps,
                    spread_cost_bps, slippage_bps, impact_bps,
                    adverse_selection_bps, funding_drift_bps, basis_drift_bps,
                    total_cost_bps, edge_after_cost_bps,
                    estimated_fee_usd, estimated_cost_usd, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """.trimIndent()
            ).use { costStmt ->
                conn.prepareStatement(
                    """
                    INSERT INTO strategy_live_backtest_drift (
                        observed_at, strategy_name, symbol,
                        live_edge_bps, backtest_edge_bps,
                        fill_quality_delta_bps, slippage_drift_bps,
                        latency_drift_ms, drift_score, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """.trimIndent()
                ).use { driftStmt ->
                    trades.forEach { trade ->
                        val baseline = baselines[Triple(trade.strategyName, trade.exchange, trade.symbol)]
                            ?: baselines[Triple(trade.strategyName, trade.exchange, "ALL")]
                        val metadataJson = gson.toJson(
                            mapOf(
                                "source" to source,
                                "strategyKind" to trade.strategyKind,
                                "barMinutes" to config.barMinutes,
                                "fillRatio" to trade.fillRatio.round(4),
                                "betaBtc" to trade.betaBtc.round(4),
                                "betaEth" to trade.betaEth.round(4),
                                "entryTrendScore" to trade.entryTrendScore.round(4),
                                "entryResidualZ" to trade.entryResidualZ.round(4),
                                "expectedGrossEdgeBps" to trade.expectedGrossEdgeBps.round(4),
                                "expectedRoundTripCostBps" to trade.expectedRoundTripCostBps.round(4),
                                "expectedNetEdgeBps" to trade.expectedNetEdgeBps.round(4),
                                "calibrationSamples" to trade.calibrationSamples,
                                "calibrationWinRate" to trade.calibrationWinRate.round(4),
                                "calibrationLowerBoundBps" to trade.calibrationLowerBoundBps.round(4),
                                "calibrationScope" to trade.calibrationScope,
                                "entryImbalance" to trade.entryImbalance.round(4),
                                "entryFlowSignal" to trade.entryFlowSignal.round(4),
                                "entryVolumeRatio" to trade.entryVolumeRatio.round(4),
                                "entryVolRegime" to trade.entryVolRegime.round(4),
                                "executionMode" to config.paperExecutionMode
                            )
                        )

                        latencyStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        latencyStmt.setString(2, trade.strategyName)
                        latencyStmt.setString(3, trade.exchange)
                        latencyStmt.setString(4, trade.symbol)
                        latencyStmt.setDouble(5, trade.decisionLatencyMs)
                        latencyStmt.setDouble(6, trade.submitToAckMs)
                        latencyStmt.setDouble(7, trade.submitToFillMs)
                        latencyStmt.setDouble(8, trade.p50RoundtripMs)
                        latencyStmt.setDouble(9, trade.p95RoundtripMs)
                        latencyStmt.setDouble(10, trade.p99RoundtripMs)
                        latencyStmt.setDouble(11, trade.jitterMs)
                        latencyStmt.setString(12, metadataJson)
                        latencyStmt.addBatch()

                        costStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        costStmt.setString(2, trade.strategyName)
                        costStmt.setString(3, trade.exchange)
                        costStmt.setString(4, trade.symbol)
                        costStmt.setString(5, trade.side)
                        costStmt.setDouble(6, trade.feeBps)
                        costStmt.setString(7, trade.feeTier)
                        costStmt.setDouble(8, trade.feeTierAdjustmentBps)
                        costStmt.setDouble(9, trade.makerFeeBps)
                        costStmt.setDouble(10, trade.takerFeeBps)
                        costStmt.setDouble(11, trade.spreadCostBps)
                        costStmt.setDouble(12, trade.slippageBps)
                        costStmt.setDouble(13, trade.impactBps)
                        costStmt.setDouble(14, trade.adverseSelectionBps)
                        costStmt.setDouble(15, trade.fundingDriftBps)
                        costStmt.setDouble(16, trade.basisDriftBps)
                        costStmt.setDouble(17, trade.totalCostBps)
                        costStmt.setDouble(18, trade.edgeAfterCostBps)
                        costStmt.setDouble(19, trade.estimatedFeeUsd)
                        costStmt.setDouble(20, trade.estimatedCostUsd)
                        costStmt.setString(21, metadataJson)
                        costStmt.addBatch()

                        val fillQualityDelta = ((baseline?.avgFillRatio ?: trade.fillRatio) - trade.fillRatio) * 10000.0
                        val slippageDrift = trade.slippageBps - (baseline?.avgSlippageBps ?: trade.slippageBps)
                        val latencyDrift = trade.submitToFillMs - (baseline?.avgSubmitToFillMs ?: trade.submitToFillMs)
                        val edgeDecay = if (baseline == null) 0.0 else max(0.0, baseline.avgEdgeAfterCostBps - trade.edgeAfterCostBps)
                        val predictionMiss = max(0.0, trade.expectedNetEdgeBps - trade.edgeAfterCostBps)
                        val driftScore = max(0.0, fillQualityDelta) +
                            max(0.0, slippageDrift) +
                            max(0.0, latencyDrift) / 10.0 +
                            edgeDecay +
                            predictionMiss

                        driftStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        driftStmt.setString(2, trade.strategyName)
                        driftStmt.setString(3, trade.symbol)
                        driftStmt.setDouble(4, trade.edgeAfterCostBps)
                        driftStmt.setObject(5, baseline?.avgEdgeAfterCostBps)
                        driftStmt.setDouble(6, fillQualityDelta)
                        driftStmt.setDouble(7, slippageDrift)
                        driftStmt.setDouble(8, latencyDrift)
                        driftStmt.setDouble(9, driftScore)
                        driftStmt.setString(10, metadataJson)
                        driftStmt.addBatch()
                    }

                    latencyStmt.executeBatch()
                    costStmt.executeBatch()
                    driftStmt.executeBatch()
                }
            }
        }

        conn.commit()
    }
}

fun fetchUserProfile(txBase: String, token: String): JsonObject? =
    runCatching {
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/user"))
            .header("Authorization", "Bearer $token")
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("User profile returned status ${response.statusCode()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("User profile unavailable: ${ex.message}")
        null
    }

fun requestBestQuote(txBase: String, symbol: String, side: String, exchanges: List<String>, executionMode: String): JsonObject? =
    runCatching {
        val query = listOf(
            "symbol=${urlEncode(symbol)}",
            "side=${urlEncode(side)}",
            "exchanges=${urlEncode(exchanges.joinToString(","))}",
            "executionMode=${urlEncode(executionMode)}"
        ).joinToString("&")
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/best-quote?$query"))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Best quote returned status ${response.statusCode()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("Best quote unavailable for $symbol/$side: ${ex.message}")
        null
    }

fun submitPaperOrder(txBase: String, token: String, exchange: String, symbol: String, side: String, size: Double, executionMode: String): JsonObject? =
    runCatching {
        val payload = gson.toJson(
            mapOf(
                "symbol" to symbol,
                "side" to side.uppercase(),
                "type" to "MARKET",
                "size" to size.toString(),
                "executionMode" to executionMode,
                "reduceOnly" to false,
                "postOnly" to false,
                "urgencyClass" to "normal"
            )
        )
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/$exchange/order"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .timeout(Duration.ofSeconds(20))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Paper order returned status ${response.statusCode()}: ${response.body()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("Paper order failed for $exchange/$symbol: ${ex.message}")
        null
    }

fun paperTradeTopSignals(
    latestSignals: List<SignalSnapshot>,
    catalog: List<ExchangeCatalogSnapshot>,
    config: ResearchConfig
): List<Map<String, Any?>> {
    val integrated = catalog
        .filter { it.capabilities.paperOrder && (it.implementationStatus == "INTEGRATED" || it.capabilities.bestQuoteDefault) }
        .map { it.apiName.lowercase() }
        .distinct()
        .ifEmpty { listOf("hyperliquid") }

    val profile = if (config.txAuthToken.isBlank()) null else fetchUserProfile(config.txGatewayUrl, config.txAuthToken)
    val allowedModes = profile?.array("allowedTradingModes")?.map { it.asString.lowercase() }?.toSet() ?: emptySet()
    val allowedExchanges = profile?.array("allowedExchanges")?.map { it.asString.lowercase() }?.toSet()

    val candidates = latestSignals
        .mapNotNull { signal ->
            val trendNet = signal.trendExpectedNetEdgeBps
            val reversionNet = signal.reversionExpectedNetEdgeBps
            val preferredAction = when {
                signal.trendAction != "FLAT" &&
                    (signal.reversionAction == "FLAT" || trendNet >= reversionNet) -> signal.trendAction
                signal.reversionAction != "FLAT" -> signal.reversionAction
                else -> null
            } ?: return@mapNotNull null
            val strategyFamily = when {
                signal.trendAction != "FLAT" &&
                    (signal.reversionAction == "FLAT" || trendNet >= reversionNet) -> "trend"
                signal.reversionAction != "FLAT" -> "reversion"
                else -> "flat"
            }
            mapOf(
                "exchange" to signal.exchange,
                "symbol" to signal.symbol,
                "action" to preferredAction,
                "strategyFamily" to strategyFamily,
                "expectedNetEdgeBps" to max(trendNet, reversionNet),
                "trendScore" to signal.trendScore,
                "residualZ" to signal.residualZ,
                "price" to signal.lastPrice
            )
        }
        .sortedByDescending { it["expectedNetEdgeBps"] as Double }
        .take(2)

    if (candidates.isEmpty()) {
        println("No paper-trade candidates qualified.")
        return emptyList()
    }

    if (config.txAuthToken.isBlank()) {
        println("Set TX_AUTH_TOKEN to enable paper order submission. Returning execution plans only.")
    } else if (config.paperExecutionMode.lowercase() !in allowedModes) {
        println("Account is not provisioned for ${config.paperExecutionMode}; allowed=$allowedModes")
        return emptyList()
    }

    return candidates.map { candidate ->
        val symbol = candidate["symbol"] as String
        val action = candidate["action"] as String
        val side = if (action == "LONG") "buy" else "sell"
        val executionExchanges = integrated.filter { exchange ->
            allowedExchanges == null || exchange in allowedExchanges
        }
        val bestQuote = requestBestQuote(
            txBase = config.txGatewayUrl,
            symbol = symbol,
            side = side,
            exchanges = executionExchanges.ifEmpty { integrated },
            executionMode = config.paperExecutionMode
        )
        val selectedExchange = bestQuote?.string("selectedExchange") ?: candidate["exchange"] as String
        val normalizedSymbol = bestQuote?.string("normalizedSymbol") ?: symbol
        val quote = bestQuote?.obj("quote")
        val lastPrice = quote?.double("last") ?: quote?.double("ask") ?: quote?.double("bid") ?: (candidate["price"] as Double)
        val size = max(config.notionalUsd / max(lastPrice, 1.0), 0.001).round(6)
        val order = if (config.enablePaperOrders && config.txAuthToken.isNotBlank()) {
            submitPaperOrder(
                txBase = config.txGatewayUrl,
                token = config.txAuthToken,
                exchange = selectedExchange,
                symbol = normalizedSymbol,
                side = side,
                size = size,
                executionMode = config.paperExecutionMode
            )
        } else {
            null
        }
        mapOf(
            "symbol" to normalizedSymbol,
            "requestedAction" to action,
            "strategyFamily" to candidate["strategyFamily"],
            "expectedNetEdgeBps" to candidate["expectedNetEdgeBps"],
            "selectedExchange" to selectedExchange,
            "executionMode" to config.paperExecutionMode,
            "size" to size,
            "quoteBid" to (quote?.double("bid")?.round(4)),
            "quoteAsk" to (quote?.double("ask")?.round(4)),
            "quoteLast" to lastPrice.round(4),
            "orderId" to order?.string("orderId"),
            "status" to order?.string("status"),
            "simulated" to order?.bool("simulated")
        )
    }
}

data class ResearchSeedSnapshot(
    val time: String,
    val symbol: String,
    val side: Int,
    val trendScore: Double? = null,
    val reversionScore: Double? = null,
    val flowSignal: Double,
    val residualZ: Double,
    val volumeRatio: Double,
    val expectedNetEdgeBps: Double,
    val expectedRoundTripCostBps: Double
)

data class ResearchDiagnostics(
    val barMinutes: Int,
    val warmupFloorBars: Int,
    val totalRows: Int,
    val liquidRows: Int,
    val rowsPerSymbol: Map<String, Int>,
    val liquidPerSymbol: Map<String, Int>,
    val liquidFailureCounts: Map<String, Int>,
    val rankEligibleCounts: Map<String, Int>,
    val seedCounts: Map<String, Int>,
    val topTrendSeeds: List<ResearchSeedSnapshot>,
    val topReversionSeeds: List<ResearchSeedSnapshot>
)

data class CrossSectionalResearchResult(
    val config: ResearchConfig,
    val exchangeCatalog: List<ExchangeCatalogSnapshot>,
    val exchangePlans: List<ExchangePlan>,
    val discoveredUniverse: Map<String, List<String>>,
    val barsLoaded: Int,
    val featureRows: Int,
    val diagnostics: ResearchDiagnostics,
    val heuristicSignals: List<SignalSnapshot>,
    val latestSignals: List<SignalSnapshot>,
    val backtestSummaries: List<StrategySummary>,
    val forwardSummaries: List<StrategySummary>,
    val forwardCutoff: Instant?,
    val calibrationRows: Int,
    val forwardRows: Int,
    val calibrationExampleCounts: Map<String, Int>
)

fun computeResearchDiagnostics(
    rows: List<FeatureRow>,
    config: ResearchConfig
): ResearchDiagnostics {
    val groupedFeatureBuckets = rows.groupBy { it.exchange to it.time }
    val warmupFloor = max(config.betaLookbackBars, config.trendSlowBars)
    val liquidRows = rows.filter { it.liquid }

    val liquidFailureCounts = mapOf(
        "warmup" to rows.count { it.barIndex < warmupFloor },
        "spread" to rows.count { it.spreadBps > config.maxSpreadBps },
        "depth" to rows.count { it.depthUsd < config.notionalUsd * config.minDepthMultiple },
        "volume" to rows.count { it.volume <= 0.0 },
        "volumeRatioFloor" to rows.count { it.volumeRatio < config.minVolumeRatio },
        "volumeRatioCap" to rows.count { it.volumeRatio > config.maxVolumeRatio },
        "volRegime" to rows.count { it.volRegime > config.maxVolRegime },
        "baseSymbols" to rows.count { it.symbol in setOf("BTC", "ETH") }
    )

    val rankEligibleCounts = mapOf(
        "trendLong" to rows.count {
            it.liquid &&
                it.trendLongRank <= config.topPerSide &&
                it.trendScore >= config.trendEntryScore &&
                abs(it.residualZ) <= config.reversionZEntry * 1.5
        },
        "trendShort" to rows.count {
            it.liquid &&
                it.trendShortRank <= config.topPerSide &&
                it.trendScore <= -config.trendEntryScore &&
                abs(it.residualZ) <= config.reversionZEntry * 1.5
        },
        "reversionLong" to rows.count {
            it.liquid &&
                it.reversionLongRank <= config.topPerSide &&
                it.residualZ <= -config.reversionZEntry &&
                it.reversionScore > 0.0
        },
        "reversionShort" to rows.count {
            it.liquid &&
                it.reversionShortRank <= config.topPerSide &&
                it.residualZ >= config.reversionZEntry &&
                it.reversionScore > 0.0
        }
    )

    val trendSeeds = groupedFeatureBuckets.values.flatMap { seedCandidateRows(StrategyKind.TREND, it, config) }
    val reversionSeeds = groupedFeatureBuckets.values.flatMap { seedCandidateRows(StrategyKind.REVERSION, it, config) }

    return ResearchDiagnostics(
        barMinutes = config.barMinutes,
        warmupFloorBars = warmupFloor,
        totalRows = rows.size,
        liquidRows = liquidRows.size,
        rowsPerSymbol = rows.groupBy { it.symbol }.mapValues { (_, bucket) -> bucket.size },
        liquidPerSymbol = rows.groupBy { it.symbol }.mapValues { (_, bucket) -> bucket.count { it.liquid } },
        liquidFailureCounts = liquidFailureCounts,
        rankEligibleCounts = rankEligibleCounts,
        seedCounts = mapOf(
            "trend" to trendSeeds.size,
            "reversion" to reversionSeeds.size
        ),
        topTrendSeeds = trendSeeds.sortedByDescending { it.expectedNetEdgeBps }.take(8).map {
            ResearchSeedSnapshot(
                time = it.row.time.toString(),
                symbol = it.row.symbol,
                side = it.side,
                trendScore = it.row.trendScore.round(4),
                flowSignal = it.row.flowSignal.round(4),
                residualZ = it.row.residualZ.round(4),
                volumeRatio = it.row.volumeRatio.round(4),
                expectedNetEdgeBps = it.expectedNetEdgeBps.round(4),
                expectedRoundTripCostBps = it.expectedRoundTripCostBps.round(4)
            )
        },
        topReversionSeeds = reversionSeeds.sortedByDescending { it.expectedNetEdgeBps }.take(8).map {
            ResearchSeedSnapshot(
                time = it.row.time.toString(),
                symbol = it.row.symbol,
                side = it.side,
                reversionScore = it.row.reversionScore.round(4),
                flowSignal = it.row.flowSignal.round(4),
                residualZ = it.row.residualZ.round(4),
                volumeRatio = it.row.volumeRatio.round(4),
                expectedNetEdgeBps = it.expectedNetEdgeBps.round(4),
                expectedRoundTripCostBps = it.expectedRoundTripCostBps.round(4)
            )
        }
    )
}

fun runCrossSectionalResearch(config: ResearchConfig = ResearchConfig()): CrossSectionalResearchResult {
    val exchangeCatalog = fetchExchangeCatalog(config.txGatewayUrl)
    val exchangePlans = buildExchangePlans(exchangeCatalog, config)

    val discoveredUniverse = exchangePlans.associate { plan ->
        plan.exchange to discoverSymbols(
            aliases = plan.marketAliases,
            lookbackHours = config.lookbackHours,
            maxSymbols = config.maxSymbols,
            minBars = config.minBars
        )
    }

    val researchBars = exchangePlans.flatMap { plan ->
        val symbols = discoveredUniverse[plan.exchange].orEmpty()
        loadBars(
            exchange = plan.exchange,
            aliases = plan.marketAliases,
            symbols = symbols,
            lookbackHours = config.lookbackHours,
            barMinutes = config.barMinutes
        )
    }

    val researchFeatureRows = engineerFeatures(researchBars, config)
    val diagnostics = computeResearchDiagnostics(researchFeatureRows, config)
    val heuristicSignals = latestSignalSnapshots(researchFeatureRows, config)

    val trendStrategyName = "cross_section_beta_trend_v1"
    val reversionStrategyName = "cross_section_beta_reversion_v1"

    val trendTrades = simulateStrategyWalkForward(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = researchFeatureRows,
        config = config
    )
    val reversionTrades = simulateStrategyWalkForward(
        strategyName = reversionStrategyName,
        kind = StrategyKind.REVERSION,
        rows = researchFeatureRows,
        config = config
    )

    val backtestSummaries =
        buildStrategySummaries(
            config = config,
            strategyName = trendStrategyName,
            strategyKind = StrategyKind.TREND,
            trades = trendTrades,
            timeframe = "candle_${config.barMinutes}m",
            notes = "${config.barMinutes}m beta-adjusted cross-sectional trend with causal calibration gating"
        ) +
        buildStrategySummaries(
            config = config,
            strategyName = reversionStrategyName,
            strategyKind = StrategyKind.REVERSION,
            trades = reversionTrades,
            timeframe = "candle_${config.barMinutes}m",
            notes = "${config.barMinutes}m beta-adjusted cross-sectional mean reversion with causal calibration gating"
        )

    if (config.persistBacktest && backtestSummaries.isNotEmpty()) {
        persistBacktestSummaries(backtestSummaries)
    }

    val forwardCutoff = researchFeatureRows.maxOfOrNull { it.time }
        ?.minus(config.forwardHours.toLong(), ChronoUnit.HOURS)

    var latestSignals = heuristicSignals
    var forwardSummaries = emptyList<StrategySummary>()
    var calibrationRowsCount = 0
    var forwardRowsCount = 0
    var calibrationCounts = emptyMap<String, Int>()

    if (forwardCutoff != null) {
        val calibrationRows = researchFeatureRows.filter { it.time.isBefore(forwardCutoff) }
        val forwardRows = researchFeatureRows.filter { !it.time.isBefore(forwardCutoff) }
        calibrationRowsCount = calibrationRows.size
        forwardRowsCount = forwardRows.size

        val calibrationTrendExamples = buildCalibrationExamples(
            strategyName = trendStrategyName,
            kind = StrategyKind.TREND,
            rows = calibrationRows,
            config = config
        )
        val calibrationReversionExamples = buildCalibrationExamples(
            strategyName = reversionStrategyName,
            kind = StrategyKind.REVERSION,
            rows = calibrationRows,
            config = config
        )
        calibrationCounts = mapOf(
            "trend" to calibrationTrendExamples.size,
            "reversion" to calibrationReversionExamples.size
        )
        val forwardCalibrationState = buildCalibrationState(calibrationTrendExamples + calibrationReversionExamples)

        val calibrationTrendTrades = simulateStrategyWalkForward(
            strategyName = trendStrategyName,
            kind = StrategyKind.TREND,
            rows = calibrationRows,
            config = config
        )
        val calibrationReversionTrades = simulateStrategyWalkForward(
            strategyName = reversionStrategyName,
            kind = StrategyKind.REVERSION,
            rows = calibrationRows,
            config = config
        )

        val calibrationSummaries =
            buildStrategySummaries(
                config = config,
                strategyName = trendStrategyName,
                strategyKind = StrategyKind.TREND,
                trades = calibrationTrendTrades,
                timeframe = "candle_${config.barMinutes}m",
                notes = "${config.barMinutes}m calibration slice with causal calibration gating"
            ) +
            buildStrategySummaries(
                config = config,
                strategyName = reversionStrategyName,
                strategyKind = StrategyKind.REVERSION,
                trades = calibrationReversionTrades,
                timeframe = "candle_${config.barMinutes}m",
                notes = "${config.barMinutes}m calibration slice with causal calibration gating"
            )

        val baselineMap = calibrationSummaries.associateBy { Triple(it.strategyName, it.exchange, it.symbol) }

        val forwardTrendTrades = simulateStrategy(
            strategyName = trendStrategyName,
            kind = StrategyKind.TREND,
            rows = forwardRows,
            config = config,
            calibrationState = forwardCalibrationState
        )
        val forwardReversionTrades = simulateStrategy(
            strategyName = reversionStrategyName,
            kind = StrategyKind.REVERSION,
            rows = forwardRows,
            config = config,
            calibrationState = forwardCalibrationState
        )

        val forwardTrades = forwardTrendTrades + forwardReversionTrades
        forwardSummaries =
            buildStrategySummaries(
                config = config,
                strategyName = trendStrategyName,
                strategyKind = StrategyKind.TREND,
                trades = forwardTrendTrades,
                timeframe = "forward_${config.barMinutes}m",
                notes = "forward ${config.barMinutes}m slice with calibrated promotion gating"
            ) +
            buildStrategySummaries(
                config = config,
                strategyName = reversionStrategyName,
                strategyKind = StrategyKind.REVERSION,
                trades = forwardReversionTrades,
                timeframe = "forward_${config.barMinutes}m",
                notes = "forward ${config.barMinutes}m slice with calibrated promotion gating"
            )

        latestSignals = latestSignalSnapshots(researchFeatureRows, config, forwardCalibrationState)

        if (config.persistForward && forwardTrades.isNotEmpty()) {
            persistForwardTelemetry(
                config = config,
                trades = forwardTrades,
                baselines = baselineMap,
                source = "alpha-analytics-service"
            )
        }
    }

    return CrossSectionalResearchResult(
        config = config,
        exchangeCatalog = exchangeCatalog,
        exchangePlans = exchangePlans,
        discoveredUniverse = discoveredUniverse,
        barsLoaded = researchBars.size,
        featureRows = researchFeatureRows.size,
        diagnostics = diagnostics,
        heuristicSignals = heuristicSignals,
        latestSignals = latestSignals,
        backtestSummaries = backtestSummaries,
        forwardSummaries = forwardSummaries,
        forwardCutoff = forwardCutoff,
        calibrationRows = calibrationRowsCount,
        forwardRows = forwardRowsCount,
        calibrationExampleCounts = calibrationCounts
    )
}
