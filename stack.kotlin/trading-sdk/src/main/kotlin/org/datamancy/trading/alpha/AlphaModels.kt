package org.datamancy.trading.alpha

import java.time.Instant

enum class AlphaDirection {
    LONG,
    SHORT
}

enum class AlphaRunMode {
    OFFLINE_BACKTEST,
    WALK_FORWARD,
    FORWARD_PAPER,
    TESTNET_LIVE,
    LIVE
}

enum class AlphaExecutionStyle {
    MAKER_RAMP,
    TWAP,
    POV,
    VWAP_TRACK
}

enum class InterdayAdjustmentMode {
    REBALANCE_STEP,
    CONTINUOUS_RAMP
}

enum class InterdayResidualizationMode {
    NONE,
    MARKET
}

enum class InterdayResidualizationBetaMode {
    SIMPLE,
    EWMA
}

enum class InterdayResidualizationMarketProxyMode {
    EQUAL_WEIGHT,
    LIQUIDITY_WEIGHTED
}

enum class InterdayTrendScoreMode {
    LEGACY,
    VOL_NORM_RETURN_STACK,
    REGRESSION_TSTAT,
    EMA_RETURN_STACK,
    VOL_NORM_PLUS_TSTAT
}

enum class InterdayFundingOverlayMode {
    NONE,
    LINEAR_FACTOR,
    BOUNDED_REINFORCEMENT,
    CROWDING_GUARD
}

enum class InterdayTailWeightingMode {
    VOLATILITY_SCALED,
    EQUAL_WEIGHT
}

enum class InterdayExitOverlayMode {
    NONE,
    TRAILING_ONLY,
    TRAILING_AND_TAKE_PROFIT,
    TIME_STOP,
    TREND_BREAK
}

enum class InterdayFlatRegimeGateMode {
    NONE,
    GROSS_THROTTLE,
    ENTRY_EDGE_BOOST,
    COMBINED
}

enum class InterdayFlatRegimeEntryControlMode {
    NONE,
    DISPERSION_GUARD,
    CONFIRMATION_BOOST,
    COMBINED
}

enum class InterdayCompressionPenaltyMode {
    NONE,
    PC1_SHARE
}

enum class InterdayFlatHazardMode {
    NONE,
    MARKET_TREND_ONLY,
    MARKET_TREND_AND_PC1_SHARE
}

data class UniverseBoundsSpec(
    val timeSeriesNormalization: String = "rolling_median_mad",
    val crossSectionalNormalization: String = "rank_gaussian",
    val lowerBandDefinition: String = "relative_rank_lower_band",
    val upperBandDefinition: String = "relative_rank_upper_band",
    val lookbackDays: List<Int> = listOf(7, 14, 30, 60),
    val lowerQuantiles: List<Double> = listOf(0.05, 0.10, 0.15),
    val upperQuantiles: List<Double> = listOf(0.85, 0.90, 0.95),
    val notes: String = "Bounds are defined relative to the active universe after normalization, not absolute token price levels."
)

data class AlphaDatasetDefaults(
    val marketExchange: String,
    val executionExchange: String,
    val canonicalBarMinutes: Int,
    val supportedSignalBarMinutes: List<Int>,
    val defaultSignalBarMinutes: Int,
    val defaultLookbackHours: Int,
    val defaultForwardHours: Int,
    val anchorHorizonsMinutes: List<Int>,
    val coreHorizonsDays: List<Int>,
    val volatilityLookbackDays: List<Int>,
    val priceReferenceMode: String,
    val featureFamilies: List<String>,
    val universeBounds: UniverseBoundsSpec
)

data class AlphaDatasetValidationRequest(
    val exchange: String? = null,
    val signalBarMinutes: Int,
    val lookbackHours: Int,
    val forwardHours: Int,
    val requireFunding: Boolean = true,
    val requireOpenInterest: Boolean = true,
    val requireExecutionConditioning: Boolean = false
)

data class AlphaDatasetValidation(
    val accepted: Boolean,
    val exchange: String,
    val signalBarMinutes: Int,
    val lookbackHours: Int,
    val forwardHours: Int,
    val requiredFeatureFamilies: List<String>,
    val requiredExecutionConditioning: Boolean,
    val reasons: List<String>
)

data class AlphaDatasetRefreshRequest(
    val exchange: String? = null,
    val signalBarMinutes: Int,
    val lookbackHours: Int,
    val symbols: List<String>? = emptyList()
)

data class AlphaDatasetRefreshResponse(
    val startedAt: Instant,
    val completedAt: Instant,
    val exchange: String,
    val signalBarMinutes: Int,
    val storedDataType: String,
    val lookbackHours: Int,
    val refreshedSymbols: Int,
    val storedBars: Int,
    val notes: List<String>
)

data class AlphaValidationDefaults(
    val walkForwardWindows: Int,
    val nestedCvFolds: Int,
    val purgedKFoldFolds: Int,
    val embargoBars: Int,
    val atomicBlockBars: Int,
    val activeBlocksPerFold: Int,
    val purgeBlocksPerSide: Int,
    val maxConcurrentFoldEvaluations: Int,
    val empiricalWeightFitPasses: Int,
    val bootstrapReplications: Int,
    val requireDeflatedSharpe: Boolean,
    val requireWhitesRealityCheck: Boolean,
    val regimeSlices: List<String>
)

data class AlphaDiscoveryDefaults(
    val strategyFamily: String,
    val supportedSignalBarMinutes: List<Int>,
    val defaultSignalBarMinutes: Int,
    val defaultLookbackHours: Int,
    val defaultForwardHours: Int,
    val rebalanceCadenceHours: List<Int>,
    val executionWindowMinutes: List<Int>,
    val selectionQuantiles: List<Double>,
    val defaultConfig: InterdayAlphaConfig,
    val enabledFeatures: List<String>,
    val universeBounds: UniverseBoundsSpec,
    val validation: AlphaValidationDefaults
)

data class AlphaDiscoveryCandidateRequest(
    val strategyFamily: String? = null,
    val maxCandidates: Int = 24,
    val signalBarMinutes: List<Int> = emptyList(),
    val rebalanceCadenceHours: List<Int> = emptyList(),
    val selectionQuantiles: List<Double> = emptyList()
)

data class AlphaDiscoveryCandidate(
    val candidateId: String,
    val strategyFamily: String,
    val signalBarMinutes: Int,
    val lookbackHours: Int,
    val forwardHours: Int,
    val rebalanceCadenceHours: Int,
    val selectionQuantile: Double,
    val enabledFeatures: List<String>,
    val notes: List<String>
)

data class AlphaSignalScore(
    val symbol: String,
    val score: Double,
    val confidence: Double,
    val predictedVolatility: Double,
    val liquidityScore: Double = 1.0,
    val sector: String? = null,
    val expectedResidualReturnBps: Double = 0.0,
    val expectedEntryCostBps: Double = 0.0,
    val expectedTurnoverPenaltyBps: Double = 0.0,
    val expectedNetEdgeBps: Double = 0.0,
    val currentWeightFraction: Double = 0.0,
    val sizingMultiplier: Double = 1.0
)

data class AlphaPortfolioDefaults(
    val longShort: Boolean,
    val selectionMode: String,
    val weightingMode: String,
    val targetGrossFraction: Double,
    val targetNetFraction: Double,
    val maxWeightPerSymbol: Double,
    val maxConcurrentLongs: Int,
    val maxConcurrentShorts: Int,
    val rebalanceTargetExposureStep: Double,
    val minTargetExposureFraction: Double,
    val maxTargetExposureFraction: Double,
    val useTrailingStops: Boolean,
    val trailingStopVolMultiple: Double,
    val takeProfitVolMultiple: Double,
    val turnoverPenaltyBps: Double,
    val maxParticipationRate: Double
)

data class AlphaPortfolioRequest(
    val signals: List<AlphaSignalScore>,
    val selectionQuantile: Double? = null,
    val respectProvidedSignalSet: Boolean = false,
    val longShort: Boolean? = null,
    val weightingMode: InterdayTailWeightingMode? = null,
    val targetGrossFraction: Double? = null,
    val targetNetFraction: Double? = null,
    val maxWeightPerSymbol: Double? = null,
    val maxConcurrentLongs: Int? = null,
    val maxConcurrentShorts: Int? = null,
    val currentWeightsBySymbol: Map<String, Double> = emptyMap(),
    val minExpectedNetEdgeBps: Double? = null
)

data class AlphaPortfolioTarget(
    val symbol: String,
    val direction: AlphaDirection,
    val weightFraction: Double,
    val leverageMultiplier: Double,
    val confidence: Double,
    val score: Double,
    val normalizedScore: Double,
    val expectedNetEdgeBps: Double = 0.0,
    val expectedCostBps: Double = 0.0,
    val turnoverDeltaFraction: Double = 0.0,
    val trailingStopVolMultiple: Double,
    val takeProfitVolMultiple: Double,
    val rationale: String
)

data class AlphaPortfolioResponse(
    val targets: List<AlphaPortfolioTarget>,
    val selectedLongs: Int,
    val selectedShorts: Int,
    val targetExposureFraction: Double,
    val targetGrossFraction: Double,
    val targetNetFraction: Double,
    val turnoverPenaltyBps: Double,
    val notes: List<String>
)

data class AlphaExecutionDefaults(
    val makerFeeBps: Double,
    val takerFeeBps: Double,
    val quoteExchange: String,
    val testnetQuoteExchange: String,
    val rebalanceTargetExposureStep: Double,
    val maxParticipationRate: Double,
    val useMakerFirstExecution: Boolean,
    val executionWindowMinutes: List<Int>
)

data class AlphaExecutionPlanRequest(
    val targets: List<AlphaPortfolioTarget>,
    val mode: AlphaRunMode = AlphaRunMode.FORWARD_PAPER,
    val executionWindowMinutes: Int? = null,
    val targetParticipationRate: Double? = null,
    val defaultSpreadBps: Double = 6.0,
    val defaultImpactBps: Double = 4.0,
    val allowTakerFallback: Boolean = true
)

data class AlphaExecutionChildOrder(
    val symbol: String,
    val direction: AlphaDirection,
    val style: AlphaExecutionStyle,
    val sequence: Int,
    val weightFraction: Double,
    val expectedFeeBps: Double,
    val expectedSpreadCostBps: Double,
    val expectedImpactBps: Double,
    val triggerMinutesFromStart: Int
)

data class AlphaExecutionPlan(
    val mode: AlphaRunMode,
    val executionWindowMinutes: Int,
    val participationRate: Double,
    val childOrders: List<AlphaExecutionChildOrder>,
    val estimatedCostBps: Double,
    val estimatedMakerShare: Double,
    val notes: List<String>
)

data class AlphaExecutionSubmitRequest(
    val exchange: String = "hyperliquid",
    val symbol: String,
    val direction: AlphaDirection,
    val orderType: String = "MARKET",
    val size: Double,
    val price: Double? = null,
    val mode: AlphaRunMode = AlphaRunMode.FORWARD_PAPER,
    val reduceOnly: Boolean = false,
    val urgencyClass: String? = null,
    val feeTier: String? = null,
    val maxSlippageBps: Double? = null,
    val cancelAfterMs: Long? = null
)

data class AlphaExecutionSubmission(
    val accepted: Boolean,
    val exchange: String,
    val symbol: String,
    val mode: AlphaRunMode,
    val orderId: String? = null,
    val status: String? = null,
    val filledSize: Double? = null,
    val fillPrice: Double? = null,
    val upstreamCode: Int? = null,
    val error: String? = null,
    val notes: List<String> = emptyList()
)

data class AlphaExecutionFill(
    val symbol: String,
    val direction: AlphaDirection,
    val plannedWeightFraction: Double,
    val filledWeightFraction: Double,
    val expectedPrice: Double,
    val executedPrice: Double,
    val feeBps: Double,
    val latencyMs: Long
)

data class AlphaExecutionMonitorRequest(
    val fills: List<AlphaExecutionFill>
)

data class AlphaExecutionMonitorSummary(
    val fillRatio: Double,
    val averageSlippageBps: Double,
    val feeBurnBps: Double,
    val latencyP50Ms: Long,
    val latencyP95Ms: Long,
    val executedNotionalFraction: Double,
    val alerts: List<String>
)

data class AlphaWorkflowPlanRequest(
    val exchange: String? = null,
    val signalBarMinutes: Int? = null,
    val forwardHours: Int? = null,
    val mode: AlphaRunMode = AlphaRunMode.FORWARD_PAPER
)

data class AlphaWorkflowStage(
    val name: String,
    val service: String,
    val endpoint: String,
    val required: Boolean,
    val purpose: String
)

data class AlphaWorkflowPlan(
    val exchange: String,
    val signalBarMinutes: Int,
    val forwardHours: Int,
    val mode: AlphaRunMode,
    val stages: List<AlphaWorkflowStage>,
    val notes: List<String>
)
