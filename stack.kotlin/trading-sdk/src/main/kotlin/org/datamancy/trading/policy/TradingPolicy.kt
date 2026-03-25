package org.datamancy.trading.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@DslMarker
annotation class TradingPolicyDsl

@Serializable
enum class UniverseSelectionMode {
    EXCHANGE_CATALOG,
    STATIC
}

@Serializable
enum class RequirementLevel {
    REQUIRED,
    OPTIONAL,
    DISABLED
}

@Serializable
data class TradingPolicy(
    val version: Int = 1,
    val venues: Map<String, VenuePolicy> = emptyMap(),
    val research: ResearchPolicy = ResearchPolicy(),
    val execution: ExecutionPolicy = ExecutionPolicy(),
    val risk: RiskPolicy = RiskPolicy(),
    val promotion: PromotionPolicy = PromotionPolicy(),
    val observability: ObservabilityPolicy = ObservabilityPolicy()
) {
    fun venue(name: String): VenuePolicy =
        venues[name] ?: error("Trading policy venue '$name' is not defined")
}

@Serializable
data class VenuePolicy(
    val venueId: String,
    val exchangeId: String,
    val enabled: Boolean = true,
    val mainnet: Boolean = true,
    val websocketUrl: String,
    val infoUrl: String,
    val universe: UniversePolicy = UniversePolicy(),
    val rawSync: RawSyncPolicy = RawSyncPolicy(),
    val features: FeatureMaterializationPolicy = FeatureMaterializationPolicy()
)

@Serializable
data class UniversePolicy(
    val selectionMode: UniverseSelectionMode = UniverseSelectionMode.EXCHANGE_CATALOG,
    val staticSymbols: List<String> = emptyList(),
    val includeSymbols: List<String> = emptyList(),
    val excludeSymbols: List<String> = emptyList(),
    val refreshIntervalMs: Long = 300_000L,
    val symbolsPerConnection: Int = 32,
    val includeDelisted: Boolean = false,
    val eligibility: UniverseEligibilityPolicy = UniverseEligibilityPolicy()
)

@Serializable
data class UniverseEligibilityPolicy(
    val minHistoryHours: Int = 2_160,
    val minFeatureCoverageRatio: Double = 0.98,
    val minFinalizedCoverageRatio: Double = 0.95,
    val minExecutionObservedRatio: Double = 0.55,
    val minAvg1mNotionalUsd: Double = 50_000.0,
    val requireOrderbook: Boolean = true,
    val minSymbols: Int = 12
)

@Serializable
data class RawSyncPolicy(
    val channels: Map<String, RequirementLevel> = mapOf(
        "trade" to RequirementLevel.REQUIRED,
        "candle_1m" to RequirementLevel.REQUIRED,
        "orderbook_l2" to RequirementLevel.REQUIRED,
        "funding" to RequirementLevel.REQUIRED,
        "open_interest" to RequirementLevel.OPTIONAL
    ),
    val splitCandlesFromExecution: Boolean = true,
    val candleSymbolsPerConnection: Int = 16,
    val executionSymbolsPerConnection: Int = 24,
    val idleTimeoutMs: Long = 120_000L,
    val freshnessCheckIntervalMs: Long = 30_000L,
    val channelActivityTimeoutMs: Long = 120_000L,
    val candleStaleMultiplier: Double = 2.5,
    val backfillLookbackHours: Long = 2_160L,
    val backfillMaxBars: Int = 5_000,
    val backfillOverlapBars: Int = 2,
    val checkpointPersistIntervalMs: Long = 10_000L,
    val staleAfterMs: Long = 120_000L
)

@Serializable
data class FeatureMaterializationPolicy(
    val enabled: Boolean = true,
    val canonicalTable: String = "research_features_1m",
    val bootstrapHours: Long = 336L,
    val refreshIntervalMs: Long = 60_000L,
    val refreshOverlapMinutes: Long = 5L,
    val backfillChunkHours: Long = 1L,
    val finalizationLagMinutes: Long = 3L,
    val freshness: FeatureFreshnessPolicy = FeatureFreshnessPolicy()
)

@Serializable
data class FeatureFreshnessPolicy(
    val maxRawLagSeconds: Long = 90L,
    val maxFeatureLagSeconds: Long = 180L,
    val maxFinalizedLagMinutes: Long = 5L
)

@Serializable
data class ResearchPolicy(
    val canonicalFeatureTable: String = "research_features_1m",
    val computeCoverageBeforeRun: Boolean = true,
    val abortOnInsufficientCoverage: Boolean = true,
    val allowRawFallback: Boolean = false,
    val crossSectional: CrossSectionalPolicy = CrossSectionalPolicy(),
    val strategyFamilies: StrategyFamilyCatalog = StrategyFamilyCatalog()
)

@Serializable
data class StrategyFamilyCatalog(
    val crossSectionalResidualTrend: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = true, stage = "active"),
    val crossSectionalResidualMeanReversion: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = true, stage = "active"),
    val timeSeriesMomentumOverlay: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "planned"),
    val timeSeriesReversalOverlay: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "planned"),
    val fundingBasisCarry: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "planned"),
    val lobFlowGating: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "planned"),
    val toxicityNoTradeFilter: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "planned"),
    val multiVenueRelativeValue: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "deferred"),
    val triangularArbitrage: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "deprioritized")
)

@Serializable
data class StrategyFamilyPolicy(
    val enabled: Boolean,
    val stage: String,
    val notes: String = ""
)

@Serializable
data class CrossSectionalPolicy(
    val marketExchange: String = "hyperliquid_mainnet",
    val executionExchange: String = "hyperliquid",
    val barMinutes: Int = 30,
    val lookbackHours: Int = 48,
    val forwardHours: Int = 12,
    val betaLookbackBars: Int = 48,
    val trendLookbackBars: Int = 12,
    val trendSlowBars: Int = 48,
    val reversionLookbackBars: Int = 8,
    val trendHoldBars: Int = 6,
    val reversionHoldBars: Int = 2,
    val topPerSide: Int = 3,
    val notionalUsd: Double = 5_000.0,
    val maxSymbols: Int = 0,
    val discoveryMaxSymbols: Int = 0,
    val minBars: Int = 48,
    val trendEntryScore: Double = 1.0,
    val reversionZEntry: Double = 1.8,
    val reversionZExit: Double = 0.45,
    val maxSpreadBps: Double = 11.0,
    val minDepthMultiple: Double = 12.0,
    val minFillRatio: Double = 0.58,
    val minVolumeRatio: Double = 0.35,
    val maxVolumeRatio: Double = 4.5,
    val maxVolRegime: Double = 2.35,
    val executionSafetyMarginBps: Double = 8.0,
    val minExpectedNetEdgeBps: Double = 4.0,
    val trendMinFlowAlignment: Double = 0.08,
    val reversionMaxContinuationPressure: Double = 0.18,
    val calibrationLookbackHours: Int = 48,
    val minCalibrationSamples: Int = 4,
    val strongCalibrationSamples: Int = 12,
    val minCalibrationLowerBoundBps: Double = 0.5,
    val minCalibrationWinRate: Double = 0.51,
    val trendCooldownBars: Int = 4,
    val reversionCooldownBars: Int = 2,
    val trendTrailingStopVolMultiple: Double = 0.75,
    val reversionTrailingStopVolMultiple: Double = 0.5,
    val trendTakeProfitVolMultiple: Double = 2.0,
    val reversionTakeProfitVolMultiple: Double = 1.5,
    val maxConcurrentPositions: Int = 12,
    val maxConcurrentLongs: Int = 6,
    val maxConcurrentShorts: Int = 6,
    val maxNetExposureFraction: Double = 0.4,
    val maxPortfolioBetaBtcAbs: Double = 0.65,
    val maxPortfolioBetaEthAbs: Double = 0.65,
    val persistBacktest: Boolean = true,
    val persistForward: Boolean = true,
    val enablePaperOrders: Boolean = false,
    val paperExecutionMode: String = "forward_paper",
    val coverage: CoverageContractPolicy = CoverageContractPolicy(),
    val universeCache: UniverseCachePolicy = UniverseCachePolicy(),
    val featureCache: FeatureQueryCachePolicy = FeatureQueryCachePolicy(),
    val search: CrossSectionalSearchPolicy = CrossSectionalSearchPolicy()
)

@Serializable
data class CoverageContractPolicy(
    val minCoverageRatio: Double = 0.98,
    val minFinalizedRatio: Double = 0.95,
    val minExecutionObservedRatio: Double = 0.55,
    val maxFeatureLagSeconds: Long = 180L,
    val maxFinalizedLagMinutes: Long = 5L,
    val minUniverseSymbols: Int = 12,
    val requireExecutionObserved: Boolean = true
)

@Serializable
data class UniverseCachePolicy(
    val enabled: Boolean = true,
    val ttlSeconds: Int = 300,
    val maxEntries: Int = 8
)

@Serializable
data class FeatureQueryCachePolicy(
    val ttlSeconds: Int = 60,
    val maxEntries: Int = 24
)

@Serializable
data class CrossSectionalSearchPolicy(
    val beamWidth: Int = 6,
    val rounds: Int = 4,
    val maxEvaluations: Int = 72,
    val leaderboardSize: Int = 8,
    val minBacktestTrades: Int = 8,
    val minForwardTrades: Int = 3,
    val minSearchFillRatio: Double = 0.6,
    val maxSearchDrawdownPct: Double = 14.0,
    val barMinutes: List<Int> = listOf(15, 30, 60),
    val lookbackHours: List<Int> = listOf(24, 48, 72, 96),
    val forwardHours: List<Int> = listOf(6, 12, 24),
    val betaLookbackBars: List<Int> = listOf(24, 36, 48, 72),
    val trendLookbackBars: List<Int> = listOf(4, 6, 12, 18, 24),
    val trendSlowBars: List<Int> = listOf(12, 24, 36, 48, 72),
    val reversionLookbackBars: List<Int> = listOf(3, 4, 8, 12),
    val trendHoldBars: List<Int> = listOf(1, 2, 3, 4, 6),
    val reversionHoldBars: List<Int> = listOf(1, 2, 3, 4),
    val topPerSide: List<Int> = listOf(1, 2, 3, 4),
    val maxSymbols: List<Int> = listOf(0, 24, 48, 72, 96),
    val discoveryMaxSymbols: List<Int> = listOf(0, 48, 96, 192),
    val trendEntryScore: List<Double> = listOf(0.8, 1.0, 1.2, 1.4, 1.6),
    val reversionZEntry: List<Double> = listOf(1.5, 1.8, 2.15, 2.4, 2.8),
    val reversionZExit: List<Double> = listOf(0.25, 0.45, 0.65, 0.85, 1.05),
    val maxSpreadBps: List<Double> = listOf(6.0, 8.0, 11.0, 14.0),
    val minDepthMultiple: List<Double> = listOf(8.0, 12.0, 16.0),
    val minFillRatio: List<Double> = listOf(0.5, 0.58, 0.65, 0.75),
    val minVolumeRatio: List<Double> = listOf(0.25, 0.35, 0.5),
    val maxVolumeRatio: List<Double> = listOf(3.0, 4.5, 6.0),
    val maxVolRegime: List<Double> = listOf(1.8, 2.35, 3.0),
    val executionSafetyMarginBps: List<Double> = listOf(4.0, 8.0, 12.0),
    val minExpectedNetEdgeBps: List<Double> = listOf(2.0, 4.0, 6.0, 8.0),
    val trendMinFlowAlignment: List<Double> = listOf(0.0, 0.05, 0.08, 0.12, 0.18),
    val reversionMaxContinuationPressure: List<Double> = listOf(0.08, 0.12, 0.18, 0.24, 0.32),
    val calibrationLookbackHours: List<Int> = listOf(24, 48, 72, 96),
    val minCalibrationSamples: List<Int> = listOf(4, 6, 8, 12),
    val strongCalibrationSamples: List<Int> = listOf(12, 16, 20),
    val minCalibrationLowerBoundBps: List<Double> = listOf(0.0, 0.5, 1.0, 2.0),
    val minCalibrationWinRate: List<Double> = listOf(0.5, 0.52, 0.55, 0.58),
    val trendCooldownBars: List<Int> = listOf(0, 2, 4, 8),
    val reversionCooldownBars: List<Int> = listOf(0, 1, 2, 4),
    val trendTrailingStopVolMultiple: List<Double> = listOf(0.0, 0.75, 1.0, 1.5, 2.0, 3.0),
    val reversionTrailingStopVolMultiple: List<Double> = listOf(0.0, 0.5, 0.75, 1.0, 1.5, 2.0),
    val trendTakeProfitVolMultiple: List<Double> = listOf(0.0, 1.0, 1.5, 2.0, 3.0, 4.0),
    val reversionTakeProfitVolMultiple: List<Double> = listOf(0.0, 0.75, 1.0, 1.5, 2.0, 3.0),
    val maxConcurrentPositions: List<Int> = listOf(8, 12, 16, 24),
    val maxConcurrentLongs: List<Int> = listOf(4, 6, 8, 12),
    val maxConcurrentShorts: List<Int> = listOf(4, 6, 8, 12),
    val maxNetExposureFraction: List<Double> = listOf(0.25, 0.4, 0.5, 0.75),
    val maxPortfolioBetaBtcAbs: List<Double> = listOf(0.35, 0.5, 0.65, 0.9),
    val maxPortfolioBetaEthAbs: List<Double> = listOf(0.35, 0.5, 0.65, 0.9)
)

@Serializable
data class ExecutionPolicy(
    val interfaceModes: List<String> = listOf(
        "offline_backtest",
        "walk_forward",
        "forward_paper",
        "testnet_live",
        "live"
    ),
    val makerFeeBps: Double = 1.5,
    val takerFeeBps: Double = 4.5,
    val quoteExchange: String = "hyperliquid_mainnet",
    val testnetQuoteExchange: String = "hyperliquid_testnet"
)

@Serializable
data class RiskPolicy(
    val maxGrossUsd: Double = 100_000.0,
    val maxNetUsd: Double = 25_000.0,
    val maxPerSymbolUsd: Double = 5_000.0,
    val maxPerSectorUsd: Double = 15_000.0,
    val maxOpenPositions: Int = 20,
    val dailyStopPct: Double = 2.0,
    val rolling30dStopPct: Double = 8.0,
    val staleDataSeconds: Long = 180L,
    val fillRatioFloor: Double = 0.6,
    val slippageDriftBps: Double = 8.0
)

@Serializable
data class PromotionPolicy(
    val ladder: List<String> = listOf(
        "idea",
        "offline_backtest",
        "walk_forward",
        "forward_paper",
        "testnet_live",
        "live"
    ),
    val minNetEdgeBps: Double = 2.0,
    val minRealizedReturnPct: Double = 0.5,
    val minSampleCount: Int = 200,
    val maxDrawdownPct: Double = 5.0,
    val minFillRatio: Double = 0.7,
    val maxLiveVsBacktestDriftBps: Double = 5.0,
    val minRegimeSlicePassRatio: Double = 0.7
)

@Serializable
data class ObservabilityPolicy(
    val grafanaDashboards: List<String> = listOf(
        "edge_after_cost",
        "fill_quality",
        "slippage_drift",
        "calibration_error",
        "latency",
        "drawdown",
        "regime_attribution",
        "degradation_alerts"
    )
)

@TradingPolicyDsl
class TradingPolicyBuilder {
    var version: Int = 1
    private val venues = linkedMapOf<String, VenuePolicy>()
    private var research: ResearchPolicy = ResearchPolicy()
    private var execution: ExecutionPolicy = ExecutionPolicy()
    private var risk: RiskPolicy = RiskPolicy()
    private var promotion: PromotionPolicy = PromotionPolicy()
    private var observability: ObservabilityPolicy = ObservabilityPolicy()

    fun hyperliquid(name: String = "hyperliquid", block: VenuePolicyBuilder.() -> Unit) {
        venues[name] = VenuePolicyBuilder(
            venueId = name,
            exchangeId = "hyperliquid_mainnet",
            websocketUrl = "wss://api.hyperliquid.xyz/ws",
            infoUrl = "https://api.hyperliquid.xyz/info"
        ).apply(block).build()
    }

    fun research(block: ResearchPolicyBuilder.() -> Unit) {
        research = ResearchPolicyBuilder().apply(block).build()
    }

    fun execution(block: ExecutionPolicyBuilder.() -> Unit) {
        execution = ExecutionPolicyBuilder().apply(block).build()
    }

    fun risk(block: RiskPolicyBuilder.() -> Unit) {
        risk = RiskPolicyBuilder().apply(block).build()
    }

    fun promotion(block: PromotionPolicyBuilder.() -> Unit) {
        promotion = PromotionPolicyBuilder().apply(block).build()
    }

    fun observability(block: ObservabilityPolicyBuilder.() -> Unit) {
        observability = ObservabilityPolicyBuilder().apply(block).build()
    }

    fun build(): TradingPolicy = TradingPolicy(
        version = version,
        venues = venues.toMap(),
        research = research,
        execution = execution,
        risk = risk,
        promotion = promotion,
        observability = observability
    )
}

@TradingPolicyDsl
class VenuePolicyBuilder(
    var venueId: String,
    var exchangeId: String,
    var websocketUrl: String,
    var infoUrl: String
) {
    var enabled: Boolean = true
    var mainnet: Boolean = true
    private var universe: UniversePolicy = UniversePolicy()
    private var rawSync: RawSyncPolicy = RawSyncPolicy()
    private var features: FeatureMaterializationPolicy = FeatureMaterializationPolicy()

    fun universe(block: UniversePolicyBuilder.() -> Unit) {
        universe = UniversePolicyBuilder().apply(block).build()
    }

    fun rawSync(block: RawSyncPolicyBuilder.() -> Unit) {
        rawSync = RawSyncPolicyBuilder().apply(block).build()
    }

    fun features(block: FeatureMaterializationPolicyBuilder.() -> Unit) {
        features = FeatureMaterializationPolicyBuilder().apply(block).build()
    }

    fun build(): VenuePolicy = VenuePolicy(
        venueId = venueId,
        exchangeId = exchangeId,
        enabled = enabled,
        mainnet = mainnet,
        websocketUrl = websocketUrl,
        infoUrl = infoUrl,
        universe = universe,
        rawSync = rawSync,
        features = features
    )
}

@TradingPolicyDsl
class UniversePolicyBuilder {
    var selectionMode: UniverseSelectionMode = UniverseSelectionMode.EXCHANGE_CATALOG
    var staticSymbols: List<String> = emptyList()
    var includeSymbols: List<String> = emptyList()
    var excludeSymbols: List<String> = emptyList()
    var refreshIntervalMs: Long = 300_000L
    var symbolsPerConnection: Int = 8
    var includeDelisted: Boolean = false
    private var eligibility: UniverseEligibilityPolicy = UniverseEligibilityPolicy()

    fun eligibility(block: UniverseEligibilityPolicyBuilder.() -> Unit) {
        eligibility = UniverseEligibilityPolicyBuilder().apply(block).build()
    }

    fun build(): UniversePolicy = UniversePolicy(
        selectionMode = selectionMode,
        staticSymbols = staticSymbols,
        includeSymbols = includeSymbols,
        excludeSymbols = excludeSymbols,
        refreshIntervalMs = refreshIntervalMs,
        symbolsPerConnection = symbolsPerConnection,
        includeDelisted = includeDelisted,
        eligibility = eligibility
    )
}

@TradingPolicyDsl
class UniverseEligibilityPolicyBuilder {
    var minHistoryHours: Int = 2_160
    var minFeatureCoverageRatio: Double = 0.98
    var minFinalizedCoverageRatio: Double = 0.95
    var minExecutionObservedRatio: Double = 0.55
    var minAvg1mNotionalUsd: Double = 50_000.0
    var requireOrderbook: Boolean = true
    var minSymbols: Int = 12

    fun build(): UniverseEligibilityPolicy = UniverseEligibilityPolicy(
        minHistoryHours = minHistoryHours,
        minFeatureCoverageRatio = minFeatureCoverageRatio,
        minFinalizedCoverageRatio = minFinalizedCoverageRatio,
        minExecutionObservedRatio = minExecutionObservedRatio,
        minAvg1mNotionalUsd = minAvg1mNotionalUsd,
        requireOrderbook = requireOrderbook,
        minSymbols = minSymbols
    )
}

@TradingPolicyDsl
class RawSyncPolicyBuilder {
    var channels: MutableMap<String, RequirementLevel> = linkedMapOf(
        "trade" to RequirementLevel.REQUIRED,
        "candle_1m" to RequirementLevel.REQUIRED,
        "orderbook_l2" to RequirementLevel.REQUIRED,
        "funding" to RequirementLevel.REQUIRED,
        "open_interest" to RequirementLevel.OPTIONAL
    )
    var splitCandlesFromExecution: Boolean = true
    var candleSymbolsPerConnection: Int = 16
    var executionSymbolsPerConnection: Int = 24
    var idleTimeoutMs: Long = 120_000L
    var freshnessCheckIntervalMs: Long = 30_000L
    var channelActivityTimeoutMs: Long = 120_000L
    var candleStaleMultiplier: Double = 2.5
    var backfillLookbackHours: Long = 2_160L
    var backfillMaxBars: Int = 5_000
    var backfillOverlapBars: Int = 2
    var checkpointPersistIntervalMs: Long = 10_000L
    var staleAfterMs: Long = 120_000L

    fun channel(name: String, requirement: RequirementLevel) {
        channels[name] = requirement
    }

    fun build(): RawSyncPolicy = RawSyncPolicy(
        channels = channels.toMap(),
        splitCandlesFromExecution = splitCandlesFromExecution,
        candleSymbolsPerConnection = candleSymbolsPerConnection,
        executionSymbolsPerConnection = executionSymbolsPerConnection,
        idleTimeoutMs = idleTimeoutMs,
        freshnessCheckIntervalMs = freshnessCheckIntervalMs,
        channelActivityTimeoutMs = channelActivityTimeoutMs,
        candleStaleMultiplier = candleStaleMultiplier,
        backfillLookbackHours = backfillLookbackHours,
        backfillMaxBars = backfillMaxBars,
        backfillOverlapBars = backfillOverlapBars,
        checkpointPersistIntervalMs = checkpointPersistIntervalMs,
        staleAfterMs = staleAfterMs
    )
}

@TradingPolicyDsl
class FeatureMaterializationPolicyBuilder {
    var enabled: Boolean = true
    var canonicalTable: String = "research_features_1m"
    var bootstrapHours: Long = 336L
    var refreshIntervalMs: Long = 60_000L
    var refreshOverlapMinutes: Long = 180L
    var backfillChunkHours: Long = 6L
    var finalizationLagMinutes: Long = 3L
    private var freshness: FeatureFreshnessPolicy = FeatureFreshnessPolicy()

    fun freshness(block: FeatureFreshnessPolicyBuilder.() -> Unit) {
        freshness = FeatureFreshnessPolicyBuilder().apply(block).build()
    }

    fun build(): FeatureMaterializationPolicy = FeatureMaterializationPolicy(
        enabled = enabled,
        canonicalTable = canonicalTable,
        bootstrapHours = bootstrapHours,
        refreshIntervalMs = refreshIntervalMs,
        refreshOverlapMinutes = refreshOverlapMinutes,
        backfillChunkHours = backfillChunkHours,
        finalizationLagMinutes = finalizationLagMinutes,
        freshness = freshness
    )
}

@TradingPolicyDsl
class FeatureFreshnessPolicyBuilder {
    var maxRawLagSeconds: Long = 90L
    var maxFeatureLagSeconds: Long = 180L
    var maxFinalizedLagMinutes: Long = 5L

    fun build(): FeatureFreshnessPolicy = FeatureFreshnessPolicy(
        maxRawLagSeconds = maxRawLagSeconds,
        maxFeatureLagSeconds = maxFeatureLagSeconds,
        maxFinalizedLagMinutes = maxFinalizedLagMinutes
    )
}

@TradingPolicyDsl
class ResearchPolicyBuilder {
    var canonicalFeatureTable: String = "research_features_1m"
    var computeCoverageBeforeRun: Boolean = true
    var abortOnInsufficientCoverage: Boolean = true
    var allowRawFallback: Boolean = false
    var strategyFamilies: StrategyFamilyCatalog = StrategyFamilyCatalog()
    private var crossSectional: CrossSectionalPolicy = CrossSectionalPolicy()

    fun crossSectional(block: CrossSectionalPolicyBuilder.() -> Unit) {
        crossSectional = CrossSectionalPolicyBuilder().apply(block).build()
    }

    fun build(): ResearchPolicy = ResearchPolicy(
        canonicalFeatureTable = canonicalFeatureTable,
        computeCoverageBeforeRun = computeCoverageBeforeRun,
        abortOnInsufficientCoverage = abortOnInsufficientCoverage,
        allowRawFallback = allowRawFallback,
        crossSectional = crossSectional,
        strategyFamilies = strategyFamilies
    )
}

@TradingPolicyDsl
class CrossSectionalPolicyBuilder {
    var marketExchange: String = "hyperliquid_mainnet"
    var executionExchange: String = "hyperliquid_mainnet"
    var barMinutes: Int = 60
    var lookbackHours: Int = 1_080
    var forwardHours: Int = 72
    var betaLookbackBars: Int = 168
    var trendLookbackBars: Int = 24
    var trendSlowBars: Int = 96
    var reversionLookbackBars: Int = 12
    var trendHoldBars: Int = 24
    var reversionHoldBars: Int = 8
    var topPerSide: Int = 1
    var notionalUsd: Double = 5_000.0
    var maxSymbols: Int = 0
    var discoveryMaxSymbols: Int = 0
    var minBars: Int = 360
    var trendEntryScore: Double = 1.05
    var reversionZEntry: Double = 2.15
    var reversionZExit: Double = 0.45
    var maxSpreadBps: Double = 11.0
    var minDepthMultiple: Double = 12.0
    var minFillRatio: Double = 0.58
    var minVolumeRatio: Double = 0.35
    var maxVolumeRatio: Double = 4.5
    var maxVolRegime: Double = 2.35
    var executionSafetyMarginBps: Double = 8.0
    var minExpectedNetEdgeBps: Double = 4.0
    var trendMinFlowAlignment: Double = 0.08
    var reversionMaxContinuationPressure: Double = 0.18
    var calibrationLookbackHours: Int = 720
    var minCalibrationSamples: Int = 4
    var strongCalibrationSamples: Int = 12
    var minCalibrationLowerBoundBps: Double = 0.5
    var minCalibrationWinRate: Double = 0.51
    var trendCooldownBars: Int = 8
    var reversionCooldownBars: Int = 4
    var trendTrailingStopVolMultiple: Double = 0.75
    var reversionTrailingStopVolMultiple: Double = 0.5
    var trendTakeProfitVolMultiple: Double = 2.0
    var reversionTakeProfitVolMultiple: Double = 1.5
    var maxConcurrentPositions: Int = 16
    var maxConcurrentLongs: Int = 8
    var maxConcurrentShorts: Int = 8
    var maxNetExposureFraction: Double = 0.4
    var maxPortfolioBetaBtcAbs: Double = 0.65
    var maxPortfolioBetaEthAbs: Double = 0.65
    var persistBacktest: Boolean = true
    var persistForward: Boolean = true
    var enablePaperOrders: Boolean = false
    var paperExecutionMode: String = "forward_paper"
    private var coverage: CoverageContractPolicy = CoverageContractPolicy()
    private var universeCache: UniverseCachePolicy = UniverseCachePolicy()
    private var featureCache: FeatureQueryCachePolicy = FeatureQueryCachePolicy()
    var search: CrossSectionalSearchPolicy = CrossSectionalSearchPolicy()

    fun coverage(block: CoverageContractPolicyBuilder.() -> Unit) {
        coverage = CoverageContractPolicyBuilder().apply(block).build()
    }

    fun universeCache(block: UniverseCachePolicyBuilder.() -> Unit) {
        universeCache = UniverseCachePolicyBuilder().apply(block).build()
    }

    fun featureCache(block: FeatureQueryCachePolicyBuilder.() -> Unit) {
        featureCache = FeatureQueryCachePolicyBuilder().apply(block).build()
    }

    fun build(): CrossSectionalPolicy = CrossSectionalPolicy(
        marketExchange = marketExchange,
        executionExchange = executionExchange,
        barMinutes = barMinutes,
        lookbackHours = lookbackHours,
        forwardHours = forwardHours,
        betaLookbackBars = betaLookbackBars,
        trendLookbackBars = trendLookbackBars,
        trendSlowBars = trendSlowBars,
        reversionLookbackBars = reversionLookbackBars,
        trendHoldBars = trendHoldBars,
        reversionHoldBars = reversionHoldBars,
        topPerSide = topPerSide,
        notionalUsd = notionalUsd,
        maxSymbols = maxSymbols,
        discoveryMaxSymbols = discoveryMaxSymbols,
        minBars = minBars,
        trendEntryScore = trendEntryScore,
        reversionZEntry = reversionZEntry,
        reversionZExit = reversionZExit,
        maxSpreadBps = maxSpreadBps,
        minDepthMultiple = minDepthMultiple,
        minFillRatio = minFillRatio,
        minVolumeRatio = minVolumeRatio,
        maxVolumeRatio = maxVolumeRatio,
        maxVolRegime = maxVolRegime,
        executionSafetyMarginBps = executionSafetyMarginBps,
        minExpectedNetEdgeBps = minExpectedNetEdgeBps,
        trendMinFlowAlignment = trendMinFlowAlignment,
        reversionMaxContinuationPressure = reversionMaxContinuationPressure,
        calibrationLookbackHours = calibrationLookbackHours,
        minCalibrationSamples = minCalibrationSamples,
        strongCalibrationSamples = strongCalibrationSamples,
        minCalibrationLowerBoundBps = minCalibrationLowerBoundBps,
        minCalibrationWinRate = minCalibrationWinRate,
        trendCooldownBars = trendCooldownBars,
        reversionCooldownBars = reversionCooldownBars,
        trendTrailingStopVolMultiple = trendTrailingStopVolMultiple,
        reversionTrailingStopVolMultiple = reversionTrailingStopVolMultiple,
        trendTakeProfitVolMultiple = trendTakeProfitVolMultiple,
        reversionTakeProfitVolMultiple = reversionTakeProfitVolMultiple,
        maxConcurrentPositions = maxConcurrentPositions,
        maxConcurrentLongs = maxConcurrentLongs,
        maxConcurrentShorts = maxConcurrentShorts,
        maxNetExposureFraction = maxNetExposureFraction,
        maxPortfolioBetaBtcAbs = maxPortfolioBetaBtcAbs,
        maxPortfolioBetaEthAbs = maxPortfolioBetaEthAbs,
        persistBacktest = persistBacktest,
        persistForward = persistForward,
        enablePaperOrders = enablePaperOrders,
        paperExecutionMode = paperExecutionMode,
        coverage = coverage,
        universeCache = universeCache,
        featureCache = featureCache,
        search = search
    )
}

@TradingPolicyDsl
class CoverageContractPolicyBuilder {
    var minCoverageRatio: Double = 0.98
    var minFinalizedRatio: Double = 0.95
    var minExecutionObservedRatio: Double = 0.55
    var maxFeatureLagSeconds: Long = 180L
    var maxFinalizedLagMinutes: Long = 5L
    var minUniverseSymbols: Int = 12
    var requireExecutionObserved: Boolean = true

    fun build(): CoverageContractPolicy = CoverageContractPolicy(
        minCoverageRatio = minCoverageRatio,
        minFinalizedRatio = minFinalizedRatio,
        minExecutionObservedRatio = minExecutionObservedRatio,
        maxFeatureLagSeconds = maxFeatureLagSeconds,
        maxFinalizedLagMinutes = maxFinalizedLagMinutes,
        minUniverseSymbols = minUniverseSymbols,
        requireExecutionObserved = requireExecutionObserved
    )
}

@TradingPolicyDsl
class UniverseCachePolicyBuilder {
    var enabled: Boolean = true
    var ttlSeconds: Int = 300
    var maxEntries: Int = 8

    fun build(): UniverseCachePolicy = UniverseCachePolicy(
        enabled = enabled,
        ttlSeconds = ttlSeconds,
        maxEntries = maxEntries
    )
}

@TradingPolicyDsl
class FeatureQueryCachePolicyBuilder {
    var ttlSeconds: Int = 60
    var maxEntries: Int = 24

    fun build(): FeatureQueryCachePolicy = FeatureQueryCachePolicy(
        ttlSeconds = ttlSeconds,
        maxEntries = maxEntries
    )
}

@TradingPolicyDsl
class ExecutionPolicyBuilder {
    var interfaceModes: List<String> = listOf(
        "offline_backtest",
        "walk_forward",
        "forward_paper",
        "testnet_live",
        "live"
    )
    var makerFeeBps: Double = 1.5
    var takerFeeBps: Double = 4.5
    var quoteExchange: String = "hyperliquid_mainnet"
    var testnetQuoteExchange: String = "hyperliquid_testnet"

    fun build(): ExecutionPolicy = ExecutionPolicy(
        interfaceModes = interfaceModes,
        makerFeeBps = makerFeeBps,
        takerFeeBps = takerFeeBps,
        quoteExchange = quoteExchange,
        testnetQuoteExchange = testnetQuoteExchange
    )
}

@TradingPolicyDsl
class RiskPolicyBuilder {
    var maxGrossUsd: Double = 100_000.0
    var maxNetUsd: Double = 25_000.0
    var maxPerSymbolUsd: Double = 5_000.0
    var maxPerSectorUsd: Double = 15_000.0
    var maxOpenPositions: Int = 20
    var dailyStopPct: Double = 2.0
    var rolling30dStopPct: Double = 8.0
    var staleDataSeconds: Long = 180L
    var fillRatioFloor: Double = 0.6
    var slippageDriftBps: Double = 8.0

    fun build(): RiskPolicy = RiskPolicy(
        maxGrossUsd = maxGrossUsd,
        maxNetUsd = maxNetUsd,
        maxPerSymbolUsd = maxPerSymbolUsd,
        maxPerSectorUsd = maxPerSectorUsd,
        maxOpenPositions = maxOpenPositions,
        dailyStopPct = dailyStopPct,
        rolling30dStopPct = rolling30dStopPct,
        staleDataSeconds = staleDataSeconds,
        fillRatioFloor = fillRatioFloor,
        slippageDriftBps = slippageDriftBps
    )
}

@TradingPolicyDsl
class PromotionPolicyBuilder {
    var ladder: List<String> = listOf(
        "idea",
        "offline_backtest",
        "walk_forward",
        "forward_paper",
        "testnet_live",
        "live"
    )
    var minNetEdgeBps: Double = 2.0
    var minRealizedReturnPct: Double = 0.5
    var minSampleCount: Int = 200
    var maxDrawdownPct: Double = 5.0
    var minFillRatio: Double = 0.7
    var maxLiveVsBacktestDriftBps: Double = 5.0
    var minRegimeSlicePassRatio: Double = 0.7

    fun build(): PromotionPolicy = PromotionPolicy(
        ladder = ladder,
        minNetEdgeBps = minNetEdgeBps,
        minRealizedReturnPct = minRealizedReturnPct,
        minSampleCount = minSampleCount,
        maxDrawdownPct = maxDrawdownPct,
        minFillRatio = minFillRatio,
        maxLiveVsBacktestDriftBps = maxLiveVsBacktestDriftBps,
        minRegimeSlicePassRatio = minRegimeSlicePassRatio
    )
}

@TradingPolicyDsl
class ObservabilityPolicyBuilder {
    var grafanaDashboards: List<String> = listOf(
        "edge_after_cost",
        "fill_quality",
        "slippage_drift",
        "calibration_error",
        "latency",
        "drawdown",
        "regime_attribution",
        "degradation_alerts"
    )

    fun build(): ObservabilityPolicy = ObservabilityPolicy(grafanaDashboards = grafanaDashboards)
}

fun tradingPolicy(block: TradingPolicyBuilder.() -> Unit): TradingPolicy =
    TradingPolicyBuilder().apply(block).build()

object DatamancyTradingPolicy {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun default(): TradingPolicy = tradingPolicy {
        hyperliquid {
            exchangeId = "hyperliquid_mainnet"
            websocketUrl = "wss://api.hyperliquid.xyz/ws"
            infoUrl = "https://api.hyperliquid.xyz/info"
            mainnet = true

            universe {
                selectionMode = UniverseSelectionMode.EXCHANGE_CATALOG
                refreshIntervalMs = 300_000L
                symbolsPerConnection = 32
                includeDelisted = false
                eligibility {
                    minHistoryHours = 2_160
                    minFeatureCoverageRatio = 0.98
                    minFinalizedCoverageRatio = 0.95
                    minExecutionObservedRatio = 0.55
                    minAvg1mNotionalUsd = 50_000.0
                    requireOrderbook = true
                    minSymbols = 12
                }
            }

            rawSync {
                channel("trade", RequirementLevel.REQUIRED)
                channel("candle_1m", RequirementLevel.REQUIRED)
                channel("orderbook_l2", RequirementLevel.REQUIRED)
                channel("funding", RequirementLevel.REQUIRED)
                channel("open_interest", RequirementLevel.OPTIONAL)
                splitCandlesFromExecution = true
                candleSymbolsPerConnection = 16
                executionSymbolsPerConnection = 24
                idleTimeoutMs = 120_000L
                freshnessCheckIntervalMs = 30_000L
                channelActivityTimeoutMs = 120_000L
                candleStaleMultiplier = 2.5
                backfillLookbackHours = 2_160L
                backfillMaxBars = 5_000
                backfillOverlapBars = 2
                checkpointPersistIntervalMs = 10_000L
                staleAfterMs = 120_000L
            }

            features {
                enabled = true
                canonicalTable = "research_features_1m"
                bootstrapHours = 336L
                refreshIntervalMs = 60_000L
                refreshOverlapMinutes = 5L
                backfillChunkHours = 1L
                finalizationLagMinutes = 3L
                freshness {
                    maxRawLagSeconds = 90L
                    maxFeatureLagSeconds = 180L
                    maxFinalizedLagMinutes = 5L
                }
            }
        }

        research {
            canonicalFeatureTable = "research_features_1m"
            computeCoverageBeforeRun = true
            abortOnInsufficientCoverage = true
            allowRawFallback = false
            strategyFamilies = StrategyFamilyCatalog(
                crossSectionalResidualTrend = StrategyFamilyPolicy(enabled = true, stage = "active", notes = "Primary residual trend search family"),
                crossSectionalResidualMeanReversion = StrategyFamilyPolicy(enabled = true, stage = "active", notes = "Primary residual mean reversion search family"),
                timeSeriesMomentumOverlay = StrategyFamilyPolicy(enabled = false, stage = "planned"),
                timeSeriesReversalOverlay = StrategyFamilyPolicy(enabled = false, stage = "planned"),
                fundingBasisCarry = StrategyFamilyPolicy(enabled = false, stage = "planned"),
                lobFlowGating = StrategyFamilyPolicy(enabled = false, stage = "planned"),
                toxicityNoTradeFilter = StrategyFamilyPolicy(enabled = false, stage = "planned"),
                multiVenueRelativeValue = StrategyFamilyPolicy(enabled = false, stage = "deferred"),
                triangularArbitrage = StrategyFamilyPolicy(enabled = false, stage = "deprioritized")
            )
            crossSectional {
                marketExchange = "hyperliquid_mainnet"
                executionExchange = "hyperliquid"
                barMinutes = 30
                lookbackHours = 48
                forwardHours = 12
                betaLookbackBars = 48
                trendLookbackBars = 12
                trendSlowBars = 48
                reversionLookbackBars = 8
                trendHoldBars = 6
                reversionHoldBars = 2
                topPerSide = 3
                notionalUsd = 5_000.0
                maxSymbols = 0
                discoveryMaxSymbols = 0
                minBars = 48
                trendEntryScore = 1.0
                reversionZEntry = 1.8
                reversionZExit = 0.45
                maxSpreadBps = 11.0
                minDepthMultiple = 12.0
                minFillRatio = 0.58
                minVolumeRatio = 0.35
                maxVolumeRatio = 4.5
                maxVolRegime = 2.35
                executionSafetyMarginBps = 8.0
                minExpectedNetEdgeBps = 4.0
                trendMinFlowAlignment = 0.08
                reversionMaxContinuationPressure = 0.18
                calibrationLookbackHours = 48
                minCalibrationSamples = 4
                strongCalibrationSamples = 12
                minCalibrationLowerBoundBps = 0.5
                minCalibrationWinRate = 0.51
                trendCooldownBars = 4
                reversionCooldownBars = 2
                trendTrailingStopVolMultiple = 0.75
                reversionTrailingStopVolMultiple = 0.5
                trendTakeProfitVolMultiple = 2.0
                reversionTakeProfitVolMultiple = 1.5
                maxConcurrentPositions = 12
                maxConcurrentLongs = 6
                maxConcurrentShorts = 6
                maxNetExposureFraction = 0.4
                maxPortfolioBetaBtcAbs = 0.65
                maxPortfolioBetaEthAbs = 0.65
                persistBacktest = true
                persistForward = true
                enablePaperOrders = false
                paperExecutionMode = "forward_paper"
                coverage {
                    minCoverageRatio = 0.98
                    minFinalizedRatio = 0.95
                    minExecutionObservedRatio = 0.55
                    maxFeatureLagSeconds = 180L
                    maxFinalizedLagMinutes = 5L
                    minUniverseSymbols = 12
                    requireExecutionObserved = true
                }
                universeCache {
                    enabled = true
                    ttlSeconds = 300
                    maxEntries = 8
                }
                featureCache {
                    ttlSeconds = 60
                    maxEntries = 24
                }
                search = CrossSectionalSearchPolicy()
            }
        }

        execution {
            interfaceModes = listOf("offline_backtest", "walk_forward", "forward_paper", "testnet_live", "live")
            makerFeeBps = 1.5
            takerFeeBps = 4.5
            quoteExchange = "hyperliquid_mainnet"
            testnetQuoteExchange = "hyperliquid_testnet"
        }

        risk {
            maxGrossUsd = 100_000.0
            maxNetUsd = 25_000.0
            maxPerSymbolUsd = 5_000.0
            maxPerSectorUsd = 15_000.0
            maxOpenPositions = 20
            dailyStopPct = 2.0
            rolling30dStopPct = 8.0
            staleDataSeconds = 180L
            fillRatioFloor = 0.6
            slippageDriftBps = 8.0
        }

        promotion {
            ladder = listOf("idea", "offline_backtest", "walk_forward", "forward_paper", "testnet_live", "live")
            minNetEdgeBps = 2.0
            minRealizedReturnPct = 0.5
            minSampleCount = 200
            maxDrawdownPct = 5.0
            minFillRatio = 0.7
            maxLiveVsBacktestDriftBps = 5.0
            minRegimeSlicePassRatio = 0.7
        }

        observability {
            grafanaDashboards = listOf(
                "edge_after_cost",
                "fill_quality",
                "slippage_drift",
                "calibration_error",
                "latency",
                "drawdown",
                "regime_attribution",
                "degradation_alerts"
            )
        }
    }

    fun defaultJson(): String = json.encodeToString(default())
}

object ActiveTradingPolicy {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Volatile
    private var overridePolicy: TradingPolicy? = null

    private val loadedPolicy: TradingPolicy by lazy { loadFromEnvironment() }

    fun current(): TradingPolicy = overridePolicy ?: loadedPolicy

    fun installOverride(policy: TradingPolicy?) {
        overridePolicy = policy
    }

    fun fromFile(file: File): TradingPolicy = json.decodeFromString(TradingPolicy.serializer(), file.readText())

    fun write(policy: TradingPolicy, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(TradingPolicy.serializer(), policy))
    }

    private fun loadFromEnvironment(): TradingPolicy {
        val configuredPath = sequenceOf(
            System.getProperty("datamancy.trading.policy.file"),
            System.getenv("DATAMANCY_TRADING_POLICY_FILE")
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotEmpty() }

        if (configuredPath == null) {
            return DatamancyTradingPolicy.default()
        }

        val file = File(configuredPath)
        require(file.exists()) { "Trading policy file not found: $configuredPath" }
        return fromFile(file)
    }
}
