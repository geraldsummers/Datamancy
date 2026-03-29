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
    val datasets: AlphaDatasetPolicy = AlphaDatasetPolicy(),
    val discovery: AlphaDiscoveryPolicy = AlphaDiscoveryPolicy(),
    val portfolio: AlphaPortfolioPolicy = AlphaPortfolioPolicy(),
    val validation: AlphaValidationPolicy = AlphaValidationPolicy(),
    val readiness: AlphaReadinessPolicy = AlphaReadinessPolicy(),
    val strategyFamilies: StrategyFamilyCatalog = StrategyFamilyCatalog()
)

@Serializable
data class StrategyFamilyCatalog(
    val interdayRelativeStrengthTrend: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = true, stage = "active"),
    val interdayResidualReversion: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "parked"),
    val fundingBasisCarry: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = false, stage = "planned"),
    val flowConditioning: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = true, stage = "planned"),
    val volatilityRegimeFilter: StrategyFamilyPolicy = StrategyFamilyPolicy(enabled = true, stage = "planned"),
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
data class AlphaDatasetPolicy(
    val marketExchange: String = "hyperliquid_mainnet",
    val executionExchange: String = "hyperliquid_mainnet",
    val canonicalBarMinutes: Int = 240,
    val supportedSignalBarMinutes: List<Int> = listOf(240, 1_440),
    val defaultSignalBarMinutes: Int = 240,
    val dailyBoundary: String = "UTC",
    val defaultLookbackHours: Int = 1_080,
    val defaultForwardHours: Int = 72,
    val anchorHorizonsMinutes: List<Int> = listOf(15, 60, 240, 720),
    val coreHorizonsDays: List<Int> = listOf(1, 3, 7, 14, 30, 60, 90),
    val volatilityLookbackDays: List<Int> = listOf(7, 14, 30, 60),
    val priceReferenceMode: String = "mid_vwap_hybrid",
    val includeFunding: Boolean = true,
    val includeOpenInterest: Boolean = true,
    val includeTradeFlow: Boolean = true,
    val includeOrderbookConditioning: Boolean = true,
    val includeReceiptTimestamps: Boolean = true,
    val requireUtcDailyBars: Boolean = true,
    val maxSymbols: Int = 0,
    val discoveryMaxSymbols: Int = 0,
    val universeCache: UniverseCachePolicy = UniverseCachePolicy(),
    val featureCache: FeatureQueryCachePolicy = FeatureQueryCachePolicy()
)

@Serializable
data class SignalReadinessPolicy(
    val defaultExchange: String = "hyperliquid_mainnet",
    val minCoverageRatio: Double = 0.98,
    val minFinalizedRatio: Double = 0.95,
    val maxFeatureLagSeconds: Long = 180L,
    val maxFinalizedLagMinutes: Long = 5L,
    val minUniverseSymbols: Int = 12,
    val minHistoryHours: Int = 2_160,
    val requireFundingForCarryFamilies: Boolean = false,
    val requireOpenInterestForCarryFamilies: Boolean = false,
    val allowPriceOnlyResearch: Boolean = true
)

@Serializable
data class ExecutionReadinessPolicy(
    val minExecutionObservedRatio: Double = 0.55,
    val minTradeObservedRatioForEligibility: Double = 0.40,
    val requireOrderbook: Boolean = true,
    val requireFillTelemetry: Boolean = false,
    val maxQuoteAgeSeconds: Long = 120L,
    val maxLatencyDriftMs: Long = 5_000L
)

@Serializable
data class PromotionReadinessPolicy(
    val requireSignalReadiness: Boolean = true,
    val requireExecutionReadiness: Boolean = true,
    val requireMultiplicityControls: Boolean = true,
    val requireSamplingRobustness: Boolean = true,
    val minRegimeSlicePassRatio: Double = 0.7,
    val maxLiveVsBacktestDriftBps: Double = 5.0,
    val minSampleCount: Int = 200,
    val minRealizedReturnPct: Double = 0.5,
    val minNetEdgeBps: Double = 2.0
)

@Serializable
data class AlphaReadinessPolicy(
    val signal: SignalReadinessPolicy = SignalReadinessPolicy(),
    val execution: ExecutionReadinessPolicy = ExecutionReadinessPolicy(),
    val promotion: PromotionReadinessPolicy = PromotionReadinessPolicy()
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
data class AlphaSearchPolicy(
    val beamWidth: Int = 6,
    val rounds: Int = 4,
    val maxEvaluations: Int = 96,
    val leaderboardSize: Int = 12,
    val minBacktestTrades: Int = 12,
    val minForwardTrades: Int = 4,
    val minNetEdgeBps: Double = 2.0,
    val maxSearchDrawdownPct: Double = 14.0,
    val minForwardCalmar: Double = 0.10,
    val maxTimeUnderWaterPct: Double = 92.0,
    val maxCvar1dPct: Double = 8.0,
    val minAlignedParticipationRate: Double = 0.15,
    val maxWrongWayExposurePct: Double = 65.0,
    val maxKillSwitchUtilization: Double = 1.0,
    val scorePlateauToleranceBps: Double = 1.0
)

@Serializable
data class AlphaDiscoveryPolicy(
    val defaultStrategyFamily: String = "interday_relative_strength_trend_v1",
    val rebalanceCadenceHours: List<Int> = listOf(24, 72, 168),
    val executionWindowMinutes: List<Int> = listOf(60, 120),
    val selectionQuantiles: List<Double> = listOf(0.05, 0.10, 0.15),
    val useRegressionSlope: Boolean = true,
    val useAdxFilter: Boolean = true,
    val useMovingAverageFilter: Boolean = true,
    val useVwapDeviation: Boolean = true,
    val useSignedVolumeImbalance: Boolean = true,
    val includeSamplingRobustnessCheck: Boolean = true,
    val search: AlphaSearchPolicy = AlphaSearchPolicy()
)

@Serializable
data class AlphaPortfolioPolicy(
    val longShort: Boolean = true,
    val selectionMode: String = "quantile",
    val weightingMode: String = "volatility_scaled_score",
    val targetGrossFraction: Double = 1.0,
    val targetNetFraction: Double = 0.0,
    val maxWeightPerSymbol: Double = 0.08,
    val maxTurnoverPerRebalance: Double = 1.0,
    val turnoverPenaltyBps: Double = 3.0,
    val maxParticipationRate: Double = 0.05,
    val maxDepthFraction: Double = 0.15,
    val rebalanceTargetExposureStep: Double = 0.15,
    val minTargetExposureFraction: Double = 0.25,
    val maxTargetExposureFraction: Double = 1.0,
    val maxConcurrentPositions: Int = 16,
    val maxConcurrentLongs: Int = 8,
    val maxConcurrentShorts: Int = 8,
    val useTrailingStops: Boolean = true,
    val trailingStopVolMultiple: Double = 1.0,
    val takeProfitVolMultiple: Double = 2.5,
    val useMakerFirstExecution: Boolean = true
)

@Serializable
data class AlphaValidationPolicy(
    val walkForwardWindows: Int = 8,
    val nestedCvFolds: Int = 4,
    val purgedKFoldFolds: Int = 5,
    val embargoBars: Int = 24,
    val useCombinatorialPurgedCv: Boolean = true,
    val bootstrapReplications: Int = 500,
    val useStationaryBootstrap: Boolean = true,
    val requireDeflatedSharpe: Boolean = true,
    val requireWhitesRealityCheck: Boolean = true,
    val multipleTestingCorrection: String = "holm_bh_reality_check",
    val samplingRobustnessBarMinutes: List<Int> = listOf(1, 5, 60, 1_440),
    val regimeSlices: List<String> = listOf("volatility", "liquidity", "funding", "open_interest")
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
    private var datasets: AlphaDatasetPolicy = AlphaDatasetPolicy()
    private var discovery: AlphaDiscoveryPolicy = AlphaDiscoveryPolicy()
    private var portfolio: AlphaPortfolioPolicy = AlphaPortfolioPolicy()
    private var validation: AlphaValidationPolicy = AlphaValidationPolicy()
    private var readiness: AlphaReadinessPolicy = AlphaReadinessPolicy()

    fun datasets(block: AlphaDatasetPolicyBuilder.() -> Unit) {
        datasets = AlphaDatasetPolicyBuilder().apply(block).build()
    }

    fun discovery(block: AlphaDiscoveryPolicyBuilder.() -> Unit) {
        discovery = AlphaDiscoveryPolicyBuilder().apply(block).build()
    }

    fun portfolio(block: AlphaPortfolioPolicyBuilder.() -> Unit) {
        portfolio = AlphaPortfolioPolicyBuilder().apply(block).build()
    }

    fun validation(block: AlphaValidationPolicyBuilder.() -> Unit) {
        validation = AlphaValidationPolicyBuilder().apply(block).build()
    }

    fun readiness(block: AlphaReadinessPolicyBuilder.() -> Unit) {
        readiness = AlphaReadinessPolicyBuilder().apply(block).build()
    }

    fun build(): ResearchPolicy = ResearchPolicy(
        canonicalFeatureTable = canonicalFeatureTable,
        computeCoverageBeforeRun = computeCoverageBeforeRun,
        abortOnInsufficientCoverage = abortOnInsufficientCoverage,
        allowRawFallback = allowRawFallback,
        datasets = datasets,
        discovery = discovery,
        portfolio = portfolio,
        validation = validation,
        readiness = readiness,
        strategyFamilies = strategyFamilies
    )
}

@TradingPolicyDsl
class AlphaDatasetPolicyBuilder {
    var marketExchange: String = "hyperliquid_mainnet"
    var executionExchange: String = "hyperliquid_mainnet"
    var canonicalBarMinutes: Int = 240
    var supportedSignalBarMinutes: List<Int> = listOf(240, 1_440)
    var defaultSignalBarMinutes: Int = 240
    var dailyBoundary: String = "UTC"
    var defaultLookbackHours: Int = 1_080
    var defaultForwardHours: Int = 72
    var anchorHorizonsMinutes: List<Int> = listOf(15, 60, 240, 720)
    var coreHorizonsDays: List<Int> = listOf(1, 3, 7, 14, 30, 60, 90)
    var volatilityLookbackDays: List<Int> = listOf(7, 14, 30, 60)
    var priceReferenceMode: String = "mid_vwap_hybrid"
    var includeFunding: Boolean = true
    var includeOpenInterest: Boolean = true
    var includeTradeFlow: Boolean = true
    var includeOrderbookConditioning: Boolean = true
    var includeReceiptTimestamps: Boolean = true
    var requireUtcDailyBars: Boolean = true
    var maxSymbols: Int = 0
    var discoveryMaxSymbols: Int = 0
    private var universeCache: UniverseCachePolicy = UniverseCachePolicy()
    private var featureCache: FeatureQueryCachePolicy = FeatureQueryCachePolicy()

    fun universeCache(block: UniverseCachePolicyBuilder.() -> Unit) {
        universeCache = UniverseCachePolicyBuilder().apply(block).build()
    }

    fun featureCache(block: FeatureQueryCachePolicyBuilder.() -> Unit) {
        featureCache = FeatureQueryCachePolicyBuilder().apply(block).build()
    }

    fun build(): AlphaDatasetPolicy = AlphaDatasetPolicy(
        marketExchange = marketExchange,
        executionExchange = executionExchange,
        canonicalBarMinutes = canonicalBarMinutes,
        supportedSignalBarMinutes = supportedSignalBarMinutes,
        defaultSignalBarMinutes = defaultSignalBarMinutes,
        dailyBoundary = dailyBoundary,
        defaultLookbackHours = defaultLookbackHours,
        defaultForwardHours = defaultForwardHours,
        anchorHorizonsMinutes = anchorHorizonsMinutes,
        coreHorizonsDays = coreHorizonsDays,
        volatilityLookbackDays = volatilityLookbackDays,
        priceReferenceMode = priceReferenceMode,
        includeFunding = includeFunding,
        includeOpenInterest = includeOpenInterest,
        includeTradeFlow = includeTradeFlow,
        includeOrderbookConditioning = includeOrderbookConditioning,
        includeReceiptTimestamps = includeReceiptTimestamps,
        requireUtcDailyBars = requireUtcDailyBars,
        maxSymbols = maxSymbols,
        discoveryMaxSymbols = discoveryMaxSymbols,
        universeCache = universeCache,
        featureCache = featureCache
    )
}

@TradingPolicyDsl
class SignalReadinessPolicyBuilder {
    var defaultExchange: String = "hyperliquid_mainnet"
    var minCoverageRatio: Double = 0.98
    var minFinalizedRatio: Double = 0.95
    var maxFeatureLagSeconds: Long = 180L
    var maxFinalizedLagMinutes: Long = 5L
    var minUniverseSymbols: Int = 12
    var minHistoryHours: Int = 2_160
    var requireFundingForCarryFamilies: Boolean = false
    var requireOpenInterestForCarryFamilies: Boolean = false
    var allowPriceOnlyResearch: Boolean = true

    fun build(): SignalReadinessPolicy = SignalReadinessPolicy(
        defaultExchange = defaultExchange,
        minCoverageRatio = minCoverageRatio,
        minFinalizedRatio = minFinalizedRatio,
        maxFeatureLagSeconds = maxFeatureLagSeconds,
        maxFinalizedLagMinutes = maxFinalizedLagMinutes,
        minUniverseSymbols = minUniverseSymbols,
        minHistoryHours = minHistoryHours,
        requireFundingForCarryFamilies = requireFundingForCarryFamilies,
        requireOpenInterestForCarryFamilies = requireOpenInterestForCarryFamilies,
        allowPriceOnlyResearch = allowPriceOnlyResearch
    )
}

@TradingPolicyDsl
class ExecutionReadinessPolicyBuilder {
    var minCoverageRatio: Double = 0.98
    var minExecutionObservedRatio: Double = 0.55
    var minTradeObservedRatioForEligibility: Double = 0.40
    var requireOrderbook: Boolean = true
    var requireFillTelemetry: Boolean = false
    var maxQuoteAgeSeconds: Long = 120L
    var maxLatencyDriftMs: Long = 5_000L

    fun build(): ExecutionReadinessPolicy = ExecutionReadinessPolicy(
        minExecutionObservedRatio = minExecutionObservedRatio,
        minTradeObservedRatioForEligibility = minTradeObservedRatioForEligibility,
        requireOrderbook = requireOrderbook,
        requireFillTelemetry = requireFillTelemetry,
        maxQuoteAgeSeconds = maxQuoteAgeSeconds,
        maxLatencyDriftMs = maxLatencyDriftMs
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
class PromotionReadinessPolicyBuilder {
    var requireSignalReadiness: Boolean = true
    var requireExecutionReadiness: Boolean = true
    var requireMultiplicityControls: Boolean = true
    var requireSamplingRobustness: Boolean = true
    var minRegimeSlicePassRatio: Double = 0.7
    var maxLiveVsBacktestDriftBps: Double = 5.0
    var minSampleCount: Int = 200
    var minRealizedReturnPct: Double = 0.5
    var minNetEdgeBps: Double = 2.0

    fun build(): PromotionReadinessPolicy = PromotionReadinessPolicy(
        requireSignalReadiness = requireSignalReadiness,
        requireExecutionReadiness = requireExecutionReadiness,
        requireMultiplicityControls = requireMultiplicityControls,
        requireSamplingRobustness = requireSamplingRobustness,
        minRegimeSlicePassRatio = minRegimeSlicePassRatio,
        maxLiveVsBacktestDriftBps = maxLiveVsBacktestDriftBps,
        minSampleCount = minSampleCount,
        minRealizedReturnPct = minRealizedReturnPct,
        minNetEdgeBps = minNetEdgeBps
    )
}

@TradingPolicyDsl
class AlphaReadinessPolicyBuilder {
    private var signal: SignalReadinessPolicy = SignalReadinessPolicy()
    private var execution: ExecutionReadinessPolicy = ExecutionReadinessPolicy()
    private var promotion: PromotionReadinessPolicy = PromotionReadinessPolicy()

    fun signal(block: SignalReadinessPolicyBuilder.() -> Unit) {
        signal = SignalReadinessPolicyBuilder().apply(block).build()
    }

    fun execution(block: ExecutionReadinessPolicyBuilder.() -> Unit) {
        execution = ExecutionReadinessPolicyBuilder().apply(block).build()
    }

    fun promotion(block: PromotionReadinessPolicyBuilder.() -> Unit) {
        promotion = PromotionReadinessPolicyBuilder().apply(block).build()
    }

    fun build(): AlphaReadinessPolicy = AlphaReadinessPolicy(
        signal = signal,
        execution = execution,
        promotion = promotion
    )
}

@TradingPolicyDsl
class AlphaSearchPolicyBuilder {
    var beamWidth: Int = 6
    var rounds: Int = 4
    var maxEvaluations: Int = 96
    var leaderboardSize: Int = 12
    var minBacktestTrades: Int = 12
    var minForwardTrades: Int = 4
    var minNetEdgeBps: Double = 2.0
    var maxSearchDrawdownPct: Double = 14.0
    var minForwardCalmar: Double = 0.10
    var maxTimeUnderWaterPct: Double = 92.0
    var maxCvar1dPct: Double = 8.0
    var minAlignedParticipationRate: Double = 0.15
    var maxWrongWayExposurePct: Double = 65.0
    var maxKillSwitchUtilization: Double = 1.0
    var scorePlateauToleranceBps: Double = 1.0

    fun build(): AlphaSearchPolicy = AlphaSearchPolicy(
        beamWidth = beamWidth,
        rounds = rounds,
        maxEvaluations = maxEvaluations,
        leaderboardSize = leaderboardSize,
        minBacktestTrades = minBacktestTrades,
        minForwardTrades = minForwardTrades,
        minNetEdgeBps = minNetEdgeBps,
        maxSearchDrawdownPct = maxSearchDrawdownPct,
        minForwardCalmar = minForwardCalmar,
        maxTimeUnderWaterPct = maxTimeUnderWaterPct,
        maxCvar1dPct = maxCvar1dPct,
        minAlignedParticipationRate = minAlignedParticipationRate,
        maxWrongWayExposurePct = maxWrongWayExposurePct,
        maxKillSwitchUtilization = maxKillSwitchUtilization,
        scorePlateauToleranceBps = scorePlateauToleranceBps
    )
}

@TradingPolicyDsl
class AlphaDiscoveryPolicyBuilder {
    var defaultStrategyFamily: String = "interday_relative_strength_trend_v1"
    var rebalanceCadenceHours: List<Int> = listOf(24, 72, 168)
    var executionWindowMinutes: List<Int> = listOf(60, 120)
    var selectionQuantiles: List<Double> = listOf(0.05, 0.10, 0.15)
    var useRegressionSlope: Boolean = true
    var useAdxFilter: Boolean = true
    var useMovingAverageFilter: Boolean = true
    var useVwapDeviation: Boolean = true
    var useSignedVolumeImbalance: Boolean = true
    var includeSamplingRobustnessCheck: Boolean = true
    private var search: AlphaSearchPolicy = AlphaSearchPolicy()

    fun search(block: AlphaSearchPolicyBuilder.() -> Unit) {
        search = AlphaSearchPolicyBuilder().apply(block).build()
    }

    fun build(): AlphaDiscoveryPolicy = AlphaDiscoveryPolicy(
        defaultStrategyFamily = defaultStrategyFamily,
        rebalanceCadenceHours = rebalanceCadenceHours,
        executionWindowMinutes = executionWindowMinutes,
        selectionQuantiles = selectionQuantiles,
        useRegressionSlope = useRegressionSlope,
        useAdxFilter = useAdxFilter,
        useMovingAverageFilter = useMovingAverageFilter,
        useVwapDeviation = useVwapDeviation,
        useSignedVolumeImbalance = useSignedVolumeImbalance,
        includeSamplingRobustnessCheck = includeSamplingRobustnessCheck,
        search = search
    )
}

@TradingPolicyDsl
class AlphaPortfolioPolicyBuilder {
    var longShort: Boolean = true
    var selectionMode: String = "quantile"
    var weightingMode: String = "volatility_scaled_score"
    var targetGrossFraction: Double = 1.0
    var targetNetFraction: Double = 0.0
    var maxWeightPerSymbol: Double = 0.08
    var maxTurnoverPerRebalance: Double = 1.0
    var turnoverPenaltyBps: Double = 3.0
    var maxParticipationRate: Double = 0.05
    var maxDepthFraction: Double = 0.15
    var rebalanceTargetExposureStep: Double = 0.15
    var minTargetExposureFraction: Double = 0.25
    var maxTargetExposureFraction: Double = 1.0
    var maxConcurrentPositions: Int = 16
    var maxConcurrentLongs: Int = 8
    var maxConcurrentShorts: Int = 8
    var useTrailingStops: Boolean = true
    var trailingStopVolMultiple: Double = 1.0
    var takeProfitVolMultiple: Double = 2.5
    var useMakerFirstExecution: Boolean = true

    fun build(): AlphaPortfolioPolicy = AlphaPortfolioPolicy(
        longShort = longShort,
        selectionMode = selectionMode,
        weightingMode = weightingMode,
        targetGrossFraction = targetGrossFraction,
        targetNetFraction = targetNetFraction,
        maxWeightPerSymbol = maxWeightPerSymbol,
        maxTurnoverPerRebalance = maxTurnoverPerRebalance,
        turnoverPenaltyBps = turnoverPenaltyBps,
        maxParticipationRate = maxParticipationRate,
        maxDepthFraction = maxDepthFraction,
        rebalanceTargetExposureStep = rebalanceTargetExposureStep,
        minTargetExposureFraction = minTargetExposureFraction,
        maxTargetExposureFraction = maxTargetExposureFraction,
        maxConcurrentPositions = maxConcurrentPositions,
        maxConcurrentLongs = maxConcurrentLongs,
        maxConcurrentShorts = maxConcurrentShorts,
        useTrailingStops = useTrailingStops,
        trailingStopVolMultiple = trailingStopVolMultiple,
        takeProfitVolMultiple = takeProfitVolMultiple,
        useMakerFirstExecution = useMakerFirstExecution
    )
}

@TradingPolicyDsl
class AlphaValidationPolicyBuilder {
    var walkForwardWindows: Int = 8
    var nestedCvFolds: Int = 4
    var purgedKFoldFolds: Int = 5
    var embargoBars: Int = 24
    var useCombinatorialPurgedCv: Boolean = true
    var bootstrapReplications: Int = 500
    var useStationaryBootstrap: Boolean = true
    var requireDeflatedSharpe: Boolean = true
    var requireWhitesRealityCheck: Boolean = true
    var multipleTestingCorrection: String = "holm_bh_reality_check"
    var samplingRobustnessBarMinutes: List<Int> = listOf(1, 5, 60, 1_440)
    var regimeSlices: List<String> = listOf("volatility", "liquidity", "funding", "open_interest")

    fun build(): AlphaValidationPolicy = AlphaValidationPolicy(
        walkForwardWindows = walkForwardWindows,
        nestedCvFolds = nestedCvFolds,
        purgedKFoldFolds = purgedKFoldFolds,
        embargoBars = embargoBars,
        useCombinatorialPurgedCv = useCombinatorialPurgedCv,
        bootstrapReplications = bootstrapReplications,
        useStationaryBootstrap = useStationaryBootstrap,
        requireDeflatedSharpe = requireDeflatedSharpe,
        requireWhitesRealityCheck = requireWhitesRealityCheck,
        multipleTestingCorrection = multipleTestingCorrection,
        samplingRobustnessBarMinutes = samplingRobustnessBarMinutes,
        regimeSlices = regimeSlices
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
                bootstrapHours = 2_160L
                refreshIntervalMs = 60_000L
                refreshOverlapMinutes = 5L
                backfillChunkHours = 3L
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
                interdayRelativeStrengthTrend = StrategyFamilyPolicy(
                    enabled = true,
                    stage = "active",
                    notes = "Primary interday cross-sectional trend search family"
                ),
                interdayResidualReversion = StrategyFamilyPolicy(
                    enabled = false,
                    stage = "parked",
                    notes = "Removed from v1 until the trend engine and universe bounds are stable"
                ),
                fundingBasisCarry = StrategyFamilyPolicy(enabled = false, stage = "planned"),
                flowConditioning = StrategyFamilyPolicy(enabled = true, stage = "planned"),
                volatilityRegimeFilter = StrategyFamilyPolicy(enabled = true, stage = "planned"),
                multiVenueRelativeValue = StrategyFamilyPolicy(enabled = false, stage = "deferred"),
                triangularArbitrage = StrategyFamilyPolicy(enabled = false, stage = "deprioritized")
            )

            datasets {
                marketExchange = "hyperliquid_mainnet"
                executionExchange = "hyperliquid_mainnet"
                canonicalBarMinutes = 240
                supportedSignalBarMinutes = listOf(240, 1_440)
                defaultSignalBarMinutes = 240
                dailyBoundary = "UTC"
                defaultLookbackHours = 1_080
                defaultForwardHours = 72
                anchorHorizonsMinutes = listOf(60, 240, 720, 1_440)
                coreHorizonsDays = listOf(1, 3, 7, 14, 30, 60, 90)
                volatilityLookbackDays = listOf(7, 14, 30, 60)
                priceReferenceMode = "utc_close_with_intraday_vwap"
                includeFunding = true
                includeOpenInterest = true
                includeTradeFlow = false
                includeOrderbookConditioning = false
                includeReceiptTimestamps = true
                requireUtcDailyBars = true
                maxSymbols = 0
                discoveryMaxSymbols = 0
                universeCache {
                    enabled = true
                    ttlSeconds = 300
                    maxEntries = 8
                }
                featureCache {
                    ttlSeconds = 60
                    maxEntries = 24
                }
            }

            discovery {
                defaultStrategyFamily = "interday_relative_strength_trend_v1"
                rebalanceCadenceHours = listOf(24, 72, 168)
                executionWindowMinutes = listOf(60, 120, 240)
                selectionQuantiles = listOf(0.05, 0.10, 0.15)
                useRegressionSlope = true
                useAdxFilter = true
                useMovingAverageFilter = true
                useVwapDeviation = false
                useSignedVolumeImbalance = false
                includeSamplingRobustnessCheck = true
                search {
                    beamWidth = 6
                    rounds = 4
                    maxEvaluations = 96
                    leaderboardSize = 12
                    minBacktestTrades = 12
                    minForwardTrades = 4
                    minNetEdgeBps = 2.0
                    maxSearchDrawdownPct = 14.0
                    scorePlateauToleranceBps = 1.0
                }
            }

            portfolio {
                longShort = true
                selectionMode = "quantile"
                weightingMode = "volatility_scaled_score"
                targetGrossFraction = 1.0
                targetNetFraction = 0.0
                maxWeightPerSymbol = 0.08
                maxTurnoverPerRebalance = 1.0
                turnoverPenaltyBps = 3.0
                maxParticipationRate = 0.05
                maxDepthFraction = 0.15
                rebalanceTargetExposureStep = 0.15
                minTargetExposureFraction = 0.25
                maxTargetExposureFraction = 1.0
                maxConcurrentPositions = 16
                maxConcurrentLongs = 8
                maxConcurrentShorts = 8
                useTrailingStops = true
                trailingStopVolMultiple = 1.0
                takeProfitVolMultiple = 2.5
                useMakerFirstExecution = true
            }

            validation {
                walkForwardWindows = 8
                nestedCvFolds = 4
                purgedKFoldFolds = 5
                embargoBars = 24
                useCombinatorialPurgedCv = true
                bootstrapReplications = 500
                useStationaryBootstrap = true
                requireDeflatedSharpe = true
                requireWhitesRealityCheck = true
                multipleTestingCorrection = "holm_bh_reality_check"
                samplingRobustnessBarMinutes = listOf(1, 5, 60, 1_440)
                regimeSlices = listOf("volatility", "liquidity", "funding", "open_interest")
            }

            readiness {
                signal {
                    defaultExchange = "hyperliquid_mainnet"
                    minCoverageRatio = 0.98
                    minFinalizedRatio = 0.95
                    maxFeatureLagSeconds = 180L
                    maxFinalizedLagMinutes = 5L
                    minUniverseSymbols = 12
                    minHistoryHours = 2_160
                    requireFundingForCarryFamilies = false
                    requireOpenInterestForCarryFamilies = false
                    allowPriceOnlyResearch = true
                }

                execution {
                    minExecutionObservedRatio = 0.55
                    minTradeObservedRatioForEligibility = 0.40
                    requireOrderbook = true
                    requireFillTelemetry = false
                    maxQuoteAgeSeconds = 120L
                    maxLatencyDriftMs = 5_000L
                }

                promotion {
                    requireSignalReadiness = true
                    requireExecutionReadiness = true
                    requireMultiplicityControls = true
                    requireSamplingRobustness = true
                    minRegimeSlicePassRatio = 0.7
                    maxLiveVsBacktestDriftBps = 5.0
                    minSampleCount = 200
                    minRealizedReturnPct = 0.5
                    minNetEdgeBps = 2.0
                }
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
