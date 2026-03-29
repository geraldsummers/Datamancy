package org.datamancy.trading.alpha

import java.time.Instant

data class InterdayAlphaConfig(
    val strategyFamily: String = "interday_relative_strength_trend_v2",
    val exchange: String = "hyperliquid_mainnet",
    val adjustmentMode: InterdayAdjustmentMode = InterdayAdjustmentMode.REBALANCE_STEP,
    val signalBarMinutes: Int = 240,
    val lookbackHours: Int = 1_080,
    val forwardHours: Int = 72,
    val rebalanceCadenceHours: Int = 24,
    val selectionQuantile: Double = 0.10,
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
    val openInterestWeight: Double = 0.15,
    val pullbackWeight: Double = 0.20,
    val adxThreshold: Double = 18.0,
    val minConfidence: Double = 0.50,
    val trailingStopVolMultiple: Double = 1.0,
    val takeProfitVolMultiple: Double = 3.0,
    val layeredStopFractions: List<Double> = listOf(0.35, 0.35, 0.30),
    val layeredStopMultipliers: List<Double> = listOf(1.0, 1.5, 2.0),
    val takeProfitFractions: List<Double> = listOf(0.20, 0.20),
    val takeProfitMultipliers: List<Double> = listOf(1.0, 1.6),
    val executionWindowMinutes: Int = 120,
    val capitalUsd: Double = 10_000.0,
    val maxSymbols: Int = 0,
    val requireFunding: Boolean = false,
    val requireOpenInterest: Boolean = false,
    val useExecutionConditioning: Boolean = false
)

data class InterdaySearchSpace(
    val adjustmentModes: List<InterdayAdjustmentMode> = emptyList(),
    val signalBarMinutes: List<Int> = emptyList(),
    val lookbackHours: List<Int> = emptyList(),
    val forwardHours: List<Int> = emptyList(),
    val rebalanceCadenceHours: List<Int> = emptyList(),
    val selectionQuantiles: List<Double> = emptyList(),
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
    val openInterestWeight: List<Double> = emptyList(),
    val pullbackWeight: List<Double> = emptyList(),
    val adxThreshold: List<Double> = emptyList(),
    val minConfidence: List<Double> = emptyList(),
    val trailingStopVolMultiple: List<Double> = emptyList(),
    val takeProfitVolMultiple: List<Double> = emptyList(),
    val executionWindowMinutes: List<Int> = emptyList()
)

data class InterdayAlphaSearchRequest(
    val baseConfig: InterdayAlphaConfig? = null,
    val searchSpace: InterdaySearchSpace = InterdaySearchSpace(),
    val maxEvaluations: Int? = null,
    val leaderboardSize: Int? = null,
    val includeInspection: Boolean = true
)

data class InterdayAlphaRunRequest(
    val config: InterdayAlphaConfig,
    val mode: AlphaRunMode = AlphaRunMode.OFFLINE_BACKTEST,
    val includeInspection: Boolean = true,
    val submitOrders: Boolean = false,
    val submitTopTargets: Int = 0
)

data class InterdayPerformance(
    val segment: String,
    val startTime: Instant?,
    val endTime: Instant?,
    val netReturnPct: Double,
    val grossReturnPct: Double,
    val sharpe: Double,
    val maxDrawdownPct: Double,
    val tradeCount: Int,
    val winRate: Double,
    val avgTurnoverPct: Double,
    val edgeAfterCostBps: Double,
    val bootstrapReturnP05Pct: Double,
    val bootstrapSharpeP05: Double,
    val stabilityScore: Double
)

data class InterdayValidation(
    val accepted: Boolean,
    val backtestAccepted: Boolean,
    val forwardAccepted: Boolean,
    val reasons: List<String>
)

data class InterdaySignalSnapshot(
    val symbol: String,
    val direction: AlphaDirection,
    val score: Double,
    val confidence: Double,
    val liquidityScore: Double,
    val trendScore: Double,
    val pullbackScore: Double,
    val fundingScore: Double,
    val openInterestScore: Double,
    val upperBound: Double,
    val lowerBound: Double,
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
    val reason: String,
    val segment: String
)

data class InterdayPortfolioSnapshot(
    val time: Instant,
    val equity: Double,
    val grossExposureFraction: Double,
    val netExposureFraction: Double,
    val openPositions: Int,
    val turnoverFraction: Double
)

data class InterdayInspectionPoint(
    val time: Instant,
    val close: Double,
    val score: Double,
    val upperBound: Double,
    val lowerBound: Double,
    val positionWeight: Double
)

data class InterdaySymbolInspection(
    val symbol: String,
    val points: List<InterdayInspectionPoint>
)

data class InterdayInspection(
    val portfolio: List<InterdayPortfolioSnapshot>,
    val symbols: List<InterdaySymbolInspection>
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
    val notes: List<String>
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
