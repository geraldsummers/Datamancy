package org.datamancy.trading.alpha

import org.datamancy.trading.policy.AlphaSearchPolicy
import java.time.Instant

data class InterdayAlphaConfig(
    val strategyFamily: String = "interday_relative_strength_trend_v2",
    val exchange: String = "hyperliquid_mainnet",
    val adjustmentMode: InterdayAdjustmentMode = InterdayAdjustmentMode.REBALANCE_STEP,
    val residualizationMode: InterdayResidualizationMode = InterdayResidualizationMode.MARKET,
    val residualizationBetaMode: InterdayResidualizationBetaMode = InterdayResidualizationBetaMode.SIMPLE,
    val residualizationMarketProxyMode: InterdayResidualizationMarketProxyMode = InterdayResidualizationMarketProxyMode.EQUAL_WEIGHT,
    val trendScoreMode: InterdayTrendScoreMode = InterdayTrendScoreMode.LEGACY,
    val signalBarMinutes: Int = 240,
    val lookbackHours: Int = 1_080,
    val forwardHours: Int = 72,
    val rebalanceCadenceHours: Int = 24,
    val factorLookbackDays: Int = 30,
    val residualizationHalfLifeDays: Int = 14,
    val selectionQuantile: Double = 0.10,
    val tailWeightingMode: InterdayTailWeightingMode = InterdayTailWeightingMode.VOLATILITY_SCALED,
    val fastTrendDays: Int = 3,
    val mediumTrendDays: Int = 7,
    val slowTrendDays: Int = 14,
    val regressionDays: Int = 14,
    val volatilityDays: Int = 14,
    val adxDays: Int = 14,
    val perturbationLookbackBars: Int = 3,
    val perturbationThresholdZ: Double = 0.75,
    val slopeWeight: Double = 0.25,
    val fundingWeight: Double = 0.15,
    val fundingOverlayMode: InterdayFundingOverlayMode = InterdayFundingOverlayMode.LINEAR_FACTOR,
    val openInterestWeight: Double = 0.15,
    val pullbackWeight: Double = 0.20,
    val minTrendAgreement: Double = 0.10,
    val adxThreshold: Double = 18.0,
    val minConfidence: Double = 0.50,
    val exitOverlayMode: InterdayExitOverlayMode = InterdayExitOverlayMode.TRAILING_AND_TAKE_PROFIT,
    val trailingStopVolMultiple: Double = 1.0,
    val takeProfitVolMultiple: Double = 3.0,
    val timeStopBars: Int = 3,
    val timeStopMinProgressVol: Double = 0.25,
    val layeredStopFractions: List<Double> = listOf(0.35, 0.35, 0.30),
    val layeredStopMultipliers: List<Double> = listOf(1.0, 1.5, 2.0),
    val takeProfitFractions: List<Double> = listOf(0.20, 0.20),
    val takeProfitMultipliers: List<Double> = listOf(1.0, 1.6),
    val executionWindowMinutes: Int = 120,
    val capitalUsd: Double = 10_000.0,
    val targetGrossFractionScale: Double = 1.0,
    val maxSymbols: Int = 0,
    val requireFunding: Boolean = false,
    val requireOpenInterest: Boolean = false,
    val useExecutionConditioning: Boolean = false,
    val empiricalFitRegularization: Double = 0.35,
    val empiricalMinTrainingObservations: Int = 96,
    val expectedCostPenaltyWeight: Double = 1.0,
    val turnoverPenaltyWeight: Double = 1.0,
    val entryEdgeFloorBps: Double = 1.0,
    val holdEdgeFloorBps: Double = 0.25,
    val regimeStrengthThreshold: Double = 0.18,
    val regimeDirectionalSuppressionThreshold: Double = 0.55,
    val regimeNetBiasScale: Double = 0.75,
    val flatRegimeGateMode: InterdayFlatRegimeGateMode = InterdayFlatRegimeGateMode.NONE,
    val flatRegimeMarketTrendThreshold: Double = 0.15,
    val flatRegimeBreadthThreshold: Double = 0.10,
    val flatRegimeGrossScale: Double = 0.65,
    val flatRegimeEntryEdgeFloorBoostBps: Double = 0.50,
    val flatRegimeEntryControlMode: InterdayFlatRegimeEntryControlMode = InterdayFlatRegimeEntryControlMode.NONE,
    val flatRegimeMinDispersion: Double = 0.20,
    val flatRegimeTrendAgreementBoost: Double = 0.10,
    val compressionPenaltyMode: InterdayCompressionPenaltyMode = InterdayCompressionPenaltyMode.NONE,
    val compressionWindowDays: Int = 10,
    val compressionSleeveSizePerSide: Int = 12,
    val compressionThresholdZ: Double = 1.0,
    val compressionPenaltyStrength: Double = 0.25,
    val flatHazardMode: InterdayFlatHazardMode = InterdayFlatHazardMode.NONE,
    val flatHazardGrossScaleFloor: Double = 1.0,
    val flatHazardCompressionWindowDays: Int = 10,
    val flatHazardCompressionSleeveSizePerSide: Int = 12,
    val flatHazardCompressionThresholdZ: Double = 0.75
)

data class InterdaySearchSpace(
    val adjustmentModes: List<InterdayAdjustmentMode> = emptyList(),
    val residualizationModes: List<InterdayResidualizationMode> = emptyList(),
    val residualizationBetaModes: List<InterdayResidualizationBetaMode> = emptyList(),
    val residualizationMarketProxyModes: List<InterdayResidualizationMarketProxyMode> = emptyList(),
    val trendScoreModes: List<InterdayTrendScoreMode> = emptyList(),
    val signalBarMinutes: List<Int> = emptyList(),
    val lookbackHours: List<Int> = emptyList(),
    val forwardHours: List<Int> = emptyList(),
    val rebalanceCadenceHours: List<Int> = emptyList(),
    val factorLookbackDays: List<Int> = emptyList(),
    val residualizationHalfLifeDays: List<Int> = emptyList(),
    val selectionQuantiles: List<Double> = emptyList(),
    val tailWeightingModes: List<InterdayTailWeightingMode> = emptyList(),
    val fastTrendDays: List<Int> = emptyList(),
    val mediumTrendDays: List<Int> = emptyList(),
    val slowTrendDays: List<Int> = emptyList(),
    val regressionDays: List<Int> = emptyList(),
    val volatilityDays: List<Int> = emptyList(),
    val adxDays: List<Int> = emptyList(),
    val perturbationLookbackBars: List<Int> = emptyList(),
    val perturbationThresholdZ: List<Double> = emptyList(),
    val slopeWeight: List<Double> = emptyList(),
    val fundingWeight: List<Double> = emptyList(),
    val fundingOverlayModes: List<InterdayFundingOverlayMode> = emptyList(),
    val openInterestWeight: List<Double> = emptyList(),
    val pullbackWeight: List<Double> = emptyList(),
    val minTrendAgreement: List<Double> = emptyList(),
    val adxThreshold: List<Double> = emptyList(),
    val minConfidence: List<Double> = emptyList(),
    val exitOverlayModes: List<InterdayExitOverlayMode> = emptyList(),
    val trailingStopVolMultiple: List<Double> = emptyList(),
    val takeProfitVolMultiple: List<Double> = emptyList(),
    val timeStopBars: List<Int> = emptyList(),
    val timeStopMinProgressVol: List<Double> = emptyList(),
    val executionWindowMinutes: List<Int> = emptyList(),
    val targetGrossFractionScale: List<Double> = emptyList(),
    val expectedCostPenaltyWeight: List<Double> = emptyList(),
    val turnoverPenaltyWeight: List<Double> = emptyList(),
    val entryEdgeFloorBps: List<Double> = emptyList(),
    val holdEdgeFloorBps: List<Double> = emptyList(),
    val regimeDirectionalSuppressionThreshold: List<Double> = emptyList(),
    val regimeNetBiasScale: List<Double> = emptyList(),
    val flatRegimeGateModes: List<InterdayFlatRegimeGateMode> = emptyList(),
    val flatRegimeMarketTrendThreshold: List<Double> = emptyList(),
    val flatRegimeBreadthThreshold: List<Double> = emptyList(),
    val flatRegimeGrossScale: List<Double> = emptyList(),
    val flatRegimeEntryEdgeFloorBoostBps: List<Double> = emptyList(),
    val flatRegimeEntryControlModes: List<InterdayFlatRegimeEntryControlMode> = emptyList(),
    val flatRegimeMinDispersion: List<Double> = emptyList(),
    val flatRegimeTrendAgreementBoost: List<Double> = emptyList(),
    val compressionPenaltyModes: List<InterdayCompressionPenaltyMode> = emptyList(),
    val compressionWindowDays: List<Int> = emptyList(),
    val compressionSleeveSizePerSide: List<Int> = emptyList(),
    val compressionThresholdZ: List<Double> = emptyList(),
    val compressionPenaltyStrength: List<Double> = emptyList(),
    val flatHazardModes: List<InterdayFlatHazardMode> = emptyList(),
    val flatHazardGrossScaleFloor: List<Double> = emptyList(),
    val flatHazardCompressionWindowDays: List<Int> = emptyList(),
    val flatHazardCompressionSleeveSizePerSide: List<Int> = emptyList(),
    val flatHazardCompressionThresholdZ: List<Double> = emptyList()
)

data class InterdayAlphaSearchRequest(
    val baseConfig: InterdayAlphaConfig? = null,
    val searchSpace: InterdaySearchSpace = InterdaySearchSpace(),
    val maxEvaluations: Int? = null,
    val leaderboardSize: Int? = null,
    val includeInspection: Boolean = true,
    val thresholdCalibration: InterdayThresholdCalibrationRequest? = null
)

data class InterdayAlphaRunRequest(
    val config: InterdayAlphaConfig,
    val mode: AlphaRunMode = AlphaRunMode.OFFLINE_BACKTEST,
    val comparisonConfigs: List<InterdayAlphaConfig>? = null,
    val includeInspection: Boolean = true,
    val submitOrders: Boolean = false,
    val submitTopTargets: Int = 0
)

data class InterdayPerformance(
    val segment: String,
    val startTime: Instant?,
    val endTime: Instant?,
    val netReturnPct: Double,
    val annualizedReturnPct: Double,
    val grossReturnPct: Double,
    val sharpe: Double,
    val maxDrawdownPct: Double,
    val tradeCount: Int,
    val winRate: Double,
    val avgTurnoverPct: Double,
    val edgeAfterCostBps: Double,
    val bootstrapReturnP05Pct: Double,
    val bootstrapSharpeP05: Double,
    val stabilityScore: Double,
    val calmar: Double,
    val ulcerIndex: Double,
    val timeUnderWaterPct: Double,
    val cvar1dPct: Double,
    val alignedParticipationRate: Double,
    val wrongWayExposurePct: Double,
    val profitGivebackPct: Double,
    val pnlSkew: Double,
    val avgWinnerLoserRatio: Double,
    val killSwitchUtilizationMax: Double,
    val regimeSlices: List<InterdayRegimeSlicePerformance> = emptyList()
)

data class InterdayRegimeSlicePerformance(
    val slice: String,
    val bucket: String,
    val sampleCount: Int,
    val tradeCount: Int,
    val netReturnPct: Double,
    val edgeAfterCostBps: Double,
    val sharpe: Double,
    val avgTurnoverPct: Double,
    val avgGrossExposureFraction: Double,
    val positiveReturnRate: Double
)

data class InterdayPurgedFoldValidation(
    val fold: Int,
    val startTime: Instant?,
    val endTime: Instant?,
    val sampleCount: Int,
    val tradeCount: Int,
    val netReturnPct: Double,
    val sharpe: Double,
    val edgeAfterCostBps: Double,
    val accepted: Boolean
)

data class InterdayMultiplicityValidation(
    val enabled: Boolean,
    val candidateCount: Int,
    val effectiveTrialCount: Int,
    val validationSampleCount: Int,
    val embargoBars: Int,
    val benchmarkSharpe: Double,
    val observedSharpe: Double,
    val probabilisticSharpeRatio: Double,
    val probabilisticSharpePValue: Double,
    val deflatedSharpeRatio: Double,
    val deflatedSharpePValue: Double,
    val deflatedAccepted: Boolean,
    val whiteRealityCheckPValue: Double?,
    val whiteRealityAccepted: Boolean,
    val benjaminiYekutieliQValue: Double?,
    val benjaminiYekutieliAccepted: Boolean,
    val purgedFoldPassRatio: Double,
    val purgedAccepted: Boolean,
    val neighborhoodPassRatio: Double?,
    val neighborhoodAccepted: Boolean,
    val promotionAccepted: Boolean,
    val folds: List<InterdayPurgedFoldValidation>,
    val notes: List<String>
)

data class InterdayValidation(
    val accepted: Boolean,
    val backtestAccepted: Boolean,
    val forwardAccepted: Boolean,
    val reasons: List<String>,
    val multiplicity: InterdayMultiplicityValidation? = null
)

data class InterdaySignalSnapshot(
    val symbol: String,
    val direction: AlphaDirection,
    val score: Double,
    val empiricalScore: Double,
    val residualRank: Double,
    val confidence: Double,
    val liquidityScore: Double,
    val trendScore: Double,
    val trendAgreement: Double,
    val pullbackScore: Double,
    val fundingScore: Double,
    val openInterestScore: Double,
    val expansionScore: Double,
    val reversalRiskScore: Double,
    val fundingOverlayMultiplier: Double = 1.0,
    val marketBeta: Double,
    val upperBound: Double,
    val lowerBound: Double,
    val expectedResidualReturnBps: Double,
    val expectedEntryCostBps: Double,
    val expectedTurnoverPenaltyBps: Double,
    val expectedNetEdgeBps: Double,
    val close: Double,
    val predictedVolatility: Double
)

data class InterdayTradeRecord(
    val symbol: String,
    val direction: AlphaDirection,
    val entryTime: Instant,
    val exitTime: Instant,
    val entryPrice: Double,
    val exitPrice: Double,
    val weightFraction: Double,
    val pnlPct: Double,
    val maxFavorablePnlPct: Double,
    val profitGivebackPct: Double,
    val reason: String,
    val segment: String
)

data class InterdayPortfolioSnapshot(
    val time: Instant,
    val equity: Double,
    val grossExposureFraction: Double,
    val longExposureFraction: Double,
    val shortExposureFraction: Double,
    val netExposureFraction: Double,
    val openPositions: Int,
    val turnoverFraction: Double,
    val regimeScore: Double = 0.0,
    val regimeStrength: Double = 0.0,
    val alignedExposureFraction: Double = 0.0,
    val wrongWayExposureFraction: Double = 0.0,
    val killSwitchUtilization: Double = 0.0
)

data class InterdayInspectionPoint(
    val time: Instant,
    val close: Double,
    val score: Double,
    val empiricalScore: Double,
    val confidence: Double,
    val regimeScore: Double,
    val expansionScore: Double,
    val reversalRiskScore: Double,
    val upperBound: Double,
    val lowerBound: Double,
    val expectedResidualReturnBps: Double = 0.0,
    val expectedEntryCostBps: Double = 0.0,
    val expectedNetEdgeBps: Double = 0.0,
    val desiredWeight: Double = 0.0,
    val appliedDelta: Double = 0.0,
    val entryEligible: Boolean = false,
    val regimeBlocked: Boolean = false,
    val positionWeight: Double
)

data class InterdaySymbolInspection(
    val symbol: String,
    val points: List<InterdayInspectionPoint>
)

data class InterdayInspection(
    val portfolio: List<InterdayPortfolioSnapshot>,
    val symbols: List<InterdaySymbolInspection>,
    val regimes: List<InterdayRegimeSnapshot>,
    val compressionDiagnostics: List<InterdayCompressionDiagnosticPoint> = emptyList()
)

data class InterdayRegimeSnapshot(
    val time: Instant,
    val regimeScore: Double,
    val breadth: Double,
    val anchorTrend: Double,
    val dispersion: Double,
    val realizedVolatility: Double,
    val liquidityScore: Double,
    val fundingPressure: Double,
    val openInterestPressure: Double,
    val marketTrendScore: Double,
    val grossExposureFraction: Double,
    val longExposureFraction: Double,
    val shortExposureFraction: Double,
    val netExposureFraction: Double,
    val alignedExposureFraction: Double,
    val wrongWayExposureFraction: Double,
    val killSwitchUtilization: Double
)

data class InterdayCompressionDiagnosticPoint(
    val time: Instant,
    val windowBars: Int,
    val sleeveSizePerSide: Int,
    val pc1Share: Double,
    val coMomentum: Double,
    val pc1ShareZ: Double,
    val coMomentumZ: Double,
    val futureFactorReturnBps: Double,
    val longSleeveSize: Int,
    val shortSleeveSize: Int,
    val marketTrendScore: Double,
    val breadth: Double,
    val dispersion: Double
)

data class InterdayCandidateEvaluation(
    val rank: Int,
    val config: InterdayAlphaConfig,
    val backtest: InterdayPerformance,
    val forward: InterdayPerformance,
    val validation: InterdayValidation,
    val selectedSignals: List<InterdaySignalSnapshot>,
    val targets: List<AlphaPortfolioTarget>
)

data class InterdayThresholdCalibrationRequest(
    val thresholdGridBps: List<Double> = emptyList(),
    val thresholdStepBps: Double = 0.25,
    val minThresholdBps: Double? = null,
    val maxThresholdBps: Double? = null,
    val minAcceptedCandidates: Int = 3,
    val minForwardPositiveRatio: Double = 0.60,
    val minMedianForwardEdgeBps: Double = 0.0
)

data class InterdayThresholdCalibrationPoint(
    val minNetEdgeBps: Double,
    val acceptedCandidates: Int,
    val edgeOnlyRejectCount: Int,
    val positiveForwardRatio: Double,
    val medianBacktestEdgeBps: Double,
    val medianForwardEdgeBps: Double,
    val medianForwardCalmar: Double,
    val medianForwardTrades: Double,
    val feasible: Boolean
)

data class InterdayThresholdCalibrationResult(
    val currentPolicy: AlphaSearchPolicy,
    val recommendedPolicy: AlphaSearchPolicy,
    val selectedMinNetEdgeBps: Double,
    val sourceCandidates: Int,
    val acceptedCandidatesAtSelectedThreshold: Int,
    val thresholdSweep: List<InterdayThresholdCalibrationPoint>,
    val notes: List<String>
)

data class InterdayExecutionPreview(
    val plan: AlphaExecutionPlan?,
    val submissions: List<AlphaExecutionSubmission>,
    val notes: List<String>
)

data class InterdayAlphaSearchResponse(
    val generatedAt: Instant,
    val defaults: AlphaDiscoveryDefaults,
    val searchRequest: InterdayAlphaSearchRequest,
    val evaluatedConfigs: Int,
    val leaderboard: List<InterdayCandidateEvaluation>,
    val thresholdCalibration: InterdayThresholdCalibrationResult? = null,
    val notes: List<String>
)

data class InterdayAlphaRunResponse(
    val generatedAt: Instant,
    val mode: AlphaRunMode,
    val config: InterdayAlphaConfig,
    val backtest: InterdayPerformance,
    val forward: InterdayPerformance,
    val validation: InterdayValidation,
    val selectedSignals: List<InterdaySignalSnapshot>,
    val targets: List<AlphaPortfolioTarget>,
    val executionPreview: InterdayExecutionPreview,
    val trades: List<InterdayTradeRecord>,
    val inspection: InterdayInspection?,
    val comparisonEvaluations: List<InterdayCandidateEvaluation> = emptyList(),
    val notes: List<String>,
    val runId: String? = null,
    val grafanaPath: String? = null
)

data class InterdayAlphaLeaderboardResponse(
    val generatedAt: Instant,
    val leaderboard: List<InterdayCandidateEvaluation>,
    val sourceSearchGeneratedAt: Instant?,
    val notes: List<String>
)

data class InterdayPanelRequest(
    val exchange: String,
    val signalBarMinutes: Int,
    val startTime: Instant,
    val endTime: Instant,
    val maxSymbols: Int = 0
)

data class InterdayBar(
    val time: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val tradeVolume: Double,
    val buyVolume: Double,
    val sellVolume: Double,
    val spreadBps: Double?,
    val depthUsd: Double?,
    val fundingRate: Double?,
    val openInterest: Double?,
    val tradeObservedRatio: Double,
    val orderbookObservedRatio: Double,
    val assetContextObservedRatio: Double
)

data class InterdaySymbolSeries(
    val symbol: String,
    val bars: List<InterdayBar?>
)

data class InterdayPanel(
    val exchange: String,
    val signalBarMinutes: Int,
    val timeline: List<Instant>,
    val series: List<InterdaySymbolSeries>
)
