package org.datamancy.trading.analytics.crosssectional

import kotlinx.serialization.Serializable
import org.datamancy.trading.policy.ActiveTradingPolicy
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.max

internal fun crossSectionalPolicy() = ActiveTradingPolicy.current().research.crossSectional

internal fun crossSectionalSearchPolicy() = crossSectionalPolicy().search

enum class ResearchFeatureContext {
    PRICE,
    CROWDING,
    EXECUTION
}

private val defaultSignalFeatureContexts = listOf(
    ResearchFeatureContext.PRICE,
    ResearchFeatureContext.CROWDING
)
private val defaultExecutionFeatureContexts = listOf(ResearchFeatureContext.EXECUTION)
private val defaultPromotionFeatureContexts = listOf(
    ResearchFeatureContext.PRICE,
    ResearchFeatureContext.CROWDING,
    ResearchFeatureContext.EXECUTION
)

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


internal fun normalizeResearchFeatureContexts(
    requested: List<ResearchFeatureContext>,
    fallback: List<ResearchFeatureContext>
): Set<ResearchFeatureContext> =
    (requested.ifEmpty { fallback })
        .ifEmpty { fallback }
        .toCollection(LinkedHashSet())

internal fun contextNames(contexts: Set<ResearchFeatureContext>): List<String> =
    contexts.map { it.name.lowercase() }.sorted()

internal fun signalFeatureContexts(config: ResearchConfig): Set<ResearchFeatureContext> =
    normalizeResearchFeatureContexts(config.requiredSignalContexts, defaultSignalFeatureContexts)

internal fun executionFeatureContexts(config: ResearchConfig): Set<ResearchFeatureContext> =
    normalizeResearchFeatureContexts(config.requiredExecutionContexts, defaultExecutionFeatureContexts)

internal fun promotionFeatureContexts(config: ResearchConfig): Set<ResearchFeatureContext> =
    normalizeResearchFeatureContexts(config.requiredPromotionContexts, defaultPromotionFeatureContexts)

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
    val paperExecutionMode: String = crossSectionalPolicy().paperExecutionMode,
    val requiredSignalContexts: List<ResearchFeatureContext> = defaultSignalFeatureContexts,
    val requiredExecutionContexts: List<ResearchFeatureContext> = defaultExecutionFeatureContexts,
    val requiredPromotionContexts: List<ResearchFeatureContext> = defaultPromotionFeatureContexts
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
    val finalized: Boolean = true,
    val fundingRate: Double? = null,
    val openInterest: Double? = null,
    val assetContextObserved: Boolean = false,
    val latestCrowdingObservedTime: Instant? = null
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
    val executionObserved: Boolean = true,
    val fundingRate: Double = 0.0,
    val fundingChange: Double = 0.0,
    val openInterest: Double = 0.0,
    val openInterestNotionalUsd: Double = 0.0,
    val oiChange: Double = 0.0,
    val oiAcceleration: Double = 0.0,
    val assetContextObserved: Boolean = false
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
    val executionObserved: Boolean = true,
    val fundingRate: Double = 0.0,
    val fundingChange: Double = 0.0,
    val openInterest: Double = 0.0,
    val openInterestNotionalUsd: Double = 0.0,
    val oiChange: Double = 0.0,
    val oiAcceleration: Double = 0.0,
    val assetContextObserved: Boolean = false
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
    val executionObserved: Boolean = true,
    val fundingRate: Double = 0.0,
    val fundingZ: Double = 0.0,
    val fundingChangeZ: Double = 0.0,
    val openInterest: Double = 0.0,
    val openInterestNotionalUsd: Double = 0.0,
    val oiChange: Double = 0.0,
    val oiChangeZ: Double = 0.0,
    val oiAccelerationZ: Double = 0.0,
    val oiNotionalZ: Double = 0.0,
    val crowdingScore: Double = 0.0,
    val participationScore: Double = 0.0,
    val assetContextObserved: Boolean = false
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
    val mediumTrendScore: Double,
    val trendConfirmationScore: Double,
    val trendPersistence: Double,
    val trendPullback: Double,
    val trendExhaustion: Double,
    val trendScore: Double,
    val breadth: Double,
    val spreadBps: Double,
    val depthUsd: Double,
    val imbalance: Double,
    val flowSignal: Double,
    val volumeRatio: Double,
    val volRegime: Double,
    val expectedNetEdgeBps: Double,
    val targetExposureFraction: Double,
    val calibrationSamples: Int,
    val calibrationLowerBoundBps: Double,
    val liquid: Boolean,
    val action: String,
    val fundingZ: Double = 0.0,
    val oiChangeZ: Double = 0.0,
    val crowdingScore: Double = 0.0,
    val participationScore: Double = 0.0
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

internal data class ExecutionProxyProfile(
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

internal data class PortfolioConstraintCounters(
    var candidateEntries: Int = 0,
    var acceptedEntries: Int = 0,
    var rejectedOpenSymbol: Int = 0,
    var rejectedGrossLimit: Int = 0,
    var rejectedLongLimit: Int = 0,
    var rejectedShortLimit: Int = 0,
    var rejectedNetLimit: Int = 0,
    var rejectedBetaLimit: Int = 0
)

internal data class PortfolioTelemetryPoint(
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

internal data class StrategySimulationResult(
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

data class ResearchSeedSnapshot(
    val time: String,
    val symbol: String,
    val side: Int,
    val trendScore: Double,
    val flowSignal: Double,
    val residualZ: Double,
    val residualCrossSectionalZ: Double,
    val volumeRatio: Double,
    val expectedNetEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val targetExposureFraction: Double,
    val fundingZ: Double = 0.0,
    val oiChangeZ: Double = 0.0,
    val crowdingScore: Double = 0.0
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
    val topTrendSeeds: List<ResearchSeedSnapshot>
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
    val minBars: Int,
    val signalContexts: List<String>,
    val executionContexts: List<String>,
    val promotionContexts: List<String>
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
    val score: Double,
    val config: ResearchConfig,
    val dataKey: ResearchDataKey,
    val evaluatedAt: Instant,
    val barsLoaded: Int,
    val featureRows: Int,
    val calibrationRows: Int,
    val forwardRows: Int,
    val trendHoldHours: Double,
    val trendFitness: StrategySearchFitness
)

data class CrossSectionalSearchResult(
    val searchConfig: CrossSectionalSearchConfig,
    val startedAt: Instant,
    val completedAt: Instant,
    val roundsCompleted: Int,
    val evaluatedConfigs: Int,
    val topTrendConfigs: List<CrossSectionalSearchCandidate>
)

fun computeResearchDiagnostics(
    rows: List<FeatureRow>,
    config: ResearchConfig
): ResearchDiagnostics {
    val groupedFeatureBuckets = rows.groupBy { it.exchange to it.time }
    val warmupFloor = researchWarmupBars(config)
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
        }
    )

    val trendSeeds = groupedFeatureBuckets.values.flatMap { seedCandidateRows(StrategyKind.TREND, it, config) }

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
            "trend" to trendSeeds.size
        ),
        topTrendSeeds = trendSeeds.sortedByDescending { it.expectedNetEdgeBps }.take(8).map {
            ResearchSeedSnapshot(
                time = it.row.time.toString(),
                symbol = it.row.symbol,
                side = it.side,
                trendScore = it.row.trendScore.round(4),
                flowSignal = it.row.flowSignal.round(4),
                residualZ = it.row.residualZ.round(4),
                residualCrossSectionalZ = it.row.residualCrossSectionalZ.round(4),
                volumeRatio = it.row.volumeRatio.round(4),
                expectedNetEdgeBps = it.expectedNetEdgeBps.round(4),
                expectedRoundTripCostBps = it.expectedRoundTripCostBps.round(4),
                targetExposureFraction = it.targetExposureFraction.round(4),
                fundingZ = it.row.fundingZ.round(4),
                oiChangeZ = it.row.oiChangeZ.round(4),
                crowdingScore = it.row.crowdingScore.round(4)
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
        minBars = config.minBars,
        signalContexts = contextNames(signalFeatureContexts(config)),
        executionContexts = contextNames(executionFeatureContexts(config)),
        promotionContexts = contextNames(promotionFeatureContexts(config))
    )
