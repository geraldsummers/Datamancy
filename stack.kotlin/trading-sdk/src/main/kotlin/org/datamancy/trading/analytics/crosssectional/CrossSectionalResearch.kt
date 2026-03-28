package org.datamancy.trading.analytics.crosssectional

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.CoverageContractPolicy
import org.datamancy.trading.policy.TradingPolicy
import org.datamancy.trading.policy.UniversePolicy
import org.datamancy.trading.policy.UniverseSelectionMode
import org.datamancy.trading.policy.VenuePolicy
import org.datamancy.trading.storage.verifyCanonicalMarketDataDatabase
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
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.ceil
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

private const val CANONICAL_RESEARCH_FEATURE_BAR_SECONDS = 60L
private const val DEFAULT_RESEARCH_QUERY_PARALLELISM = 4

private fun crossSectionalPolicy() = ActiveTradingPolicy.current().research.crossSectional

private fun crossSectionalSearchPolicy() = crossSectionalPolicy().search

internal data class ResearchWindowBounds(
    val startInclusive: Instant,
    val endExclusive: Instant,
    val bucketSeconds: Int
)

internal fun alignedResearchWindowBounds(
    lookbackHours: Int,
    barMinutes: Int,
    lagMinutes: Long = 0L,
    now: Instant = Instant.now()
): ResearchWindowBounds {
    val normalizedBarMinutes = max(barMinutes, 1)
    val bucketSeconds = normalizedBarMinutes * 60
    val effectiveNow = now.minus(max(lagMinutes, 0L), ChronoUnit.MINUTES)
    val endEpochSecond = (effectiveNow.epochSecond / bucketSeconds.toLong()) * bucketSeconds.toLong()
    val endExclusive = Instant.ofEpochSecond(endEpochSecond)
    return ResearchWindowBounds(
        startInclusive = endExclusive.minus(max(lookbackHours, 1).toLong(), ChronoUnit.HOURS),
        endExclusive = endExclusive,
        bucketSeconds = bucketSeconds
    )
}

internal fun barCloseLagSeconds(
    bucketStartTime: Instant?,
    referenceTime: Instant = Instant.now(),
    bucketSeconds: Long = CANONICAL_RESEARCH_FEATURE_BAR_SECONDS
): Long =
    bucketStartTime
        ?.plusSeconds(max(bucketSeconds, 1L))
        ?.let { bucketClose ->
            Duration.between(bucketClose, referenceTime).seconds.coerceAtLeast(0L)
        }
        ?: Long.MAX_VALUE

internal fun barCloseLagMinutes(
    bucketStartTime: Instant?,
    referenceTime: Instant = Instant.now(),
    bucketSeconds: Long = CANONICAL_RESEARCH_FEATURE_BAR_SECONDS
): Long? =
    bucketStartTime
        ?.plusSeconds(max(bucketSeconds, 1L))
        ?.let { bucketClose ->
            Duration.between(bucketClose, referenceTime).toMinutes().coerceAtLeast(0L)
        }

internal fun effectiveCoverageMaxFeatureLagSeconds(
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int
): Long =
    max(
        coveragePolicy.maxFeatureLagSeconds,
        coveragePolicy.maxFeatureLagSeconds + (max(barMinutes, 1).toLong() - 1L).coerceAtLeast(0L) * 60L
    )

internal fun effectiveCoverageMaxExecutionLagSeconds(
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int
): Long = effectiveCoverageMaxFeatureLagSeconds(coveragePolicy, barMinutes)

internal fun effectiveCoverageMaxFinalizedLagMinutes(
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int
): Long =
    max(
        coveragePolicy.maxFinalizedLagMinutes,
        coveragePolicy.maxFinalizedLagMinutes + (max(barMinutes, 1).toLong() - 1L).coerceAtLeast(0L)
    )

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

internal fun resolveResearchQueryParallelism(
    workItems: Int,
    configuredMax: Int = envInt("ALPHA_RESEARCH_QUERY_PARALLELISM", DEFAULT_RESEARCH_QUERY_PARALLELISM)
): Int =
    workItems.coerceAtLeast(1).coerceAtMost(configuredMax.coerceAtLeast(1))

internal fun <T, R> parallelMapBlocking(
    items: List<T>,
    maxParallelism: Int,
    block: (T) -> R
): List<R> {
    if (items.isEmpty()) return emptyList()
    val parallelism = maxParallelism.coerceIn(1, items.size)
    if (parallelism == 1) {
        return items.map(block)
    }

    return runBlocking {
        val semaphore = Semaphore(parallelism)
        coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        block(item)
                    }
                }
            }.awaitAll()
        }
    }
}

fun mean(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else values.average()

fun percentile(values: List<Double>, quantile: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val position = clamp(quantile, 0.0, 1.0) * (sorted.lastIndex.toDouble())
    val lower = position.toInt()
    val upper = ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    val weight = position - lower.toDouble()
    return (sorted[lower] * (1.0 - weight)) + (sorted[upper] * weight)
}

fun median(values: List<Double>): Double = percentile(values, 0.5)

fun medianAbsoluteDeviation(values: List<Double>, center: Double = median(values)): Double {
    if (values.isEmpty()) return 0.0
    return median(values.map { abs(it - center) })
}

fun robustZScore(value: Double, values: List<Double>, fallbackScale: Double = 1.0): Double {
    if (values.isEmpty()) return 0.0
    val center = median(values)
    val mad = medianAbsoluteDeviation(values, center)
    val scale = max(mad * 1.4826, fallbackScale)
    return clamp((value - center) / scale, -6.0, 6.0)
}

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

data class CandleSource(
    val intervalLabel: String,
    val minutes: Int
)

data class ExchangeMarketSnapshot(
    val symbol: String,
    val attributes: Map<String, String> = emptyMap()
)

data class SymbolLiquiditySnapshot(
    val symbol: String,
    val bars: Int,
    val avgVolume: Double
)

internal data class ResearchUniverseCandidate(
    val exchange: String,
    val symbol: String,
    val totalBars: Int,
    val recentBars: Int,
    val recentObservedBars: Int,
    val recentTradableBars: Int,
    val recentTradableRatio: Double,
    val recentObservedRatio: Double,
    val avgRecentDepthUsd: Double,
    val avgRecentVolumeUsd: Double,
    val avgRecentSpreadBps: Double
)

data class UniverseLiquidityBucketSnapshot(
    val label: String,
    val symbols: Int,
    val avgSpreadBps: Double,
    val avgDepthUsd: Double,
    val avgVolumeUsd: Double,
    val avgTradableRatio: Double
)

data class UniverseProfileSnapshot(
    val exchange: String,
    val candidateSymbols: Int,
    val selectedSymbols: Int,
    val benchmarkSymbols: Int,
    val candidateAvgRecentTradableRatio: Double,
    val selectedAvgRecentTradableRatio: Double,
    val candidateAvgRecentObservedRatio: Double,
    val selectedAvgRecentObservedRatio: Double,
    val candidateAvgRecentSpreadBps: Double,
    val selectedAvgRecentSpreadBps: Double,
    val candidateMedianRecentSpreadBps: Double,
    val selectedMedianRecentSpreadBps: Double,
    val candidateAvgRecentDepthUsd: Double,
    val selectedAvgRecentDepthUsd: Double,
    val candidateAvgRecentVolumeUsd: Double,
    val selectedAvgRecentVolumeUsd: Double,
    val candidateObservedExecutionShare: Double,
    val selectedObservedExecutionShare: Double,
    val candidateTradableExecutionShare: Double,
    val selectedTradableExecutionShare: Double,
    val liquidityBuckets: List<UniverseLiquidityBucketSnapshot>,
    val selectedUniverse: List<String>,
    val topCandidates: List<String>
)

data class PortfolioConstraintSnapshot(
    val candidateEntries: Int,
    val acceptedEntries: Int,
    val rejectedOpenSymbol: Int,
    val rejectedGrossLimit: Int,
    val rejectedLongLimit: Int,
    val rejectedShortLimit: Int,
    val rejectedNetLimit: Int,
    val rejectedBetaLimit: Int
)

data class PortfolioProfileSnapshot(
    val strategyKind: String,
    val stage: String,
    val exchanges: List<String>,
    val trades: Int,
    val policyMaxConcurrentPositions: Int,
    val policyMaxConcurrentLongs: Int,
    val policyMaxConcurrentShorts: Int,
    val policyMaxNetExposureFraction: Double,
    val policyMaxAbsBetaBtc: Double,
    val policyMaxAbsBetaEth: Double,
    val maxConcurrentPositions: Int,
    val maxConcurrentLongs: Int,
    val maxConcurrentShorts: Int,
    val avgConcurrentPositions: Double,
    val avgConcurrentLongs: Double,
    val avgConcurrentShorts: Double,
    val maxGrossExposureUsd: Double,
    val avgGrossExposureUsd: Double,
    val maxNetExposureUsd: Double,
    val avgNetExposureUsd: Double,
    val maxAbsNetExposureFraction: Double,
    val avgAbsNetExposureFraction: Double,
    val maxAbsBetaBtc: Double,
    val avgAbsBetaBtc: Double,
    val maxAbsBetaEth: Double,
    val avgAbsBetaEth: Double,
    val avgCapacityUtilization: Double,
    val maxCapacityUtilization: Double,
    val entryConstraints: PortfolioConstraintSnapshot
)

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
    val marketExchange: String = crossSectionalPolicy().marketExchange,
    val executionExchangeOverride: String = crossSectionalPolicy().executionExchange,
    val barMinutes: Int = crossSectionalPolicy().barMinutes,
    val lookbackHours: Int = crossSectionalPolicy().lookbackHours,
    val forwardHours: Int = crossSectionalPolicy().forwardHours,
    val betaLookbackBars: Int = crossSectionalPolicy().betaLookbackBars,
    val trendLookbackBars: Int = crossSectionalPolicy().trendLookbackBars,
    val trendSlowBars: Int = crossSectionalPolicy().trendSlowBars,
    val trendMediumBars: Int = crossSectionalPolicy().trendMediumBars,
    val trendLongBars: Int = crossSectionalPolicy().trendLongBars,
    val reversionLookbackBars: Int = crossSectionalPolicy().reversionLookbackBars,
    val trendHoldBars: Int = crossSectionalPolicy().trendHoldBars,
    val reversionHoldBars: Int = crossSectionalPolicy().reversionHoldBars,
    val topPerSide: Int = crossSectionalPolicy().topPerSide,
    val notionalUsd: Double = crossSectionalPolicy().notionalUsd,
    val maxSymbols: Int = crossSectionalPolicy().maxSymbols,
    val discoveryMaxSymbols: Int = crossSectionalPolicy().discoveryMaxSymbols,
    val minBars: Int = crossSectionalPolicy().minBars,
    val trendEntryScore: Double = crossSectionalPolicy().trendEntryScore,
    val reversionZEntry: Double = crossSectionalPolicy().reversionZEntry,
    val reversionZExit: Double = crossSectionalPolicy().reversionZExit,
    val reversionEntryQuantile: Double = crossSectionalPolicy().reversionEntryQuantile,
    val reversionExitQuantile: Double = crossSectionalPolicy().reversionExitQuantile,
    val reversionCrossSectionalWeight: Double = crossSectionalPolicy().reversionCrossSectionalWeight,
    val maxSpreadBps: Double = crossSectionalPolicy().maxSpreadBps,
    val minDepthMultiple: Double = crossSectionalPolicy().minDepthMultiple,
    val minFillRatio: Double = crossSectionalPolicy().minFillRatio,
    val minVolumeRatio: Double = crossSectionalPolicy().minVolumeRatio,
    val maxVolumeRatio: Double = crossSectionalPolicy().maxVolumeRatio,
    val maxVolRegime: Double = crossSectionalPolicy().maxVolRegime,
    val executionSafetyMarginBps: Double = crossSectionalPolicy().executionSafetyMarginBps,
    val minExpectedNetEdgeBps: Double = crossSectionalPolicy().minExpectedNetEdgeBps,
    val trendMinFlowAlignment: Double = crossSectionalPolicy().trendMinFlowAlignment,
    val reversionMaxContinuationPressure: Double = crossSectionalPolicy().reversionMaxContinuationPressure,
    val calibrationLookbackHours: Int = crossSectionalPolicy().calibrationLookbackHours,
    val minCalibrationSamples: Int = crossSectionalPolicy().minCalibrationSamples,
    val strongCalibrationSamples: Int = crossSectionalPolicy().strongCalibrationSamples,
    val minCalibrationLowerBoundBps: Double = crossSectionalPolicy().minCalibrationLowerBoundBps,
    val minCalibrationWinRate: Double = crossSectionalPolicy().minCalibrationWinRate,
    val trendCooldownBars: Int = crossSectionalPolicy().trendCooldownBars,
    val reversionCooldownBars: Int = crossSectionalPolicy().reversionCooldownBars,
    val trendTrailingStopVolMultiple: Double = crossSectionalPolicy().trendTrailingStopVolMultiple,
    val reversionTrailingStopVolMultiple: Double = crossSectionalPolicy().reversionTrailingStopVolMultiple,
    val trendTakeProfitVolMultiple: Double = crossSectionalPolicy().trendTakeProfitVolMultiple,
    val reversionTakeProfitVolMultiple: Double = crossSectionalPolicy().reversionTakeProfitVolMultiple,
    val minTargetExposureFraction: Double = crossSectionalPolicy().minTargetExposureFraction,
    val maxTargetExposureFraction: Double = crossSectionalPolicy().maxTargetExposureFraction,
    val rebalanceTargetExposureStep: Double = crossSectionalPolicy().rebalanceTargetExposureStep,
    val maxConcurrentPositions: Int = crossSectionalPolicy().maxConcurrentPositions,
    val maxConcurrentLongs: Int = crossSectionalPolicy().maxConcurrentLongs,
    val maxConcurrentShorts: Int = crossSectionalPolicy().maxConcurrentShorts,
    val maxNetExposureFraction: Double = crossSectionalPolicy().maxNetExposureFraction,
    val maxPortfolioBetaBtcAbs: Double = crossSectionalPolicy().maxPortfolioBetaBtcAbs,
    val maxPortfolioBetaEthAbs: Double = crossSectionalPolicy().maxPortfolioBetaEthAbs,
    val persistBacktest: Boolean = crossSectionalPolicy().persistBacktest,
    val persistForward: Boolean = crossSectionalPolicy().persistForward,
    val enablePaperOrders: Boolean = crossSectionalPolicy().enablePaperOrders,
    val paperExecutionMode: String = crossSectionalPolicy().paperExecutionMode
)

@Serializable
data class CrossSectionalSearchConfig(
    val baseConfig: ResearchConfig = ResearchConfig(
        persistBacktest = false,
        persistForward = false,
        enablePaperOrders = false
    ),
    val beamWidth: Int = crossSectionalSearchPolicy().beamWidth,
    val rounds: Int = crossSectionalSearchPolicy().rounds,
    val maxEvaluations: Int = crossSectionalSearchPolicy().maxEvaluations,
    val leaderboardSize: Int = crossSectionalSearchPolicy().leaderboardSize,
    val minBacktestTrades: Int = crossSectionalSearchPolicy().minBacktestTrades,
    val minForwardTrades: Int = crossSectionalSearchPolicy().minForwardTrades,
    val minSearchFillRatio: Double = crossSectionalSearchPolicy().minSearchFillRatio,
    val maxSearchDrawdownPct: Double = crossSectionalSearchPolicy().maxSearchDrawdownPct,
    val barMinutes: List<Int> = crossSectionalSearchPolicy().barMinutes,
    val lookbackHours: List<Int> = crossSectionalSearchPolicy().lookbackHours,
    val forwardHours: List<Int> = crossSectionalSearchPolicy().forwardHours,
    val betaLookbackBars: List<Int> = crossSectionalSearchPolicy().betaLookbackBars,
    val trendLookbackBars: List<Int> = crossSectionalSearchPolicy().trendLookbackBars,
    val trendSlowBars: List<Int> = crossSectionalSearchPolicy().trendSlowBars,
    val trendMediumBars: List<Int> = crossSectionalSearchPolicy().trendMediumBars,
    val trendLongBars: List<Int> = crossSectionalSearchPolicy().trendLongBars,
    val reversionLookbackBars: List<Int> = crossSectionalSearchPolicy().reversionLookbackBars,
    val trendHoldBars: List<Int> = crossSectionalSearchPolicy().trendHoldBars,
    val reversionHoldBars: List<Int> = crossSectionalSearchPolicy().reversionHoldBars,
    val topPerSide: List<Int> = crossSectionalSearchPolicy().topPerSide,
    val maxSymbols: List<Int> = crossSectionalSearchPolicy().maxSymbols,
    val discoveryMaxSymbols: List<Int> = crossSectionalSearchPolicy().discoveryMaxSymbols,
    val trendEntryScore: List<Double> = crossSectionalSearchPolicy().trendEntryScore,
    val reversionZEntry: List<Double> = crossSectionalSearchPolicy().reversionZEntry,
    val reversionZExit: List<Double> = crossSectionalSearchPolicy().reversionZExit,
    val reversionEntryQuantile: List<Double> = crossSectionalSearchPolicy().reversionEntryQuantile,
    val reversionExitQuantile: List<Double> = crossSectionalSearchPolicy().reversionExitQuantile,
    val reversionCrossSectionalWeight: List<Double> = crossSectionalSearchPolicy().reversionCrossSectionalWeight,
    val maxSpreadBps: List<Double> = crossSectionalSearchPolicy().maxSpreadBps,
    val minDepthMultiple: List<Double> = crossSectionalSearchPolicy().minDepthMultiple,
    val minFillRatio: List<Double> = crossSectionalSearchPolicy().minFillRatio,
    val minVolumeRatio: List<Double> = crossSectionalSearchPolicy().minVolumeRatio,
    val maxVolumeRatio: List<Double> = crossSectionalSearchPolicy().maxVolumeRatio,
    val maxVolRegime: List<Double> = crossSectionalSearchPolicy().maxVolRegime,
    val executionSafetyMarginBps: List<Double> = crossSectionalSearchPolicy().executionSafetyMarginBps,
    val minExpectedNetEdgeBps: List<Double> = crossSectionalSearchPolicy().minExpectedNetEdgeBps,
    val trendMinFlowAlignment: List<Double> = crossSectionalSearchPolicy().trendMinFlowAlignment,
    val reversionMaxContinuationPressure: List<Double> = crossSectionalSearchPolicy().reversionMaxContinuationPressure,
    val calibrationLookbackHours: List<Int> = crossSectionalSearchPolicy().calibrationLookbackHours,
    val minCalibrationSamples: List<Int> = crossSectionalSearchPolicy().minCalibrationSamples,
    val strongCalibrationSamples: List<Int> = crossSectionalSearchPolicy().strongCalibrationSamples,
    val minCalibrationLowerBoundBps: List<Double> = crossSectionalSearchPolicy().minCalibrationLowerBoundBps,
    val minCalibrationWinRate: List<Double> = crossSectionalSearchPolicy().minCalibrationWinRate,
    val trendCooldownBars: List<Int> = crossSectionalSearchPolicy().trendCooldownBars,
    val reversionCooldownBars: List<Int> = crossSectionalSearchPolicy().reversionCooldownBars,
    val trendTrailingStopVolMultiple: List<Double> = crossSectionalSearchPolicy().trendTrailingStopVolMultiple,
    val reversionTrailingStopVolMultiple: List<Double> = crossSectionalSearchPolicy().reversionTrailingStopVolMultiple,
    val trendTakeProfitVolMultiple: List<Double> = crossSectionalSearchPolicy().trendTakeProfitVolMultiple,
    val reversionTakeProfitVolMultiple: List<Double> = crossSectionalSearchPolicy().reversionTakeProfitVolMultiple,
    val minTargetExposureFraction: List<Double> = crossSectionalSearchPolicy().minTargetExposureFraction,
    val maxTargetExposureFraction: List<Double> = crossSectionalSearchPolicy().maxTargetExposureFraction,
    val rebalanceTargetExposureStep: List<Double> = crossSectionalSearchPolicy().rebalanceTargetExposureStep,
    val maxConcurrentPositions: List<Int> = crossSectionalSearchPolicy().maxConcurrentPositions,
    val maxConcurrentLongs: List<Int> = crossSectionalSearchPolicy().maxConcurrentLongs,
    val maxConcurrentShorts: List<Int> = crossSectionalSearchPolicy().maxConcurrentShorts,
    val maxNetExposureFraction: List<Double> = crossSectionalSearchPolicy().maxNetExposureFraction,
    val maxPortfolioBetaBtcAbs: List<Double> = crossSectionalSearchPolicy().maxPortfolioBetaBtcAbs,
    val maxPortfolioBetaEthAbs: List<Double> = crossSectionalSearchPolicy().maxPortfolioBetaEthAbs
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
    val midPrice: Double,
    val executionObserved: Boolean = true,
    val finalized: Boolean = true
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
    val vol30: Double,
    val executionObserved: Boolean = true
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
    val residualMomMedium: Double,
    val residualMomLong: Double,
    val residualZ: Double,
    val residualCrossSectionalZ: Double,
    val reversionState: Double,
    val reversionEntryLowerBound: Double,
    val reversionEntryUpperBound: Double,
    val reversionExitLowerBound: Double,
    val reversionExitUpperBound: Double,
    val imbalance: Double,
    val volumeRatio: Double,
    val depthRatio: Double,
    val volRegime: Double,
    val flowSignal: Double,
    val mediumTrendScore: Double,
    val trendConfirmationScore: Double,
    val trendPersistence: Double,
    val trendPullback: Double,
    val trendExhaustion: Double,
    val rawTrend: Double,
    val trendExpectedGrossEdgeBps: Double,
    val reversionExpectedGrossEdgeBps: Double,
    val liquid: Boolean,
    val executionObserved: Boolean = true
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
    val residualMomMedium: Double,
    val residualMomLong: Double,
    val residualZ: Double,
    val residualCrossSectionalZ: Double,
    val reversionState: Double,
    val reversionEntryLowerBound: Double,
    val reversionEntryUpperBound: Double,
    val reversionExitLowerBound: Double,
    val reversionExitUpperBound: Double,
    val imbalance: Double,
    val volumeRatio: Double,
    val depthRatio: Double,
    val volRegime: Double,
    val flowSignal: Double,
    val breadth: Double,
    val mediumTrendScore: Double,
    val trendConfirmationScore: Double,
    val trendPersistence: Double,
    val trendPullback: Double,
    val trendExhaustion: Double,
    val rawTrend: Double,
    val trendScore: Double,
    val reversionScore: Double,
    val trendExpectedGrossEdgeBps: Double,
    val reversionExpectedGrossEdgeBps: Double,
    val trendTargetExposureFraction: Double,
    val reversionTargetExposureFraction: Double,
    val liquid: Boolean,
    val trendLongRank: Int,
    val trendShortRank: Int,
    val reversionLongRank: Int,
    val reversionShortRank: Int,
    val executionObserved: Boolean = true
)

data class SignalSnapshot(
    val exchange: String,
    val symbol: String,
    val time: String,
    val lastPrice: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val residualZ: Double,
    val residualCrossSectionalZ: Double,
    val reversionState: Double,
    val reversionEntryLowerBound: Double,
    val reversionEntryUpperBound: Double,
    val reversionExitLowerBound: Double,
    val reversionExitUpperBound: Double,
    val mediumTrendScore: Double,
    val trendConfirmationScore: Double,
    val trendPersistence: Double,
    val trendPullback: Double,
    val trendExhaustion: Double,
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
    val trendTargetExposureFraction: Double,
    val reversionTargetExposureFraction: Double,
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

private data class ExecutionProxyProfile(
    val spreadBps: Double,
    val depthToVolumeRatio: Double,
    val depthFloorUsd: Double
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
    val targetExposureFraction: Double,
    val entryNotionalUsd: Double,
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
    val targetExposureFraction: Double,
    val calibrationSamples: Int,
    val calibrationWinRate: Double,
    val calibrationLowerBoundBps: Double,
    val calibrationScope: String,
    val maxFavorableReturnFraction: Double = 0.0
)

private data class PortfolioConstraintCounters(
    var candidateEntries: Int = 0,
    var acceptedEntries: Int = 0,
    var rejectedOpenSymbol: Int = 0,
    var rejectedGrossLimit: Int = 0,
    var rejectedLongLimit: Int = 0,
    var rejectedShortLimit: Int = 0,
    var rejectedNetLimit: Int = 0,
    var rejectedBetaLimit: Int = 0
)

private data class PortfolioTelemetryPoint(
    val grossPositions: Int,
    val longPositions: Int,
    val shortPositions: Int,
    val grossExposureUnits: Double,
    val longExposureUnits: Double,
    val shortExposureUnits: Double,
    val netExposureUnits: Double,
    val betaBtcUnits: Double,
    val betaEthUnits: Double
)

private data class StrategySimulationResult(
    val trades: List<TradeRecord>,
    val portfolioProfile: PortfolioProfileSnapshot
)

data class EntryCandidate(
    val row: FeatureRow,
    val side: Int,
    val entryEstimate: ExecutionEstimate,
    val expectedGrossEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val expectedNetEdgeBps: Double,
    val targetExposureFraction: Double,
    val signalConfidence: Double,
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
val pgHost = env("POSTGRES_HOST", "market-postgres")
val pgPort = env("POSTGRES_PORT", "5432")
val pgDb = env("POSTGRES_DB", "datamancy")
val pgUser = env("POSTGRES_USER", "pipeline_user")
val pgPassword = env("POSTGRES_PASSWORD", "")
val jdbcUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDb"

fun pgConnection(): Connection =
    DriverManager.getConnection(jdbcUrl, pgUser, pgPassword)
        .also { connection ->
            verifyCanonicalMarketDataDatabase(
                connection = connection,
                verificationKey = "trading-sdk:$jdbcUrl:$pgUser",
                descriptor = "trading-sdk market-data connection $jdbcUrl as $pgUser"
            )
        }

inline fun <T> timedMillis(block: () -> T): Pair<T, Long> {
    val started = System.nanoTime()
    val result = block()
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    return result to elapsedMs
}

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

fun fetchExchangeMarkets(txBase: String, exchange: String): List<ExchangeMarketSnapshot> =
    runCatching {
        val request = HttpRequest.newBuilder(
            URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/${urlEncode(exchange)}/markets")
        )
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Exchange markets returned status ${response.statusCode()}")
        }
        val payload = JsonParser.parseString(response.body()).asJsonObject
        payload.array("markets")
            ?.mapNotNull { element ->
                val obj = element.asJsonObject
                val symbol = obj.string("symbol")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val attributes = obj.obj("attributes")
                    ?.entrySet()
                    ?.asSequence()
                    ?.filter { (_, value) -> !value.isJsonNull }
                    ?.associate { (key, value) -> key to value.toMarketAttributeValue() }
                    ?.filterValues { it.isNotEmpty() }
                    ?: emptyMap()
                ExchangeMarketSnapshot(symbol = symbol, attributes = attributes)
            }
            ?.distinctBy { it.symbol }
            ?: emptyList()
    }.getOrElse { ex ->
        println("Exchange market catalog unavailable for $exchange: ${ex.message}")
        emptyList()
    }

private fun com.google.gson.JsonElement.toMarketAttributeValue(): String =
    when {
        isJsonNull -> ""
        isJsonPrimitive && asJsonPrimitive.isString -> asString.trim()
        else -> toString()
    }

private fun symbolKey(symbol: String): String = symbol.trim().uppercase()

fun ExchangeMarketSnapshot.isDelisted(): Boolean {
    val raw = attributes["isDelisted"] ?: attributes["delisted"] ?: return false
    return raw.equals("true", ignoreCase = true)
}

fun TradingPolicy.findVenueForExchange(exchange: String, aliases: Collection<String> = emptyList()): VenuePolicy? {
    val keys = buildSet {
        add(exchange.trim().lowercase())
        aliases.mapTo(this) { it.trim().lowercase() }
    }.filter { it.isNotEmpty() }.toSet()
    return venues.values.firstOrNull { venue ->
        venue.venueId.trim().lowercase() in keys || venue.exchangeId.trim().lowercase() in keys
    }
}

fun filterSymbolsByUniversePolicy(
    symbols: Collection<String>,
    universe: UniversePolicy
): List<String> {
    val includeSymbols = universe.includeSymbols.map(::symbolKey).toSet()
    val excludeSymbols = universe.excludeSymbols.map(::symbolKey).toSet()
    return symbols
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter { includeSymbols.isEmpty() || symbolKey(it) in includeSymbols }
        .filterNot { symbolKey(it) in excludeSymbols }
        .distinct()
        .sortedWith(compareBy<String> { symbolKey(it) }.thenBy { it })
        .toList()
}

fun filterExchangeMarketsByUniversePolicy(
    markets: Collection<ExchangeMarketSnapshot>,
    universe: UniversePolicy
): List<ExchangeMarketSnapshot> {
    val includeSymbols = universe.includeSymbols.map(::symbolKey).toSet()
    val excludeSymbols = universe.excludeSymbols.map(::symbolKey).toSet()
    return markets
        .asSequence()
        .filter { universe.includeDelisted || !it.isDelisted() }
        .filter { includeSymbols.isEmpty() || symbolKey(it.symbol) in includeSymbols }
        .filterNot { symbolKey(it.symbol) in excludeSymbols }
        .distinctBy { symbolKey(it.symbol) }
        .sortedWith(compareBy<ExchangeMarketSnapshot> { symbolKey(it.symbol) }.thenBy { it.symbol })
        .toList()
}

fun resolveAuthoritativeMarketSymbols(
    txBase: String,
    exchange: String,
    aliases: Collection<String> = emptyList(),
    policy: TradingPolicy = ActiveTradingPolicy.current(),
    fetchMarkets: (String, String) -> List<ExchangeMarketSnapshot> = ::fetchExchangeMarkets
): List<String> {
    val venue = policy.findVenueForExchange(exchange, aliases)
        ?: error("No trading policy venue configured for exchange=$exchange aliases=${aliases.joinToString(",")}")
    return when (venue.universe.selectionMode) {
        UniverseSelectionMode.STATIC -> {
            val symbols = filterSymbolsByUniversePolicy(venue.universe.staticSymbols, venue.universe)
            require(symbols.isNotEmpty()) {
                "Static universe policy resolved no symbols for venue=${venue.venueId} exchange=${venue.exchangeId}"
            }
            symbols
        }

        UniverseSelectionMode.EXCHANGE_CATALOG -> {
            val markets = filterExchangeMarketsByUniversePolicy(
                markets = fetchMarkets(txBase, venue.venueId),
                universe = venue.universe
            )
            require(markets.isNotEmpty()) {
                "Exchange catalog resolved no markets for venue=${venue.venueId} exchange=${venue.exchangeId}"
            }
            markets.map { it.symbol }
        }
    }
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

fun selectResearchCandleSource(barMinutes: Int): CandleSource =
    max(barMinutes, 1).let { minutes ->
        val intervalLabel = if (minutes % 60 == 0) {
            "${minutes / 60}h"
        } else {
            "${minutes}m"
        }
        CandleSource(intervalLabel = intervalLabel, minutes = minutes)
    }

fun scaleRequiredSourceBars(minBars: Int, sourceMinutes: Int, targetBarMinutes: Int): Int {
    val normalizedSourceMinutes = max(sourceMinutes, 1)
    val normalizedTargetMinutes = max(targetBarMinutes, normalizedSourceMinutes)
    val coverageMinutes = minBars.toDouble() * normalizedTargetMinutes.toDouble()
    return max(1, ceil(coverageMinutes / normalizedSourceMinutes.toDouble()).toInt())
}

internal fun requiredResearchWindowBars(lookbackHours: Int, barMinutes: Int, minBars: Int): Int {
    val lookbackBars = ceil((lookbackHours.toDouble() * 60.0) / max(barMinutes, 1).toDouble()).toInt()
    return max(minBars, lookbackBars)
}

private data class TimedCacheEntry<T>(
    val loadedAt: Instant,
    val value: T
)

private class TimedLruCache<K, V>(private val maxEntries: Int) {
    private val entries = object : LinkedHashMap<K, TimedCacheEntry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, TimedCacheEntry<V>>?): Boolean =
            size > maxEntries
    }

    fun get(key: K, ttl: Duration, now: Instant = Instant.now()): V? = synchronized(this) {
        val entry = entries[key] ?: return null
        if (Duration.between(entry.loadedAt, now) > ttl) {
            entries.remove(key)
            return null
        }
        entry.value
    }

    fun put(key: K, value: V, now: Instant = Instant.now()) = synchronized(this) {
        entries[key] = TimedCacheEntry(loadedAt = now, value = value)
    }
}

private val researchFeatureQueryCacheTtl: Duration =
    Duration.ofSeconds(crossSectionalPolicy().featureCache.ttlSeconds.toLong())
private val researchFeatureLiquidityCache = TimedLruCache<String, List<SymbolLiquiditySnapshot>>(
    crossSectionalPolicy().featureCache.maxEntries
)
private val researchFeatureBarCache = TimedLruCache<String, List<Bar>>(
    crossSectionalPolicy().featureCache.maxEntries
)

private fun featureCacheKey(
    prefix: String,
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int
): String = buildString {
    append(prefix)
    append('|')
    append(aliases.sorted().joinToString(","))
    append('|')
    append(symbols.sorted().joinToString(","))
    append('|')
    append(lookbackHours)
    append('|')
    append(barMinutes)
}

private fun queryDiscoveredSymbolLiquidityFromFeatures(
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    source: CandleSource,
    scaledMinBars: Int
): List<SymbolLiquiditySnapshot> {
    if (symbols.isEmpty()) return emptyList()
    loadUniverseSnapshotLiquidity(
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = source.minutes,
        minBars = scaledMinBars
    ).takeIf { it.isNotEmpty() }?.let { return it }

    val cacheKey = featureCacheKey(
        prefix = "feature-liquidity",
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = source.minutes
    )
    researchFeatureLiquidityCache.get(cacheKey, researchFeatureQueryCacheTtl)?.let { return it }

    val aliasSql = sqlList(aliases)
    val symbolSql = sqlList(symbols)
    val preferredAlias = aliases.firstOrNull().orEmpty()
    val window = alignedResearchWindowBounds(lookbackHours = lookbackHours, barMinutes = source.minutes)
    val bucketSeconds = window.bucketSeconds
    val sql = """
        WITH minute_rows AS (
            SELECT DISTINCT ON (symbol, time)
                symbol,
                time,
                COALESCE(volume, 0) AS volume
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= ?
              AND time < ?
              AND symbol IN ($symbolSql)
            ORDER BY
                symbol,
                time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END
        ),
        bucketed AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                volume
            FROM minute_rows
        ),
        aggregated AS (
            SELECT
                symbol,
                bucket_time,
                SUM(volume) AS bucket_volume
            FROM bucketed
            GROUP BY symbol, bucket_time
        )
        SELECT
            symbol,
            COUNT(*) AS bars,
            COALESCE(AVG(bucket_volume), 0) AS avg_volume
        FROM aggregated
        GROUP BY symbol
        HAVING COUNT(*) >= $scaledMinBars
        ORDER BY bars DESC, avg_volume DESC, symbol ASC
    """.trimIndent()

    val result = runCatching {
        buildList {
            pgConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(window.startInclusive))
                    stmt.setTimestamp(2, Timestamp.from(window.endExclusive))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            add(
                                SymbolLiquiditySnapshot(
                                    symbol = rs.getString("symbol"),
                                    bars = rs.getInt("bars"),
                                    avgVolume = rs.getDouble("avg_volume")
                                )
                            )
                        }
                    }
                }
            }
        }
    }.getOrElse { emptyList() }

    if (result.isNotEmpty()) {
        researchFeatureLiquidityCache.put(cacheKey, result)
    }
    return result
}

private fun discoverSymbolsByAggregateFromFeatures(
    aliases: List<String>,
    lookbackHours: Int,
    maxSymbols: Int,
    minBars: Int,
    barMinutes: Int
): List<String> {
    val source = selectResearchCandleSource(barMinutes)
    val scaledMinBars = scaleRequiredSourceBars(minBars, source.minutes, barMinutes)
    loadUniverseSnapshotLiquidity(
        aliases = aliases,
        symbols = emptyList(),
        lookbackHours = lookbackHours,
        barMinutes = source.minutes,
        minBars = scaledMinBars,
        maxSymbols = maxSymbols
    ).takeIf { it.isNotEmpty() }?.let { ranked ->
        return ranked.map { it.symbol }
    }

    val aliasSql = sqlList(aliases)
    val preferredAlias = aliases.firstOrNull().orEmpty()
    val window = alignedResearchWindowBounds(lookbackHours = lookbackHours, barMinutes = source.minutes)
    val bucketSeconds = window.bucketSeconds
    val sql = """
        WITH minute_rows AS (
            SELECT DISTINCT ON (symbol, time)
                symbol,
                time,
                COALESCE(volume, 0) AS volume
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= ?
              AND time < ?
            ORDER BY
                symbol,
                time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END
        ),
        bucketed AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                volume
            FROM minute_rows
        ),
        aggregated AS (
            SELECT
                symbol,
                bucket_time,
                SUM(volume) AS bucket_volume
            FROM bucketed
            GROUP BY symbol, bucket_time
        )
        SELECT
            symbol,
            COUNT(*) AS bars,
            COALESCE(AVG(bucket_volume), 0) AS avg_volume
        FROM aggregated
        GROUP BY symbol
        HAVING COUNT(*) >= $scaledMinBars
        ORDER BY bars DESC, avg_volume DESC, symbol ASC
        ${if (maxSymbols > 0) "LIMIT $maxSymbols" else ""}
    """.trimIndent()

    return runCatching {
        buildList {
            pgConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(window.startInclusive))
                    stmt.setTimestamp(2, Timestamp.from(window.endExclusive))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            add(rs.getString("symbol"))
                        }
                    }
                }
            }
        }
    }.getOrElse { emptyList() }
}

fun queryDiscoveredSymbolLiquidity(
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    source: CandleSource,
    scaledMinBars: Int
): List<SymbolLiquiditySnapshot> =
    queryDiscoveredSymbolLiquidityFromFeatures(
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        source = source,
        scaledMinBars = scaledMinBars
    )
        .sortedWith(
            compareByDescending<SymbolLiquiditySnapshot> { it.bars }
                .thenByDescending { it.avgVolume }
                .thenBy { it.symbol }
        )

internal fun rankDiscoveredSymbolLiquidityBatches(
    batches: List<List<String>>,
    maxParallelism: Int,
    query: (List<String>) -> List<SymbolLiquiditySnapshot>
): List<SymbolLiquiditySnapshot> =
    parallelMapBlocking(
        items = batches,
        maxParallelism = maxParallelism,
        block = query
    )
        .flatten()
        .distinctBy { it.symbol }
        .sortedWith(
            compareByDescending<SymbolLiquiditySnapshot> { it.bars }
                .thenByDescending { it.avgVolume }
                .thenBy { it.symbol }
        )

fun discoverSymbolsFromMarketCatalog(
    aliases: List<String>,
    candidateSymbols: List<String>,
    lookbackHours: Int,
    maxSymbols: Int,
    minBars: Int,
    barMinutes: Int
): List<String> {
    val normalizedSymbols = candidateSymbols
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .distinct()
    if (normalizedSymbols.isEmpty()) return emptyList()

    val source = selectResearchCandleSource(barMinutes)
    val scaledMinBars = scaleRequiredSourceBars(minBars, source.minutes, barMinutes)
    loadUniverseSnapshot(
        aliases = aliases,
        lookbackHours = lookbackHours,
        barMinutes = source.minutes
    )?.let { snapshot ->
        val ranked = rankUniverseSnapshotLiquidity(
            snapshot = snapshot,
            symbols = normalizedSymbols,
            lookbackHours = lookbackHours,
            barMinutes = source.minutes,
            minBars = scaledMinBars
        ).map { it.symbol }
        if (ranked.isNotEmpty()) {
            return if (maxSymbols > 0) ranked.take(maxSymbols) else ranked
        }
    }

    val batchSize = if (maxSymbols > 0) {
        min(64, max(16, maxSymbols * 4))
    } else {
        64
    }
    val batches = normalizedSymbols.chunked(batchSize)
    val ranked = rankDiscoveredSymbolLiquidityBatches(
        batches = batches,
        maxParallelism = resolveResearchQueryParallelism(batches.size)
    ) { batch ->
        queryDiscoveredSymbolLiquidity(
            aliases = aliases,
            symbols = batch,
            lookbackHours = lookbackHours,
            source = source,
            scaledMinBars = scaledMinBars
        )
    }
        .map { it.symbol }

    return if (ranked.isNotEmpty()) {
        if (maxSymbols > 0) ranked.take(maxSymbols) else ranked
    } else {
        if (maxSymbols > 0) normalizedSymbols.take(maxSymbols) else normalizedSymbols
    }
}

fun discoverSymbolsByAggregate(
    aliases: List<String>,
    lookbackHours: Int,
    maxSymbols: Int,
    minBars: Int,
    barMinutes: Int
): List<String> =
    discoverSymbolsByAggregateFromFeatures(
        aliases = aliases,
        lookbackHours = lookbackHours,
        maxSymbols = maxSymbols,
        minBars = minBars,
        barMinutes = barMinutes
    )

fun discoverSymbols(
    txBase: String,
    exchange: String,
    aliases: List<String>,
    lookbackHours: Int,
    maxSymbols: Int,
    minBars: Int,
    barMinutes: Int
): List<String> {
    val requiredBars = requiredResearchWindowBars(
        lookbackHours = lookbackHours,
        barMinutes = barMinutes,
        minBars = minBars
    )
    val markets = resolveAuthoritativeMarketSymbols(
        txBase = txBase,
        exchange = exchange,
        aliases = aliases
    )
    val discovered = if (markets.isNotEmpty()) {
        val ranked = discoverSymbolsFromMarketCatalog(
            aliases = aliases,
            candidateSymbols = markets,
            lookbackHours = lookbackHours,
            maxSymbols = maxSymbols,
            minBars = requiredBars,
            barMinutes = barMinutes
        )
        println(
            "Cross-sectional universe discovery exchange=$exchange source=market_catalog " +
                "markets=${markets.size} discovered=${ranked.size}"
        )
        ranked
    } else {
        val ranked = discoverSymbolsByAggregate(
            aliases = aliases,
            lookbackHours = lookbackHours,
            maxSymbols = maxSymbols,
            minBars = requiredBars,
            barMinutes = barMinutes
        )
        println(
            "Cross-sectional universe discovery exchange=$exchange source=market_data_aggregate " +
                "discovered=${ranked.size}"
        )
        ranked
    }

    return discovered
}

internal fun discoveryCandidateLimit(maxSymbols: Int, discoveryMaxSymbols: Int): Int =
    when {
        discoveryMaxSymbols > 0 -> max(discoveryMaxSymbols, maxSymbols)
        else -> 0
    }

private fun universeSelectionWindowHours(config: ResearchConfig): Int =
    max(max(config.forwardHours * 2, 72), max((config.barMinutes * 12) / 60, 24))

internal fun rankResearchUniverseCandidates(
    bars: List<Bar>,
    config: ResearchConfig
): List<ResearchUniverseCandidate> {
    if (bars.isEmpty()) return emptyList()

    val latestTime = bars.maxOfOrNull { it.time } ?: return emptyList()
    val recentCutoff = latestTime.minus(universeSelectionWindowHours(config).toLong(), ChronoUnit.HOURS)
    val benchmarkSymbols = setOf("BTC", "ETH")
    val grouped = bars.groupBy { it.exchange to it.symbol }

    return grouped.mapNotNull { (key, series) ->
        val exchange = key.first
        val symbol = key.second
        val ordered = series.sortedBy { it.time }
        val recent = ordered.filter { !it.time.isBefore(recentCutoff) }
        val observedRecent = recent.filter { it.executionObserved && observedSpreadBps(it) > 0.0 && observedDepthUsd(it) > 0.0 }
        val tradableRecent = observedRecent.filter {
            observedSpreadBps(it) <= config.maxSpreadBps &&
                observedDepthUsd(it) >= config.notionalUsd * config.minDepthMultiple
        }
        val recentBars = recent.size
        val recentObservedBars = observedRecent.size
        val recentTradableBars = tradableRecent.size
        if (symbol !in benchmarkSymbols && ordered.isEmpty()) return@mapNotNull null

        ResearchUniverseCandidate(
            exchange = exchange,
            symbol = symbol,
            totalBars = ordered.size,
            recentBars = recentBars,
            recentObservedBars = recentObservedBars,
            recentTradableBars = recentTradableBars,
            recentTradableRatio = recentTradableBars.toDouble() / max(recentBars, 1).toDouble(),
            recentObservedRatio = recentObservedBars.toDouble() / max(recentBars, 1).toDouble(),
            avgRecentDepthUsd = if (observedRecent.isEmpty()) 0.0 else mean(observedRecent.map(::observedDepthUsd)),
            avgRecentVolumeUsd = if (recent.isEmpty()) 0.0 else mean(recent.map(::observedVolumeUsd)),
            avgRecentSpreadBps = if (observedRecent.isEmpty()) Double.POSITIVE_INFINITY else mean(observedRecent.map(::observedSpreadBps))
        )
    }
}

internal fun selectResearchUniverseFromCandidates(
    candidates: List<ResearchUniverseCandidate>,
    config: ResearchConfig
): Map<String, List<String>> {
    if (candidates.isEmpty()) return emptyMap()
    val benchmarkSymbols = setOf("BTC", "ETH")
    val requiredHistoryBars = max(
        config.minBars,
        maxOf(config.betaLookbackBars, config.trendSlowBars, config.reversionLookbackBars) + 1
    )

    return candidates.groupBy { it.exchange }
        .mapValues { (_, candidates) ->
            val benchmarks = candidates
                .filter { it.symbol in benchmarkSymbols }
                .sortedBy { it.symbol }
                .map { it.symbol }
            val selected = candidates
                .filter { it.symbol !in benchmarkSymbols && it.totalBars >= requiredHistoryBars }
                .sortedWith(
                    compareByDescending<ResearchUniverseCandidate> { it.recentTradableBars }
                        .thenByDescending { it.recentTradableRatio }
                        .thenByDescending { it.recentObservedBars }
                        .thenByDescending { it.recentObservedRatio }
                        .thenByDescending { it.avgRecentDepthUsd }
                        .thenByDescending { it.avgRecentVolumeUsd }
                        .thenBy { it.avgRecentSpreadBps }
                        .thenByDescending { it.totalBars }
                        .thenBy { it.symbol }
                )
                .let { ranked ->
                    if (config.maxSymbols > 0) ranked.take(config.maxSymbols) else ranked
                }
                .map { it.symbol }

            (benchmarks + selected).distinct()
        }
}

fun selectResearchUniverseFromBars(
    bars: List<Bar>,
    config: ResearchConfig
): Map<String, List<String>> =
    selectResearchUniverseFromCandidates(rankResearchUniverseCandidates(bars, config), config)

private fun universeLiquidityBucket(candidate: ResearchUniverseCandidate, config: ResearchConfig): String =
    when {
        candidate.recentTradableRatio >= 0.75 &&
            candidate.avgRecentDepthUsd >= (config.notionalUsd * config.minDepthMultiple * 6.0) &&
            candidate.avgRecentSpreadBps <= (config.maxSpreadBps * 0.55) -> "deep"
        candidate.recentTradableRatio >= 0.60 &&
            candidate.avgRecentDepthUsd >= (config.notionalUsd * config.minDepthMultiple * 3.0) &&
            candidate.avgRecentSpreadBps <= (config.maxSpreadBps * 0.8) -> "core"
        candidate.recentTradableRatio >= 0.35 &&
            candidate.avgRecentDepthUsd >= (config.notionalUsd * config.minDepthMultiple) -> "tradable"
        else -> "fragile"
    }

internal fun buildUniverseProfiles(
    candidates: List<ResearchUniverseCandidate>,
    selectedUniverse: Map<String, List<String>>,
    config: ResearchConfig
): List<UniverseProfileSnapshot> {
    if (candidates.isEmpty()) return emptyList()

    fun sanitized(value: Double): Double =
        if (value.isFinite()) value else max(config.maxSpreadBps * 4.0, 0.0)

    fun avg(candidates: List<ResearchUniverseCandidate>, selector: (ResearchUniverseCandidate) -> Double): Double =
        mean(candidates.map { sanitized(selector(it)) })

    fun median(candidates: List<ResearchUniverseCandidate>, selector: (ResearchUniverseCandidate) -> Double): Double =
        percentile(candidates.map { sanitized(selector(it)) }, 0.5)

    fun share(sumNumerator: Int, sumDenominator: Int): Double =
        if (sumDenominator <= 0) 0.0 else sumNumerator.toDouble() / sumDenominator.toDouble()

    return candidates.groupBy { it.exchange }
        .map { (exchange, exchangeCandidates) ->
            val selectedSymbols = selectedUniverse[exchange].orEmpty().toSet()
            val selectedCandidates = exchangeCandidates.filter { it.symbol in selectedSymbols }
            val orderedCandidates = exchangeCandidates.sortedWith(
                compareByDescending<ResearchUniverseCandidate> { it.recentTradableBars }
                    .thenByDescending { it.recentTradableRatio }
                    .thenByDescending { it.avgRecentDepthUsd }
                    .thenByDescending { it.avgRecentVolumeUsd }
                    .thenBy { it.avgRecentSpreadBps }
                    .thenBy { it.symbol }
            )
            val liquidityBuckets = orderedCandidates.groupBy { universeLiquidityBucket(it, config) }
                .map { (label, bucket) ->
                    UniverseLiquidityBucketSnapshot(
                        label = label,
                        symbols = bucket.size,
                        avgSpreadBps = avg(bucket) { it.avgRecentSpreadBps }.round(4),
                        avgDepthUsd = avg(bucket) { it.avgRecentDepthUsd }.round(4),
                        avgVolumeUsd = avg(bucket) { it.avgRecentVolumeUsd }.round(4),
                        avgTradableRatio = avg(bucket) { it.recentTradableRatio }.round(4)
                    )
                }
                .sortedBy { listOf("deep", "core", "tradable", "fragile").indexOf(it.label).let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } }

            UniverseProfileSnapshot(
                exchange = exchange,
                candidateSymbols = orderedCandidates.size,
                selectedSymbols = selectedCandidates.size,
                benchmarkSymbols = orderedCandidates.count { it.symbol in setOf("BTC", "ETH") },
                candidateAvgRecentTradableRatio = avg(orderedCandidates) { it.recentTradableRatio }.round(4),
                selectedAvgRecentTradableRatio = avg(selectedCandidates) { it.recentTradableRatio }.round(4),
                candidateAvgRecentObservedRatio = avg(orderedCandidates) { it.recentObservedRatio }.round(4),
                selectedAvgRecentObservedRatio = avg(selectedCandidates) { it.recentObservedRatio }.round(4),
                candidateAvgRecentSpreadBps = avg(orderedCandidates) { it.avgRecentSpreadBps }.round(4),
                selectedAvgRecentSpreadBps = avg(selectedCandidates) { it.avgRecentSpreadBps }.round(4),
                candidateMedianRecentSpreadBps = median(orderedCandidates) { it.avgRecentSpreadBps }.round(4),
                selectedMedianRecentSpreadBps = median(selectedCandidates) { it.avgRecentSpreadBps }.round(4),
                candidateAvgRecentDepthUsd = avg(orderedCandidates) { it.avgRecentDepthUsd }.round(4),
                selectedAvgRecentDepthUsd = avg(selectedCandidates) { it.avgRecentDepthUsd }.round(4),
                candidateAvgRecentVolumeUsd = avg(orderedCandidates) { it.avgRecentVolumeUsd }.round(4),
                selectedAvgRecentVolumeUsd = avg(selectedCandidates) { it.avgRecentVolumeUsd }.round(4),
                candidateObservedExecutionShare = share(
                    orderedCandidates.sumOf { it.recentObservedBars },
                    orderedCandidates.sumOf { it.recentBars }
                ).round(4),
                selectedObservedExecutionShare = share(
                    selectedCandidates.sumOf { it.recentObservedBars },
                    selectedCandidates.sumOf { it.recentBars }
                ).round(4),
                candidateTradableExecutionShare = share(
                    orderedCandidates.sumOf { it.recentTradableBars },
                    orderedCandidates.sumOf { it.recentBars }
                ).round(4),
                selectedTradableExecutionShare = share(
                    selectedCandidates.sumOf { it.recentTradableBars },
                    selectedCandidates.sumOf { it.recentBars }
                ).round(4),
                liquidityBuckets = liquidityBuckets,
                selectedUniverse = selectedCandidates.map { it.symbol }.sorted(),
                topCandidates = orderedCandidates.take(12).map { it.symbol }
            )
        }
        .sortedBy { it.exchange }
}

fun loadBars(exchange: String, aliases: List<String>, symbols: List<String>, lookbackHours: Int, barMinutes: Int): List<Bar> =
    queryBarsFromFeatures(
        exchange = exchange,
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = barMinutes
    )
        .distinctBy { Triple(it.symbol, it.time, it.exchange) }
        .sortedWith(compareBy<Bar> { it.time }.thenBy { it.symbol })

private fun queryBarsFromFeatures(
    exchange: String,
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int
): List<Bar> {
    if (symbols.isEmpty()) return emptyList()
    loadUniverseSnapshotBars(
        exchange = exchange,
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = barMinutes
    ).takeIf { it.isNotEmpty() }?.let { return it }

    val cacheKey = featureCacheKey(
        prefix = "feature-bars:$exchange",
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = barMinutes
    )
    researchFeatureBarCache.get(cacheKey, researchFeatureQueryCacheTtl)?.let { return it }

    val aliasSql = sqlList(aliases)
    val symbolSql = sqlList(symbols)
    val preferredAlias = aliases.first()
    val window = alignedResearchWindowBounds(lookbackHours = lookbackHours, barMinutes = barMinutes)
    val bucketSeconds = window.bucketSeconds
    val sql = """
        WITH minute_rows AS (
            SELECT DISTINCT ON (symbol, time)
                symbol,
                time,
                close,
                COALESCE(volume, 0) AS volume,
                COALESCE(spread_pct, 0) AS spread_pct,
                COALESCE(bid_depth_10, 0) AS bid_depth_10,
                COALESCE(ask_depth_10, 0) AS ask_depth_10,
                COALESCE(NULLIF(mid_price, 0), close) AS mid_price,
                orderbook_observed,
                exchange
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= ?
              AND time < ?
              AND symbol IN ($symbolSql)
            ORDER BY
                symbol,
                time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END
        ),
        bucketed AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                time,
                close,
                volume,
                spread_pct,
                bid_depth_10,
                ask_depth_10,
                mid_price,
                orderbook_observed
            FROM minute_rows
        ),
        bucket_volume AS (
            SELECT
                symbol,
                bucket_time,
                SUM(volume) AS volume
            FROM bucketed
            GROUP BY symbol, bucket_time
        ),
        bucket_close AS (
            SELECT DISTINCT ON (symbol, bucket_time)
                symbol,
                bucket_time,
                close
            FROM bucketed
            ORDER BY symbol, bucket_time, time DESC
        ),
        bucket_orderbook AS (
            SELECT DISTINCT ON (symbol, bucket_time)
                symbol,
                bucket_time,
                spread_pct,
                bid_depth_10,
                ask_depth_10,
                mid_price,
                orderbook_observed
            FROM bucketed
            WHERE orderbook_observed
            ORDER BY symbol, bucket_time, time DESC
        )
        SELECT
            c.symbol,
            c.bucket_time,
            c.close,
            v.volume,
            COALESCE(o.spread_pct, 0) AS spread_pct,
            COALESCE(o.bid_depth_10, 0) AS bid_depth_10,
            COALESCE(o.ask_depth_10, 0) AS ask_depth_10,
            COALESCE(o.mid_price, c.close) AS mid_price,
            CASE WHEN o.symbol IS NULL THEN FALSE ELSE TRUE END AS execution_observed
        FROM bucket_close c
        JOIN bucket_volume v
          ON v.symbol = c.symbol
         AND v.bucket_time = c.bucket_time
        LEFT JOIN bucket_orderbook o
          ON o.symbol = c.symbol
         AND o.bucket_time = c.bucket_time
        ORDER BY c.bucket_time ASC, c.symbol ASC
    """.trimIndent()

    val result = runCatching {
        buildList {
            pgConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(window.startInclusive))
                    stmt.setTimestamp(2, Timestamp.from(window.endExclusive))
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
                                    midPrice = rs.getDouble("mid_price"),
                                    executionObserved = rs.getBoolean("execution_observed")
                                )
                            )
                        }
                    }
                }
            }
        }
    }.getOrElse { emptyList() }

    if (result.isNotEmpty()) {
        researchFeatureBarCache.put(cacheKey, result)
    }
    return result
}

private fun observedSpreadBps(bar: Bar): Double =
    max(bar.spreadPct, 0.0) * 100.0

private fun observedMidPrice(bar: Bar): Double =
    max(bar.midPrice, bar.close)

private fun observedDepthUsd(bar: Bar): Double =
    max((max(bar.bidDepth10, 0.0) + max(bar.askDepth10, 0.0)) * observedMidPrice(bar), 0.0)

private fun observedVolumeUsd(bar: Bar): Double =
    max(bar.volume * observedMidPrice(bar), 0.0)

// Use live-observed execution shapes as conservative priors when historical bars lack orderbook snapshots.
private fun buildExecutionProxyProfiles(
    bars: List<Bar>,
    config: ResearchConfig
): Pair<Map<Pair<String, String>, ExecutionProxyProfile>, ExecutionProxyProfile> {
    val observedBars = bars.filter { it.executionObserved && observedSpreadBps(it) > 0.0 && observedDepthUsd(it) > 0.0 }

    fun buildProfile(samples: List<Bar>): ExecutionProxyProfile {
        if (samples.isEmpty()) {
            return ExecutionProxyProfile(
                spreadBps = max(config.maxSpreadBps * 0.9, 0.5),
                depthToVolumeRatio = 0.03,
                depthFloorUsd = config.notionalUsd * 2.0
            )
        }

        val spreads = samples.map(::observedSpreadBps)
        val depths = samples.map(::observedDepthUsd)
        val depthRatios = samples.map { sample ->
            observedDepthUsd(sample) / max(observedVolumeUsd(sample), config.notionalUsd)
        }

        return ExecutionProxyProfile(
            spreadBps = max(percentile(spreads, 0.75) * 1.15, 0.25),
            depthToVolumeRatio = max(percentile(depthRatios, 0.25) * 0.85, 0.02),
            depthFloorUsd = max(percentile(depths, 0.25) * 0.75, config.notionalUsd * 2.0)
        )
    }

    val marketProfile = buildProfile(observedBars)
    val symbolProfiles = observedBars.groupBy { it.exchange to it.symbol }
        .mapValues { (_, samples) ->
            buildProfile(samples).let { profile ->
                ExecutionProxyProfile(
                    spreadBps = max(profile.spreadBps, marketProfile.spreadBps * 0.85),
                    depthToVolumeRatio = max(profile.depthToVolumeRatio, marketProfile.depthToVolumeRatio * 0.65),
                    depthFloorUsd = max(profile.depthFloorUsd, marketProfile.depthFloorUsd * 0.65)
                )
            }
        }

    return symbolProfiles to marketProfile
}

fun engineerFeatures(bars: List<Bar>, config: ResearchConfig): List<FeatureRow> {
    if (bars.isEmpty()) return emptyList()

    val seriesByKey = bars.groupBy { it.exchange to it.symbol }
        .mapValues { (_, value) -> value.sortedBy { it.time } }
    val (executionProxyByKey, marketExecutionProxy) = buildExecutionProxyProfiles(bars, config)

    val baseByKey = seriesByKey.mapValues { (key, series) ->
        val returns = ArrayDeque<Double>()
        val executionProxy = executionProxyByKey[key] ?: marketExecutionProxy
        series.mapIndexed { index, bar ->
            val previous = series.getOrNull(index - 1)
            val ret1m = if (previous == null || previous.close <= 0.0) 0.0 else (bar.close / previous.close) - 1.0
            returns.addLast(ret1m)
            if (returns.size > 30) {
                returns.removeFirst()
            }
            val vol30 = stdev(returns.toList())
            val midPrice = observedMidPrice(bar)
            val rawSpreadBps = observedSpreadBps(bar)
            val rawDepthUsd = observedDepthUsd(bar)
            val executionObserved = bar.executionObserved && rawSpreadBps > 0.0 && rawDepthUsd > 0.0
            val volumeUsd = max(observedVolumeUsd(bar), config.notionalUsd)
            val proxyDepthUsd = max(
                executionProxy.depthFloorUsd,
                volumeUsd * executionProxy.depthToVolumeRatio
            )
            val proxyDepthUnits = proxyDepthUsd / max(midPrice, 1e-6)
            val spreadBps = if (executionObserved) rawSpreadBps else executionProxy.spreadBps
            val spreadPct = if (executionObserved) max(bar.spreadPct, 0.0) else executionProxy.spreadBps / 10000.0
            val bidDepth10 = if (executionObserved) max(bar.bidDepth10, 0.0) else proxyDepthUnits / 2.0
            val askDepth10 = if (executionObserved) max(bar.askDepth10, 0.0) else proxyDepthUnits / 2.0
            val depthUsd = if (executionObserved) rawDepthUsd else proxyDepthUsd
            BasePoint(
                exchange = key.first,
                symbol = key.second,
                time = bar.time,
                barIndex = index,
                close = bar.close,
                volume = bar.volume,
                spreadPct = spreadPct,
                spreadBps = spreadBps,
                bidDepth10 = bidDepth10,
                askDepth10 = askDepth10,
                midPrice = midPrice,
                depthUsd = depthUsd,
                ret1m = ret1m,
                vol30 = vol30,
                executionObserved = executionObserved
            )
        }
    }

    val baseLookup = mutableMapOf<Triple<String, String, Instant>, BasePoint>()
    baseByKey.values.flatten().forEach { point ->
        baseLookup[Triple(point.exchange, point.symbol, point.time)] = point
    }

    val mediumTrendBars = max(config.trendMediumBars, config.trendSlowBars + 1)
    val longTrendBars = max(config.trendLongBars, mediumTrendBars + 1)
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
            val residualMomMedium = rollingSum(residuals, residuals.lastIndex, mediumTrendBars)
            val residualMomLong = rollingSum(residuals, residuals.lastIndex, longTrendBars)
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
            val mediumScale = max(point.vol30 * sqrt(mediumTrendBars.toDouble()), 1e-6)
            val longScale = max(point.vol30 * sqrt(longTrendBars.toDouble()), 1e-6)
            val normalizedFast = residualMomFast / fastScale
            val normalizedSlow = residualMomSlow / slowScale
            val normalizedMedium = residualMomMedium / mediumScale
            val normalizedLong = residualMomLong / longScale
            val mediumTrendScore = (normalizedSlow * 0.20) +
                (normalizedMedium * 0.45) +
                (normalizedLong * 0.35)
            val mediumTrendDirection = direction(mediumTrendScore)
            val trendPersistence = if (mediumTrendDirection == 0.0) {
                0.0
            } else {
                listOf(normalizedFast, normalizedSlow, normalizedMedium, normalizedLong)
                    .count { direction(it) == mediumTrendDirection }
                    .toDouble() / 4.0
            }
            val trendPullback = if (mediumTrendDirection == 0.0) 0.0 else max(0.0, -(mediumTrendDirection * residualZ))
            val trendExhaustion = if (mediumTrendDirection == 0.0) 0.0 else max(0.0, mediumTrendDirection * residualZ)
            val rawTrend = (normalizedFast * 0.25) +
                (normalizedSlow * 0.15) +
                (mediumTrendScore * 0.60) +
                (flowSignal * 0.12) +
                (mediumTrendDirection * max(0.0, trendPersistence - 0.5) * 0.55) +
                (mediumTrendDirection * min(trendPullback, 1.5) * 0.12) -
                (point.spreadBps / 55.0)
            val volBps = point.vol30 * 10000.0
            val mediumAlignment = mediumTrendDirection * direction(residualZ)
            val trendConfirmationScore = clamp(
                (abs(mediumTrendScore) * 0.55) +
                    (trendPersistence * 0.95) +
                    (max(0.0, direction(rawTrend) * flowSignal) * 0.70) +
                    (max(0.0, mediumAlignment) * 0.45) +
                    (max(0.0, min(depthRatio, 2.0) - 1.0) * 0.30) +
                    (max(0.0, min(volumeRatio, 3.0) - 1.0) * 0.18) -
                    (max(0.0, trendExhaustion - 0.9) * 0.55) -
                    (max(0.0, volRegime - 1.6) * 0.35),
                0.0,
                6.0
            )
            val trendReentryBias = max(0.0, abs(mediumTrendScore) - abs(normalizedFast)) * max(0.0, -mediumAlignment)
            val trendExhaustionBias = max(0.0, abs(mediumTrendScore) - 0.6) * max(0.0, mediumAlignment)
            val trendExpectedGrossEdgeBps = clamp(
                (abs(mediumTrendScore) * max(volBps, 4.0) * 0.58 * sqrt(config.trendHoldBars.toDouble() / 12.0)) +
                    (max(0.0, direction(rawTrend) * flowSignal) * 7.0) +
                    (max(0.0, trendPersistence - 0.5) * 14.0) +
                    (min(trendPullback, 1.25) * 4.5) +
                    (max(0.0, depthRatio - 1.0) * 2.0) +
                    (max(0.0, min(volumeRatio, 3.0) - 1.0) * 2.5) -
                    (max(0.0, trendExhaustion - 1.25) * 6.0) -
                    (max(0.0, volRegime - 1.6) * 5.0),
                0.0,
                220.0
            )
            val reversionExpectedGrossEdgeBps = clamp(
                (abs(residualZ) * max(volBps, 4.0) * 0.62 * sqrt(config.reversionHoldBars.toDouble() / 10.0)) +
                    (max(0.0, -(direction(residualZ) * flowSignal)) * 8.0) +
                    (trendReentryBias * 8.5) +
                    (trendExhaustionBias * 6.0) +
                    (max(0.0, depthRatio - 1.0) * 2.0) -
                    (max(0.0, abs(rawTrend) - 1.25) * 4.5) -
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
                residualMomMedium = residualMomMedium,
                residualMomLong = residualMomLong,
                residualZ = residualZ,
                residualCrossSectionalZ = 0.0,
                reversionState = residualZ,
                reversionEntryLowerBound = -config.reversionZEntry,
                reversionEntryUpperBound = config.reversionZEntry,
                reversionExitLowerBound = -config.reversionZExit,
                reversionExitUpperBound = config.reversionZExit,
                imbalance = imbalance,
                volumeRatio = volumeRatio,
                depthRatio = depthRatio,
                volRegime = volRegime,
                flowSignal = flowSignal,
                mediumTrendScore = mediumTrendScore,
                trendConfirmationScore = trendConfirmationScore,
                trendPersistence = trendPersistence,
                trendPullback = trendPullback,
                trendExhaustion = trendExhaustion,
                rawTrend = rawTrend,
                trendExpectedGrossEdgeBps = trendExpectedGrossEdgeBps,
                reversionExpectedGrossEdgeBps = reversionExpectedGrossEdgeBps,
                liquid = liquid,
                executionObserved = point.executionObserved
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
        val comparableCohorts = bucket.groupBy(::reversionUniverseBucket)
        val minimumCohortSize = min(max(bucket.size / 8, 6), max(bucket.size, 1))
        val residualCrossSectionalZByRow = mutableMapOf<UnrankedFeature, Double>()

        bucket.forEach { row ->
            val cohort = comparableCohorts[reversionUniverseBucket(row)].orEmpty()
                .takeIf { it.size >= minimumCohortSize }
                ?: bucket
            val residualRetUniverse = cohort.map { it.residualRet }
            val fallbackScale = max(stdev(residualRetUniverse), max(row.vol30, 1e-6))
            residualCrossSectionalZByRow[row] = robustZScore(
                value = row.residualRet,
                values = residualRetUniverse,
                fallbackScale = fallbackScale
            )
        }

        val reversionStateByRow = bucket.associateWith { row ->
            val crossSectionalComponent = residualCrossSectionalZByRow[row] ?: 0.0
            val blendedState = ((1.0 - config.reversionCrossSectionalWeight) * row.residualZ) +
                (config.reversionCrossSectionalWeight * crossSectionalComponent)
            clamp(blendedState, -6.0, 6.0)
        }
        val reversionEntryBoundsByRow = mutableMapOf<UnrankedFeature, Pair<Double, Double>>()
        val reversionExitBoundsByRow = mutableMapOf<UnrankedFeature, Pair<Double, Double>>()

        bucket.forEach { row ->
            val cohort = comparableCohorts[reversionUniverseBucket(row)].orEmpty()
                .takeIf { it.size >= minimumCohortSize }
                ?: bucket
            val stateUniverse = cohort.map { reversionStateByRow[it] ?: 0.0 }
            val entryLowerQuantile = percentile(stateUniverse, config.reversionEntryQuantile)
            val entryUpperQuantile = percentile(stateUniverse, 1.0 - config.reversionEntryQuantile)
            val exitLowerQuantile = percentile(stateUniverse, config.reversionExitQuantile)
            val exitUpperQuantile = percentile(stateUniverse, 1.0 - config.reversionExitQuantile)
            val entryLowerBound = min(entryLowerQuantile, -config.reversionZEntry)
            val entryUpperBound = max(entryUpperQuantile, config.reversionZEntry)
            val exitLowerBound = min(max(exitLowerQuantile, -config.reversionZExit), 0.0)
            val exitUpperBound = max(min(exitUpperQuantile, config.reversionZExit), 0.0)
            reversionEntryBoundsByRow[row] = entryLowerBound to entryUpperBound
            reversionExitBoundsByRow[row] = exitLowerBound to exitUpperBound
        }

        val trendScores = bucket.associateWith { row ->
            val mediumDirection = direction(row.mediumTrendScore)
            val trendReference = if (abs(row.mediumTrendScore) > abs(row.rawTrend)) row.mediumTrendScore else row.rawTrend
            val flowAlignment = direction(trendReference) * row.flowSignal
            val breadthAlignment = direction(trendReference) * breadthTilt
            row.rawTrend +
                (row.mediumTrendScore * 0.85) +
                (mediumDirection * max(0.0, row.trendPersistence - 0.5) * 0.70) +
                (mediumDirection * min(row.trendPullback, 1.25) * 0.18) +
                (breadthAlignment * 0.9) +
                (max(0.0, flowAlignment) * 0.65) -
                (max(0.0, -flowAlignment) * 1.0) +
                min(max(0.0, row.depthRatio - 1.0) * 0.35, 0.55) +
                (max(0.0, min(row.volumeRatio, 3.0) - 1.0) * 0.22) -
                (max(0.0, row.trendExhaustion - 1.1) * 0.55) -
                (max(0.0, row.volRegime - 1.7) * 0.45)
        }
        val reversionScores = bucket.associateWith { row ->
            val reversionState = reversionStateByRow[row] ?: row.residualZ
            val continuationPressure = direction(reversionState) * row.flowSignal
            val breadthContinuation = direction(reversionState) * breadthTilt
            val mediumAlignment = direction(row.mediumTrendScore) * direction(reversionState)
            val reentryBonus = max(0.0, abs(row.mediumTrendScore) - abs(row.rawTrend)) * max(0.0, -mediumAlignment)
            val exhaustionBonus = max(0.0, abs(row.mediumTrendScore) - 0.6) * max(0.0, mediumAlignment)
            abs(reversionState) +
                (reentryBonus * 0.95) +
                (exhaustionBonus * 0.80) +
                (max(0.0, -continuationPressure) * 0.95) -
                (max(0.0, continuationPressure) * 1.25) -
                (max(0.0, abs(row.rawTrend) - 1.15) * 0.65) -
                (row.spreadBps / 35.0) -
                (max(0.0, breadthContinuation) * 1.15) -
                (max(0.0, row.volRegime - 1.55) * 0.60)
        }
        val trendExpectedEdges = bucket.associateWith { row ->
            val breadthAlignment = direction(if (abs(row.mediumTrendScore) > abs(row.rawTrend)) row.mediumTrendScore else row.rawTrend) * breadthTilt
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
            val breadthContinuation = direction(reversionStateByRow[row] ?: row.residualZ) * breadthTilt
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
            .filter {
                val reversionState = reversionStateByRow[it] ?: it.residualZ
                it.liquid && reversionState < 0.0 && (reversionExpectedEdges[it] ?: 0.0) > 0.0
            }
            .sortedBy { reversionStateByRow[it] ?: it.residualZ }
            .mapIndexed { index, row -> row to index + 1 }
            .toMap()
        val reversionShortRanks = bucket
            .filter {
                val reversionState = reversionStateByRow[it] ?: it.residualZ
                it.liquid && reversionState > 0.0 && (reversionExpectedEdges[it] ?: 0.0) > 0.0
            }
            .sortedByDescending { reversionStateByRow[it] ?: it.residualZ }
            .mapIndexed { index, row -> row to index + 1 }
            .toMap()

        bucket.forEach { row ->
            val provisionalRow = FeatureRow(
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
                residualMomMedium = row.residualMomMedium,
                residualMomLong = row.residualMomLong,
                residualZ = row.residualZ,
                residualCrossSectionalZ = (residualCrossSectionalZByRow[row] ?: 0.0).round(6),
                reversionState = (reversionStateByRow[row] ?: row.residualZ).round(6),
                reversionEntryLowerBound = (reversionEntryBoundsByRow[row]?.first ?: -config.reversionZEntry).round(6),
                reversionEntryUpperBound = (reversionEntryBoundsByRow[row]?.second ?: config.reversionZEntry).round(6),
                reversionExitLowerBound = (reversionExitBoundsByRow[row]?.first ?: -config.reversionZExit).round(6),
                reversionExitUpperBound = (reversionExitBoundsByRow[row]?.second ?: config.reversionZExit).round(6),
                imbalance = row.imbalance,
                volumeRatio = row.volumeRatio,
                depthRatio = row.depthRatio,
                volRegime = row.volRegime,
                flowSignal = row.flowSignal,
                breadth = breadth,
                mediumTrendScore = row.mediumTrendScore,
                trendConfirmationScore = row.trendConfirmationScore,
                trendPersistence = row.trendPersistence,
                trendPullback = row.trendPullback,
                trendExhaustion = row.trendExhaustion,
                rawTrend = row.rawTrend,
                trendScore = trendScores[row] ?: row.rawTrend,
                reversionScore = reversionScores[row] ?: 0.0,
                trendExpectedGrossEdgeBps = trendExpectedEdges[row] ?: row.trendExpectedGrossEdgeBps,
                reversionExpectedGrossEdgeBps = reversionExpectedEdges[row] ?: row.reversionExpectedGrossEdgeBps,
                trendTargetExposureFraction = 0.0,
                reversionTargetExposureFraction = 0.0,
                liquid = row.liquid,
                trendLongRank = trendLongRanks[row] ?: Int.MAX_VALUE,
                trendShortRank = trendShortRanks[row] ?: Int.MAX_VALUE,
                reversionLongRank = reversionLongRanks[row] ?: Int.MAX_VALUE,
                reversionShortRank = reversionShortRanks[row] ?: Int.MAX_VALUE,
                executionObserved = row.executionObserved
            )
            val trendSide = direction(provisionalRow.trendScore).toInt()
            val reversionSide = (-direction(provisionalRow.reversionState)).toInt()
            val trendNetEdge = provisionalRow.trendExpectedGrossEdgeBps -
                buildExpectedRoundTripCostBps(
                    provisionalRow,
                    buildExecutionEstimate(
                        provisionalRow,
                        config.notionalUsd,
                        if (trendSide == 0) 1 else trendSide,
                        StrategyKind.TREND
                    )
                )
            val reversionNetEdge = provisionalRow.reversionExpectedGrossEdgeBps -
                buildExpectedRoundTripCostBps(
                    provisionalRow,
                    buildExecutionEstimate(
                        provisionalRow,
                        config.notionalUsd,
                        if (reversionSide == 0) 1 else reversionSide,
                        StrategyKind.REVERSION
                    )
                )
            val trendSizing = targetExposureFraction(
                kind = StrategyKind.TREND,
                row = provisionalRow,
                side = if (trendSide == 0) 1 else trendSide,
                expectedNetEdgeBps = trendNetEdge,
                config = config
            )
            val reversionSizing = targetExposureFraction(
                kind = StrategyKind.REVERSION,
                row = provisionalRow,
                side = if (reversionSide == 0) 1 else reversionSide,
                expectedNetEdgeBps = reversionNetEdge,
                config = config
            )
            finalRows += provisionalRow.copy(
                trendTargetExposureFraction = trendSizing.first,
                reversionTargetExposureFraction = reversionSizing.first
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
    var makerShare = clamp(
        (if (kind == StrategyKind.REVERSION) 0.62 else 0.52) -
            (depthPressure * 0.32) -
            (volatilityPenalty * 0.03) -
            (flowAgainstTrade * 0.05) +
            (imbalanceWithTrade * 0.05) -
            (spreadHalfBps / 45.0),
        0.12,
        0.88
    )
    var fillRatio = clamp(
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
    var slippageBps = clamp(
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
    var impactBps = clamp(
        0.15 +
            (depthPressure * 14.0) +
            (max(0.0, row.volumeRatio - 2.0) * 0.9) +
            (max(0.0, row.volRegime - 1.3) * 1.8),
        0.10,
        22.0
    )
    var adverseSelectionBps = clamp(
        (abs(row.residualZ) * 0.35) +
            (flowAgainstTrade * 2.5) +
            (max(0.0, row.volRegime - 1.0) * 2.2) +
            (max(0.0, abs(row.rawTrend) - 1.5) * 1.2),
        0.0,
        10.0
    )
    if (!row.executionObserved) {
        makerShare = clamp(makerShare - 0.10, 0.05, 0.78)
        fillRatio = clamp(fillRatio - 0.08, 0.18, 0.96)
        slippageBps = clamp((slippageBps * 1.18) + 0.9, 0.25, 24.0)
        impactBps = clamp((impactBps * 1.20) + 1.1, 0.15, 28.0)
        adverseSelectionBps = clamp((adverseSelectionBps * 1.15) + 0.6, 0.0, 12.0)
    }
    val feeBps = (makerFeeBps * makerShare) + (takerFeeBps * (1.0 - makerShare))
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

fun reversionUniverseBucket(row: UnrankedFeature): String {
    val liquidityScore = min(((min(row.depthRatio, 3.0) + min(row.volumeRatio, 3.0)) / 2.0), 3.0)
    val liquidityBucket = when {
        liquidityScore < 0.9 -> "thin"
        liquidityScore < 1.45 -> "normal"
        else -> "deep"
    }
    return "$liquidityBucket|${volatilityRegimeBucket(row.volRegime)}"
}

fun breadthTilt(row: FeatureRow): Double =
    (row.breadth - 0.5) * 2.0

private fun confidenceToExposureFraction(confidence: Double, config: ResearchConfig): Double =
    clamp(
        config.minTargetExposureFraction +
            ((config.maxTargetExposureFraction - config.minTargetExposureFraction) * clamp(confidence, 0.0, 1.0)),
        config.minTargetExposureFraction,
        config.maxTargetExposureFraction
    ).round(4)

fun targetExposureFraction(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    expectedNetEdgeBps: Double,
    config: ResearchConfig
): Pair<Double, Double> {
    if (side == 0) return config.minTargetExposureFraction.round(4) to 0.0
    val expectedEdgeConfidence = clamp(
        expectedNetEdgeBps / max(config.minExpectedNetEdgeBps + 12.0, 20.0),
        0.0,
        1.0
    )
    val confidence = when (kind) {
        StrategyKind.TREND -> {
            val signalStrength = clamp((side.toDouble() * row.trendScore) / max(config.trendEntryScore * 2.0, 1.0), 0.0, 1.25)
            val confirmation = clamp(row.trendConfirmationScore / 3.0, 0.0, 1.25)
            val flowAlignment = clamp((side.toDouble() * row.flowSignal) / 1.25, 0.0, 1.0)
            val breadthAlignment = clamp((side.toDouble() * breadthTilt(row) + 0.35) / 1.35, 0.0, 1.0)
            val persistence = clamp(row.trendPersistence, 0.0, 1.0)
            val pullbackSupport = clamp(row.trendPullback / 1.15, 0.0, 1.0)
            val exhaustionPenalty = clamp(max(0.0, row.trendExhaustion - 0.9) / 1.2, 0.0, 1.0)
            clamp(
                (signalStrength * 0.28) +
                    (confirmation * 0.24) +
                    (persistence * 0.16) +
                    (flowAlignment * 0.10) +
                    (breadthAlignment * 0.08) +
                    (pullbackSupport * 0.06) +
                    (expectedEdgeConfidence * 0.18) -
                    (exhaustionPenalty * 0.18),
                0.0,
                1.0
            )
        }
        StrategyKind.REVERSION -> {
            val entryBound = if (side > 0) abs(row.reversionEntryLowerBound) else abs(row.reversionEntryUpperBound)
            val exitBound = if (side > 0) abs(row.reversionExitLowerBound) else abs(row.reversionExitUpperBound)
            val stateAbs = abs(row.reversionState)
            val penetration = clamp((stateAbs - entryBound) / max(entryBound, 0.35), 0.0, 1.4)
            val traversal = clamp(
                (stateAbs - exitBound) / max(entryBound - exitBound, 0.2),
                0.0,
                1.25
            )
            val antiContinuation = clamp(
                ((-direction(row.reversionState) * row.flowSignal) + 0.35) / 1.4,
                0.0,
                1.0
            )
            val pullbackSupport = clamp(max(row.trendPullback, row.trendExhaustion) / 1.1, 0.0, 1.0)
            val rawTrendPenalty = clamp(abs(row.rawTrend) / max(config.trendEntryScore * 1.6, 1.0), 0.0, 1.0)
            val scoreStrength = clamp(row.reversionScore / 3.0, 0.0, 1.2)
            clamp(
                (penetration * 0.28) +
                    (traversal * 0.18) +
                    (antiContinuation * 0.14) +
                    (pullbackSupport * 0.10) +
                    (scoreStrength * 0.14) +
                    (expectedEdgeConfidence * 0.18) -
                    (rawTrendPenalty * 0.16),
                0.0,
                1.0
            )
        }
    }
    return confidenceToExposureFraction(confidence, config) to confidence.round(4)
}

private fun buildSizedCandidate(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    expectedGrossEdgeBps: Double,
    cappedNetEdgeBps: Double,
    config: ResearchConfig,
    calibrationSamples: Int = 0,
    calibrationWinRate: Double = 0.0,
    calibrationLowerBoundBps: Double = 0.0,
    calibrationScope: String = "heuristic"
): EntryCandidate {
    val sizing = targetExposureFraction(kind, row, side, cappedNetEdgeBps, config)
    val targetExposureFraction = sizing.first
    val scaledNotionalUsd = config.notionalUsd * targetExposureFraction
    val entryEstimate = buildExecutionEstimate(row, scaledNotionalUsd, side, kind)
    val expectedRoundTripCostBps = buildExpectedRoundTripCostBps(row, entryEstimate)
    val feasibleNetEdgeBps = max(0.0, expectedGrossEdgeBps - expectedRoundTripCostBps)
    val expectedNetEdgeBps = min(max(cappedNetEdgeBps, 0.0), feasibleNetEdgeBps).round(4)
    return EntryCandidate(
        row = row,
        side = side,
        entryEstimate = entryEstimate,
        expectedGrossEdgeBps = expectedGrossEdgeBps.round(4),
        expectedRoundTripCostBps = expectedRoundTripCostBps.round(4),
        expectedNetEdgeBps = expectedNetEdgeBps,
        targetExposureFraction = targetExposureFraction,
        signalConfidence = sizing.second,
        calibrationSamples = calibrationSamples,
        calibrationWinRate = calibrationWinRate,
        calibrationLowerBoundBps = calibrationLowerBoundBps,
        calibrationScope = calibrationScope
    )
}

fun volatilityRegimeBucket(volRegime: Double): String =
    when {
        volRegime < 0.95 -> "calm"
        volRegime < 1.45 -> "normal"
        else -> "stress"
    }

fun calibrationRegimeBucket(row: FeatureRow): String =
    volatilityRegimeBucket(row.volRegime)

fun tradeRegimeBucket(trade: TradeRecord): String =
    volatilityRegimeBucket(trade.entryVolRegime)

fun calibrationSignalBucket(kind: StrategyKind, row: FeatureRow): String =
    when (kind) {
        StrategyKind.TREND -> when {
            max(abs(row.trendScore), abs(row.mediumTrendScore)) < 1.35 -> "entry"
            max(abs(row.trendScore), abs(row.mediumTrendScore)) < 1.90 -> "strong"
            else -> "extreme"
        }
        StrategyKind.REVERSION -> when {
            abs(row.reversionState) < max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * 1.2 -> "entry"
            abs(row.reversionState) < max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * 1.8 -> "deep"
            else -> "extreme"
        }
    }

fun calibrationConfirmationBucket(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): String {
    val flowAlignment = side.toDouble() * row.flowSignal
    val fastAlignment = side.toDouble() * direction(row.residualMomFast)
    val slowAlignment = side.toDouble() * direction(row.residualMomSlow)
    val mediumAlignment = side.toDouble() * direction(row.mediumTrendScore)
    val continuationPressure = direction(row.reversionState) * row.flowSignal
    return when (kind) {
        StrategyKind.TREND -> when {
            flowAlignment >= config.trendMinFlowAlignment &&
                slowAlignment > 0.0 &&
                mediumAlignment > 0.0 -> "confirmed"
            flowAlignment >= -0.04 && (fastAlignment > 0.0 || slowAlignment > 0.0 || mediumAlignment > 0.0) -> "mixed"
            else -> "fragile"
        }
        StrategyKind.REVERSION -> when {
            row.trendPullback >= 0.35 || row.trendExhaustion >= 0.55 -> "confirmed"
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

fun conservativeCalibrationNetEdgeBps(
    calibration: CalibrationStats,
    config: ResearchConfig
): Double {
    val confidence = clamp(
        (calibration.samples - config.minCalibrationSamples).toDouble() /
            max(config.strongCalibrationSamples - config.minCalibrationSamples, 1).toDouble(),
        0.0,
        1.0
    )
    val scopeBonus = when {
        calibration.scope.startsWith("symbol_regime_signal_confirmation") -> 0.10
        calibration.scope.startsWith("symbol_regime") || calibration.scope.startsWith("symbol_confirmation") -> 0.05
        calibration.scope.startsWith("market_regime") -> 0.02
        else -> 0.0
    }
    val avgWeight = clamp(0.15 + (confidence * 0.35) + scopeBonus, 0.15, 0.60)
    return max(
        (calibration.lowerBoundNetEdgeBps * (1.0 - avgWeight)) +
            (calibration.avgNetEdgeBps * avgWeight),
        0.0
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

    val expectedGrossEdgeBps = when (kind) {
        StrategyKind.TREND -> row.trendExpectedGrossEdgeBps
        StrategyKind.REVERSION -> row.reversionExpectedGrossEdgeBps
    }
    val rowBreadthTilt = breadthTilt(row)
    val continuationPressure = direction(row.reversionState) * row.flowSignal

    when (kind) {
        StrategyKind.TREND -> {
            val flowAlignment = side.toDouble() * row.flowSignal
            val breadthAlignment = side.toDouble() * rowBreadthTilt
            val mediumAlignment = side.toDouble() * row.mediumTrendScore
            val pullbackAllowance = if (mediumAlignment > 0.55 && row.trendPersistence >= 0.5) 1.45 else 1.05
            if ((side.toDouble() * row.trendScore) < config.trendEntryScore) return null
            if ((side.toDouble() * row.rawTrend) <= 0.0 && mediumAlignment < 0.35) return null
            if (mediumAlignment <= 0.0) return null
            if (flowAlignment < config.trendMinFlowAlignment && mediumAlignment < 0.65) return null
            if (breadthAlignment < -0.05 && mediumAlignment < 0.65) return null
            if ((side.toDouble() * direction(row.residualMomFast)) <= 0.0 && mediumAlignment < 0.75) return null
            if ((side.toDouble() * direction(row.residualMomSlow)) <= 0.0) return null
            if ((side.toDouble() * direction(row.residualMomMedium)) <= 0.0) return null
            if (abs(row.reversionState) > max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * pullbackAllowance) {
                return null
            }
        }
        StrategyKind.REVERSION -> {
            val trendAwareRawTrendCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) {
                config.trendEntryScore * 1.55
            } else {
                config.trendEntryScore * 1.10
            }
            val continuationCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) {
                config.reversionMaxContinuationPressure * 1.10
            } else {
                config.reversionMaxContinuationPressure * 0.75
            }
            val breadthCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) 0.30 else 0.15
            if (side > 0 && row.reversionState > row.reversionEntryLowerBound) return null
            if (side < 0 && row.reversionState < row.reversionEntryUpperBound) return null
            if (row.reversionScore <= 0.0) return null
            if (abs(row.rawTrend) > trendAwareRawTrendCap) return null
            if (continuationPressure > continuationCap) return null
            if (direction(row.reversionState) * rowBreadthTilt > breadthCap) return null
            if ((side.toDouble() * row.flowSignal) < -0.08 && row.trendPullback < 0.35 && row.trendExhaustion < 0.55) return null
            if ((side.toDouble() * direction(row.residualMomFast)) < 0.0 &&
                abs(row.rawTrend) > (config.trendEntryScore * 0.85) &&
                row.trendPullback < 0.35
            ) return null
        }
    }

    val baseSizing = targetExposureFraction(kind, row, side, expectedGrossEdgeBps, config)
    val scaledEntryEstimate = buildExecutionEstimate(row, config.notionalUsd * baseSizing.first, side, kind)
    if (scaledEntryEstimate.fillRatio < config.minFillRatio) return null
    val expectedRoundTripCostBps = buildExpectedRoundTripCostBps(row, scaledEntryEstimate)
    val expectedNetEdgeBps = expectedGrossEdgeBps - expectedRoundTripCostBps
    return buildSizedCandidate(
        kind = kind,
        row = row,
        side = side,
        expectedGrossEdgeBps = expectedGrossEdgeBps,
        cappedNetEdgeBps = expectedNetEdgeBps,
        config = config
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
    val calibratedNetEdgeBps = min(
        seed.expectedNetEdgeBps,
        conservativeCalibrationNetEdgeBps(calibration, config)
    )
    if (calibratedNetEdgeBps < config.minExpectedNetEdgeBps) return null

    val calibratedGrossEdgeBps = seed.expectedRoundTripCostBps + calibratedNetEdgeBps
    if (calibratedNetEdgeBps < safetyMarginBps) return null

    return buildSizedCandidate(
        kind = kind,
        row = row,
        side = side,
        expectedGrossEdgeBps = calibratedGrossEdgeBps.round(4),
        cappedNetEdgeBps = calibratedNetEdgeBps.round(4),
        config = config,
        calibrationSamples = calibration.samples,
        calibrationWinRate = calibration.winRate.round(4),
        calibrationLowerBoundBps = calibration.lowerBoundNetEdgeBps.round(4),
        calibrationScope = calibration.scope
    )
}

fun shouldExitPosition(kind: StrategyKind, position: OpenPosition, current: FeatureRow, config: ResearchConfig): Boolean =
    baseExitTriggered(kind, position, current, config) ||
        trailingStopTriggered(kind, position, current, config) ||
        takeProfitTriggered(kind, position, current, config)

private fun baseExitTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean =
    when (kind) {
        StrategyKind.TREND -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.trendHoldBars ||
                ((current.trendScore * position.side.toDouble()) <= 0.12 &&
                    (current.mediumTrendScore * position.side.toDouble()) <= 0.10) ||
                ((position.side.toDouble() * current.flowSignal) < -0.18 && current.trendPullback < 0.35) ||
                current.volRegime > (config.maxVolRegime * 1.15)
        }
        StrategyKind.REVERSION -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.reversionHoldBars ||
                current.reversionState in current.reversionExitLowerBound..current.reversionExitUpperBound ||
                (current.reversionState * position.side.toDouble()) >= -0.05 ||
                (direction(position.entryRow.reversionState) * current.flowSignal) > config.reversionMaxContinuationPressure
        }
    }

private fun positionSignedReturnFraction(position: OpenPosition, current: FeatureRow): Double {
    if (position.entryRow.close <= 0.0 || current.close <= 0.0) return 0.0
    return position.side.toDouble() * ((current.close / position.entryRow.close) - 1.0)
}

private fun exitDistanceFraction(position: OpenPosition, current: FeatureRow, multiple: Double): Double {
    if (!multiple.isFinite() || multiple <= 0.0) return Double.POSITIVE_INFINITY
    val referenceVol = max(max(position.entryRow.vol30, current.vol30), 1e-6)
    return multiple * referenceVol
}

private fun trailingStopMultiple(kind: StrategyKind, config: ResearchConfig): Double =
    when (kind) {
        StrategyKind.TREND -> config.trendTrailingStopVolMultiple
        StrategyKind.REVERSION -> config.reversionTrailingStopVolMultiple
    }

private fun takeProfitMultiple(kind: StrategyKind, config: ResearchConfig): Double =
    when (kind) {
        StrategyKind.TREND -> config.trendTakeProfitVolMultiple
        StrategyKind.REVERSION -> config.reversionTakeProfitVolMultiple
    }

private fun trailingStopTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean {
    val distance = exitDistanceFraction(position, current, trailingStopMultiple(kind, config))
    if (!distance.isFinite()) return false
    val favorableReturn = positionSignedReturnFraction(position, current)
    return position.maxFavorableReturnFraction >= distance &&
        (position.maxFavorableReturnFraction - favorableReturn) >= distance
}

private fun takeProfitTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean {
    val distance = exitDistanceFraction(position, current, takeProfitMultiple(kind, config))
    if (!distance.isFinite()) return false
    return positionSignedReturnFraction(position, current) >= distance
}

private fun updateOpenPosition(position: OpenPosition, current: FeatureRow): OpenPosition =
    position.copy(
        maxFavorableReturnFraction = max(
            position.maxFavorableReturnFraction,
            positionSignedReturnFraction(position, current)
        )
    )

fun seedCandidateRows(kind: StrategyKind, bucket: List<FeatureRow>, config: ResearchConfig): List<EntryCandidate> =
    when (kind) {
        StrategyKind.TREND -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.trendLongRank <= config.topPerSide &&
                        it.trendScore >= config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.TREND, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
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
                        it.reversionState <= it.reversionEntryLowerBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.REVERSION, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.reversionState >= it.reversionEntryUpperBound &&
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
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, 1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
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
                        it.reversionState <= it.reversionEntryLowerBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, 1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.reversionState >= it.reversionEntryUpperBound &&
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
                    row.reversionState <= row.reversionEntryLowerBound &&
                    row.reversionScore > 0.0
            ) {
                buildEntryCandidate(StrategyKind.REVERSION, row, 1, config, calibrationState)
            } else {
                null
            }
            val reversionShort = if (
                row.liquid &&
                    row.reversionShortRank <= config.topPerSide &&
                    row.reversionState >= row.reversionEntryUpperBound &&
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
                residualCrossSectionalZ = row.residualCrossSectionalZ.round(4),
                reversionState = row.reversionState.round(4),
                reversionEntryLowerBound = row.reversionEntryLowerBound.round(4),
                reversionEntryUpperBound = row.reversionEntryUpperBound.round(4),
                reversionExitLowerBound = row.reversionExitLowerBound.round(4),
                reversionExitUpperBound = row.reversionExitUpperBound.round(4),
                mediumTrendScore = row.mediumTrendScore.round(4),
                trendConfirmationScore = row.trendConfirmationScore.round(4),
                trendPersistence = row.trendPersistence.round(4),
                trendPullback = row.trendPullback.round(4),
                trendExhaustion = row.trendExhaustion.round(4),
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
                trendTargetExposureFraction = (trendCandidate?.targetExposureFraction ?: row.trendTargetExposureFraction).round(4),
                reversionTargetExposureFraction = (reversionCandidate?.targetExposureFraction ?: row.reversionTargetExposureFraction).round(4),
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
    val entryNotionalUsd = config.notionalUsd * position.targetExposureFraction
    val exitEstimate = buildExecutionEstimate(current, entryNotionalUsd, -position.side, kind)
    val effectiveFill = min(position.entryEstimate.fillRatio, exitEstimate.fillRatio)
    val deployedGrossReturn = position.side * ((current.close / position.entryRow.close) - 1.0) * effectiveFill
    val totalCostBps = position.entryEstimate.totalCostBps + exitEstimate.totalCostBps
    val grossReturn = deployedGrossReturn * position.targetExposureFraction
    val netReturn = (deployedGrossReturn - (totalCostBps / 10000.0)) * position.targetExposureFraction
    val signalMagnitude = when (kind) {
        StrategyKind.TREND -> abs(position.entryRow.trendScore)
        StrategyKind.REVERSION -> abs(position.entryRow.reversionState)
    }
    val jitter = deterministicJitter(current.time, position.side)
    val decisionLatencyMs = clamp(6.0 + (signalMagnitude * 7.0) + (jitter * 0.6), 4.0, 60.0)
    val submitToAckMs = clamp(
        55.0 +
            (current.spreadBps * 1.1) +
            ((entryNotionalUsd / max(current.depthUsd, entryNotionalUsd)) * 120.0) +
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
        edgeAfterCostBps = (deployedGrossReturn - (totalCostBps / 10000.0)) * 10000.0,
        targetExposureFraction = position.targetExposureFraction,
        entryNotionalUsd = entryNotionalUsd.round(4),
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
    var position = OpenPosition(
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
        targetExposureFraction = candidate.targetExposureFraction,
        calibrationSamples = candidate.calibrationSamples,
        calibrationWinRate = candidate.calibrationWinRate,
        calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
        calibrationScope = candidate.calibrationScope,
        maxFavorableReturnFraction = 0.0
    )
    for (index in (startIndex + 1) until series.size) {
        val current = series[index]
        position = updateOpenPosition(position, current)
        if (shouldExitPosition(kind, position, current, config)) {
            return buildTradeRecord(position, current, config)
        }
    }
    val last = series.lastOrNull() ?: return null
    return if (last.time == candidate.row.time) {
        null
    } else {
        buildTradeRecord(updateOpenPosition(position, last), last, config)
    }
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
            val realizedGrossEdgeBps = if (trade.targetExposureFraction > 1e-9) {
                (trade.grossReturnFraction * 10000.0) / trade.targetExposureFraction
            } else {
                0.0
            }
            examples += CalibrationExample(
                key = calibrationBaseKey(kind, candidate.row, candidate.side, config),
                entryTime = candidate.row.time,
                availableAt = trade.exitTime,
                grossEdgeBps = realizedGrossEdgeBps,
                netEdgeBps = trade.edgeAfterCostBps,
                totalCostBps = trade.totalCostBps,
                fillRatio = trade.fillRatio
            )
        }
    }
    return examples.sortedBy { it.availableAt }
}

private fun effectiveLongCapacity(config: ResearchConfig): Int =
    min(max(config.maxConcurrentLongs, 1), max(config.maxConcurrentPositions, 1))

private fun effectiveShortCapacity(config: ResearchConfig): Int =
    min(max(config.maxConcurrentShorts, 1), max(config.maxConcurrentPositions, 1))

private fun currentGrossExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.targetExposureFraction }

private fun currentLongExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.filter { it.side > 0 }.sumOf { it.targetExposureFraction }

private fun currentShortExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.filter { it.side < 0 }.sumOf { it.targetExposureFraction }

private fun currentBetaBtcUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction * it.entryRow.betaBtc }

private fun currentBetaEthUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction * it.entryRow.betaEth }

private fun currentNetUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction }

private fun portfolioTelemetryPoint(
    positions: Collection<OpenPosition>
): PortfolioTelemetryPoint {
    val grossPositions = positions.size
    val longPositions = positions.count { it.side > 0 }
    val shortPositions = positions.count { it.side < 0 }
    return PortfolioTelemetryPoint(
        grossPositions = grossPositions,
        longPositions = longPositions,
        shortPositions = shortPositions,
        grossExposureUnits = currentGrossExposureUnits(positions).round(4),
        longExposureUnits = currentLongExposureUnits(positions).round(4),
        shortExposureUnits = currentShortExposureUnits(positions).round(4),
        netExposureUnits = currentNetUnits(positions).round(4),
        betaBtcUnits = currentBetaBtcUnits(positions).round(4),
        betaEthUnits = currentBetaEthUnits(positions).round(4)
    )
}

private fun portfolioAcceptanceScore(
    positions: Collection<OpenPosition>,
    candidate: EntryCandidate,
    config: ResearchConfig
): Double {
    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val candidateNetContribution = candidate.side.toDouble() * candidate.targetExposureFraction
    val currentNetFraction = abs(currentNetUnits(positions)) / capacity
    val candidateNetFraction = abs(currentNetUnits(positions) + candidateNetContribution) / capacity
    val currentBetaPenalty =
        (abs(currentBetaBtcUnits(positions)) / capacity) +
            (abs(currentBetaEthUnits(positions)) / capacity)
    val candidateBetaPenalty =
        (abs(currentBetaBtcUnits(positions) + (candidateNetContribution * candidate.row.betaBtc)) / capacity) +
            (abs(currentBetaEthUnits(positions) + (candidateNetContribution * candidate.row.betaEth)) / capacity)
    val balanceBonus = (currentNetFraction - candidateNetFraction) * 6.0
    val betaBonus = (currentBetaPenalty - candidateBetaPenalty) * 10.0
    val capacityPenalty = (currentGrossExposureUnits(positions) / capacity) * 0.75
    val similarityPenalty = positions
        .filter { it.side == candidate.side }
        .map {
            val betaDistance = (abs(it.entryRow.betaBtc - candidate.row.betaBtc) + abs(it.entryRow.betaEth - candidate.row.betaEth)) / 2.0
            clamp(1.0 - betaDistance, 0.0, 1.0)
        }
        .takeIf { it.isNotEmpty() }
        ?.let(::mean)
        ?.times(2.0)
        ?: 0.0
    return candidate.expectedNetEdgeBps + balanceBonus + betaBonus - capacityPenalty - similarityPenalty
}

private fun canAddCandidateToPortfolio(
    positions: Map<String, OpenPosition>,
    candidate: EntryCandidate,
    config: ResearchConfig,
    counters: PortfolioConstraintCounters
): Boolean {
    counters.candidateEntries += 1
    val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
    if (positions.containsKey(positionKey)) {
        counters.rejectedOpenSymbol += 1
        return false
    }

    val grossAfter = positions.size + 1
    if (grossAfter > config.maxConcurrentPositions) {
        counters.rejectedGrossLimit += 1
        return false
    }

    val longAfter = positions.values.count { it.side > 0 } + if (candidate.side > 0) 1 else 0
    val shortAfter = positions.values.count { it.side < 0 } + if (candidate.side < 0) 1 else 0
    if (candidate.side > 0 && longAfter > effectiveLongCapacity(config)) {
        counters.rejectedLongLimit += 1
        return false
    }
    if (candidate.side < 0 && shortAfter > effectiveShortCapacity(config)) {
        counters.rejectedShortLimit += 1
        return false
    }

    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val candidateNetContribution = candidate.side.toDouble() * candidate.targetExposureFraction
    val nextNetFraction = abs(currentNetUnits(positions.values) + candidateNetContribution) / capacity
    if (nextNetFraction > config.maxNetExposureFraction + 1e-9) {
        counters.rejectedNetLimit += 1
        return false
    }

    val nextBetaBtc = abs(currentBetaBtcUnits(positions.values) + (candidateNetContribution * candidate.row.betaBtc)) / capacity
    val nextBetaEth = abs(currentBetaEthUnits(positions.values) + (candidateNetContribution * candidate.row.betaEth)) / capacity
    if (nextBetaBtc > config.maxPortfolioBetaBtcAbs + 1e-9 || nextBetaEth > config.maxPortfolioBetaEthAbs + 1e-9) {
        counters.rejectedBetaLimit += 1
        return false
    }

    counters.acceptedEntries += 1
    return true
}

private fun buildPortfolioProfile(
    kind: StrategyKind,
    stage: String,
    exchanges: List<String>,
    trades: List<TradeRecord>,
    telemetry: List<PortfolioTelemetryPoint>,
    counters: PortfolioConstraintCounters,
    config: ResearchConfig
): PortfolioProfileSnapshot {
    val samples = if (telemetry.isEmpty()) {
        listOf(PortfolioTelemetryPoint(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    } else {
        telemetry
    }
    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val grossSeries = samples.map { it.grossPositions.toDouble() }
    val longSeries = samples.map { it.longPositions.toDouble() }
    val shortSeries = samples.map { it.shortPositions.toDouble() }
    val grossExposureSeries = samples.map { it.grossExposureUnits }
    val netSeries = samples.map { abs(it.netExposureUnits) }
    val betaBtcSeries = samples.map { abs(it.betaBtcUnits) / capacity }
    val betaEthSeries = samples.map { abs(it.betaEthUnits) / capacity }
    val utilizationSeries = samples.map { it.grossExposureUnits / capacity }

    return PortfolioProfileSnapshot(
        strategyKind = kind.name.lowercase(),
        stage = stage,
        exchanges = exchanges,
        trades = trades.size,
        policyMaxConcurrentPositions = config.maxConcurrentPositions,
        policyMaxConcurrentLongs = effectiveLongCapacity(config),
        policyMaxConcurrentShorts = effectiveShortCapacity(config),
        policyMaxNetExposureFraction = config.maxNetExposureFraction.round(4),
        policyMaxAbsBetaBtc = config.maxPortfolioBetaBtcAbs.round(4),
        policyMaxAbsBetaEth = config.maxPortfolioBetaEthAbs.round(4),
        maxConcurrentPositions = samples.maxOfOrNull { it.grossPositions } ?: 0,
        maxConcurrentLongs = samples.maxOfOrNull { it.longPositions } ?: 0,
        maxConcurrentShorts = samples.maxOfOrNull { it.shortPositions } ?: 0,
        avgConcurrentPositions = mean(grossSeries).round(4),
        avgConcurrentLongs = mean(longSeries).round(4),
        avgConcurrentShorts = mean(shortSeries).round(4),
        maxGrossExposureUsd = ((samples.maxOfOrNull { it.grossExposureUnits } ?: 0.0) * config.notionalUsd).round(4),
        avgGrossExposureUsd = (mean(grossExposureSeries) * config.notionalUsd).round(4),
        maxNetExposureUsd = ((samples.maxOfOrNull { abs(it.netExposureUnits) } ?: 0.0) * config.notionalUsd).round(4),
        avgNetExposureUsd = (mean(netSeries) * config.notionalUsd).round(4),
        maxAbsNetExposureFraction = (netSeries.maxOrNull()?.div(capacity) ?: 0.0).round(4),
        avgAbsNetExposureFraction = (mean(netSeries) / capacity).round(4),
        maxAbsBetaBtc = (betaBtcSeries.maxOrNull() ?: 0.0).round(4),
        avgAbsBetaBtc = mean(betaBtcSeries).round(4),
        maxAbsBetaEth = (betaEthSeries.maxOrNull() ?: 0.0).round(4),
        avgAbsBetaEth = mean(betaEthSeries).round(4),
        avgCapacityUtilization = mean(utilizationSeries).round(4),
        maxCapacityUtilization = (utilizationSeries.maxOrNull() ?: 0.0).round(4),
        entryConstraints = PortfolioConstraintSnapshot(
            candidateEntries = counters.candidateEntries,
            acceptedEntries = counters.acceptedEntries,
            rejectedOpenSymbol = counters.rejectedOpenSymbol,
            rejectedGrossLimit = counters.rejectedGrossLimit,
            rejectedLongLimit = counters.rejectedLongLimit,
            rejectedShortLimit = counters.rejectedShortLimit,
            rejectedNetLimit = counters.rejectedNetLimit,
            rejectedBetaLimit = counters.rejectedBetaLimit
        )
    )
}

private fun buildOpenPosition(
    strategyName: String,
    kind: StrategyKind,
    candidate: EntryCandidate
): OpenPosition =
    OpenPosition(
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
        targetExposureFraction = candidate.targetExposureFraction,
        calibrationSamples = candidate.calibrationSamples,
        calibrationWinRate = candidate.calibrationWinRate,
        calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
        calibrationScope = candidate.calibrationScope,
        maxFavorableReturnFraction = 0.0
    )

private fun shouldRebalancePosition(
    existing: OpenPosition,
    candidate: EntryCandidate,
    config: ResearchConfig
): Boolean =
    existing.side == candidate.side &&
        candidate.row.barIndex > existing.entryRow.barIndex &&
        abs(candidate.targetExposureFraction - existing.targetExposureFraction) >= config.rebalanceTargetExposureStep

private fun simulateStrategyWithPortfolio(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    stage: String,
    bucketCandidates: (List<FeatureRow>, Instant) -> List<EntryCandidate>
): StrategySimulationResult {
    if (rows.isEmpty()) {
        return StrategySimulationResult(
            trades = emptyList(),
            portfolioProfile = buildPortfolioProfile(
                kind = kind,
                stage = stage,
                exchanges = emptyList(),
                trades = emptyList(),
                telemetry = emptyList(),
                counters = PortfolioConstraintCounters(),
                config = config
            )
        )
    }

    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val positions = mutableMapOf<String, OpenPosition>()
    val cooldownUntilBar = mutableMapOf<String, Int>()
    val trades = mutableListOf<TradeRecord>()
    val telemetry = mutableListOf<PortfolioTelemetryPoint>()
    val counters = PortfolioConstraintCounters()

    for (key in orderedKeys) {
        val exchange = key.first
        val currentTime = key.second
        val bucket = grouped[key].orEmpty()
        val rowBySymbol = bucket.associateBy { it.symbol }

        for ((positionKey, position) in positions.toMap()) {
            if (position.exchange != exchange) continue
            val current = rowBySymbol[position.symbol] ?: continue
            val updatedPosition = updateOpenPosition(position, current)
            positions[positionKey] = updatedPosition
            if (!shouldExitPosition(kind, updatedPosition, current, config)) continue
            trades += buildTradeRecord(updatedPosition, current, config)
            positions.remove(positionKey)
            cooldownUntilBar[positionKey] = current.barIndex + when (kind) {
                StrategyKind.TREND -> config.trendCooldownBars
                StrategyKind.REVERSION -> config.reversionCooldownBars
            }
        }

        val pendingCandidates = bucketCandidates(bucket, currentTime).toMutableList()
        while (pendingCandidates.isNotEmpty()) {
            val candidate = pendingCandidates.maxWithOrNull(
                compareBy<EntryCandidate>(
                    { portfolioAcceptanceScore(positions.values, it, config) },
                    { it.expectedNetEdgeBps },
                    { -it.row.barIndex },
                    { it.row.symbol }
                )
            ) ?: break
            pendingCandidates.remove(candidate)

            val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
            val existingPosition = positions[positionKey]
            if (existingPosition != null && shouldRebalancePosition(existingPosition, candidate, config)) {
                trades += buildTradeRecord(updateOpenPosition(existingPosition, candidate.row), candidate.row, config)
                positions.remove(positionKey)
                positions[positionKey] = buildOpenPosition(strategyName, kind, candidate)
                continue
            }
            if ((cooldownUntilBar[positionKey] ?: Int.MIN_VALUE) > candidate.row.barIndex) {
                continue
            }
            if (!canAddCandidateToPortfolio(positions, candidate, config, counters)) {
                continue
            }
            positions[positionKey] = buildOpenPosition(strategyName, kind, candidate)
        }

        telemetry += portfolioTelemetryPoint(positions.values)
    }

    val latestByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.maxByOrNull { it.time } }

    for ((positionKey, position) in positions.toMap()) {
        val current = latestByExchangeSymbol[position.exchange to position.symbol] ?: continue
        if (current.time == position.entryRow.time) continue
        trades += buildTradeRecord(updateOpenPosition(position, current), current, config)
        positions.remove(positionKey)
    }

    return StrategySimulationResult(
        trades = trades.sortedBy { it.entryTime },
        portfolioProfile = buildPortfolioProfile(
            kind = kind,
            stage = stage,
            exchanges = rows.map { it.exchange }.distinct().sorted(),
            trades = trades,
            telemetry = telemetry,
            counters = counters,
            config = config
        )
    )
}

private fun simulateStrategyResult(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null,
    stage: String = "backtest"
): StrategySimulationResult =
    simulateStrategyWithPortfolio(
        strategyName = strategyName,
        kind = kind,
        rows = rows,
        config = config,
        stage = stage
    ) { bucket, _ ->
        candidateRows(kind, bucket, config, calibrationState)
    }

fun simulateStrategy(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<TradeRecord> =
    simulateStrategyResult(strategyName, kind, rows, config, calibrationState).trades

private fun simulateStrategyWalkForwardResult(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    seedExamples: List<CalibrationExample> = emptyList(),
    stage: String = "forward"
): StrategySimulationResult {
    if (rows.isEmpty()) {
        return StrategySimulationResult(
            trades = emptyList(),
            portfolioProfile = buildPortfolioProfile(
                kind = kind,
                stage = stage,
                exchanges = emptyList(),
                trades = emptyList(),
                telemetry = emptyList(),
                counters = PortfolioConstraintCounters(),
                config = config
            )
        )
    }

    val calibrationExamples = buildCalibrationExamples(strategyName, kind, rows, config)
    val calibrationState = buildCalibrationState(seedExamples)
    val activeExamples = ArrayDeque<CalibrationExample>()
    seedExamples
        .sortedBy { it.availableAt }
        .forEach(activeExamples::addLast)
    var exampleIndex = 0

    return simulateStrategyWithPortfolio(
        strategyName = strategyName,
        kind = kind,
        rows = rows,
        config = config,
        stage = stage
    ) { bucket, currentTime ->
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
        candidateRows(kind, bucket, config, calibrationState)
    }
}

fun simulateStrategyWalkForward(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig
): List<TradeRecord> =
    simulateStrategyWalkForwardResult(strategyName, kind, rows, config).trades

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
                "trend_trailing_stop_vol_multiple" to config.trendTrailingStopVolMultiple.round(4),
                "reversion_trailing_stop_vol_multiple" to config.reversionTrailingStopVolMultiple.round(4),
                "trend_take_profit_vol_multiple" to config.trendTakeProfitVolMultiple.round(4),
                "reversion_take_profit_vol_multiple" to config.reversionTakeProfitVolMultiple.round(4),
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

private fun effectiveSliceCount(counts: List<Int>): Double {
    val total = counts.sum().toDouble()
    if (total <= 0.0) return 0.0
    val hhi = counts.sumOf { count ->
        val share = count.toDouble() / total
        share * share
    }
    if (hhi <= 0.0) return 0.0
    return (1.0 / hhi).round(4)
}

private fun summarizeTradeSlice(label: String, trades: List<TradeRecord>): StrategySliceSnapshot {
    val sorted = trades.sortedBy { it.entryTime }
    var equity = 1.0
    var peak = 1.0
    var maxDrawdown = 0.0
    sorted.forEach { trade ->
        equity *= (1.0 + trade.netReturnFraction)
        peak = max(peak, equity)
        maxDrawdown = max(maxDrawdown, 1.0 - (equity / peak))
    }
    return StrategySliceSnapshot(
        label = label,
        trades = sorted.size,
        winRate = (sorted.count { it.netReturnFraction > 0.0 }.toDouble() / max(sorted.size, 1).toDouble()).round(4),
        netReturnPct = ((equity - 1.0) * 100.0).round(4),
        maxDrawdownPct = (maxDrawdown * 100.0).round(4),
        avgEdgeAfterCostBps = mean(sorted.map { it.edgeAfterCostBps }).round(4),
        avgFillRatio = mean(sorted.map { it.fillRatio }).round(4)
    )
}

fun computeStrategyRobustness(
    kind: StrategyKind,
    trades: List<TradeRecord>
): StrategyRobustnessSnapshot? {
    if (trades.isEmpty()) return null

    val totalTrades = trades.size
    val multipleExchanges = trades.map { it.exchange }.distinct().size > 1
    val symbolSlices = trades.groupBy { it.exchange to it.symbol }
        .map { (key, bucket) ->
            val label = if (multipleExchanges) "${key.first}:${key.second}" else key.second
            summarizeTradeSlice(label, bucket)
        }
        .sortedWith(
            compareByDescending<StrategySliceSnapshot> { it.trades }
                .thenByDescending { it.avgEdgeAfterCostBps }
                .thenBy { it.label }
        )
    val regimeOrder = mapOf("calm" to 0, "normal" to 1, "stress" to 2)
    val regimeSlices = trades.groupBy(::tradeRegimeBucket)
        .map { (bucket, bucketTrades) -> summarizeTradeSlice(bucket, bucketTrades) }
        .sortedWith(
            compareBy<StrategySliceSnapshot> { regimeOrder[it.label] ?: Int.MAX_VALUE }
                .thenByDescending { it.trades }
        )

    val symbolCount = symbolSlices.size
    val regimeCount = regimeSlices.size
    val effectiveSymbolCount = effectiveSliceCount(symbolSlices.map { it.trades })
    val effectiveRegimeCount = effectiveSliceCount(regimeSlices.map { it.trades })
    val largestSymbolTradeShare =
        ((symbolSlices.maxOfOrNull { it.trades } ?: 0).toDouble() / max(totalTrades, 1).toDouble()).round(4)
    val largestRegimeTradeShare =
        ((regimeSlices.maxOfOrNull { it.trades } ?: 0).toDouble() / max(totalTrades, 1).toDouble()).round(4)
    val profitableSymbolShare = if (symbolCount == 0) {
        0.0
    } else {
        symbolSlices.count { it.netReturnPct > 0.0 && it.avgEdgeAfterCostBps > 0.0 }.toDouble() / symbolCount.toDouble()
    }.round(4)
    val profitableRegimeShare = if (regimeCount == 0) {
        0.0
    } else {
        regimeSlices.count { it.netReturnPct > 0.0 && it.avgEdgeAfterCostBps > 0.0 }.toDouble() / regimeCount.toDouble()
    }.round(4)
    val worstSymbolNetReturnPct = (symbolSlices.minOfOrNull { it.netReturnPct } ?: 0.0).round(4)
    val worstSymbolEdgeAfterCostBps = (symbolSlices.minOfOrNull { it.avgEdgeAfterCostBps } ?: 0.0).round(4)
    val worstRegimeNetReturnPct = (regimeSlices.minOfOrNull { it.netReturnPct } ?: 0.0).round(4)
    val worstRegimeEdgeAfterCostBps = (regimeSlices.minOfOrNull { it.avgEdgeAfterCostBps } ?: 0.0).round(4)

    val normalizedSymbolBreadth = if (symbolCount <= 1) {
        0.0
    } else {
        clamp((effectiveSymbolCount - 1.0) / (symbolCount.toDouble() - 1.0), 0.0, 1.0)
    }
    val normalizedRegimeBreadth = if (regimeCount <= 1) {
        0.0
    } else {
        clamp((effectiveRegimeCount - 1.0) / (regimeCount.toDouble() - 1.0), 0.0, 1.0)
    }
    val worstSlicePenalty = clamp(
        (max(0.0, -worstSymbolEdgeAfterCostBps) + max(0.0, -worstRegimeEdgeAfterCostBps)) / 16.0,
        0.0,
        1.0
    )
    val stabilityScore = (
        (normalizedSymbolBreadth * 30.0) +
            (normalizedRegimeBreadth * 25.0) +
            (profitableSymbolShare * 20.0) +
            (profitableRegimeShare * 15.0) +
            ((1.0 - worstSlicePenalty) * 10.0)
        ).round(4)

    return StrategyRobustnessSnapshot(
        strategyKind = kind.name.lowercase(),
        totalTrades = totalTrades,
        symbolCount = symbolCount,
        regimeCount = regimeCount,
        effectiveSymbolCount = effectiveSymbolCount,
        effectiveRegimeCount = effectiveRegimeCount,
        largestSymbolTradeShare = largestSymbolTradeShare,
        largestRegimeTradeShare = largestRegimeTradeShare,
        profitableSymbolShare = profitableSymbolShare,
        profitableRegimeShare = profitableRegimeShare,
        worstSymbolNetReturnPct = worstSymbolNetReturnPct,
        worstSymbolEdgeAfterCostBps = worstSymbolEdgeAfterCostBps,
        worstRegimeNetReturnPct = worstRegimeNetReturnPct,
        worstRegimeEdgeAfterCostBps = worstRegimeEdgeAfterCostBps,
        stabilityScore = stabilityScore,
        symbolSlices = symbolSlices,
        regimeSlices = regimeSlices
    )
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
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_universe_profiles (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT 'research',
                timeframe TEXT NOT NULL,
                candidate_symbols INTEGER NOT NULL DEFAULT 0,
                selected_symbols INTEGER NOT NULL DEFAULT 0,
                benchmark_symbols INTEGER NOT NULL DEFAULT 0,
                candidate_avg_tradable_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_tradable_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_observed_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_observed_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_depth_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_depth_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_volume_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_volume_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_observed_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_observed_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_tradable_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_tradable_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                deep_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                core_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                tradable_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                fragile_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("ALTER TABLE strategy_universe_profiles ADD COLUMN IF NOT EXISTS candidate_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_universe_profiles ADD COLUMN IF NOT EXISTS selected_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_run_at ON strategy_universe_profiles (run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_strategy_time ON strategy_universe_profiles (strategy_name, run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_stage_time ON strategy_universe_profiles (stage, run_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_portfolio_profiles (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                strategy_kind TEXT NOT NULL,
                stage TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                policy_max_concurrent_positions INTEGER NOT NULL DEFAULT 0,
                policy_max_concurrent_longs INTEGER NOT NULL DEFAULT 0,
                policy_max_concurrent_shorts INTEGER NOT NULL DEFAULT 0,
                policy_max_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                policy_max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                policy_max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_concurrent_positions INTEGER NOT NULL DEFAULT 0,
                max_concurrent_longs INTEGER NOT NULL DEFAULT 0,
                max_concurrent_shorts INTEGER NOT NULL DEFAULT 0,
                avg_concurrent_positions DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_concurrent_longs DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_concurrent_shorts DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_gross_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_gross_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_net_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_net_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_capacity_utilization DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_capacity_utilization DOUBLE PRECISION NOT NULL DEFAULT 0,
                trades INTEGER NOT NULL DEFAULT 0,
                candidate_entries INTEGER NOT NULL DEFAULT 0,
                accepted_entries INTEGER NOT NULL DEFAULT 0,
                rejected_open_symbol INTEGER NOT NULL DEFAULT 0,
                rejected_gross_limit INTEGER NOT NULL DEFAULT 0,
                rejected_long_limit INTEGER NOT NULL DEFAULT 0,
                rejected_short_limit INTEGER NOT NULL DEFAULT 0,
                rejected_net_limit INTEGER NOT NULL DEFAULT 0,
                rejected_beta_limit INTEGER NOT NULL DEFAULT 0,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_positions INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_longs INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_shorts INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_run_at ON strategy_portfolio_profiles (run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_strategy_time ON strategy_portfolio_profiles (strategy_name, run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_stage_time ON strategy_portfolio_profiles (stage, run_at DESC)")
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

fun persistUniverseProfiles(
    config: ResearchConfig,
    timeframe: String,
    strategyNames: Collection<String>,
    profiles: List<UniverseProfileSnapshot>,
    stage: String = "research"
) {
    if (strategyNames.isEmpty() || profiles.isEmpty()) return
    val fingerprint = researchConfigFingerprint(config)
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.prepareStatement(
            """
            INSERT INTO strategy_universe_profiles (
                strategy_name, exchange, stage, timeframe,
                candidate_symbols, selected_symbols, benchmark_symbols,
                candidate_avg_tradable_ratio, selected_avg_tradable_ratio,
                candidate_avg_observed_ratio, selected_avg_observed_ratio,
                candidate_avg_spread_bps, selected_avg_spread_bps,
                candidate_median_spread_bps, selected_median_spread_bps,
                candidate_avg_depth_usd, selected_avg_depth_usd,
                candidate_avg_volume_usd, selected_avg_volume_usd,
                candidate_observed_execution_share, selected_observed_execution_share,
                candidate_tradable_execution_share, selected_tradable_execution_share,
                deep_liquidity_symbols, core_liquidity_symbols,
                tradable_liquidity_symbols, fragile_liquidity_symbols,
                metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { stmt ->
            strategyNames.forEach { strategyName ->
                profiles.forEach { profile ->
                    val bucketMap = profile.liquidityBuckets.associateBy { it.label }
                    val metadataJson = gson.toJson(
                        mapOf(
                            "config_fingerprint" to fingerprint,
                            "candidate_scan_limit" to config.discoveryMaxSymbols,
                            "selected_universe_limit" to config.maxSymbols,
                            "selected_universe" to profile.selectedUniverse,
                            "top_candidates" to profile.topCandidates,
                            "liquidity_buckets" to profile.liquidityBuckets
                        )
                    )
                    stmt.setString(1, strategyName)
                    stmt.setString(2, profile.exchange)
                    stmt.setString(3, stage)
                    stmt.setString(4, timeframe)
                    stmt.setInt(5, profile.candidateSymbols)
                    stmt.setInt(6, profile.selectedSymbols)
                    stmt.setInt(7, profile.benchmarkSymbols)
                    stmt.setDouble(8, profile.candidateAvgRecentTradableRatio)
                    stmt.setDouble(9, profile.selectedAvgRecentTradableRatio)
                    stmt.setDouble(10, profile.candidateAvgRecentObservedRatio)
                    stmt.setDouble(11, profile.selectedAvgRecentObservedRatio)
                    stmt.setDouble(12, profile.candidateAvgRecentSpreadBps)
                    stmt.setDouble(13, profile.selectedAvgRecentSpreadBps)
                    stmt.setDouble(14, profile.candidateMedianRecentSpreadBps)
                    stmt.setDouble(15, profile.selectedMedianRecentSpreadBps)
                    stmt.setDouble(16, profile.candidateAvgRecentDepthUsd)
                    stmt.setDouble(17, profile.selectedAvgRecentDepthUsd)
                    stmt.setDouble(18, profile.candidateAvgRecentVolumeUsd)
                    stmt.setDouble(19, profile.selectedAvgRecentVolumeUsd)
                    stmt.setDouble(20, profile.candidateObservedExecutionShare)
                    stmt.setDouble(21, profile.selectedObservedExecutionShare)
                    stmt.setDouble(22, profile.candidateTradableExecutionShare)
                    stmt.setDouble(23, profile.selectedTradableExecutionShare)
                    stmt.setInt(24, bucketMap["deep"]?.symbols ?: 0)
                    stmt.setInt(25, bucketMap["core"]?.symbols ?: 0)
                    stmt.setInt(26, bucketMap["tradable"]?.symbols ?: 0)
                    stmt.setInt(27, bucketMap["fragile"]?.symbols ?: 0)
                    stmt.setString(28, metadataJson)
                    stmt.addBatch()
                }
            }
            stmt.executeBatch()
        }
    }
}

fun persistPortfolioProfiles(
    config: ResearchConfig,
    timeframe: String,
    strategyNames: Map<String, String>,
    profiles: Map<String, PortfolioProfileSnapshot>
) {
    if (strategyNames.isEmpty() || profiles.isEmpty()) return
    val fingerprint = researchConfigFingerprint(config)
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.prepareStatement(
            """
            INSERT INTO strategy_portfolio_profiles (
                strategy_name, strategy_kind, stage, timeframe,
                policy_max_concurrent_positions, policy_max_concurrent_longs, policy_max_concurrent_shorts,
                policy_max_net_exposure_fraction, policy_max_abs_beta_btc, policy_max_abs_beta_eth,
                max_concurrent_positions, max_concurrent_longs, max_concurrent_shorts,
                avg_concurrent_positions, avg_concurrent_longs, avg_concurrent_shorts,
                max_gross_exposure_usd, avg_gross_exposure_usd,
                max_net_exposure_usd, avg_net_exposure_usd,
                max_abs_net_exposure_fraction, avg_abs_net_exposure_fraction,
                max_abs_beta_btc, avg_abs_beta_btc,
                max_abs_beta_eth, avg_abs_beta_eth,
                avg_capacity_utilization, max_capacity_utilization,
                trades, candidate_entries, accepted_entries,
                rejected_open_symbol, rejected_gross_limit,
                rejected_long_limit, rejected_short_limit,
                rejected_net_limit, rejected_beta_limit,
                metadata
            ) VALUES (
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?, ?,
                ?, ?,
                ?, ?, ?, ?,
                CAST(? AS jsonb)
            )
            """.trimIndent()
        ).use { stmt ->
            profiles.forEach { (kind, profile) ->
                val strategyName = strategyNames[kind] ?: return@forEach
                val metadataJson = gson.toJson(
                    mapOf(
                        "config_fingerprint" to fingerprint,
                        "exchanges" to profile.exchanges,
                        "policy" to mapOf(
                            "max_concurrent_positions" to profile.policyMaxConcurrentPositions,
                            "max_concurrent_longs" to profile.policyMaxConcurrentLongs,
                            "max_concurrent_shorts" to profile.policyMaxConcurrentShorts,
                            "max_net_exposure_fraction" to profile.policyMaxNetExposureFraction,
                            "max_abs_beta_btc" to profile.policyMaxAbsBetaBtc,
                            "max_abs_beta_eth" to profile.policyMaxAbsBetaEth
                        )
                    )
                )
                stmt.setString(1, strategyName)
                stmt.setString(2, profile.strategyKind)
                stmt.setString(3, profile.stage)
                stmt.setString(4, timeframe)
                stmt.setInt(5, profile.policyMaxConcurrentPositions)
                stmt.setInt(6, profile.policyMaxConcurrentLongs)
                stmt.setInt(7, profile.policyMaxConcurrentShorts)
                stmt.setDouble(8, profile.policyMaxNetExposureFraction)
                stmt.setDouble(9, profile.policyMaxAbsBetaBtc)
                stmt.setDouble(10, profile.policyMaxAbsBetaEth)
                stmt.setInt(11, profile.maxConcurrentPositions)
                stmt.setInt(12, profile.maxConcurrentLongs)
                stmt.setInt(13, profile.maxConcurrentShorts)
                stmt.setDouble(14, profile.avgConcurrentPositions)
                stmt.setDouble(15, profile.avgConcurrentLongs)
                stmt.setDouble(16, profile.avgConcurrentShorts)
                stmt.setDouble(17, profile.maxGrossExposureUsd)
                stmt.setDouble(18, profile.avgGrossExposureUsd)
                stmt.setDouble(19, profile.maxNetExposureUsd)
                stmt.setDouble(20, profile.avgNetExposureUsd)
                stmt.setDouble(21, profile.maxAbsNetExposureFraction)
                stmt.setDouble(22, profile.avgAbsNetExposureFraction)
                stmt.setDouble(23, profile.maxAbsBetaBtc)
                stmt.setDouble(24, profile.avgAbsBetaBtc)
                stmt.setDouble(25, profile.maxAbsBetaEth)
                stmt.setDouble(26, profile.avgAbsBetaEth)
                stmt.setDouble(27, profile.avgCapacityUtilization)
                stmt.setDouble(28, profile.maxCapacityUtilization)
                stmt.setInt(29, profile.trades)
                stmt.setInt(30, profile.entryConstraints.candidateEntries)
                stmt.setInt(31, profile.entryConstraints.acceptedEntries)
                stmt.setInt(32, profile.entryConstraints.rejectedOpenSymbol)
                stmt.setInt(33, profile.entryConstraints.rejectedGrossLimit)
                stmt.setInt(34, profile.entryConstraints.rejectedLongLimit)
                stmt.setInt(35, profile.entryConstraints.rejectedShortLimit)
                stmt.setInt(36, profile.entryConstraints.rejectedNetLimit)
                stmt.setInt(37, profile.entryConstraints.rejectedBetaLimit)
                stmt.setString(38, metadataJson)
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
                "targetExposureFraction" to when (strategyFamily) {
                    "trend" -> signal.trendTargetExposureFraction
                    "reversion" -> signal.reversionTargetExposureFraction
                    else -> config.minTargetExposureFraction
                },
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
        val targetExposureFraction = candidate["targetExposureFraction"] as Double
        val size = max((config.notionalUsd * targetExposureFraction) / max(lastPrice, 1.0), 0.001).round(6)
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
            "targetExposureFraction" to targetExposureFraction,
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
    val reversionState: Double,
    val volumeRatio: Double,
    val expectedNetEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val targetExposureFraction: Double
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

data class StrategySliceSnapshot(
    val label: String,
    val trades: Int,
    val winRate: Double,
    val netReturnPct: Double,
    val maxDrawdownPct: Double,
    val avgEdgeAfterCostBps: Double,
    val avgFillRatio: Double
)

data class StrategyRobustnessSnapshot(
    val strategyKind: String,
    val totalTrades: Int,
    val symbolCount: Int,
    val regimeCount: Int,
    val effectiveSymbolCount: Double,
    val effectiveRegimeCount: Double,
    val largestSymbolTradeShare: Double,
    val largestRegimeTradeShare: Double,
    val profitableSymbolShare: Double,
    val profitableRegimeShare: Double,
    val worstSymbolNetReturnPct: Double,
    val worstSymbolEdgeAfterCostBps: Double,
    val worstRegimeNetReturnPct: Double,
    val worstRegimeEdgeAfterCostBps: Double,
    val stabilityScore: Double,
    val symbolSlices: List<StrategySliceSnapshot>,
    val regimeSlices: List<StrategySliceSnapshot>
)

data class CrossSectionalResearchResult(
    val config: ResearchConfig,
    val exchangeCatalog: List<ExchangeCatalogSnapshot>,
    val exchangePlans: List<ExchangePlan>,
    val candidateUniverse: Map<String, List<String>>,
    val discoveredUniverse: Map<String, List<String>>,
    val universeProfiles: List<UniverseProfileSnapshot>,
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
    val calibrationExampleCounts: Map<String, Int>,
    val backtestPortfolioProfiles: Map<String, PortfolioProfileSnapshot> = emptyMap(),
    val forwardPortfolioProfiles: Map<String, PortfolioProfileSnapshot> = emptyMap(),
    val backtestRobustness: Map<String, StrategyRobustnessSnapshot> = emptyMap(),
    val forwardRobustness: Map<String, StrategyRobustnessSnapshot> = emptyMap()
)

data class ResearchDataKey(
    val txGatewayUrl: String,
    val marketExchange: String,
    val executionExchangeOverride: String,
    val barMinutes: Int,
    val lookbackHours: Int,
    val discoveryMaxSymbols: Int,
    val maxSymbols: Int,
    val minBars: Int
)

data class ResearchDataContext(
    val key: ResearchDataKey,
    val exchangeCatalog: List<ExchangeCatalogSnapshot>,
    val exchangePlans: List<ExchangePlan>,
    val candidateUniverse: Map<String, List<String>>,
    val discoveredUniverse: Map<String, List<String>>,
    val universeProfiles: List<UniverseProfileSnapshot>,
    val bars: List<Bar>,
    val loadedAt: Instant
)

internal data class ResearchWindowSplit(
    val forwardCutoff: Instant?,
    val calibrationRows: List<FeatureRow>,
    val backtestCalibrationRows: List<FeatureRow>,
    val backtestRows: List<FeatureRow>,
    val forwardRows: List<FeatureRow>
)

data class StrategyAggregateSnapshot(
    val exchanges: List<String>,
    val trades: Int,
    val winRate: Double,
    val netReturnPct: Double,
    val maxDrawdownPct: Double,
    val sharpe: Double,
    val avgEdgeAfterCostBps: Double,
    val avgTotalCostBps: Double,
    val avgFillRatio: Double,
    val avgSubmitToFillMs: Double
)

data class StrategySearchFitness(
    val strategyKind: String,
    val score: Double,
    val passesFilters: Boolean,
    val rejectionReasons: List<String>,
    val backtest: StrategyAggregateSnapshot?,
    val forward: StrategyAggregateSnapshot?,
    val robustnessScore: Double = 0.0,
    val backtestRobustness: StrategyRobustnessSnapshot? = null,
    val forwardRobustness: StrategyRobustnessSnapshot? = null
)

data class CrossSectionalSearchCandidate(
    val rank: Int,
    val combinedScore: Double,
    val config: ResearchConfig,
    val dataKey: ResearchDataKey,
    val evaluatedAt: Instant,
    val barsLoaded: Int,
    val featureRows: Int,
    val calibrationRows: Int,
    val forwardRows: Int,
    val trendHoldHours: Double,
    val reversionHoldHours: Double,
    val trendFitness: StrategySearchFitness,
    val reversionFitness: StrategySearchFitness
)

data class CrossSectionalSearchResult(
    val searchConfig: CrossSectionalSearchConfig,
    val startedAt: Instant,
    val completedAt: Instant,
    val roundsCompleted: Int,
    val evaluatedConfigs: Int,
    val topTrendConfigs: List<CrossSectionalSearchCandidate>,
    val topReversionConfigs: List<CrossSectionalSearchCandidate>,
    val topCombinedConfigs: List<CrossSectionalSearchCandidate>
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
                abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
        },
        "trendShort" to rows.count {
            it.liquid &&
                it.trendShortRank <= config.topPerSide &&
                it.trendScore <= -config.trendEntryScore &&
                abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
        },
        "reversionLong" to rows.count {
            it.liquid &&
                it.reversionLongRank <= config.topPerSide &&
                it.reversionState <= it.reversionEntryLowerBound &&
                it.reversionScore > 0.0
        },
        "reversionShort" to rows.count {
            it.liquid &&
                it.reversionShortRank <= config.topPerSide &&
                it.reversionState >= it.reversionEntryUpperBound &&
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
                reversionState = it.row.reversionState.round(4),
                volumeRatio = it.row.volumeRatio.round(4),
                expectedNetEdgeBps = it.expectedNetEdgeBps.round(4),
                expectedRoundTripCostBps = it.expectedRoundTripCostBps.round(4),
                targetExposureFraction = it.targetExposureFraction.round(4)
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
                reversionState = it.row.reversionState.round(4),
                volumeRatio = it.row.volumeRatio.round(4),
                expectedNetEdgeBps = it.expectedNetEdgeBps.round(4),
                expectedRoundTripCostBps = it.expectedRoundTripCostBps.round(4),
                targetExposureFraction = it.targetExposureFraction.round(4)
            )
        }
    )
}

fun researchDataKey(config: ResearchConfig): ResearchDataKey =
    ResearchDataKey(
        txGatewayUrl = config.txGatewayUrl,
        marketExchange = config.marketExchange,
        executionExchangeOverride = config.executionExchangeOverride,
        barMinutes = config.barMinutes,
        lookbackHours = config.lookbackHours,
        discoveryMaxSymbols = config.discoveryMaxSymbols,
        maxSymbols = config.maxSymbols,
        minBars = config.minBars
    )

data class ResearchCoverageSnapshot(
    val symbol: String,
    val expectedBars: Int,
    val observedBars: Int,
    val finalizedBars: Int,
    val executionObservedBars: Int,
    val coverageRatio: Double,
    val finalizedRatio: Double,
    val executionObservedRatio: Double,
    val latestFeatureTime: Instant?,
    val finalizedThrough: Instant?,
    val latestExecutionObservedTime: Instant?,
    val latestFeatureLagSeconds: Long,
    val finalizedLagMinutes: Long?,
    val latestExecutionObservedLagSeconds: Long?
)

data class ResearchCoverageVerdict(
    val exchange: String,
    val requestedSymbols: Int,
    val requiredBars: Int,
    val minimumEligibleSymbols: Int,
    val eligibleSymbols: List<String>,
    val snapshots: List<ResearchCoverageSnapshot>,
    val passed: Boolean,
    val reason: String?
)

internal data class ResearchCoverageLagMetrics(
    val latestFeatureLagSeconds: Long,
    val finalizedLagMinutes: Long?,
    val latestExecutionObservedLagSeconds: Long?
)

data class CrossSectionalExchangeReadiness(
    val exchange: String,
    val marketAliases: List<String>,
    val discoveredSymbols: Int,
    val eligibleSymbols: Int,
    val requiredBars: Int,
    val minimumEligibleSymbols: Int,
    val passed: Boolean,
    val reason: String?,
    val sampleEligibleSymbols: List<String>,
    val sampleCoverageFailures: List<ResearchCoverageSnapshot>
)

data class CrossSectionalResearchReadiness(
    val config: ResearchConfig,
    val exchangeCatalog: List<ExchangeCatalogSnapshot>,
    val exchangePlans: List<ExchangePlan>,
    val exchangeCatalogMs: Long,
    val discoveryMs: Long,
    val discoveryCandidateLimit: Int,
    val requiredBars: Int,
    val minimumEligibleSymbols: Int,
    val passed: Boolean,
    val reason: String?,
    val exchanges: List<CrossSectionalExchangeReadiness>
)

class ResearchCoverageException(message: String) : IllegalStateException(message)

private data class PreparedResearchUniverse(
    val exchangeCatalog: List<ExchangeCatalogSnapshot>,
    val exchangeCatalogMs: Long,
    val exchangePlans: List<ExchangePlan>,
    val discoveryMs: Long,
    val discoveryCandidateLimit: Int,
    val requiredCoverageBars: Int,
    val discoveredUniverse: Map<String, List<String>>,
    val coverageVerdicts: Map<String, ResearchCoverageVerdict>
)

private fun expectedCoverageBars(lookbackHours: Int, barMinutes: Int, minBars: Int): Int {
    return requiredResearchWindowBars(
        lookbackHours = lookbackHours,
        barMinutes = barMinutes,
        minBars = minBars
    )
}

internal fun computeResearchCoverageSnapshotsFromUniverseSnapshot(
    exchange: String,
    snapshot: UniverseSnapshot,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int,
    minBars: Int,
    referenceTime: Instant = Instant.now()
): List<ResearchCoverageSnapshot> {
    if (symbols.isEmpty()) return emptyList()

    val expectedBars = expectedCoverageBars(
        lookbackHours = lookbackHours,
        barMinutes = barMinutes,
        minBars = minBars
    )
    val window = alignedResearchWindowBounds(
        lookbackHours = lookbackHours,
        barMinutes = barMinutes,
        now = referenceTime
    )

    return symbols
        .map(String::trim)
        .map(String::uppercase)
        .filter(String::isNotEmpty)
        .distinct()
        .mapNotNull { symbol ->
            val bars = snapshot.barsBySymbol[symbol].orEmpty()
                .filter { !it.time.isBefore(window.startInclusive) && it.time.isBefore(window.endExclusive) }
            if (bars.isEmpty()) {
                null
            } else {
                val latestFeatureTime = bars.maxOfOrNull { it.time }
                val finalizedThrough = bars.filter { it.finalized }.maxOfOrNull { it.time }
                val latestExecutionObservedTime = bars.filter { it.executionObserved }.maxOfOrNull { it.time }
                val lagMetrics = computeResearchCoverageLagMetrics(
                    latestFeatureTime = latestFeatureTime,
                    finalizedThrough = finalizedThrough,
                    latestExecutionObservedTime = latestExecutionObservedTime,
                    referenceTime = referenceTime,
                    bucketSeconds = window.bucketSeconds.toLong()
                )

                ResearchCoverageSnapshot(
                    symbol = symbol,
                    expectedBars = expectedBars,
                    observedBars = bars.size,
                    finalizedBars = bars.count { it.finalized },
                    executionObservedBars = bars.count { it.executionObserved },
                    coverageRatio = clamp(bars.size.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                    finalizedRatio = clamp(bars.count { it.finalized }.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                    executionObservedRatio = clamp(bars.count { it.executionObserved }.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                    latestFeatureTime = latestFeatureTime,
                    finalizedThrough = finalizedThrough,
                    latestExecutionObservedTime = latestExecutionObservedTime,
                    latestFeatureLagSeconds = lagMetrics.latestFeatureLagSeconds,
                    finalizedLagMinutes = lagMetrics.finalizedLagMinutes,
                    latestExecutionObservedLagSeconds = lagMetrics.latestExecutionObservedLagSeconds
                )
            }
        }
        .sortedBy { it.symbol }
}

internal fun computeResearchCoverageLagMetrics(
    latestFeatureTime: Instant?,
    finalizedThrough: Instant?,
    latestExecutionObservedTime: Instant?,
    referenceTime: Instant,
    bucketSeconds: Long
): ResearchCoverageLagMetrics = ResearchCoverageLagMetrics(
    latestFeatureLagSeconds = barCloseLagSeconds(
        bucketStartTime = latestFeatureTime,
        referenceTime = referenceTime,
        bucketSeconds = bucketSeconds
    ),
    finalizedLagMinutes = barCloseLagMinutes(
        bucketStartTime = finalizedThrough,
        referenceTime = referenceTime,
        bucketSeconds = bucketSeconds
    ),
    latestExecutionObservedLagSeconds = latestExecutionObservedTime?.let {
        barCloseLagSeconds(
            bucketStartTime = it,
            referenceTime = referenceTime,
            bucketSeconds = bucketSeconds
        )
    }
)

private fun computeResearchCoverageSnapshots(
    exchange: String,
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int,
    minBars: Int
): List<ResearchCoverageSnapshot> {
    if (symbols.isEmpty()) return emptyList()

    val normalizedSymbols = symbols.map(String::trim).map(String::uppercase).filter(String::isNotEmpty).distinct()
    if (normalizedSymbols.isEmpty()) return emptyList()
    val referenceTime = Instant.now()

    loadUniverseSnapshot(
        aliases = aliases,
        lookbackHours = lookbackHours,
        barMinutes = barMinutes
    )?.let { snapshot ->
        return computeResearchCoverageSnapshotsFromUniverseSnapshot(
            exchange = exchange,
            snapshot = snapshot,
            symbols = normalizedSymbols,
            lookbackHours = lookbackHours,
            barMinutes = barMinutes,
            minBars = minBars,
            referenceTime = referenceTime
        )
    }

    val aliasSql = sqlList(aliases)
    val symbolSql = sqlList(normalizedSymbols)
    val preferredAlias = aliases.firstOrNull().orEmpty()
    val window = alignedResearchWindowBounds(lookbackHours = lookbackHours, barMinutes = barMinutes)
    val bucketSeconds = window.bucketSeconds
    val expectedBars = expectedCoverageBars(lookbackHours = lookbackHours, barMinutes = barMinutes, minBars = minBars)
    val sql = """
        WITH minute_rows AS (
            SELECT DISTINCT ON (symbol, time)
                symbol,
                time,
                orderbook_observed,
                is_finalized,
                exchange
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= ?
              AND time < ?
              AND symbol IN ($symbolSql)
            ORDER BY
                symbol,
                time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END
        ),
        bucketed AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                orderbook_observed,
                is_finalized
            FROM minute_rows
        ),
        bucket_rollup AS (
            SELECT
                symbol,
                bucket_time,
                BOOL_OR(orderbook_observed) AS execution_observed,
                BOOL_AND(is_finalized) AS finalized
            FROM bucketed
            GROUP BY symbol, bucket_time
        ),
        feature_freshness AS (
            SELECT
                symbol,
                MAX(time) AS latest_feature_time,
                MAX(time) FILTER (WHERE is_finalized) AS finalized_through
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND symbol IN ($symbolSql)
            GROUP BY symbol
        )
        SELECT
            b.symbol,
            COUNT(*)::INTEGER AS observed_bars,
            COUNT(*) FILTER (WHERE b.finalized)::INTEGER AS finalized_bars,
            COUNT(*) FILTER (WHERE b.execution_observed)::INTEGER AS execution_observed_bars,
            MAX(b.bucket_time) FILTER (WHERE b.execution_observed) AS latest_execution_observed_time,
            f.latest_feature_time,
            f.finalized_through
        FROM bucket_rollup b
        JOIN feature_freshness f
          ON f.symbol = b.symbol
        GROUP BY b.symbol, f.latest_feature_time, f.finalized_through
        ORDER BY b.symbol ASC
    """.trimIndent()

    return buildList {
        pgConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(window.startInclusive))
                stmt.setTimestamp(2, Timestamp.from(window.endExclusive))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val observedBars = rs.getInt("observed_bars")
                        val finalizedBars = rs.getInt("finalized_bars")
                        val executionObservedBars = rs.getInt("execution_observed_bars")
                        val latestExecutionObservedTime = rs.getTimestamp("latest_execution_observed_time")?.toInstant()
                        val latestFeatureTime = rs.getTimestamp("latest_feature_time")?.toInstant()
                        val finalizedThrough = rs.getTimestamp("finalized_through")?.toInstant()
                        val lagMetrics = computeResearchCoverageLagMetrics(
                            latestFeatureTime = latestFeatureTime,
                            finalizedThrough = finalizedThrough,
                            latestExecutionObservedTime = latestExecutionObservedTime,
                            referenceTime = referenceTime,
                            bucketSeconds = bucketSeconds.toLong()
                        )

                        add(
                            ResearchCoverageSnapshot(
                                symbol = rs.getString("symbol"),
                                expectedBars = expectedBars,
                                observedBars = observedBars,
                                finalizedBars = finalizedBars,
                                executionObservedBars = executionObservedBars,
                                coverageRatio = clamp(observedBars.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                                finalizedRatio = clamp(finalizedBars.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                                executionObservedRatio = clamp(executionObservedBars.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                                latestFeatureTime = latestFeatureTime,
                                finalizedThrough = finalizedThrough,
                                latestExecutionObservedTime = latestExecutionObservedTime,
                                latestFeatureLagSeconds = lagMetrics.latestFeatureLagSeconds,
                                finalizedLagMinutes = lagMetrics.finalizedLagMinutes,
                                latestExecutionObservedLagSeconds = lagMetrics.latestExecutionObservedLagSeconds
                            )
                        )
                    }
                }
            }
        }
    }
}

internal fun buildResearchCoverageVerdict(
    exchange: String,
    symbols: List<String>,
    snapshots: List<ResearchCoverageSnapshot>,
    requiredBars: Int,
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int
): ResearchCoverageVerdict {
    val maxFeatureLagSeconds = effectiveCoverageMaxFeatureLagSeconds(
        coveragePolicy = coveragePolicy,
        barMinutes = barMinutes
    )
    val maxExecutionLagSeconds = effectiveCoverageMaxExecutionLagSeconds(
        coveragePolicy = coveragePolicy,
        barMinutes = barMinutes
    )
    val maxFinalizedLagMinutes = effectiveCoverageMaxFinalizedLagMinutes(
        coveragePolicy = coveragePolicy,
        barMinutes = barMinutes
    )
    val eligibleSymbols = snapshots
        .filter { snapshot ->
            snapshot.observedBars >= requiredBars &&
                snapshot.coverageRatio >= coveragePolicy.minCoverageRatio &&
                snapshot.finalizedRatio >= coveragePolicy.minFinalizedRatio &&
                snapshot.latestFeatureLagSeconds <= maxFeatureLagSeconds &&
                (snapshot.finalizedLagMinutes ?: Long.MAX_VALUE) <= maxFinalizedLagMinutes &&
                (
                    !coveragePolicy.requireExecutionObserved ||
                        (
                            snapshot.executionObservedRatio >= coveragePolicy.minExecutionObservedRatio &&
                                (snapshot.latestExecutionObservedLagSeconds ?: Long.MAX_VALUE) <= maxExecutionLagSeconds
                            )
                    )
        }
        .map { it.symbol }
        .sorted()

    val requestedSymbols = symbols.map(String::trim).count(String::isNotEmpty)
    val reason = when {
        requestedSymbols == 0 ->
            "coverage gate failed exchange=$exchange requestedSymbols=0 reason=no_candidate_universe"
        snapshots.isEmpty() ->
            "coverage gate failed exchange=$exchange requestedSymbols=$requestedSymbols reason=no_feature_rows"
        eligibleSymbols.size < coveragePolicy.minUniverseSymbols -> {
            val sample = snapshots.take(3).joinToString(";") { snapshot ->
                "${snapshot.symbol}:cov=${snapshot.coverageRatio.round(3)} " +
                    "fin=${snapshot.finalizedRatio.round(3)} " +
                    "exec=${snapshot.executionObservedRatio.round(3)} " +
                    "execLag=${snapshot.latestExecutionObservedLagSeconds ?: Long.MAX_VALUE}s " +
                    "featLag=${snapshot.latestFeatureLagSeconds}s " +
                    "finLag=${snapshot.finalizedLagMinutes ?: Long.MAX_VALUE}m"
            }
            "coverage gate failed exchange=$exchange eligible=${eligibleSymbols.size}/$requestedSymbols " +
                "requiredMinSymbols=${coveragePolicy.minUniverseSymbols} requiredBars=$requiredBars " +
                "thresholds=cov>=${coveragePolicy.minCoverageRatio.round(3)} fin>=${coveragePolicy.minFinalizedRatio.round(3)} " +
                "exec>=${coveragePolicy.minExecutionObservedRatio.round(3)} " +
                "execLag<=${maxExecutionLagSeconds}s featLag<=${maxFeatureLagSeconds}s finLag<=${maxFinalizedLagMinutes}m sample=[$sample]"
        }
        else -> null
    }

    return ResearchCoverageVerdict(
        exchange = exchange,
        requestedSymbols = requestedSymbols,
        requiredBars = requiredBars,
        minimumEligibleSymbols = coveragePolicy.minUniverseSymbols,
        eligibleSymbols = eligibleSymbols,
        snapshots = snapshots,
        passed = reason == null,
        reason = reason
    )
}

private fun prepareResearchUniverse(config: ResearchConfig): PreparedResearchUniverse {
    val (exchangeCatalog, exchangeCatalogMs) = timedMillis {
        fetchExchangeCatalog(config.txGatewayUrl)
    }
    val exchangePlans = buildExchangePlans(exchangeCatalog, config)
    val discoveryMaxSymbols = discoveryCandidateLimit(config.maxSymbols, config.discoveryMaxSymbols)
    val requiredCoverageBars = expectedCoverageBars(
        lookbackHours = config.lookbackHours,
        barMinutes = config.barMinutes,
        minBars = config.minBars
    )
    val coveragePolicy = crossSectionalPolicy().coverage

    val (discoveredUniverse, discoveryMs) = timedMillis {
        parallelMapBlocking(
            items = exchangePlans,
            maxParallelism = resolveResearchQueryParallelism(exchangePlans.size)
        ) { plan ->
            plan.exchange to discoverSymbols(
                txBase = config.txGatewayUrl,
                exchange = plan.exchange,
                aliases = plan.marketAliases,
                lookbackHours = config.lookbackHours,
                maxSymbols = discoveryMaxSymbols,
                minBars = config.minBars,
                barMinutes = config.barMinutes
            )
        }.toMap(LinkedHashMap())
    }

    val coverageVerdicts = parallelMapBlocking(
        items = exchangePlans,
        maxParallelism = resolveResearchQueryParallelism(exchangePlans.size)
    ) { plan ->
        val symbols = discoveredUniverse[plan.exchange].orEmpty()
        val snapshots = computeResearchCoverageSnapshots(
            exchange = plan.exchange,
            aliases = plan.marketAliases,
            symbols = symbols,
            lookbackHours = config.lookbackHours,
            barMinutes = config.barMinutes,
            minBars = config.minBars
        )
        plan.exchange to buildResearchCoverageVerdict(
            exchange = plan.exchange,
            symbols = symbols,
            snapshots = snapshots,
            requiredBars = requiredCoverageBars,
            coveragePolicy = coveragePolicy,
            barMinutes = config.barMinutes
        )
    }.toMap(LinkedHashMap())

    return PreparedResearchUniverse(
        exchangeCatalog = exchangeCatalog,
        exchangeCatalogMs = exchangeCatalogMs,
        exchangePlans = exchangePlans,
        discoveryMs = discoveryMs,
        discoveryCandidateLimit = discoveryMaxSymbols,
        requiredCoverageBars = requiredCoverageBars,
        discoveredUniverse = discoveredUniverse,
        coverageVerdicts = coverageVerdicts
    )
}

fun evaluateCrossSectionalReadiness(config: ResearchConfig): CrossSectionalResearchReadiness {
    val prepared = prepareResearchUniverse(config)
    val coveragePolicy = crossSectionalPolicy().coverage
    val exchanges = prepared.exchangePlans.map { plan ->
        val verdict = prepared.coverageVerdicts.getValue(plan.exchange)
        val failingSamples = verdict.snapshots
            .filterNot { it.symbol in verdict.eligibleSymbols }
            .sortedWith(
                compareBy<ResearchCoverageSnapshot> { it.coverageRatio }
                    .thenBy { it.finalizedRatio }
                    .thenBy { it.executionObservedRatio }
                    .thenByDescending { it.latestFeatureLagSeconds }
                    .thenBy { it.symbol }
            )
            .take(5)
        CrossSectionalExchangeReadiness(
            exchange = plan.exchange,
            marketAliases = plan.marketAliases,
            discoveredSymbols = prepared.discoveredUniverse[plan.exchange].orEmpty().size,
            eligibleSymbols = verdict.eligibleSymbols.size,
            requiredBars = verdict.requiredBars,
            minimumEligibleSymbols = verdict.minimumEligibleSymbols,
            passed = verdict.passed,
            reason = verdict.reason,
            sampleEligibleSymbols = verdict.eligibleSymbols.take(12),
            sampleCoverageFailures = failingSamples
        )
    }
    val failure = exchanges.firstOrNull { !it.passed }
    return CrossSectionalResearchReadiness(
        config = config,
        exchangeCatalog = prepared.exchangeCatalog,
        exchangePlans = prepared.exchangePlans,
        exchangeCatalogMs = prepared.exchangeCatalogMs,
        discoveryMs = prepared.discoveryMs,
        discoveryCandidateLimit = prepared.discoveryCandidateLimit,
        requiredBars = prepared.requiredCoverageBars,
        minimumEligibleSymbols = coveragePolicy.minUniverseSymbols,
        passed = failure == null,
        reason = failure?.reason,
        exchanges = exchanges
    )
}

fun loadResearchDataContext(config: ResearchConfig): ResearchDataContext {
    val prepared = prepareResearchUniverse(config)
    val exchangeCatalog = prepared.exchangeCatalog
    val exchangeCatalogMs = prepared.exchangeCatalogMs
    val exchangePlans = prepared.exchangePlans
    val discoveryMs = prepared.discoveryMs
    val requiredCoverageBars = prepared.requiredCoverageBars
    val discoveredUniverse = prepared.discoveredUniverse
    val coverageVerdicts = prepared.coverageVerdicts

    coverageVerdicts.values.forEach { verdict ->
        println(
            "Cross-sectional coverage exchange=${verdict.exchange} " +
                "eligible=${verdict.eligibleSymbols.size}/${verdict.requestedSymbols} " +
                "requiredBars=${verdict.requiredBars} passed=${verdict.passed} " +
                "reason=${verdict.reason ?: "ok"}"
        )
    }

    val coverageFailure = coverageVerdicts.values.firstOrNull { !it.passed }
    if (coverageFailure != null) {
        throw ResearchCoverageException(coverageFailure.reason ?: "coverage gate failed for ${coverageFailure.exchange}")
    }

    val candidateUniverse = coverageVerdicts.mapValues { (_, verdict) -> verdict.eligibleSymbols }

    val (researchBars, loadBarsMs) = timedMillis {
        exchangePlans.flatMap { plan ->
            val symbols = candidateUniverse[plan.exchange].orEmpty()
            loadBars(
                exchange = plan.exchange,
                aliases = plan.marketAliases,
                symbols = symbols,
                lookbackHours = config.lookbackHours,
                barMinutes = config.barMinutes
            )
        }
    }
    val rankedUniverseCandidates = rankResearchUniverseCandidates(researchBars, config)
    val refinedUniverse = selectResearchUniverseFromCandidates(rankedUniverseCandidates, config)
        .takeIf { it.isNotEmpty() }
        ?: candidateUniverse
    val refinedBars = researchBars.filter { bar ->
        bar.symbol in refinedUniverse[bar.exchange].orEmpty()
    }
    val universeProfiles = buildUniverseProfiles(rankedUniverseCandidates, refinedUniverse, config)
    val totalCandidateSymbols = candidateUniverse.values.sumOf { it.size }
    val totalSelectedSymbols = refinedUniverse.values.sumOf { it.size }
    refinedUniverse.forEach { (exchange, symbols) ->
        val candidateSymbols = candidateUniverse[exchange].orEmpty()
        println(
            "Cross-sectional universe refinement exchange=$exchange " +
                "candidates=${candidateSymbols.size} selected=${symbols.size} " +
                "symbols=${symbols.joinToString(",")}"
        )
    }
    println(
        "Cross-sectional data load exchangePlans=${exchangePlans.size} " +
            "candidateSymbols=$totalCandidateSymbols selectedSymbols=$totalSelectedSymbols " +
            "candidateBars=${researchBars.size} selectedBars=${refinedBars.size} " +
            "catalogMs=$exchangeCatalogMs discoveryMs=$discoveryMs loadBarsMs=$loadBarsMs"
    )

    return ResearchDataContext(
        key = researchDataKey(config),
        exchangeCatalog = exchangeCatalog,
        exchangePlans = exchangePlans,
        candidateUniverse = candidateUniverse,
        discoveredUniverse = refinedUniverse,
        universeProfiles = universeProfiles,
        bars = refinedBars,
        loadedAt = Instant.now()
    )
}

fun evaluateCrossSectionalResearch(
    context: ResearchDataContext,
    config: ResearchConfig
): CrossSectionalResearchResult =
    evaluateCrossSectionalResearchRows(
        context = context,
        researchFeatureRows = engineerFeatures(context.bars, config),
        config = config
    )

internal fun splitResearchWindow(
    researchFeatureRows: List<FeatureRow>,
    config: ResearchConfig
): ResearchWindowSplit {
    val forwardCutoff = researchFeatureRows.maxOfOrNull { it.time }
        ?.minus(config.forwardHours.toLong(), ChronoUnit.HOURS)
        ?: return ResearchWindowSplit(
            forwardCutoff = null,
            calibrationRows = researchFeatureRows,
            backtestCalibrationRows = emptyList(),
            backtestRows = researchFeatureRows,
            forwardRows = emptyList()
        )
    val calibrationRows = researchFeatureRows.filter { it.time.isBefore(forwardCutoff) }
    if (calibrationRows.isEmpty()) {
        return ResearchWindowSplit(
            forwardCutoff = forwardCutoff,
            calibrationRows = emptyList(),
            backtestCalibrationRows = emptyList(),
            backtestRows = emptyList(),
            forwardRows = researchFeatureRows.filter { !it.time.isBefore(forwardCutoff) }
        )
    }
    val calibrationStart = calibrationRows.minOf { it.time }
    val calibrationHours = max(Duration.between(calibrationStart, forwardCutoff).toHours(), 1L)
    val backtestCalibrationHours = min(
        max(calibrationHours / 2L, 1L),
        max(config.calibrationLookbackHours.toLong(), 1L)
    )
    val backtestCalibrationCutoff = calibrationStart.plus(backtestCalibrationHours, ChronoUnit.HOURS)
    return ResearchWindowSplit(
        forwardCutoff = forwardCutoff,
        calibrationRows = calibrationRows,
        backtestCalibrationRows = calibrationRows.filter { it.time.isBefore(backtestCalibrationCutoff) },
        backtestRows = calibrationRows.filter { !it.time.isBefore(backtestCalibrationCutoff) },
        forwardRows = researchFeatureRows.filter { !it.time.isBefore(forwardCutoff) }
    )
}

internal fun evaluateCrossSectionalResearchRows(
    context: ResearchDataContext,
    researchFeatureRows: List<FeatureRow>,
    config: ResearchConfig
): CrossSectionalResearchResult {
    val researchBars = context.bars
    val diagnostics = computeResearchDiagnostics(researchFeatureRows, config)
    val heuristicSignals = latestSignalSnapshots(researchFeatureRows, config)
    val windowSplit = splitResearchWindow(researchFeatureRows, config)
    val backtestSeedRows = if (windowSplit.backtestRows.isNotEmpty()) {
        windowSplit.backtestCalibrationRows
    } else {
        emptyList()
    }
    val backtestRows = if (windowSplit.backtestRows.isNotEmpty()) {
        windowSplit.backtestRows
    } else {
        windowSplit.calibrationRows
    }

    val trendStrategyName = "cross_section_beta_trend_v1"
    val reversionStrategyName = "cross_section_beta_reversion_v1"
    val strategyNamesByKind = mapOf(
        StrategyKind.TREND.name.lowercase() to trendStrategyName,
        StrategyKind.REVERSION.name.lowercase() to reversionStrategyName
    )
    val backtestCalibrationSeedExamples = buildCalibrationExamples(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = backtestSeedRows,
        config = config
    ) + buildCalibrationExamples(
        strategyName = reversionStrategyName,
        kind = StrategyKind.REVERSION,
        rows = backtestSeedRows,
        config = config
    )

    val trendBacktest = simulateStrategyWalkForwardResult(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = backtestRows,
        config = config,
        seedExamples = backtestCalibrationSeedExamples,
        stage = "backtest"
    )
    val reversionBacktest = simulateStrategyWalkForwardResult(
        strategyName = reversionStrategyName,
        kind = StrategyKind.REVERSION,
        rows = backtestRows,
        config = config,
        seedExamples = backtestCalibrationSeedExamples,
        stage = "backtest"
    )

    val backtestSummaries =
        buildStrategySummaries(
            config = config,
            strategyName = trendStrategyName,
            strategyKind = StrategyKind.TREND,
            trades = trendBacktest.trades,
            timeframe = "candle_${config.barMinutes}m",
            notes = "${config.barMinutes}m beta-adjusted cross-sectional trend with causal calibration gating"
        ) +
        buildStrategySummaries(
            config = config,
            strategyName = reversionStrategyName,
            strategyKind = StrategyKind.REVERSION,
            trades = reversionBacktest.trades,
            timeframe = "candle_${config.barMinutes}m",
            notes = "${config.barMinutes}m beta-adjusted cross-sectional mean reversion with causal calibration gating"
        )
    val backtestPortfolioProfiles = mapOf(
        StrategyKind.TREND.name.lowercase() to trendBacktest.portfolioProfile,
        StrategyKind.REVERSION.name.lowercase() to reversionBacktest.portfolioProfile
    )
    val backtestRobustness = mutableMapOf<String, StrategyRobustnessSnapshot>().apply {
        computeStrategyRobustness(StrategyKind.TREND, trendBacktest.trades)?.let {
            put(StrategyKind.TREND.name.lowercase(), it)
        }
        computeStrategyRobustness(StrategyKind.REVERSION, reversionBacktest.trades)?.let {
            put(StrategyKind.REVERSION.name.lowercase(), it)
        }
    }

    if (config.persistBacktest && backtestSummaries.isNotEmpty()) {
        persistBacktestSummaries(backtestSummaries)
        persistPortfolioProfiles(
            config = config,
            timeframe = "candle_${config.barMinutes}m",
            strategyNames = strategyNamesByKind,
            profiles = backtestPortfolioProfiles
        )
    }
    if ((config.persistBacktest || config.persistForward) && context.universeProfiles.isNotEmpty()) {
        persistUniverseProfiles(
            config = config,
            timeframe = "candle_${config.barMinutes}m",
            strategyNames = strategyNamesByKind.values,
            profiles = context.universeProfiles
        )
    }

    var latestSignals = heuristicSignals
    var forwardSummaries = emptyList<StrategySummary>()
    var calibrationRowsCount = 0
    var forwardRowsCount = 0
    var calibrationCounts = emptyMap<String, Int>()
    var forwardPortfolioProfiles = emptyMap<String, PortfolioProfileSnapshot>()
    var forwardRobustness = emptyMap<String, StrategyRobustnessSnapshot>()

    if (windowSplit.forwardCutoff != null) {
        val calibrationRows = windowSplit.calibrationRows
        val forwardRows = windowSplit.forwardRows
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
        val baselineMap = backtestSummaries.associateBy { Triple(it.strategyName, it.exchange, it.symbol) }

        val forwardTrend = simulateStrategyResult(
            strategyName = trendStrategyName,
            kind = StrategyKind.TREND,
            rows = forwardRows,
            config = config,
            calibrationState = forwardCalibrationState,
            stage = "forward"
        )
        val forwardReversion = simulateStrategyResult(
            strategyName = reversionStrategyName,
            kind = StrategyKind.REVERSION,
            rows = forwardRows,
            config = config,
            calibrationState = forwardCalibrationState,
            stage = "forward"
        )

        val forwardTrendTrades = forwardTrend.trades
        val forwardReversionTrades = forwardReversion.trades
        val forwardTrades = forwardTrendTrades + forwardReversionTrades
        forwardPortfolioProfiles = mapOf(
            StrategyKind.TREND.name.lowercase() to forwardTrend.portfolioProfile,
            StrategyKind.REVERSION.name.lowercase() to forwardReversion.portfolioProfile
        )
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
        forwardRobustness = mutableMapOf<String, StrategyRobustnessSnapshot>().apply {
            computeStrategyRobustness(StrategyKind.TREND, forwardTrendTrades)?.let {
                put(StrategyKind.TREND.name.lowercase(), it)
            }
            computeStrategyRobustness(StrategyKind.REVERSION, forwardReversionTrades)?.let {
                put(StrategyKind.REVERSION.name.lowercase(), it)
            }
        }

        latestSignals = latestSignalSnapshots(researchFeatureRows, config, forwardCalibrationState)

        if (config.persistForward && forwardTrades.isNotEmpty()) {
            persistForwardTelemetry(
                config = config,
                trades = forwardTrades,
                baselines = baselineMap,
                source = "alpha-analytics-service"
            )
            persistPortfolioProfiles(
                config = config,
                timeframe = "forward_${config.barMinutes}m",
                strategyNames = strategyNamesByKind,
                profiles = forwardPortfolioProfiles
            )
        }
    }

    return CrossSectionalResearchResult(
        config = config,
        exchangeCatalog = context.exchangeCatalog,
        exchangePlans = context.exchangePlans,
        candidateUniverse = context.candidateUniverse,
        discoveredUniverse = context.discoveredUniverse,
        universeProfiles = context.universeProfiles,
        barsLoaded = researchBars.size,
        featureRows = researchFeatureRows.size,
        diagnostics = diagnostics,
        heuristicSignals = heuristicSignals,
        latestSignals = latestSignals,
        backtestSummaries = backtestSummaries,
        forwardSummaries = forwardSummaries,
        forwardCutoff = windowSplit.forwardCutoff,
        calibrationRows = calibrationRowsCount,
        forwardRows = forwardRowsCount,
        calibrationExampleCounts = calibrationCounts,
        backtestPortfolioProfiles = backtestPortfolioProfiles,
        forwardPortfolioProfiles = forwardPortfolioProfiles,
        backtestRobustness = backtestRobustness,
        forwardRobustness = forwardRobustness
    )
}

fun runCrossSectionalResearch(config: ResearchConfig = ResearchConfig()): CrossSectionalResearchResult =
    evaluateCrossSectionalResearch(loadResearchDataContext(config), config)

private data class SearchMutation(
    val name: String,
    val group: String,
    val variants: (ResearchConfig) -> List<ResearchConfig>
)

private data class SearchEvaluation(
    val config: ResearchConfig,
    val result: CrossSectionalResearchResult,
    val trendFitness: StrategySearchFitness,
    val reversionFitness: StrategySearchFitness,
    val combinedScore: Double,
    val evaluatedAt: Instant
)

fun searchSafeConfig(config: ResearchConfig): ResearchConfig =
    config.copy(
        persistBacktest = false,
        persistForward = false,
        enablePaperOrders = false
    )

fun normalizeSearchConfig(searchConfig: CrossSectionalSearchConfig): CrossSectionalSearchConfig =
    searchConfig.copy(
        baseConfig = searchSafeConfig(searchConfig.baseConfig),
        beamWidth = max(1, searchConfig.beamWidth),
        rounds = max(1, searchConfig.rounds),
        maxEvaluations = max(1, searchConfig.maxEvaluations),
        leaderboardSize = max(1, searchConfig.leaderboardSize),
        minBacktestTrades = max(1, searchConfig.minBacktestTrades),
        minForwardTrades = max(1, searchConfig.minForwardTrades),
        minSearchFillRatio = clamp(searchConfig.minSearchFillRatio, 0.0, 1.0),
        maxSearchDrawdownPct = max(0.0, searchConfig.maxSearchDrawdownPct)
    )

fun isValidResearchConfig(config: ResearchConfig): Boolean {
    val warmupBars = max(config.betaLookbackBars, max(config.trendSlowBars, config.reversionLookbackBars))
    val minimumHours = max(
        config.forwardHours + 1,
        ((warmupBars * max(config.barMinutes, 1)) / 60) + 1
    )
    return config.barMinutes > 0 &&
        config.lookbackHours >= minimumHours &&
        config.forwardHours > 0 &&
        config.betaLookbackBars > 1 &&
        config.trendLookbackBars > 1 &&
        config.trendSlowBars > config.trendLookbackBars &&
        config.trendMediumBars > config.trendSlowBars &&
        config.trendLongBars > config.trendMediumBars &&
        config.reversionLookbackBars > 1 &&
        config.trendHoldBars > 0 &&
        config.reversionHoldBars > 0 &&
        config.topPerSide > 0 &&
        config.notionalUsd > 0.0 &&
        (config.maxSymbols == 0 || config.maxSymbols >= 2) &&
        config.discoveryMaxSymbols >= 0 &&
        config.minBars > 0 &&
        config.reversionZEntry > 0.0 &&
        config.reversionZExit >= 0.0 &&
        config.reversionZExit < config.reversionZEntry &&
        config.reversionEntryQuantile in 0.0..0.5 &&
        config.reversionExitQuantile in 0.0..0.5 &&
        config.reversionExitQuantile > config.reversionEntryQuantile &&
        config.reversionCrossSectionalWeight in 0.0..1.0 &&
        config.maxSpreadBps > 0.0 &&
        config.minDepthMultiple > 0.0 &&
        config.minFillRatio in 0.0..1.0 &&
        config.minVolumeRatio >= 0.0 &&
        config.maxVolumeRatio > config.minVolumeRatio &&
        config.maxVolRegime > 0.0 &&
        config.executionSafetyMarginBps >= 0.0 &&
        config.minExpectedNetEdgeBps >= 0.0 &&
        config.calibrationLookbackHours > 0 &&
        config.minCalibrationSamples > 0 &&
        config.strongCalibrationSamples >= config.minCalibrationSamples &&
        config.minCalibrationWinRate in 0.0..1.0 &&
        config.trendCooldownBars >= 0 &&
        config.reversionCooldownBars >= 0 &&
        config.trendTrailingStopVolMultiple >= 0.0 &&
        config.reversionTrailingStopVolMultiple >= 0.0 &&
        config.trendTakeProfitVolMultiple >= 0.0 &&
        config.reversionTakeProfitVolMultiple >= 0.0 &&
        config.minTargetExposureFraction > 0.0 &&
        config.maxTargetExposureFraction >= config.minTargetExposureFraction &&
        config.rebalanceTargetExposureStep >= 0.0 &&
        config.maxConcurrentPositions > 0 &&
        config.maxConcurrentLongs in 1..config.maxConcurrentPositions &&
        config.maxConcurrentShorts in 1..config.maxConcurrentPositions &&
        config.maxNetExposureFraction in 0.0..1.0 &&
        config.maxPortfolioBetaBtcAbs >= 0.0 &&
        config.maxPortfolioBetaEthAbs >= 0.0
}

private fun normalizeIntCandidates(
    current: Int,
    values: List<Int>,
    predicate: (Int) -> Boolean = { it > 0 }
): List<Int> =
    (listOf(current) + values)
        .filter(predicate)
        .distinct()
        .sorted()

private fun normalizeDoubleCandidates(
    current: Double,
    values: List<Double>,
    predicate: (Double) -> Boolean = { it > 0.0 }
): List<Double> =
    (listOf(current) + values)
        .filter { it.isFinite() && predicate(it) }
        .map { it.round(6) }
        .distinct()
        .sorted()

private fun <T> rotate(values: List<T>, offset: Int): List<T> {
    if (values.isEmpty()) return values
    val normalized = ((offset % values.size) + values.size) % values.size
    if (normalized == 0) return values
    return values.drop(normalized) + values.take(normalized)
}

private fun intMutation(
    name: String,
    group: String,
    values: List<Int>,
    current: (ResearchConfig) -> Int,
    apply: (ResearchConfig, Int) -> ResearchConfig,
    predicate: (Int) -> Boolean = { it > 0 }
): SearchMutation =
    SearchMutation(name, group) { config ->
        normalizeIntCandidates(current(config), values, predicate)
            .asSequence()
            .filter { it != current(config) }
            .map { apply(config, it) }
            .toList()
    }

private fun doubleMutation(
    name: String,
    group: String,
    values: List<Double>,
    current: (ResearchConfig) -> Double,
    apply: (ResearchConfig, Double) -> ResearchConfig,
    predicate: (Double) -> Boolean = { it > 0.0 }
): SearchMutation =
    SearchMutation(name, group) { config ->
        normalizeDoubleCandidates(current(config), values, predicate)
            .asSequence()
            .filter { abs(it - current(config)) > 1e-9 }
            .map { apply(config, it) }
            .toList()
    }

private fun buildSearchMutations(searchConfig: CrossSectionalSearchConfig): List<SearchMutation> =
    listOf(
        intMutation("barMinutes", "timeframe", searchConfig.barMinutes, { it.barMinutes }, { cfg, value -> cfg.copy(barMinutes = value) }),
        intMutation("lookbackHours", "timeframe", searchConfig.lookbackHours, { it.lookbackHours }, { cfg, value -> cfg.copy(lookbackHours = value) }),
        intMutation("forwardHours", "timeframe", searchConfig.forwardHours, { it.forwardHours }, { cfg, value -> cfg.copy(forwardHours = value) }),
        intMutation("betaLookbackBars", "timeframe", searchConfig.betaLookbackBars, { it.betaLookbackBars }, { cfg, value -> cfg.copy(betaLookbackBars = value) }),
        intMutation("maxSymbols", "universe_breadth", searchConfig.maxSymbols, { it.maxSymbols }, { cfg, value -> cfg.copy(maxSymbols = value) }, predicate = { it == 0 || it >= 2 }),
        intMutation(
            "discoveryMaxSymbols",
            "universe_breadth",
            searchConfig.discoveryMaxSymbols,
            { it.discoveryMaxSymbols },
            { cfg, value -> cfg.copy(discoveryMaxSymbols = value) },
            predicate = { it >= 0 }
        ),
        doubleMutation("trendEntryScore", "trend_signal", searchConfig.trendEntryScore, { it.trendEntryScore }, { cfg, value -> cfg.copy(trendEntryScore = value) }),
        doubleMutation(
            "trendMinFlowAlignment",
            "trend_signal",
            searchConfig.trendMinFlowAlignment,
            { it.trendMinFlowAlignment },
            { cfg, value -> cfg.copy(trendMinFlowAlignment = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation("trendLookbackBars", "trend_signal", searchConfig.trendLookbackBars, { it.trendLookbackBars }, { cfg, value -> cfg.copy(trendLookbackBars = value) }),
        intMutation("trendSlowBars", "trend_signal", searchConfig.trendSlowBars, { it.trendSlowBars }, { cfg, value -> cfg.copy(trendSlowBars = value) }),
        intMutation("trendMediumBars", "trend_signal", searchConfig.trendMediumBars, { it.trendMediumBars }, { cfg, value -> cfg.copy(trendMediumBars = value) }),
        intMutation("trendLongBars", "trend_signal", searchConfig.trendLongBars, { it.trendLongBars }, { cfg, value -> cfg.copy(trendLongBars = value) }),
        intMutation("trendHoldBars", "trend_signal", searchConfig.trendHoldBars, { it.trendHoldBars }, { cfg, value -> cfg.copy(trendHoldBars = value) }),
        intMutation(
            "trendCooldownBars",
            "trend_signal",
            searchConfig.trendCooldownBars,
            { it.trendCooldownBars },
            { cfg, value -> cfg.copy(trendCooldownBars = value) },
            predicate = { it >= 0 }
        ),
        doubleMutation("reversionZEntry", "reversion_signal", searchConfig.reversionZEntry, { it.reversionZEntry }, { cfg, value -> cfg.copy(reversionZEntry = value) }),
        doubleMutation(
            "reversionZExit",
            "reversion_signal",
            searchConfig.reversionZExit,
            { it.reversionZExit },
            { cfg, value -> cfg.copy(reversionZExit = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "reversionEntryQuantile",
            "reversion_signal",
            searchConfig.reversionEntryQuantile,
            { it.reversionEntryQuantile },
            { cfg, value -> cfg.copy(reversionEntryQuantile = value) },
            predicate = { it in 0.0..0.5 }
        ),
        doubleMutation(
            "reversionExitQuantile",
            "reversion_signal",
            searchConfig.reversionExitQuantile,
            { it.reversionExitQuantile },
            { cfg, value -> cfg.copy(reversionExitQuantile = value) },
            predicate = { it in 0.0..0.5 }
        ),
        doubleMutation(
            "reversionCrossSectionalWeight",
            "reversion_signal",
            searchConfig.reversionCrossSectionalWeight,
            { it.reversionCrossSectionalWeight },
            { cfg, value -> cfg.copy(reversionCrossSectionalWeight = value) },
            predicate = { it in 0.0..1.0 }
        ),
        doubleMutation(
            "reversionMaxContinuationPressure",
            "reversion_signal",
            searchConfig.reversionMaxContinuationPressure,
            { it.reversionMaxContinuationPressure },
            { cfg, value -> cfg.copy(reversionMaxContinuationPressure = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation("reversionLookbackBars", "reversion_signal", searchConfig.reversionLookbackBars, { it.reversionLookbackBars }, { cfg, value -> cfg.copy(reversionLookbackBars = value) }),
        intMutation("reversionHoldBars", "reversion_signal", searchConfig.reversionHoldBars, { it.reversionHoldBars }, { cfg, value -> cfg.copy(reversionHoldBars = value) }),
        intMutation(
            "reversionCooldownBars",
            "reversion_signal",
            searchConfig.reversionCooldownBars,
            { it.reversionCooldownBars },
            { cfg, value -> cfg.copy(reversionCooldownBars = value) },
            predicate = { it >= 0 }
        ),
        doubleMutation(
            "trendTrailingStopVolMultiple",
            "trend_exit",
            searchConfig.trendTrailingStopVolMultiple,
            { it.trendTrailingStopVolMultiple },
            { cfg, value -> cfg.copy(trendTrailingStopVolMultiple = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "trendTakeProfitVolMultiple",
            "trend_exit",
            searchConfig.trendTakeProfitVolMultiple,
            { it.trendTakeProfitVolMultiple },
            { cfg, value -> cfg.copy(trendTakeProfitVolMultiple = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "reversionTrailingStopVolMultiple",
            "reversion_exit",
            searchConfig.reversionTrailingStopVolMultiple,
            { it.reversionTrailingStopVolMultiple },
            { cfg, value -> cfg.copy(reversionTrailingStopVolMultiple = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "reversionTakeProfitVolMultiple",
            "reversion_exit",
            searchConfig.reversionTakeProfitVolMultiple,
            { it.reversionTakeProfitVolMultiple },
            { cfg, value -> cfg.copy(reversionTakeProfitVolMultiple = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation(
            "maxConcurrentPositions",
            "portfolio_policy",
            searchConfig.maxConcurrentPositions,
            { it.maxConcurrentPositions },
            { cfg, value -> cfg.copy(maxConcurrentPositions = value) }
        ),
        intMutation(
            "maxConcurrentLongs",
            "portfolio_policy",
            searchConfig.maxConcurrentLongs,
            { it.maxConcurrentLongs },
            { cfg, value -> cfg.copy(maxConcurrentLongs = value) }
        ),
        intMutation(
            "maxConcurrentShorts",
            "portfolio_policy",
            searchConfig.maxConcurrentShorts,
            { it.maxConcurrentShorts },
            { cfg, value -> cfg.copy(maxConcurrentShorts = value) }
        ),
        doubleMutation(
            "maxNetExposureFraction",
            "portfolio_policy",
            searchConfig.maxNetExposureFraction,
            { it.maxNetExposureFraction },
            { cfg, value -> cfg.copy(maxNetExposureFraction = value) },
            predicate = { it in 0.0..1.0 }
        ),
        doubleMutation(
            "minTargetExposureFraction",
            "portfolio_policy",
            searchConfig.minTargetExposureFraction,
            { it.minTargetExposureFraction },
            { cfg, value -> cfg.copy(minTargetExposureFraction = value) },
            predicate = { it > 0.0 }
        ),
        doubleMutation(
            "maxTargetExposureFraction",
            "portfolio_policy",
            searchConfig.maxTargetExposureFraction,
            { it.maxTargetExposureFraction },
            { cfg, value -> cfg.copy(maxTargetExposureFraction = value) },
            predicate = { it > 0.0 }
        ),
        doubleMutation(
            "rebalanceTargetExposureStep",
            "portfolio_policy",
            searchConfig.rebalanceTargetExposureStep,
            { it.rebalanceTargetExposureStep },
            { cfg, value -> cfg.copy(rebalanceTargetExposureStep = value) },
            predicate = { it >= 0.0 && it <= 1.0 }
        ),
        doubleMutation(
            "maxPortfolioBetaBtcAbs",
            "portfolio_policy",
            searchConfig.maxPortfolioBetaBtcAbs,
            { it.maxPortfolioBetaBtcAbs },
            { cfg, value -> cfg.copy(maxPortfolioBetaBtcAbs = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "maxPortfolioBetaEthAbs",
            "portfolio_policy",
            searchConfig.maxPortfolioBetaEthAbs,
            { it.maxPortfolioBetaEthAbs },
            { cfg, value -> cfg.copy(maxPortfolioBetaEthAbs = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation("topPerSide", "execution_liquidity", searchConfig.topPerSide, { it.topPerSide }, { cfg, value -> cfg.copy(topPerSide = value) }),
        doubleMutation("maxSpreadBps", "execution_liquidity", searchConfig.maxSpreadBps, { it.maxSpreadBps }, { cfg, value -> cfg.copy(maxSpreadBps = value) }),
        doubleMutation("minDepthMultiple", "execution_liquidity", searchConfig.minDepthMultiple, { it.minDepthMultiple }, { cfg, value -> cfg.copy(minDepthMultiple = value) }),
        doubleMutation(
            "minFillRatio",
            "execution_liquidity",
            searchConfig.minFillRatio,
            { it.minFillRatio },
            { cfg, value -> cfg.copy(minFillRatio = value) },
            predicate = { it in 0.0..1.0 }
        ),
        doubleMutation(
            "minVolumeRatio",
            "execution_liquidity",
            searchConfig.minVolumeRatio,
            { it.minVolumeRatio },
            { cfg, value -> cfg.copy(minVolumeRatio = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation("maxVolumeRatio", "execution_liquidity", searchConfig.maxVolumeRatio, { it.maxVolumeRatio }, { cfg, value -> cfg.copy(maxVolumeRatio = value) }),
        doubleMutation("maxVolRegime", "execution_liquidity", searchConfig.maxVolRegime, { it.maxVolRegime }, { cfg, value -> cfg.copy(maxVolRegime = value) }),
        doubleMutation(
            "executionSafetyMarginBps",
            "execution_liquidity",
            searchConfig.executionSafetyMarginBps,
            { it.executionSafetyMarginBps },
            { cfg, value -> cfg.copy(executionSafetyMarginBps = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "minExpectedNetEdgeBps",
            "execution_liquidity",
            searchConfig.minExpectedNetEdgeBps,
            { it.minExpectedNetEdgeBps },
            { cfg, value -> cfg.copy(minExpectedNetEdgeBps = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation("calibrationLookbackHours", "calibration", searchConfig.calibrationLookbackHours, { it.calibrationLookbackHours }, { cfg, value -> cfg.copy(calibrationLookbackHours = value) }),
        intMutation("minCalibrationSamples", "calibration", searchConfig.minCalibrationSamples, { it.minCalibrationSamples }, { cfg, value -> cfg.copy(minCalibrationSamples = value) }),
        intMutation("strongCalibrationSamples", "calibration", searchConfig.strongCalibrationSamples, { it.strongCalibrationSamples }, { cfg, value -> cfg.copy(strongCalibrationSamples = value) }),
        doubleMutation(
            "minCalibrationLowerBoundBps",
            "calibration",
            searchConfig.minCalibrationLowerBoundBps,
            { it.minCalibrationLowerBoundBps },
            { cfg, value -> cfg.copy(minCalibrationLowerBoundBps = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "minCalibrationWinRate",
            "calibration",
            searchConfig.minCalibrationWinRate,
            { it.minCalibrationWinRate },
            { cfg, value -> cfg.copy(minCalibrationWinRate = value) },
            predicate = { it in 0.0..1.0 }
        )
    )

fun aggregateStrategySnapshot(
    summaries: List<StrategySummary>,
    kind: StrategyKind
): StrategyAggregateSnapshot? {
    val relevant = summaries.filter {
        it.strategyKind == kind.name.lowercase() &&
            it.symbol == "ALL"
    }
    if (relevant.isEmpty()) return null
    val totalTrades = relevant.sumOf { it.trades }
    if (totalTrades <= 0) return null

    fun weighted(selector: (StrategySummary) -> Double): Double =
        relevant.sumOf { selector(it) * it.trades.toDouble() } / totalTrades.toDouble()

    return StrategyAggregateSnapshot(
        exchanges = relevant.map { it.exchange }.distinct().sorted(),
        trades = totalTrades,
        winRate = weighted { it.winRate }.round(4),
        netReturnPct = weighted { it.netReturnPct }.round(4),
        maxDrawdownPct = (relevant.maxOfOrNull { it.maxDrawdownPct } ?: 0.0).round(4),
        sharpe = weighted { it.sharpe }.round(4),
        avgEdgeAfterCostBps = weighted { it.avgEdgeAfterCostBps }.round(4),
        avgTotalCostBps = weighted { it.avgTotalCostBps }.round(4),
        avgFillRatio = weighted { it.avgFillRatio }.round(4),
        avgSubmitToFillMs = weighted { it.avgSubmitToFillMs }.round(4)
    )
}

fun computeStrategySearchFitness(
    searchConfig: CrossSectionalSearchConfig,
    kind: StrategyKind,
    backtest: StrategyAggregateSnapshot?,
    forward: StrategyAggregateSnapshot?,
    backtestRobustness: StrategyRobustnessSnapshot? = null,
    forwardRobustness: StrategyRobustnessSnapshot? = null
): StrategySearchFitness {
    val rejectionReasons = mutableListOf<String>()
    val backtestTrades = backtest?.trades ?: 0
    val forwardTrades = forward?.trades ?: 0
    val realizedFill = forward?.avgFillRatio ?: backtest?.avgFillRatio ?: 0.0
    val realizedDrawdown = max(backtest?.maxDrawdownPct ?: 0.0, forward?.maxDrawdownPct ?: 0.0)
    val realizedEdge = forward?.avgEdgeAfterCostBps ?: backtest?.avgEdgeAfterCostBps ?: 0.0
    val realizedReturn = forward?.netReturnPct ?: backtest?.netReturnPct ?: 0.0
    val primaryRobustness = backtestRobustness ?: forwardRobustness
    val profitableSymbolShare = primaryRobustness?.profitableSymbolShare ?: 1.0
    val profitableRegimeShare = primaryRobustness?.profitableRegimeShare ?: 1.0
    val largestSymbolTradeShare = primaryRobustness?.largestSymbolTradeShare ?: 0.0
    val largestRegimeTradeShare = primaryRobustness?.largestRegimeTradeShare ?: 0.0
    val worstSymbolEdgeAfterCostBps = listOfNotNull(
        backtestRobustness?.worstSymbolEdgeAfterCostBps,
        forwardRobustness?.worstSymbolEdgeAfterCostBps
    ).minOrNull() ?: 0.0
    val worstRegimeEdgeAfterCostBps = listOfNotNull(
        backtestRobustness?.worstRegimeEdgeAfterCostBps,
        forwardRobustness?.worstRegimeEdgeAfterCostBps
    ).minOrNull() ?: 0.0
    val robustnessScore = listOfNotNull(
        backtestRobustness?.stabilityScore,
        forwardRobustness?.stabilityScore
    ).takeIf { it.isNotEmpty() }
        ?.average()
        ?.round(4)
        ?: 0.0

    if (backtest == null) rejectionReasons += "missing_backtest"
    if (forward == null) rejectionReasons += "missing_forward"
    if (backtestTrades < searchConfig.minBacktestTrades) rejectionReasons += "backtest_trades<${
        searchConfig.minBacktestTrades
    }"
    if (forwardTrades < searchConfig.minForwardTrades) rejectionReasons += "forward_trades<${
        searchConfig.minForwardTrades
    }"
    if (realizedFill < searchConfig.minSearchFillRatio) rejectionReasons += "fill_ratio<${searchConfig.minSearchFillRatio.round(3)}"
    if (realizedDrawdown > searchConfig.maxSearchDrawdownPct) {
        rejectionReasons += "drawdown>${searchConfig.maxSearchDrawdownPct.round(2)}"
    }
    if (realizedEdge <= 0.0) rejectionReasons += "non_positive_edge"
    if (realizedReturn <= 0.0) rejectionReasons += "non_positive_return"
    if ((backtestRobustness?.symbolCount ?: 0) >= 2 && largestSymbolTradeShare > 0.85) {
        rejectionReasons += "symbol_concentration>${0.85.round(2)}"
    }
    if ((backtestRobustness?.regimeCount ?: 0) >= 2 && largestRegimeTradeShare > 0.9) {
        rejectionReasons += "regime_concentration>${0.9.round(2)}"
    }
    if ((backtestRobustness?.symbolCount ?: 0) >= 3 && profitableSymbolShare < 0.34) {
        rejectionReasons += "symbol_fragile"
    }
    if ((backtestRobustness?.regimeCount ?: 0) >= 3 && profitableRegimeShare < 0.34) {
        rejectionReasons += "regime_fragile"
    }

    val backtestReturnScore = (backtest?.netReturnPct ?: 0.0) * 1.8
    val forwardReturnScore = (forward?.netReturnPct ?: 0.0) * 3.4
    val backtestEdgeScore = (backtest?.avgEdgeAfterCostBps ?: 0.0) * 0.45
    val forwardEdgeScore = (forward?.avgEdgeAfterCostBps ?: 0.0) * 0.85
    val backtestSharpeScore = (backtest?.sharpe ?: 0.0) * 2.5
    val forwardSharpeScore = (forward?.sharpe ?: 0.0) * 5.0
    val tradeSupportScore =
        (min(backtestTrades.toDouble(), searchConfig.minBacktestTrades.toDouble()) / searchConfig.minBacktestTrades.toDouble()) * 10.0 +
            (min(forwardTrades.toDouble(), searchConfig.minForwardTrades.toDouble()) / searchConfig.minForwardTrades.toDouble()) * 12.0
    val costPenalty =
        ((backtest?.avgTotalCostBps ?: 0.0) * 0.08) +
            ((forward?.avgTotalCostBps ?: 0.0) * 0.16)
    val drawdownPenalty =
        ((backtest?.maxDrawdownPct ?: 0.0) * 0.45) +
            ((forward?.maxDrawdownPct ?: 0.0) * 1.1)
    val fillPenalty = max(0.0, searchConfig.minSearchFillRatio - realizedFill) * 45.0
    val driftPenalty = max(
        0.0,
        (backtest?.avgEdgeAfterCostBps ?: 0.0) - (forward?.avgEdgeAfterCostBps ?: backtest?.avgEdgeAfterCostBps ?: 0.0)
    ) * 0.4
    val robustnessBonus =
        ((backtestRobustness?.stabilityScore ?: 0.0) * 0.12) +
            ((forwardRobustness?.stabilityScore ?: 0.0) * 0.16)
    val concentrationPenalty =
        (max(0.0, largestSymbolTradeShare - 0.55) * 28.0) +
            (max(0.0, largestRegimeTradeShare - 0.65) * 18.0)
    val fragilityPenalty =
        (max(0.0, 0.55 - profitableSymbolShare) * 20.0) +
            (max(0.0, 0.55 - profitableRegimeShare) * 16.0)
    val worstSlicePenalty =
        (max(0.0, -worstSymbolEdgeAfterCostBps) * 0.8) +
            (max(0.0, -worstRegimeEdgeAfterCostBps) * 0.9)
    val missingPenalty =
        (if (backtest == null) 20.0 else 0.0) +
            (if (forward == null) 24.0 else 0.0)
    val rejectionPenalty = rejectionReasons.size.toDouble() * 9.0
    val score = (
        backtestReturnScore +
            forwardReturnScore +
            backtestEdgeScore +
            forwardEdgeScore +
            backtestSharpeScore +
            forwardSharpeScore +
            tradeSupportScore -
            costPenalty -
            drawdownPenalty -
            fillPenalty -
            driftPenalty -
            concentrationPenalty -
            fragilityPenalty -
            worstSlicePenalty -
            missingPenalty -
            rejectionPenalty +
            robustnessBonus
        ).round(4)

    return StrategySearchFitness(
        strategyKind = kind.name.lowercase(),
        score = score,
        passesFilters = rejectionReasons.isEmpty(),
        rejectionReasons = rejectionReasons,
        backtest = backtest,
        forward = forward,
        robustnessScore = robustnessScore,
        backtestRobustness = backtestRobustness,
        forwardRobustness = forwardRobustness
    )
}

fun researchConfigFingerprint(config: ResearchConfig): String =
    listOf(
        config.txGatewayUrl,
        config.marketExchange,
        config.executionExchangeOverride,
        config.barMinutes,
        config.lookbackHours,
        config.forwardHours,
        config.betaLookbackBars,
        config.trendLookbackBars,
        config.trendSlowBars,
        config.trendMediumBars,
        config.trendLongBars,
        config.reversionLookbackBars,
        config.trendHoldBars,
        config.reversionHoldBars,
        config.topPerSide,
        config.notionalUsd.round(4),
        config.maxSymbols,
        config.discoveryMaxSymbols,
        config.minBars,
        config.trendEntryScore.round(6),
        config.reversionZEntry.round(6),
        config.reversionZExit.round(6),
        config.reversionEntryQuantile.round(6),
        config.reversionExitQuantile.round(6),
        config.reversionCrossSectionalWeight.round(6),
        config.maxSpreadBps.round(6),
        config.minDepthMultiple.round(6),
        config.minFillRatio.round(6),
        config.minVolumeRatio.round(6),
        config.maxVolumeRatio.round(6),
        config.maxVolRegime.round(6),
        config.executionSafetyMarginBps.round(6),
        config.minExpectedNetEdgeBps.round(6),
        config.trendMinFlowAlignment.round(6),
        config.reversionMaxContinuationPressure.round(6),
        config.calibrationLookbackHours,
        config.minCalibrationSamples,
        config.strongCalibrationSamples,
        config.minCalibrationLowerBoundBps.round(6),
        config.minCalibrationWinRate.round(6),
        config.trendCooldownBars,
        config.reversionCooldownBars,
        config.trendTrailingStopVolMultiple.round(6),
        config.reversionTrailingStopVolMultiple.round(6),
        config.trendTakeProfitVolMultiple.round(6),
        config.reversionTakeProfitVolMultiple.round(6),
        config.minTargetExposureFraction.round(6),
        config.maxTargetExposureFraction.round(6),
        config.rebalanceTargetExposureStep.round(6),
        config.maxConcurrentPositions,
        config.maxConcurrentLongs,
        config.maxConcurrentShorts,
        config.maxNetExposureFraction.round(6),
        config.maxPortfolioBetaBtcAbs.round(6),
        config.maxPortfolioBetaEthAbs.round(6)
    ).joinToString("|")

private fun buildSearchEvaluation(
    searchConfig: CrossSectionalSearchConfig,
    result: CrossSectionalResearchResult,
    evaluatedAt: Instant = Instant.now()
): SearchEvaluation {
    val trendBacktest = aggregateStrategySnapshot(result.backtestSummaries, StrategyKind.TREND)
    val trendForward = aggregateStrategySnapshot(result.forwardSummaries, StrategyKind.TREND)
    val reversionBacktest = aggregateStrategySnapshot(result.backtestSummaries, StrategyKind.REVERSION)
    val reversionForward = aggregateStrategySnapshot(result.forwardSummaries, StrategyKind.REVERSION)
    val trendFitness = computeStrategySearchFitness(
        searchConfig = searchConfig,
        kind = StrategyKind.TREND,
        backtest = trendBacktest,
        forward = trendForward,
        backtestRobustness = result.backtestRobustness[StrategyKind.TREND.name.lowercase()],
        forwardRobustness = result.forwardRobustness[StrategyKind.TREND.name.lowercase()]
    )
    val reversionFitness = computeStrategySearchFitness(
        searchConfig = searchConfig,
        kind = StrategyKind.REVERSION,
        backtest = reversionBacktest,
        forward = reversionForward,
        backtestRobustness = result.backtestRobustness[StrategyKind.REVERSION.name.lowercase()],
        forwardRobustness = result.forwardRobustness[StrategyKind.REVERSION.name.lowercase()]
    )
    return SearchEvaluation(
        config = result.config,
        result = result,
        trendFitness = trendFitness,
        reversionFitness = reversionFitness,
        combinedScore = (trendFitness.score + reversionFitness.score).round(4),
        evaluatedAt = evaluatedAt
    )
}

private fun toSearchCandidate(
    evaluation: SearchEvaluation,
    rank: Int
): CrossSectionalSearchCandidate =
    CrossSectionalSearchCandidate(
        rank = rank,
        combinedScore = evaluation.combinedScore.round(4),
        config = evaluation.config,
        dataKey = researchDataKey(evaluation.config),
        evaluatedAt = evaluation.evaluatedAt,
        barsLoaded = evaluation.result.barsLoaded,
        featureRows = evaluation.result.featureRows,
        calibrationRows = evaluation.result.calibrationRows,
        forwardRows = evaluation.result.forwardRows,
        trendHoldHours = ((evaluation.config.barMinutes.toDouble() * evaluation.config.trendHoldBars.toDouble()) / 60.0).round(4),
        reversionHoldHours = ((evaluation.config.barMinutes.toDouble() * evaluation.config.reversionHoldBars.toDouble()) / 60.0).round(4),
        trendFitness = evaluation.trendFitness,
        reversionFitness = evaluation.reversionFitness
    )

private fun rankTrendEvaluations(evaluations: List<SearchEvaluation>): List<SearchEvaluation> =
    evaluations.sortedWith(
        compareByDescending<SearchEvaluation> { if (it.trendFitness.passesFilters) 1 else 0 }
            .thenByDescending { it.trendFitness.score }
            .thenByDescending { it.combinedScore }
            .thenByDescending { it.result.forwardRows }
            .thenByDescending { it.result.featureRows }
    )

private fun rankReversionEvaluations(evaluations: List<SearchEvaluation>): List<SearchEvaluation> =
    evaluations.sortedWith(
        compareByDescending<SearchEvaluation> { if (it.reversionFitness.passesFilters) 1 else 0 }
            .thenByDescending { it.reversionFitness.score }
            .thenByDescending { it.combinedScore }
            .thenByDescending { it.result.forwardRows }
            .thenByDescending { it.result.featureRows }
    )

private fun rankCombinedEvaluations(evaluations: List<SearchEvaluation>): List<SearchEvaluation> =
    evaluations.sortedWith(
        compareByDescending<SearchEvaluation> {
            listOf(it.trendFitness.passesFilters, it.reversionFitness.passesFilters).count { passed -> passed }
        }
            .thenByDescending { it.combinedScore }
            .thenByDescending { it.trendFitness.score }
            .thenByDescending { it.reversionFitness.score }
            .thenByDescending { it.result.forwardRows }
    )

private fun nextSearchSeeds(
    evaluations: List<SearchEvaluation>,
    searchConfig: CrossSectionalSearchConfig
): List<ResearchConfig> {
    val desiredSeeds = max(searchConfig.beamWidth * 2, searchConfig.beamWidth)
    return (
        rankTrendEvaluations(evaluations).take(searchConfig.beamWidth) +
            rankReversionEvaluations(evaluations).take(searchConfig.beamWidth) +
            rankCombinedEvaluations(evaluations).take(searchConfig.beamWidth)
        )
        .map { it.config }
        .distinctBy(::researchConfigFingerprint)
        .take(desiredSeeds)
}

private fun roundEvaluationBudget(
    searchConfig: CrossSectionalSearchConfig,
    evaluatedConfigs: Int,
    roundsCompleted: Int
): Int {
    val remainingBudget = max(searchConfig.maxEvaluations - evaluatedConfigs, 0)
    if (remainingBudget <= 0) return 0
    val remainingRounds = max(searchConfig.rounds - roundsCompleted, 1)
    val evenSplit = ceil(remainingBudget.toDouble() / remainingRounds.toDouble()).toInt()
    return min(remainingBudget, max(searchConfig.beamWidth, evenSplit))
}

private data class SearchMutationCandidate(
    val seedFingerprint: String,
    val mutationName: String,
    val mutationGroup: String,
    val config: ResearchConfig
)

private fun selectIntSearchValue(
    current: Int,
    values: List<Int>,
    preferred: List<Int> = emptyList(),
    predicate: (Int) -> Boolean = { true }
): Int {
    val candidates = values.filter(predicate).distinct()
    return (preferred + current).firstOrNull { candidate -> candidate in candidates }
        ?: candidates.firstOrNull()
        ?: current
}

private fun selectDoubleSearchValue(
    current: Double,
    values: List<Double>,
    preferred: List<Double> = emptyList(),
    predicate: (Double) -> Boolean = { true }
): Double {
    val candidates = values.filter(predicate).distinct()
    return (preferred + current).firstOrNull { candidate ->
        candidates.any { abs(it - candidate) < 1e-9 }
    }
        ?: candidates.firstOrNull()
        ?: current
}

private fun prioritizedSeedBarMinutes(searchConfig: CrossSectionalSearchConfig): List<Int> {
    val barMinutes = searchConfig.barMinutes.distinct()
    val nonBase = barMinutes.filter { it != searchConfig.baseConfig.barMinutes }
    val shortHorizons = listOf(5, 15, 30).filter { it in nonBase }
    val fallbackHorizons = nonBase
        .filter { it !in shortHorizons }
        .sortedBy { abs(it - searchConfig.baseConfig.barMinutes) }
    return shortHorizons + fallbackHorizons
}

private fun buildSeedAnchorConfig(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val base = searchConfig.baseConfig
    val sharedLookbackPreferences = when {
        barMinutes <= 5 -> listOf(240, 360, 720)
        barMinutes <= 30 -> listOf(360, 720, 1080)
        else -> listOf(720, 1080, 1440)
    }
    val sharedForwardPreferences = when {
        barMinutes <= 30 -> listOf(24, 48, 72)
        else -> listOf(48, 72, 96)
    }
    val sharedBetaPreferences = when {
        barMinutes <= 30 -> listOf(48, 72, 96)
        else -> listOf(72, 96, 168)
    }
    return base.copy(
        barMinutes = barMinutes,
        lookbackHours = selectIntSearchValue(base.lookbackHours, searchConfig.lookbackHours, sharedLookbackPreferences),
        forwardHours = selectIntSearchValue(base.forwardHours, searchConfig.forwardHours, sharedForwardPreferences),
        betaLookbackBars = selectIntSearchValue(base.betaLookbackBars, searchConfig.betaLookbackBars, sharedBetaPreferences),
        maxSymbols = selectIntSearchValue(base.maxSymbols, searchConfig.maxSymbols, listOf(searchConfig.maxSymbols.maxOrNull() ?: base.maxSymbols)),
        discoveryMaxSymbols = selectIntSearchValue(
            base.discoveryMaxSymbols,
            searchConfig.discoveryMaxSymbols,
            listOf(searchConfig.discoveryMaxSymbols.maxOrNull() ?: base.discoveryMaxSymbols),
            predicate = { it >= 0 }
        ),
        topPerSide = selectIntSearchValue(base.topPerSide, searchConfig.topPerSide, listOf(searchConfig.topPerSide.maxOrNull() ?: base.topPerSide)),
        maxConcurrentPositions = selectIntSearchValue(
            base.maxConcurrentPositions,
            searchConfig.maxConcurrentPositions,
            listOf(searchConfig.maxConcurrentPositions.maxOrNull() ?: base.maxConcurrentPositions)
        ),
        maxConcurrentLongs = selectIntSearchValue(
            base.maxConcurrentLongs,
            searchConfig.maxConcurrentLongs,
            listOf(searchConfig.maxConcurrentLongs.maxOrNull() ?: base.maxConcurrentLongs)
        ),
        maxConcurrentShorts = selectIntSearchValue(
            base.maxConcurrentShorts,
            searchConfig.maxConcurrentShorts,
            listOf(searchConfig.maxConcurrentShorts.maxOrNull() ?: base.maxConcurrentShorts)
        ),
        minTargetExposureFraction = selectDoubleSearchValue(
            base.minTargetExposureFraction,
            searchConfig.minTargetExposureFraction,
            listOf(searchConfig.minTargetExposureFraction.minOrNull() ?: base.minTargetExposureFraction),
            predicate = { it > 0.0 }
        ),
        maxTargetExposureFraction = selectDoubleSearchValue(
            base.maxTargetExposureFraction,
            searchConfig.maxTargetExposureFraction,
            listOf(searchConfig.maxTargetExposureFraction.maxOrNull() ?: base.maxTargetExposureFraction),
            predicate = { it > 0.0 }
        ),
        rebalanceTargetExposureStep = selectDoubleSearchValue(
            base.rebalanceTargetExposureStep,
            searchConfig.rebalanceTargetExposureStep,
            listOf(searchConfig.rebalanceTargetExposureStep.minOrNull() ?: base.rebalanceTargetExposureStep),
            predicate = { it >= 0.0 && it <= 1.0 }
        )
    )
}

private fun buildTrendSearchSeed(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val anchor = buildSeedAnchorConfig(searchConfig, barMinutes)
    val trendLookbackBars = selectIntSearchValue(
        anchor.trendLookbackBars,
        searchConfig.trendLookbackBars,
        preferred = when {
            barMinutes <= 5 -> listOf(4, 6, 12)
            barMinutes <= 30 -> listOf(6, 12, 18)
            else -> listOf(12, 18, 24)
        }
    )
    val trendSlowBars = selectIntSearchValue(
        anchor.trendSlowBars,
        searchConfig.trendSlowBars,
        preferred = when {
            barMinutes <= 5 -> listOf(12, 18, 24)
            barMinutes <= 30 -> listOf(18, 24, 36)
            else -> listOf(24, 36, 48)
        },
        predicate = { it > trendLookbackBars }
    )
    val trendMediumBars = selectIntSearchValue(
        anchor.trendMediumBars,
        searchConfig.trendMediumBars,
        preferred = when {
            barMinutes <= 5 -> listOf(24, 36, 48)
            barMinutes <= 30 -> listOf(48, 72, 96)
            else -> listOf(72, 96, 144)
        },
        predicate = { it > trendSlowBars }
    )
    val trendLongBars = selectIntSearchValue(
        anchor.trendLongBars,
        searchConfig.trendLongBars,
        preferred = when {
            barMinutes <= 5 -> listOf(48, 72, 96)
            barMinutes <= 30 -> listOf(96, 144, 192)
            else -> listOf(144, 192, 288)
        },
        predicate = { it > trendMediumBars }
    )
    return anchor.copy(
        trendLookbackBars = trendLookbackBars,
        trendSlowBars = trendSlowBars,
        trendMediumBars = trendMediumBars,
        trendLongBars = trendLongBars,
        trendHoldBars = selectIntSearchValue(
            anchor.trendHoldBars,
            searchConfig.trendHoldBars,
            preferred = if (barMinutes <= 30) listOf(1, 2, 3) else listOf(2, 3, 4)
        ),
        trendEntryScore = selectDoubleSearchValue(
            anchor.trendEntryScore,
            searchConfig.trendEntryScore,
            preferred = listOf(0.8, 1.0, 1.2),
            predicate = { it > 0.0 }
        ),
        trendMinFlowAlignment = selectDoubleSearchValue(
            anchor.trendMinFlowAlignment,
            searchConfig.trendMinFlowAlignment,
            preferred = listOf(0.0, 0.05, 0.08),
            predicate = { it >= 0.0 }
        ),
        trendTakeProfitVolMultiple = selectDoubleSearchValue(
            anchor.trendTakeProfitVolMultiple,
            searchConfig.trendTakeProfitVolMultiple,
            preferred = listOf(2.0, 1.5, 3.0),
            predicate = { it > 0.0 }
        ),
        trendTrailingStopVolMultiple = selectDoubleSearchValue(
            anchor.trendTrailingStopVolMultiple,
            searchConfig.trendTrailingStopVolMultiple,
            preferred = listOf(0.0, 0.75, 1.0),
            predicate = { it >= 0.0 }
        )
    )
}

private fun buildReversionSearchSeed(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val anchor = buildSeedAnchorConfig(searchConfig, barMinutes)
    val trendMediumBars = selectIntSearchValue(
        anchor.trendMediumBars,
        searchConfig.trendMediumBars,
        preferred = when {
            barMinutes <= 5 -> listOf(24, 36, 48)
            barMinutes <= 30 -> listOf(48, 72, 96)
            else -> listOf(72, 96, 144)
        },
        predicate = { it > anchor.trendSlowBars }
    )
    val trendLongBars = selectIntSearchValue(
        anchor.trendLongBars,
        searchConfig.trendLongBars,
        preferred = when {
            barMinutes <= 5 -> listOf(48, 72, 96)
            barMinutes <= 30 -> listOf(96, 144, 192)
            else -> listOf(144, 192, 288)
        },
        predicate = { it > trendMediumBars }
    )
    return anchor.copy(
        trendMediumBars = trendMediumBars,
        trendLongBars = trendLongBars,
        reversionLookbackBars = selectIntSearchValue(
            anchor.reversionLookbackBars,
            searchConfig.reversionLookbackBars,
            preferred = when {
                barMinutes <= 5 -> listOf(3, 4, 8)
                barMinutes <= 30 -> listOf(4, 8, 12)
                else -> listOf(8, 12, 16)
            }
        ),
        reversionHoldBars = selectIntSearchValue(
            anchor.reversionHoldBars,
            searchConfig.reversionHoldBars,
            preferred = if (barMinutes <= 30) listOf(1, 2, 3) else listOf(2, 3, 4)
        ),
        reversionZEntry = selectDoubleSearchValue(
            anchor.reversionZEntry,
            searchConfig.reversionZEntry,
            preferred = searchConfig.reversionZEntry.sorted(),
            predicate = { it > 0.0 }
        ),
        reversionZExit = selectDoubleSearchValue(
            anchor.reversionZExit,
            searchConfig.reversionZExit,
            preferred = searchConfig.reversionZExit.sorted(),
            predicate = { it >= 0.0 }
        ),
        reversionEntryQuantile = selectDoubleSearchValue(
            anchor.reversionEntryQuantile,
            searchConfig.reversionEntryQuantile,
            preferred = searchConfig.reversionEntryQuantile.sorted(),
            predicate = { it in 0.0..0.5 }
        ),
        reversionExitQuantile = selectDoubleSearchValue(
            anchor.reversionExitQuantile,
            searchConfig.reversionExitQuantile,
            preferred = searchConfig.reversionExitQuantile.sortedDescending(),
            predicate = { it in 0.0..0.5 }
        ),
        reversionCrossSectionalWeight = selectDoubleSearchValue(
            anchor.reversionCrossSectionalWeight,
            searchConfig.reversionCrossSectionalWeight,
            preferred = searchConfig.reversionCrossSectionalWeight.sorted(),
            predicate = { it in 0.0..1.0 }
        ),
        reversionMaxContinuationPressure = selectDoubleSearchValue(
            anchor.reversionMaxContinuationPressure,
            searchConfig.reversionMaxContinuationPressure,
            preferred = searchConfig.reversionMaxContinuationPressure.sorted(),
            predicate = { it >= 0.0 }
        ),
        reversionTrailingStopVolMultiple = selectDoubleSearchValue(
            anchor.reversionTrailingStopVolMultiple,
            searchConfig.reversionTrailingStopVolMultiple,
            preferred = listOf(0.75, 1.0, 1.5),
            predicate = { it > 0.0 }
        ),
        reversionTakeProfitVolMultiple = selectDoubleSearchValue(
            anchor.reversionTakeProfitVolMultiple,
            searchConfig.reversionTakeProfitVolMultiple,
            preferred = listOf(0.0, 1.0, 1.5),
            predicate = { it >= 0.0 }
        )
    )
}

private fun prioritizedMediumSeedBarMinutes(searchConfig: CrossSectionalSearchConfig): List<Int> {
    val barMinutes = searchConfig.barMinutes.distinct().filter { it >= 30 }
    val preferred = listOf(30, 60, 120, 240)
    return preferred.filter { it in barMinutes } + barMinutes.filter { it !in preferred }
}

private fun buildMediumTrendSearchSeed(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val anchor = buildSeedAnchorConfig(searchConfig, barMinutes)
    val trendLookbackBars = selectIntSearchValue(
        anchor.trendLookbackBars,
        searchConfig.trendLookbackBars,
        preferred = when {
            barMinutes <= 30 -> listOf(12, 18, 24)
            barMinutes <= 120 -> listOf(18, 24, 36)
            else -> listOf(24, 36, 48)
        }
    )
    val trendSlowBars = selectIntSearchValue(
        anchor.trendSlowBars,
        searchConfig.trendSlowBars,
        preferred = when {
            barMinutes <= 30 -> listOf(72, 96, 144)
            barMinutes <= 120 -> listOf(96, 144, 192)
            else -> listOf(144, 192, 288)
        },
        predicate = { it > trendLookbackBars }
    )
    val trendMediumBars = selectIntSearchValue(
        anchor.trendMediumBars,
        searchConfig.trendMediumBars,
        preferred = when {
            barMinutes <= 30 -> listOf(144, 192, 288)
            barMinutes <= 120 -> listOf(192, 288, 384)
            else -> listOf(288, 384, 576)
        },
        predicate = { it > trendSlowBars }
    )
    val trendLongBars = selectIntSearchValue(
        anchor.trendLongBars,
        searchConfig.trendLongBars,
        preferred = when {
            barMinutes <= 30 -> listOf(288, 384, 576)
            barMinutes <= 120 -> listOf(384, 576, 768)
            else -> listOf(576, 768, 1152)
        },
        predicate = { it > trendMediumBars }
    )
    return anchor.copy(
        lookbackHours = selectIntSearchValue(anchor.lookbackHours, searchConfig.lookbackHours, listOf(720, 1080, 1440)),
        forwardHours = selectIntSearchValue(anchor.forwardHours, searchConfig.forwardHours, listOf(48, 72, 96)),
        betaLookbackBars = selectIntSearchValue(anchor.betaLookbackBars, searchConfig.betaLookbackBars, listOf(72, 96, 168)),
        trendLookbackBars = trendLookbackBars,
        trendSlowBars = trendSlowBars,
        trendMediumBars = trendMediumBars,
        trendLongBars = trendLongBars,
        trendHoldBars = selectIntSearchValue(
            anchor.trendHoldBars,
            searchConfig.trendHoldBars,
            when {
                barMinutes <= 30 -> listOf(48, 72, 96, 144)
                barMinutes <= 120 -> listOf(24, 36, 48, 72)
                else -> listOf(12, 18, 24, 36)
            }
        ),
        topPerSide = selectIntSearchValue(anchor.topPerSide, searchConfig.topPerSide, listOf(4, 6, 10)),
        trendEntryScore = selectDoubleSearchValue(anchor.trendEntryScore, searchConfig.trendEntryScore, listOf(0.8, 1.0, 1.2), predicate = { it > 0.0 }),
        trendMinFlowAlignment = selectDoubleSearchValue(anchor.trendMinFlowAlignment, searchConfig.trendMinFlowAlignment, listOf(0.0, 0.05, 0.08), predicate = { it >= 0.0 }),
        trendTrailingStopVolMultiple = selectDoubleSearchValue(anchor.trendTrailingStopVolMultiple, searchConfig.trendTrailingStopVolMultiple, listOf(0.75, 1.0, 1.5), predicate = { it >= 0.0 }),
        trendTakeProfitVolMultiple = selectDoubleSearchValue(anchor.trendTakeProfitVolMultiple, searchConfig.trendTakeProfitVolMultiple, listOf(0.0, 1.5, 2.0, 3.0), predicate = { it >= 0.0 })
    )
}

private fun buildMediumReversionSearchSeed(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val anchor = buildSeedAnchorConfig(searchConfig, barMinutes)
    return anchor.copy(
        lookbackHours = selectIntSearchValue(anchor.lookbackHours, searchConfig.lookbackHours, listOf(720, 1080, 1440)),
        forwardHours = selectIntSearchValue(anchor.forwardHours, searchConfig.forwardHours, listOf(24, 48, 72)),
        betaLookbackBars = selectIntSearchValue(anchor.betaLookbackBars, searchConfig.betaLookbackBars, listOf(72, 96, 168)),
        reversionLookbackBars = selectIntSearchValue(
            anchor.reversionLookbackBars,
            searchConfig.reversionLookbackBars,
            preferred = when {
                barMinutes <= 30 -> listOf(4, 8, 12)
                barMinutes <= 120 -> listOf(8, 12, 16)
                else -> listOf(12, 16, 24)
            }
        ),
        reversionHoldBars = selectIntSearchValue(anchor.reversionHoldBars, searchConfig.reversionHoldBars, listOf(1, 2, 3, 4)),
        topPerSide = selectIntSearchValue(anchor.topPerSide, searchConfig.topPerSide, listOf(4, 6, 10)),
        reversionZEntry = selectDoubleSearchValue(anchor.reversionZEntry, searchConfig.reversionZEntry, listOf(1.0, 1.5, 1.8), predicate = { it > 0.0 }),
        reversionZExit = selectDoubleSearchValue(anchor.reversionZExit, searchConfig.reversionZExit, listOf(0.1, 0.2, 0.45), predicate = { it >= 0.0 }),
        reversionEntryQuantile = selectDoubleSearchValue(anchor.reversionEntryQuantile, searchConfig.reversionEntryQuantile, listOf(0.08, 0.12, 0.18), predicate = { it in 0.0..0.5 }),
        reversionExitQuantile = selectDoubleSearchValue(anchor.reversionExitQuantile, searchConfig.reversionExitQuantile, listOf(0.25, 0.35, 0.45), predicate = { it in 0.0..0.5 }),
        reversionCrossSectionalWeight = selectDoubleSearchValue(anchor.reversionCrossSectionalWeight, searchConfig.reversionCrossSectionalWeight, listOf(0.25, 0.4, 0.55), predicate = { it in 0.0..1.0 }),
        reversionMaxContinuationPressure = selectDoubleSearchValue(
            anchor.reversionMaxContinuationPressure,
            searchConfig.reversionMaxContinuationPressure,
            listOf(0.18, 0.24, 0.32),
            predicate = { it >= 0.0 }
        ),
        reversionTrailingStopVolMultiple = selectDoubleSearchValue(anchor.reversionTrailingStopVolMultiple, searchConfig.reversionTrailingStopVolMultiple, listOf(0.0, 0.75, 1.0, 1.5), predicate = { it >= 0.0 }),
        reversionTakeProfitVolMultiple = selectDoubleSearchValue(anchor.reversionTakeProfitVolMultiple, searchConfig.reversionTakeProfitVolMultiple, listOf(0.0, 0.5, 1.0, 1.5), predicate = { it >= 0.0 })
    )
}

private fun buildBreadthSearchSeed(searchConfig: CrossSectionalSearchConfig): ResearchConfig {
    val base = searchConfig.baseConfig
    return base.copy(
        maxSymbols = selectIntSearchValue(base.maxSymbols, searchConfig.maxSymbols, listOf(searchConfig.maxSymbols.maxOrNull() ?: base.maxSymbols)),
        discoveryMaxSymbols = selectIntSearchValue(
            base.discoveryMaxSymbols,
            searchConfig.discoveryMaxSymbols,
            listOf(searchConfig.discoveryMaxSymbols.maxOrNull() ?: base.discoveryMaxSymbols),
            predicate = { it >= 0 }
        ),
        topPerSide = selectIntSearchValue(base.topPerSide, searchConfig.topPerSide, listOf(searchConfig.topPerSide.maxOrNull() ?: base.topPerSide)),
        maxConcurrentPositions = selectIntSearchValue(
            base.maxConcurrentPositions,
            searchConfig.maxConcurrentPositions,
            listOf(searchConfig.maxConcurrentPositions.maxOrNull() ?: base.maxConcurrentPositions)
        ),
        maxConcurrentLongs = selectIntSearchValue(
            base.maxConcurrentLongs,
            searchConfig.maxConcurrentLongs,
            listOf(searchConfig.maxConcurrentLongs.maxOrNull() ?: base.maxConcurrentLongs)
        ),
        maxConcurrentShorts = selectIntSearchValue(
            base.maxConcurrentShorts,
            searchConfig.maxConcurrentShorts,
            listOf(searchConfig.maxConcurrentShorts.maxOrNull() ?: base.maxConcurrentShorts)
        ),
        minTargetExposureFraction = selectDoubleSearchValue(
            base.minTargetExposureFraction,
            searchConfig.minTargetExposureFraction,
            listOf(searchConfig.minTargetExposureFraction.minOrNull() ?: base.minTargetExposureFraction),
            predicate = { it > 0.0 }
        ),
        maxTargetExposureFraction = selectDoubleSearchValue(
            base.maxTargetExposureFraction,
            searchConfig.maxTargetExposureFraction,
            listOf(searchConfig.maxTargetExposureFraction.maxOrNull() ?: base.maxTargetExposureFraction),
            predicate = { it > 0.0 }
        ),
        rebalanceTargetExposureStep = selectDoubleSearchValue(
            base.rebalanceTargetExposureStep,
            searchConfig.rebalanceTargetExposureStep,
            listOf(searchConfig.rebalanceTargetExposureStep.minOrNull() ?: base.rebalanceTargetExposureStep),
            predicate = { it >= 0.0 && it <= 1.0 }
        ),
        maxNetExposureFraction = selectDoubleSearchValue(
            base.maxNetExposureFraction,
            searchConfig.maxNetExposureFraction,
            listOf(searchConfig.maxNetExposureFraction.maxOrNull() ?: base.maxNetExposureFraction),
            predicate = { it in 0.0..1.0 }
        ),
        maxPortfolioBetaBtcAbs = selectDoubleSearchValue(
            base.maxPortfolioBetaBtcAbs,
            searchConfig.maxPortfolioBetaBtcAbs,
            listOf(searchConfig.maxPortfolioBetaBtcAbs.maxOrNull() ?: base.maxPortfolioBetaBtcAbs),
            predicate = { it >= 0.0 }
        ),
        maxPortfolioBetaEthAbs = selectDoubleSearchValue(
            base.maxPortfolioBetaEthAbs,
            searchConfig.maxPortfolioBetaEthAbs,
            listOf(searchConfig.maxPortfolioBetaEthAbs.maxOrNull() ?: base.maxPortfolioBetaEthAbs),
            predicate = { it >= 0.0 }
        )
    )
}

private fun buildDiversifiedSearchSeeds(searchConfig: CrossSectionalSearchConfig): List<ResearchConfig> {
    val seedBarMinutes = prioritizedSeedBarMinutes(searchConfig)
        .ifEmpty { listOf(searchConfig.baseConfig.barMinutes) }
    val mediumSeedBarMinutes = prioritizedMediumSeedBarMinutes(searchConfig)
    return buildList {
        seedBarMinutes.forEach { barMinutes ->
            add(buildTrendSearchSeed(searchConfig, barMinutes))
            add(buildReversionSearchSeed(searchConfig, barMinutes))
        }
        mediumSeedBarMinutes.forEach { barMinutes ->
            add(buildMediumTrendSearchSeed(searchConfig, barMinutes))
            add(buildMediumReversionSearchSeed(searchConfig, barMinutes))
        }
        add(buildBreadthSearchSeed(searchConfig))
    }
        .filter(::isValidResearchConfig)
        .distinctBy(::researchConfigFingerprint)
}

private fun buildSeedMutationCandidates(
    seed: ResearchConfig,
    mutations: List<SearchMutation>,
    roundIndex: Int
): List<SearchMutationCandidate> {
    if (mutations.isEmpty()) return emptyList()

    val seedFingerprint = researchConfigFingerprint(seed)
    val preferredGroups = listOf(
        "timeframe",
        "universe_breadth",
        "trend_signal",
        "trend_exit",
        "reversion_signal",
        "reversion_exit",
        "portfolio_policy",
        "execution_liquidity",
        "calibration"
    )
    val discoveredGroups = mutations.map { it.group }.distinct()
    val orderedGroups = rotate(
        preferredGroups.filter { it in discoveredGroups } + discoveredGroups.filter { it !in preferredGroups },
        roundIndex
    )
    val seedOffset = abs(seedFingerprint.hashCode())
    val perGroupCandidates = orderedGroups.map { group ->
        val groupMutations = mutations.filter { it.group == group }
        val rotatedMutations = rotate(groupMutations, seedOffset + roundIndex)
        val mutationIterators = rotatedMutations.map { mutation ->
            mutation.variants(seed)
                .map { candidate ->
                    SearchMutationCandidate(
                        seedFingerprint = seedFingerprint,
                        mutationName = mutation.name,
                        mutationGroup = mutation.group,
                        config = candidate
                    )
                }
                .iterator()
        }.toMutableList()

        buildList {
            var progressed = true
            while (progressed) {
                progressed = false
                mutationIterators.forEach { iterator ->
                    if (iterator.hasNext()) {
                        add(iterator.next())
                        progressed = true
                    }
                }
            }
        }
    }

    val groupIterators = perGroupCandidates.map { it.iterator() }.toMutableList()
    return buildList {
        var progressed = true
        while (progressed) {
            progressed = false
            groupIterators.forEach { iterator ->
                if (iterator.hasNext()) {
                    add(iterator.next())
                    progressed = true
                }
            }
        }
    }
}

private fun buildSearchGeneration(
    seeds: List<ResearchConfig>,
    mutations: List<SearchMutation>,
    roundsCompleted: Int,
    limit: Int,
    evaluatedFingerprints: Set<String>
): List<SearchMutationCandidate> {
    if (limit <= 0 || seeds.isEmpty()) return emptyList()

    val rotatedSeeds = rotate(
        seeds.distinctBy(::researchConfigFingerprint),
        roundsCompleted
    )
    val seedIterators = rotatedSeeds.mapIndexed { index, seed ->
        buildList {
            add(
                SearchMutationCandidate(
                    seedFingerprint = researchConfigFingerprint(seed),
                    mutationName = "seed",
                    mutationGroup = "seed_anchor",
                    config = seed
                )
            )
            addAll(buildSeedMutationCandidates(seed, mutations, roundsCompleted + index))
        }.iterator()
    }.toMutableList()
    val seenFingerprints = evaluatedFingerprints.toMutableSet()

    return buildList {
        while (size < limit) {
            var progressed = false
            seedIterators.forEach { iterator ->
                while (iterator.hasNext() && size < limit) {
                    val candidate = iterator.next()
                    val safeConfig = searchSafeConfig(candidate.config)
                    if (!isValidResearchConfig(safeConfig)) {
                        continue
                    }
                    val fingerprint = researchConfigFingerprint(safeConfig)
                    if (!seenFingerprints.add(fingerprint)) {
                        continue
                    }
                    add(candidate.copy(config = safeConfig))
                    progressed = true
                    break
                }
            }
            if (!progressed) break
        }
    }
}

fun searchCrossSectionalResearch(
    searchConfig: CrossSectionalSearchConfig = CrossSectionalSearchConfig()
): CrossSectionalSearchResult {
    val contextCache = mutableMapOf<ResearchDataKey, ResearchDataContext>()
    return searchCrossSectionalResearch(searchConfig) { candidate ->
        val safeConfig = searchSafeConfig(candidate)
        val key = researchDataKey(safeConfig)
        val context = contextCache.getOrPut(key) { loadResearchDataContext(safeConfig) }
        evaluateCrossSectionalResearch(context, safeConfig)
    }
}

fun searchCrossSectionalResearch(
    searchConfig: CrossSectionalSearchConfig,
    evaluator: (ResearchConfig) -> CrossSectionalResearchResult
): CrossSectionalSearchResult {
    val normalizedSearch = normalizeSearchConfig(searchConfig)
    val startedAt = Instant.now()
    val mutations = buildSearchMutations(normalizedSearch)
    val evaluations = linkedMapOf<String, SearchEvaluation>()
    val attemptedFingerprints = linkedSetOf<String>()

    fun evaluateCandidate(candidate: ResearchConfig): SearchEvaluation? {
        val safeConfig = searchSafeConfig(candidate)
        if (!isValidResearchConfig(safeConfig)) return null
        val fingerprint = researchConfigFingerprint(safeConfig)
        if (!attemptedFingerprints.add(fingerprint)) {
            return evaluations[fingerprint]
        }
        if (evaluations.size >= normalizedSearch.maxEvaluations) return null
        evaluations[fingerprint]?.let { return it }
        val result = try {
            evaluator(safeConfig)
        } catch (e: ResearchCoverageException) {
            println(
                "Cross-sectional search skipped config fingerprint=$fingerprint " +
                    "reason=${e.message ?: "coverage gate failed"}"
            )
            return null
        }
        val evaluation = buildSearchEvaluation(normalizedSearch, result, Instant.now())
        evaluations[fingerprint] = evaluation
        return evaluation
    }

    evaluateCandidate(normalizedSearch.baseConfig)

    var roundsCompleted = 0
    var seeds = listOf(normalizedSearch.baseConfig) + buildDiversifiedSearchSeeds(normalizedSearch)
    while (
        roundsCompleted < normalizedSearch.rounds &&
        evaluations.size < normalizedSearch.maxEvaluations &&
        seeds.isNotEmpty()
    ) {
        val remainingBudget = normalizedSearch.maxEvaluations - evaluations.size
        val roundBudget = roundEvaluationBudget(normalizedSearch, evaluations.size, roundsCompleted)
        val generation = buildSearchGeneration(
            seeds = seeds,
            mutations = mutations,
            roundsCompleted = roundsCompleted,
            limit = min(remainingBudget, roundBudget),
            evaluatedFingerprints = evaluations.keys
        )

        if (generation.isEmpty()) break
        generation.forEach { evaluateCandidate(it.config) }
        val mutationCoverage = generation.groupingBy { it.mutationGroup }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(",") { (group, count) -> "$group:$count" }
        val mutationSample = generation.take(6)
            .joinToString(",") { "${it.mutationGroup}:${it.mutationName}" }
        println(
            "Cross-sectional search round=${roundsCompleted + 1} " +
                "seeds=${seeds.size} evaluated=${generation.size} total=${evaluations.size} " +
                "mutationCoverage=$mutationCoverage mutationSample=$mutationSample"
        )
        roundsCompleted += 1
        seeds = nextSearchSeeds(evaluations.values.toList(), normalizedSearch)
    }

    val rankedTrend = rankTrendEvaluations(evaluations.values.toList())
    val rankedReversion = rankReversionEvaluations(evaluations.values.toList())
    val rankedCombined = rankCombinedEvaluations(evaluations.values.toList())

    return CrossSectionalSearchResult(
        searchConfig = normalizedSearch,
        startedAt = startedAt,
        completedAt = Instant.now(),
        roundsCompleted = roundsCompleted,
        evaluatedConfigs = evaluations.size,
        topTrendConfigs = rankedTrend.take(normalizedSearch.leaderboardSize).mapIndexed { index, evaluation ->
            toSearchCandidate(evaluation, index + 1)
        },
        topReversionConfigs = rankedReversion.take(normalizedSearch.leaderboardSize).mapIndexed { index, evaluation ->
            toSearchCandidate(evaluation, index + 1)
        },
        topCombinedConfigs = rankedCombined.take(normalizedSearch.leaderboardSize).mapIndexed { index, evaluation ->
            toSearchCandidate(evaluation, index + 1)
        }
    )
}
