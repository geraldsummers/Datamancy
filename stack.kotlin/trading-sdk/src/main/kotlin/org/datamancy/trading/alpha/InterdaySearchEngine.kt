package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

class InterdaySearchEngine(
    private val panelSource: InterdayPanelSource,
    private val policyProvider: () -> TradingPolicy,
    private val portfolioConstructor: AlphaPortfolioConstructor = AlphaPortfolioConstructor(policyProvider),
    private val executionPlanner: AlphaExecutionPlanner = AlphaExecutionPlanner(policyProvider),
    private val thresholdCalibrator: InterdayThresholdCalibrator = InterdayThresholdCalibrator()
) {
    fun defaults(): AlphaDiscoveryDefaults = AlphaDefaultsFactory.discoveryDefaults(policyProvider())

    suspend fun search(request: InterdayAlphaSearchRequest): InterdayAlphaSearchResponse {
        val policy = policyProvider()
        val discoveryDefaults = defaults()
        val searchPolicy = policy.research.discovery.search
        val baseConfig = request.baseConfig ?: defaultConfig(discoveryDefaults)
        val maxEvaluations = request.maxEvaluations ?: searchPolicy.maxEvaluations
        val leaderboardSize = request.leaderboardSize ?: searchPolicy.leaderboardSize
        val configs = generateConfigs(baseConfig, request.searchSpace, maxEvaluations)
        val evaluations = buildList {
            configs.groupBy(::panelKeyFor).values.forEach { groupedConfigs ->
                val representative = groupedConfigs.first()
                val panel = runCatching { loadPanelForSearch(representative) }.getOrElse { error ->
                    groupedConfigs.forEach { config ->
                        add(failedEvaluation(config, error))
                    }
                    return@forEach
                }
                groupedConfigs.forEach { config ->
                    add(
                        runCatching {
                            evaluate(config = config, panel = panel, mode = AlphaRunMode.OFFLINE_BACKTEST, includeInspection = false)
                        }.getOrElse { error ->
                            failedEvaluation(config, error)
                        }
                    )
                }
            }
        }
        val failedEvaluations = evaluations.count { !it.validation.accepted && it.validation.reasons.any { reason -> reason.startsWith("evaluation failed:") } }
        val rankedAll = evaluations
            .sortedWith(survivorOrdering())
            .mapIndexed { index, bundle -> candidateEvaluation(index + 1, bundle) }
        val ranked = rankedAll.take(leaderboardSize)
        val thresholdCalibration = request.thresholdCalibration?.let {
            thresholdCalibrator.calibrate(
                evaluations = rankedAll,
                currentPolicy = searchPolicy,
                request = it
            )
        }

        return InterdayAlphaSearchResponse(
            generatedAt = Instant.now(),
            defaults = discoveryDefaults,
            searchRequest = request.copy(baseConfig = baseConfig),
            evaluatedConfigs = evaluations.size,
            leaderboard = ranked,
            thresholdCalibration = thresholdCalibration,
            notes = listOf(
                "Signals are ranked cross-sectionally relative to the active universe instead of using absolute token price levels.",
                "Common market structure is removed before ranking so the engine searches for symbol-level residual trend instead of broad tape beta.",
                "Execution conditioning is attached as a cost and liquidity model so long-range signal research can run beyond shallow orderbook history.",
                "Search isolates dead parameter regions instead of aborting the whole sweep; failed evaluations in this run=$failedEvaluations."
            )
        )
    }

    private suspend fun loadPanelForSearch(config: InterdayAlphaConfig): InterdayPanel {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                return loadPanel(config)
            } catch (error: Throwable) {
                lastError = error
                if (attempt == 1) throw error
            }
        }
        throw checkNotNull(lastError) { "search panel load failed without an exception" }
    }

    suspend fun run(request: InterdayAlphaRunRequest): InterdayAlphaRunResponse {
        val panel = loadPanel(request.config)
        val evaluation = evaluate(config = request.config, panel = panel, mode = request.mode, includeInspection = request.includeInspection)
        val latestBars = latestBars(panel)
        val spreadBps = evaluation.latestSignals.mapNotNull { latestBars[it.symbol]?.spreadBps }.ifEmpty { listOf(6.0) }.average()
        val impactBps = estimatePortfolioImpactBps(request.config, evaluation.latestSignals, latestBars)
        val plan = evaluation.latestTargets.takeIf { it.isNotEmpty() }?.let {
            executionPlanner.plan(
                AlphaExecutionPlanRequest(
                    targets = it,
                    mode = request.mode,
                    executionWindowMinutes = request.config.executionWindowMinutes,
                    defaultSpreadBps = spreadBps,
                    defaultImpactBps = impactBps
                )
            )
        }
        return InterdayAlphaRunResponse(
            generatedAt = Instant.now(),
            mode = request.mode,
            config = request.config,
            backtest = evaluation.backtest,
            forward = evaluation.forward,
            validation = evaluation.validation,
            selectedSignals = evaluation.latestSignals.take(16),
            targets = evaluation.latestTargets,
            executionPreview = InterdayExecutionPreview(
                plan = plan,
                submissions = emptyList(),
                notes = listOf(
                    "Use alpha-execution-agent or tx-gateway submission after reviewing the current targets and execution plan.",
                    "Testnet and paper submission remain downstream of the discovery engine so signal research and execution control stay separate."
                )
            ),
            trades = evaluation.trades.takeLast(200),
            inspection = evaluation.inspection,
            notes = listOf(
                "Trend strength is built from multi-horizon relative returns, regression slope, moving-average separation, and ADX-style persistence.",
                "Alpha is ranked on residualized symbol trend after removing common market structure, then penalized by expected execution cost.",
                "Entries size on universe-edge names when they show either a local pullback into trend or a confirmed continuation regime."
            )
        )
    }

    private suspend fun loadPanel(config: InterdayAlphaConfig): InterdayPanel {
        val start = Instant.now().minusSeconds(requiredHistoryHours(config).toLong() * 3_600L)
        return panelSource.load(
            InterdayPanelRequest(
                exchange = config.exchange,
                signalBarMinutes = config.signalBarMinutes,
                startTime = start,
                endTime = Instant.now(),
                maxSymbols = config.maxSymbols
            )
        )
    }

    private fun panelKeyFor(config: InterdayAlphaConfig): PanelCacheKey =
        PanelCacheKey(config.exchange, config.signalBarMinutes, requiredHistoryHours(config), config.maxSymbols)

    private fun defaultConfig(defaults: AlphaDiscoveryDefaults): InterdayAlphaConfig =
        InterdayAlphaConfig(
            exchange = policyProvider().research.datasets.marketExchange,
            signalBarMinutes = defaults.defaultSignalBarMinutes,
            lookbackHours = defaults.defaultLookbackHours,
            forwardHours = defaults.defaultForwardHours,
            rebalanceCadenceHours = defaults.rebalanceCadenceHours.firstOrNull() ?: 72
        )

    private fun candidateEvaluation(rank: Int, bundle: EvaluationBundle): InterdayCandidateEvaluation =
        InterdayCandidateEvaluation(
            rank = rank,
            config = bundle.config,
            backtest = bundle.backtest,
            forward = bundle.forward,
            validation = bundle.validation,
            selectedSignals = bundle.latestSignals.take(16),
            targets = bundle.latestTargets
        )

    private fun generateConfigs(
        base: InterdayAlphaConfig,
        searchSpace: InterdaySearchSpace,
        maxEvaluations: Int
    ): List<InterdayAlphaConfig> {
        val adjustmentModes = prioritizeAnchored(
            searchSpace.adjustmentModes.ifEmpty { listOf(base.adjustmentMode) },
            base.adjustmentMode
        )
        val residualizationModes = prioritizeAnchored(
            searchSpace.residualizationModes.ifEmpty { listOf(base.residualizationMode) },
            base.residualizationMode
        )
        val residualizationBetaModes = prioritizeAnchored(
            searchSpace.residualizationBetaModes.ifEmpty { listOf(base.residualizationBetaMode) },
            base.residualizationBetaMode
        )
        val residualizationMarketProxyModes = prioritizeAnchored(
            searchSpace.residualizationMarketProxyModes.ifEmpty { listOf(base.residualizationMarketProxyMode) },
            base.residualizationMarketProxyMode
        )
        val signalBars = prioritizeValues(searchSpace.signalBarMinutes.ifEmpty { listOf(base.signalBarMinutes) }, base.signalBarMinutes)
        val lookbacks = prioritizeValues(searchSpace.lookbackHours.ifEmpty { listOf(base.lookbackHours) }, base.lookbackHours)
        val forwards = prioritizeValues(searchSpace.forwardHours.ifEmpty { listOf(base.forwardHours) }, base.forwardHours)
        val cadences = prioritizeValues(searchSpace.rebalanceCadenceHours.ifEmpty { listOf(base.rebalanceCadenceHours) }, base.rebalanceCadenceHours)
        val factorLookbackDays = prioritizeValues(
            searchSpace.factorLookbackDays.ifEmpty { listOf(base.factorLookbackDays) },
            base.factorLookbackDays
        )
        val residualizationHalfLifeDays = prioritizeValues(
            searchSpace.residualizationHalfLifeDays.ifEmpty { listOf(base.residualizationHalfLifeDays) },
            base.residualizationHalfLifeDays
        )
        val quantiles = prioritizeDoubles(searchSpace.selectionQuantiles.ifEmpty { listOf(base.selectionQuantile) }, base.selectionQuantile)
        val tailWeightingModes = prioritizeAnchored(
            searchSpace.tailWeightingModes.ifEmpty { listOf(base.tailWeightingMode) },
            base.tailWeightingMode
        )
        val fastDays = prioritizeValues(searchSpace.fastTrendDays.ifEmpty { listOf(base.fastTrendDays) }, base.fastTrendDays)
        val mediumDays = prioritizeValues(searchSpace.mediumTrendDays.ifEmpty { listOf(base.mediumTrendDays) }, base.mediumTrendDays)
        val slowDays = prioritizeValues(searchSpace.slowTrendDays.ifEmpty { listOf(base.slowTrendDays) }, base.slowTrendDays)
        val regressionDays = prioritizeValues(searchSpace.regressionDays.ifEmpty { listOf(base.regressionDays) }, base.regressionDays)
        val volatilityDays = prioritizeValues(searchSpace.volatilityDays.ifEmpty { listOf(base.volatilityDays) }, base.volatilityDays)
        val adxDays = prioritizeValues(searchSpace.adxDays.ifEmpty { listOf(base.adxDays) }, base.adxDays)
        val perturbBars = prioritizeValues(searchSpace.perturbationLookbackBars.ifEmpty { listOf(base.perturbationLookbackBars) }, base.perturbationLookbackBars)
        val perturbThresholds = prioritizeDoubles(searchSpace.perturbationThresholdZ.ifEmpty { listOf(base.perturbationThresholdZ) }, base.perturbationThresholdZ)
        val slopeWeights = prioritizeDoubles(searchSpace.slopeWeight.ifEmpty { listOf(base.slopeWeight) }, base.slopeWeight)
        val fundingWeights = prioritizeDoubles(searchSpace.fundingWeight.ifEmpty { listOf(base.fundingWeight) }, base.fundingWeight)
        val fundingOverlayModes = prioritizeAnchored(
            searchSpace.fundingOverlayModes.ifEmpty { listOf(base.fundingOverlayMode) },
            base.fundingOverlayMode
        )
        val openInterestWeights = prioritizeDoubles(searchSpace.openInterestWeight.ifEmpty { listOf(base.openInterestWeight) }, base.openInterestWeight)
        val pullbackWeights = prioritizeDoubles(searchSpace.pullbackWeight.ifEmpty { listOf(base.pullbackWeight) }, base.pullbackWeight)
        val minTrendAgreements = prioritizeDoubles(searchSpace.minTrendAgreement.ifEmpty { listOf(base.minTrendAgreement) }, base.minTrendAgreement)
        val adxThresholds = prioritizeDoubles(searchSpace.adxThreshold.ifEmpty { listOf(base.adxThreshold) }, base.adxThreshold)
        val minConfidences = prioritizeDoubles(searchSpace.minConfidence.ifEmpty { listOf(base.minConfidence) }, base.minConfidence)
        val exitOverlayModes = prioritizeAnchored(
            searchSpace.exitOverlayModes.ifEmpty { listOf(base.exitOverlayMode) },
            base.exitOverlayMode
        )
        val trailingStops = prioritizeDoubles(searchSpace.trailingStopVolMultiple.ifEmpty { listOf(base.trailingStopVolMultiple) }, base.trailingStopVolMultiple)
        val takeProfits = prioritizeDoubles(searchSpace.takeProfitVolMultiple.ifEmpty { listOf(base.takeProfitVolMultiple) }, base.takeProfitVolMultiple)
        val timeStopBars = prioritizeValues(searchSpace.timeStopBars.ifEmpty { listOf(base.timeStopBars) }, base.timeStopBars)
        val timeStopMinProgressVol = prioritizeDoubles(
            searchSpace.timeStopMinProgressVol.ifEmpty { listOf(base.timeStopMinProgressVol) },
            base.timeStopMinProgressVol
        )
        val executionWindows = prioritizeValues(searchSpace.executionWindowMinutes.ifEmpty { listOf(base.executionWindowMinutes) }, base.executionWindowMinutes)
        val targetGrossFractionScales = prioritizeDoubles(searchSpace.targetGrossFractionScale.ifEmpty { listOf(base.targetGrossFractionScale) }, base.targetGrossFractionScale)
        val expectedCostPenaltyWeights = prioritizeDoubles(
            searchSpace.expectedCostPenaltyWeight.ifEmpty { listOf(base.expectedCostPenaltyWeight) },
            base.expectedCostPenaltyWeight
        )
        val turnoverPenaltyWeights = prioritizeDoubles(
            searchSpace.turnoverPenaltyWeight.ifEmpty { listOf(base.turnoverPenaltyWeight) },
            base.turnoverPenaltyWeight
        )
        val entryEdgeFloorBps = prioritizeDoubles(searchSpace.entryEdgeFloorBps.ifEmpty { listOf(base.entryEdgeFloorBps) }, base.entryEdgeFloorBps)
        val holdEdgeFloorBps = prioritizeDoubles(searchSpace.holdEdgeFloorBps.ifEmpty { listOf(base.holdEdgeFloorBps) }, base.holdEdgeFloorBps)
        val regimeDirectionalSuppressionThresholds = prioritizeDoubles(
            searchSpace.regimeDirectionalSuppressionThreshold.ifEmpty { listOf(base.regimeDirectionalSuppressionThreshold) },
            base.regimeDirectionalSuppressionThreshold
        )
        val regimeNetBiasScales = prioritizeDoubles(searchSpace.regimeNetBiasScale.ifEmpty { listOf(base.regimeNetBiasScale) }, base.regimeNetBiasScale)
        val flatRegimeGateModes = prioritizeAnchored(
            searchSpace.flatRegimeGateModes.ifEmpty { listOf(base.flatRegimeGateMode) },
            base.flatRegimeGateMode
        )
        val flatRegimeMarketTrendThresholds = prioritizeDoubles(
            searchSpace.flatRegimeMarketTrendThreshold.ifEmpty { listOf(base.flatRegimeMarketTrendThreshold) },
            base.flatRegimeMarketTrendThreshold
        )
        val flatRegimeBreadthThresholds = prioritizeDoubles(
            searchSpace.flatRegimeBreadthThreshold.ifEmpty { listOf(base.flatRegimeBreadthThreshold) },
            base.flatRegimeBreadthThreshold
        )
        val flatRegimeGrossScales = prioritizeDoubles(
            searchSpace.flatRegimeGrossScale.ifEmpty { listOf(base.flatRegimeGrossScale) },
            base.flatRegimeGrossScale
        )
        val flatRegimeEntryEdgeFloorBoostBps = prioritizeDoubles(
            searchSpace.flatRegimeEntryEdgeFloorBoostBps.ifEmpty { listOf(base.flatRegimeEntryEdgeFloorBoostBps) },
            base.flatRegimeEntryEdgeFloorBoostBps
        )
        val flatRegimeEntryControlModes = prioritizeAnchored(
            searchSpace.flatRegimeEntryControlModes.ifEmpty { listOf(base.flatRegimeEntryControlMode) },
            base.flatRegimeEntryControlMode
        )
        val flatRegimeMinDispersions = prioritizeDoubles(
            searchSpace.flatRegimeMinDispersion.ifEmpty { listOf(base.flatRegimeMinDispersion) },
            base.flatRegimeMinDispersion
        )
        val flatRegimeTrendAgreementBoosts = prioritizeDoubles(
            searchSpace.flatRegimeTrendAgreementBoost.ifEmpty { listOf(base.flatRegimeTrendAgreementBoost) },
            base.flatRegimeTrendAgreementBoost
        )

        val generated = mutableListOf<InterdayAlphaConfig>()
        outer@ for (adjustmentMode in adjustmentModes) {
            for (residualizationMode in residualizationModes) {
                for (residualizationBetaMode in residualizationBetaModes) {
                    for (residualizationMarketProxyMode in residualizationMarketProxyModes) {
                        for (signalBar in signalBars) {
                            for (lookback in lookbacks) {
                                for (forward in forwards) {
                                    for (cadence in cadences) {
                                        for (factorLookbackDay in factorLookbackDays) {
                                            for (residualizationHalfLifeDay in residualizationHalfLifeDays) {
                                                for (quantile in quantiles) {
                                                    for (tailWeightingMode in tailWeightingModes) {
                                for (fast in fastDays) {
                                    for (medium in mediumDays) {
                                        for (slow in slowDays) {
                                            if (!(fast <= medium && medium <= slow)) continue
                                            for (regression in regressionDays) {
                                                for (volatility in volatilityDays) {
                                                    for (adx in adxDays) {
                                                        for (perturbBar in perturbBars) {
                                                            for (perturbThreshold in perturbThresholds) {
                                                                for (slopeWeight in slopeWeights) {
                                                                    for (fundingWeight in fundingWeights) {
                                                                        for (fundingOverlayMode in fundingOverlayModes) {
                                                                            for (openInterestWeight in openInterestWeights) {
                                                                                for (pullbackWeight in pullbackWeights) {
                                                                                    for (minTrendAgreement in minTrendAgreements) {
                                                                                        for (adxThreshold in adxThresholds) {
                                                                                            for (minConfidence in minConfidences) {
                                                                                                for (exitOverlayMode in exitOverlayModes) {
                                                                                                    for (trailing in trailingStops) {
                                                                                                        for (takeProfit in takeProfits) {
                                                                                                            for (timeStopBar in timeStopBars) {
                                                                                                                for (timeStopProgress in timeStopMinProgressVol) {
                                                                                                                    for (executionWindow in executionWindows) {
                                                                                                                        for (targetGrossFractionScale in targetGrossFractionScales) {
                                                                                                                            for (expectedCostPenaltyWeight in expectedCostPenaltyWeights) {
                                                                                                                                for (turnoverPenaltyWeight in turnoverPenaltyWeights) {
                                                                                                                                    for (entryEdgeFloor in entryEdgeFloorBps) {
                                                                                                                                        for (holdEdgeFloor in holdEdgeFloorBps) {
                                                                                                                                            for (regimeDirectionalSuppressionThreshold in regimeDirectionalSuppressionThresholds) {
                                                                                                                                                for (regimeNetBiasScale in regimeNetBiasScales) {
                                                                                                                                                    for (flatRegimeGateMode in flatRegimeGateModes) {
                                                                                                                                                        for (flatRegimeMarketTrendThreshold in flatRegimeMarketTrendThresholds) {
                                                                                                                                                            for (flatRegimeBreadthThreshold in flatRegimeBreadthThresholds) {
                                                                                                                                                                for (flatRegimeGrossScale in flatRegimeGrossScales) {
                                                                                                                                                                    for (flatRegimeEntryEdgeFloorBoost in flatRegimeEntryEdgeFloorBoostBps) {
                                                                                                                                                                        for (flatRegimeEntryControlMode in flatRegimeEntryControlModes) {
                                                                                                                                                                            for (flatRegimeMinDispersion in flatRegimeMinDispersions) {
                                                                                                                                                                                for (flatRegimeTrendAgreementBoost in flatRegimeTrendAgreementBoosts) {
                                                                                                                                                                                    generated += base.copy(
                                                                                                                                        adjustmentMode = adjustmentMode,
                                                                                                                                        residualizationMode = residualizationMode,
                                                                                                                                        residualizationBetaMode = residualizationBetaMode,
                                                                                                                                        residualizationMarketProxyMode = residualizationMarketProxyMode,
                                                                                                                                        signalBarMinutes = signalBar,
                                                                                                                                        lookbackHours = lookback,
                                                                                                                                        forwardHours = forward,
                                                                                                                                        rebalanceCadenceHours = cadence,
                                                                                                                                        factorLookbackDays = factorLookbackDay,
                                                                                                                                        residualizationHalfLifeDays = residualizationHalfLifeDay,
                                                                                                                                        selectionQuantile = quantile,
                                                                                                                                        tailWeightingMode = tailWeightingMode,
                                                                                                                                        fastTrendDays = fast,
                                                                                                                                        mediumTrendDays = medium,
                                                                                                                                        slowTrendDays = slow,
                                                                                                                                        regressionDays = regression,
                                                                                                                                        volatilityDays = volatility,
                                                                                                                                        adxDays = adx,
                                                                                                                                        perturbationLookbackBars = perturbBar,
                                                                                                                                        perturbationThresholdZ = perturbThreshold,
                                                                                                                                        slopeWeight = slopeWeight,
                                                                                                                                        fundingWeight = fundingWeight,
                                                                                                                                        fundingOverlayMode = fundingOverlayMode,
                                                                                                                                        openInterestWeight = openInterestWeight,
                                                                                                                                        pullbackWeight = pullbackWeight,
                                                                                                                                        minTrendAgreement = minTrendAgreement,
                                                                                                                                        adxThreshold = adxThreshold,
                                                                                                                                        minConfidence = minConfidence,
                                                                                                                                        exitOverlayMode = exitOverlayMode,
                                                                                                                                        trailingStopVolMultiple = trailing,
                                                                                                                                        takeProfitVolMultiple = takeProfit,
                                                                                                                                        timeStopBars = timeStopBar,
                                                                                                                                        timeStopMinProgressVol = timeStopProgress,
                                                                                                                                        executionWindowMinutes = executionWindow,
                                                                                                                                        targetGrossFractionScale = targetGrossFractionScale,
                                                                                                                                        expectedCostPenaltyWeight = expectedCostPenaltyWeight,
                                                                                                                                        turnoverPenaltyWeight = turnoverPenaltyWeight,
                                                                                                                                        entryEdgeFloorBps = entryEdgeFloor,
                                                                                                                                        holdEdgeFloorBps = holdEdgeFloor,
                                                                                                                                        regimeDirectionalSuppressionThreshold = regimeDirectionalSuppressionThreshold,
                                                                                                                                        regimeNetBiasScale = regimeNetBiasScale,
                                                                                                                                        flatRegimeGateMode = flatRegimeGateMode,
                                                                                                                                        flatRegimeMarketTrendThreshold = flatRegimeMarketTrendThreshold,
                                                                                                                                        flatRegimeBreadthThreshold = flatRegimeBreadthThreshold,
                                                                                                                                        flatRegimeGrossScale = flatRegimeGrossScale,
                                                                                                                                        flatRegimeEntryEdgeFloorBoostBps = flatRegimeEntryEdgeFloorBoost,
                                                                                                                                        flatRegimeEntryControlMode = flatRegimeEntryControlMode,
                                                                                                                                        flatRegimeMinDispersion = flatRegimeMinDispersion,
                                                                                                                                        flatRegimeTrendAgreementBoost = flatRegimeTrendAgreementBoost
                                                                                                                                    )
                                                                                                                                    if (generated.size >= maxEvaluations) break@outer
                                                                                                                                                                                }
                                                                                                                                                                            }
                                                                                                                                                                        }
                                                                                                                                                                    }
                                                                                                                                                                }
                                                                                                                                                            }
                                                                                                                                                        }
                                                                                                                                                    }
                                                                                                                                                }
                                                                                                                                            }
                                                                                                                                        }
                                                                                                                                    }
                                                                                                                                }
                                                                                                                            }
                                                                                                                        }
                                                                                                                    }
                                                                                                                }
                                                                                                            }
                                                                                                        }
                                                                                                    }
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return generated.distinct()
    }

    private fun evaluate(
        config: InterdayAlphaConfig,
        panel: InterdayPanel,
        mode: AlphaRunMode,
        includeInspection: Boolean
    ): EvaluationBundle {
        require(panel.series.isNotEmpty()) { "panel contains no symbol series" }
        val indicators = IndicatorWindows.fromConfig(config)
        val rebalanceBars = barsForHours(config.rebalanceCadenceHours, config.signalBarMinutes).coerceAtLeast(1)
        val targetHorizonBars = trainingTargetBars(config)
        val latestTime = panel.timeline.lastOrNull() ?: error("panel timeline is empty")
        val splitTime = latestTime.minus(Duration.ofHours(config.forwardHours.toLong()))
        val lookbackStart = latestTime.minus(Duration.ofHours((config.lookbackHours + config.forwardHours).toLong()))
        val configuredStartIndex = panel.timeline.indexOfFirst { it >= lookbackStart }.takeIf { it >= 0 } ?: 1
        val evaluationStartIndex = max(indicators.requiredBars, configuredStartIndex)
        require(panel.timeline.size > evaluationStartIndex + 2) {
            "panel has insufficient history for config=$config"
        }

        val policy = policyProvider()
        val searchPolicy = policy.research.discovery.search
        val portfolioDefaults = portfolioConstructor.defaults()
        val liquidityRanks = precomputeLiquidityRanks(panel)
        val seriesBySymbol = panel.seriesBySymbol()
        val currentWeights = mutableMapOf<String, Double>()
        val activePositions = mutableMapOf<String, ActivePosition>()
        val trades = mutableListOf<InterdayTradeRecord>()
        val snapshots = mutableListOf<InterdayPortfolioSnapshot>()
        val regimeSnapshots = mutableListOf<InterdayRegimeSnapshot>()
        val signalHistory = mutableMapOf<String, MutableList<InterdayInspectionPoint>>()
        val compressionDiagnostics = mutableListOf<InterdayCompressionDiagnosticPoint>()
        val compressionHistoryByKey = mutableMapOf<Pair<Int, Int>, MutableList<CompressionDiagnosticRawPoint>>()
        val compressionPenaltyHistoryByKey = mutableMapOf<Pair<Int, Int>, MutableList<CompressionDiagnosticRawPoint>>()
        val flatHazardHistoryByKey = mutableMapOf<Pair<Int, Int>, MutableList<CompressionDiagnosticRawPoint>>()
        val weightTimeline = mutableMapOf<Instant, Map<String, Double>>()
        val desiredWeightTimeline = mutableMapOf<Instant, Map<String, Double>>()
        val appliedDeltaTimeline = mutableMapOf<Instant, Map<String, Double>>()
        var equity = 1.0
        var peakEquity = 1.0
        var grossAccumulator = 0.0
        var latestSignals = emptyList<InterdaySignalSnapshot>()
        var latestTargets = emptyList<AlphaPortfolioTarget>()
        var latestDesiredWeights = emptyMap<String, Double>()
        var latestSignalsBySymbol = emptyMap<String, InterdaySignalSnapshot>()
        var latestRegime = RegimeState(
            score = 0.0,
            breadth = 0.0,
            anchorTrend = 0.0,
            dispersion = 0.0,
            realizedVolatility = 0.0,
            liquidityScore = 0.0,
            fundingPressure = 0.0,
            openInterestPressure = 0.0,
            marketTrendScore = 0.0
        )

        for (index in 1 until panel.timeline.size) {
            val time = panel.timeline[index]
            var logReturn = 0.0
            currentWeights.forEach { (symbol, signedWeight) ->
                val series = seriesBySymbol[symbol] ?: return@forEach
                val previous = series.bars[index - 1] ?: return@forEach
                val current = series.bars[index] ?: return@forEach
                if (previous.close <= 0.0 || current.close <= 0.0) return@forEach
                logReturn += signedWeight * ln(current.close / previous.close)
            }
            equity *= kotlin.math.exp(logReturn)
            peakEquity = max(peakEquity, equity)
            grossAccumulator += logReturn

            if (activePositions.isNotEmpty()) {
                val adjustments = mutableListOf<PositionAdjustment>()
                activePositions.values.forEach { position ->
                    val bar = seriesBySymbol[position.symbol]?.bars?.get(index) ?: return@forEach
                    position.refresh(bar)
                    adjustments += position.overlayAdjustments(config, bar, time)
                }
                adjustments.forEach { adjustment ->
                    val bar = seriesBySymbol[adjustment.symbol]?.bars?.get(index) ?: return@forEach
                    val signedCurrent = currentWeights[adjustment.symbol] ?: return@forEach
                    val signedReduction = sign(signedCurrent) * min(abs(signedCurrent), adjustment.deltaWeight)
                    if (abs(signedReduction) <= 1e-9) return@forEach
                    equity *= 1.0 - estimateTransactionCostFraction(
                        weightDelta = abs(signedReduction),
                        bar = bar,
                        confidence = 0.65,
                        config = config
                    )
                    realizeTrade(
                        activePositions = activePositions,
                        currentWeights = currentWeights,
                        trades = trades,
                        symbol = adjustment.symbol,
                        signedDelta = -signedReduction,
                        price = bar.close,
                        time = time,
                        reason = adjustment.reason,
                        splitTime = splitTime
                    )
                }
            }

            if (index < evaluationStartIndex) {
                val snapshot = portfolioSnapshot(
                    time = time,
                    equity = equity,
                    peakEquity = peakEquity,
                    currentWeights = currentWeights,
                    turnoverFraction = 0.0,
                    regime = latestRegime,
                    searchPolicy = searchPolicy,
                    config = config
                )
                snapshots += snapshot
                regimeSnapshots += regimeSnapshot(time, latestRegime, snapshot)
                continue
            }

            val rebalanceNow = (index - evaluationStartIndex) % rebalanceBars == 0
            var turnoverFraction = 0.0
            if (rebalanceNow) {
                latestRegime = computeRegime(panel, index, config, indicators, liquidityRanks)
                val signalResult = computeSignals(
                    panel = panel,
                    index = index,
                    config = config,
                    regime = latestRegime,
                    indicators = indicators,
                    liquidityRanks = liquidityRanks,
                    portfolioDefaults = portfolioDefaults,
                    currentWeights = currentWeights,
                    evaluationStartIndex = evaluationStartIndex,
                    rebalanceBars = rebalanceBars,
                    targetHorizonBars = targetHorizonBars
                )
                val rawSignals = signalResult.signals
                val compressionPenaltyState = currentCompressionPenaltyState(
                    signals = rawSignals,
                    structuralState = signalResult.structuralState,
                    signalBarMinutes = config.signalBarMinutes,
                    config = config,
                    historyByKey = compressionPenaltyHistoryByKey
                )
                val signals = rawSignals.map { signal ->
                    val incumbentSameSide = holdsSignalDirection(currentWeights[signal.symbol] ?: 0.0, signal.direction)
                    applyCompressionPenalty(signal, incumbentSameSide, compressionPenaltyState)
                }
                val flatHazardState = currentFlatHazardState(
                    regime = latestRegime,
                    signals = rawSignals,
                    structuralState = signalResult.structuralState,
                    signalBarMinutes = config.signalBarMinutes,
                    config = config,
                    historyByKey = flatHazardHistoryByKey
                )
                latestSignals = signals
                    .sortedByDescending { abs(it.score) }
                    .take(32)
                latestSignalsBySymbol = signals.associateBy { it.symbol }
                if (includeInspection) {
                    compressionDiagnostics += buildCompressionDiagnostics(
                        time = time,
                        signals = rawSignals,
                        structuralState = signalResult.structuralState,
                        regime = latestRegime,
                        panel = panel,
                        index = index,
                        targetHorizonBars = targetHorizonBars,
                        signalBarMinutes = config.signalBarMinutes,
                        rebalanceHours = config.rebalanceCadenceHours,
                        historyByKey = compressionHistoryByKey
                    )
                }
                val flatRegimeEntryEdgeBoostBps = flatRegimeEntryEdgeBoostBps(
                    marketTrendScore = latestRegime.marketTrendScore,
                    breadth = latestRegime.breadth,
                    config = config
                )
                val eligibleBySymbol = signals.associate { signal ->
                    val incumbentSameSide = holdsSignalDirection(currentWeights[signal.symbol] ?: 0.0, signal.direction)
                    val requiredTrendAgreement = flatRegimeTrendAgreementFloor(
                        marketTrendScore = latestRegime.marketTrendScore,
                        breadth = latestRegime.breadth,
                        incumbentSameSide = incumbentSameSide,
                        config = config
                    )
                    val trendAgreementOk = signal.trendAgreement >= requiredTrendAgreement
                    val entryStyleOk = incumbentSameSide || satisfiesEntryStyle(signal, config)
                    val regimeAllowed = isDirectionAllowedByRegime(
                        direction = signal.direction,
                        regimeScore = latestRegime.score,
                        config = config
                    )
                    val universeEdgeOk = withinDirectionalTail(
                        signal = signal,
                        plateauToleranceBps = searchPolicy.scorePlateauToleranceBps
                    )
                    val requiredEdgeFloorBps = if (incumbentSameSide) {
                        config.holdEdgeFloorBps
                    } else {
                        config.entryEdgeFloorBps + flatRegimeEntryEdgeBoostBps
                    }
                    val edgeFloorOk = directionalEdgeBps(signal) >= requiredEdgeFloorBps
                    val dispersionOk = flatRegimeDispersionAllowed(
                        marketTrendScore = latestRegime.marketTrendScore,
                        breadth = latestRegime.breadth,
                        dispersion = latestRegime.dispersion,
                        incumbentSameSide = incumbentSameSide,
                        config = config
                    )
                    signal.symbol to (
                        signal.confidence >= config.minConfidence &&
                            trendAgreementOk &&
                            entryStyleOk &&
                            regimeAllowed &&
                            dispersionOk &&
                            edgeFloorOk &&
                            (universeEdgeOk || (incumbentSameSide && directionalEdgeBps(signal) >= config.holdEdgeFloorBps))
                        )
                }
                val eligible = signals.filter { eligibleBySymbol[it.symbol] == true }
                val portfolioSignals = eligible.map {
                    AlphaSignalScore(
                        symbol = it.symbol,
                        score = it.expectedNetEdgeBps,
                        confidence = it.confidence,
                        predictedVolatility = it.predictedVolatility.coerceAtLeast(0.0001),
                        liquidityScore = it.liquidityScore,
                        expectedResidualReturnBps = it.expectedResidualReturnBps,
                        expectedEntryCostBps = it.expectedEntryCostBps,
                        expectedTurnoverPenaltyBps = it.expectedTurnoverPenaltyBps,
                        expectedNetEdgeBps = it.expectedNetEdgeBps,
                        currentWeightFraction = currentWeights[it.symbol] ?: 0.0,
                        sizingMultiplier = it.fundingOverlayMultiplier
                    )
                }.filter {
                    it.score.isFinite() &&
                        abs(it.score) > 1e-9 &&
                        it.confidence.isFinite() &&
                        it.predictedVolatility.isFinite() &&
                        it.liquidityScore.isFinite()
                }
                latestTargets = if (portfolioSignals.isEmpty()) {
                    emptyList()
                } else {
                    val baseGrossFraction = scaledTargetGrossFraction(portfolioDefaults, config)
                    val scaledGrossFraction = baseGrossFraction * flatRegimeGrossScale(
                        marketTrendScore = latestRegime.marketTrendScore,
                        breadth = latestRegime.breadth,
                        config = config
                    ) * flatHazardGrossScale(flatHazardState.intensity, config)
                    val constructed = portfolioConstructor.construct(
                        AlphaPortfolioRequest(
                            signals = portfolioSignals,
                            selectionQuantile = config.selectionQuantile,
                            respectProvidedSignalSet = true,
                            weightingMode = config.tailWeightingMode,
                            targetGrossFraction = scaledGrossFraction,
                            currentWeightsBySymbol = currentWeights.toMap(),
                            minExpectedNetEdgeBps = config.entryEdgeFloorBps + flatRegimeEntryEdgeBoostBps,
                            targetNetFraction = regimeTargetNetFraction(
                                regimeScore = latestRegime.score,
                                config = config,
                                scaledGrossFraction = scaledGrossFraction
                            )
                        )
                    )
                    constructed.targets.map {
                        it.copy(
                            trailingStopVolMultiple = config.trailingStopVolMultiple,
                            takeProfitVolMultiple = config.takeProfitVolMultiple
                        )
                    }
                }
                latestDesiredWeights = latestTargets.associate { target ->
                    target.symbol to if (target.direction == AlphaDirection.LONG) target.weightFraction else -target.weightFraction
                }
                desiredWeightTimeline[time] = latestDesiredWeights
                if (includeInspection) {
                    signals.forEach { signal ->
                        signalHistory.getOrPut(signal.symbol) { mutableListOf() } += InterdayInspectionPoint(
                            time = time,
                            close = signal.close,
                            score = signal.score,
                            empiricalScore = signal.empiricalScore,
                            confidence = signal.confidence,
                            regimeScore = latestRegime.score,
                            expansionScore = signal.expansionScore,
                            reversalRiskScore = signal.reversalRiskScore,
                            upperBound = signal.upperBound,
                            lowerBound = signal.lowerBound,
                            expectedResidualReturnBps = signal.expectedResidualReturnBps,
                            expectedEntryCostBps = signal.expectedEntryCostBps,
                            expectedNetEdgeBps = signal.expectedNetEdgeBps,
                            desiredWeight = latestDesiredWeights[signal.symbol] ?: 0.0,
                            entryEligible = eligibleBySymbol[signal.symbol] == true,
                            regimeBlocked = !isDirectionAllowedByRegime(
                                direction = signal.direction,
                                regimeScore = latestRegime.score,
                                config = config
                            ),
                            positionWeight = 0.0
                        )
                    }
                }
            }

            val shouldAdjustBetweenRebalances = when (config.adjustmentMode) {
                InterdayAdjustmentMode.REBALANCE_STEP -> rebalanceNow
                InterdayAdjustmentMode.CONTINUOUS_RAMP -> latestDesiredWeights.isNotEmpty() || currentWeights.isNotEmpty()
            }
            if (shouldAdjustBetweenRebalances) {
                val appliedDeltas = mutableMapOf<String, Double>()
                val symbols = (currentWeights.keys + latestDesiredWeights.keys).sorted()
                symbols.forEach { symbol ->
                    val currentSigned = currentWeights[symbol] ?: 0.0
                    val targetSigned = latestDesiredWeights[symbol] ?: 0.0
                    val signal = latestSignalsBySymbol[symbol]
                    val confidence = signal?.confidence ?: 0.5
                    val step = adjustmentStep(
                        baseStep = policy.research.portfolio.rebalanceTargetExposureStep.coerceAtLeast(0.01),
                        rebalanceBars = rebalanceBars,
                        confidence = confidence,
                        mode = config.adjustmentMode
                    )
                    val forcedFlatten = rebalanceNow && shouldForceFlattenByRegime(
                        currentSigned = currentSigned,
                        regimeScore = latestRegime.score,
                        config = config
                    )
                    val exitOverlayFlatten = rebalanceNow && shouldForceFlattenByExitOverlay(
                        currentSigned = currentSigned,
                        positionEntryTime = activePositions[symbol]?.entryTime,
                        maxFavorableMoveVol = activePositions[symbol]?.maxFavorableMoveVol(),
                        signal = signal,
                        config = config,
                        time = time
                    )
                    val plannedDelta = if (forcedFlatten || exitOverlayFlatten) {
                        -currentSigned
                    } else {
                        gradualDelta(currentSigned, targetSigned, step)
                    }
                    if (abs(plannedDelta) <= 1e-9) return@forEach
                    val currentBar = seriesBySymbol[symbol]?.bars?.get(index) ?: return@forEach
                    equity *= 1.0 - estimateTransactionCostFraction(
                        weightDelta = abs(plannedDelta),
                        bar = currentBar,
                        confidence = confidence,
                        config = config
                    )
                    applyDelta(
                        activePositions = activePositions,
                        currentWeights = currentWeights,
                        trades = trades,
                        symbol = symbol,
                        signedDelta = plannedDelta,
                        price = currentBar.close,
                        volatility = signal?.predictedVolatility ?: 0.01,
                        confidence = confidence,
                        time = time,
                        reason = when {
                            forcedFlatten -> "regime-flush"
                            exitOverlayFlatten -> "exit-overlay"
                            rebalanceNow -> "rebalance"
                            else -> "continuous-ramp"
                        },
                        splitTime = splitTime
                    )
                    turnoverFraction += abs(plannedDelta)
                    appliedDeltas[symbol] = (appliedDeltas[symbol] ?: 0.0) + plannedDelta
                }
                if (includeInspection) {
                    weightTimeline[time] = currentWeights.toMap()
                    appliedDeltaTimeline[time] = appliedDeltas.toMap()
                }
            }
            val snapshot = portfolioSnapshot(
                time = time,
                equity = equity,
                peakEquity = peakEquity,
                currentWeights = currentWeights,
                turnoverFraction = turnoverFraction,
                regime = latestRegime,
                searchPolicy = searchPolicy,
                config = config
            )
            snapshots += snapshot
            regimeSnapshots += regimeSnapshot(time, latestRegime, snapshot)
        }

        val backtest = buildPerformance(
            segment = "backtest",
            snapshots = snapshots,
            regimes = regimeSnapshots,
            trades = trades,
            splitTime = splitTime,
            beforeSplit = true,
            signalBarMinutes = config.signalBarMinutes,
            searchPolicy = searchPolicy,
            regimeStrengthThreshold = config.regimeStrengthThreshold,
            configuredRegimeSlices = normalizedRegimeSlices(policy.research.validation.regimeSlices)
        )
        val forward = buildPerformance(
            segment = "forward",
            snapshots = snapshots,
            regimes = regimeSnapshots,
            trades = trades,
            splitTime = splitTime,
            beforeSplit = false,
            signalBarMinutes = config.signalBarMinutes,
            searchPolicy = searchPolicy,
            regimeStrengthThreshold = config.regimeStrengthThreshold,
            configuredRegimeSlices = normalizedRegimeSlices(policy.research.validation.regimeSlices)
        )
        val validation = validate(config, backtest, forward, searchPolicy)
        val inspection = if (includeInspection) {
            buildInspection(
                snapshots = snapshots,
                regimes = regimeSnapshots,
                signalHistory = signalHistory,
                compressionDiagnostics = compressionDiagnostics,
                weightTimeline = weightTimeline,
                desiredWeightTimeline = desiredWeightTimeline,
                appliedDeltaTimeline = appliedDeltaTimeline,
                topSymbols = latestSignals.map { it.symbol }.take(4)
            )
        } else {
            null
        }
        return EvaluationBundle(
            config = config,
            backtest = backtest,
            forward = forward,
            validation = validation,
            latestSignals = latestSignals,
            latestTargets = latestTargets,
            trades = trades,
            inspection = inspection,
            grossLogReturn = grossAccumulator
        )
    }

    private fun computeSignals(
        panel: InterdayPanel,
        index: Int,
        config: InterdayAlphaConfig,
        regime: RegimeState,
        indicators: IndicatorWindows,
        liquidityRanks: Map<String, Double>,
        portfolioDefaults: AlphaPortfolioDefaults,
        currentWeights: Map<String, Double>,
        evaluationStartIndex: Int,
        rebalanceBars: Int,
        targetHorizonBars: Int
    ): SignalComputationResult {
        val raw = collectRawSignals(panel, index, config, indicators, liquidityRanks)
        if (raw.isEmpty()) {
            return SignalComputationResult(
                signals = emptyList(),
                structuralState = StructuralState.disabled(raw)
            )
        }
        val structuralState = buildStructuralState(
            panel = panel,
            index = index,
            raw = raw,
            config = config,
            indicators = indicators,
            enabled = residualizationActive(config)
        )
        val scoringRaw = structuralState.residualizedRaw
        val featureVectors = buildFeatureVectors(scoringRaw, config)
        val empiricalWeights = fitEmpiricalWeights(
            panel = panel,
            currentIndex = index,
            evaluationStartIndex = evaluationStartIndex,
            rebalanceBars = rebalanceBars,
            targetHorizonBars = targetHorizonBars,
            config = config,
            indicators = indicators,
            liquidityRanks = liquidityRanks,
            residualizationEnabled = structuralState.enabled
        )
        val empiricalScores = scoringRaw.associate { candidate ->
            candidate.symbol to empiricalWeights.score(featureVectors.getValue(candidate.symbol), config)
        }
        val empiricalRanks = centeredRanks(empiricalScores)
        val spreadRanks = centeredRanks(raw.associate { it.symbol to -(it.spreadBps) })
        val edgeEstimates = raw.associate { candidate ->
            val features = featureVectors.getValue(candidate.symbol)
            val empiricalScore = empiricalScores.getValue(candidate.symbol)
            val residualRank = empiricalRanks.getValue(candidate.symbol)
            val direction = if (empiricalScore >= 0.0) AlphaDirection.LONG else AlphaDirection.SHORT
            val executionSupport = if (!config.useExecutionConditioning) {
                1.0
            } else {
                (candidate.orderbookObservedRatio * 0.6 + candidate.tradeObservedRatio * 0.4).coerceIn(0.0, 1.0)
            }
            val provisionalConfidence = listOf(
                abs(empiricalRanks.getValue(candidate.symbol)).coerceIn(0.0, 1.0),
                features.trendAgreement.coerceAtLeast(0.0),
                features.pullback.coerceIn(0.0, 1.0),
                features.expansion.coerceIn(0.0, 1.0),
                features.liquidity.coerceIn(0.0, 1.0),
                executionSupport.coerceIn(0.0, 1.0),
                ((spreadRanks.getValue(candidate.symbol) + 1.0) / 2.0).coerceIn(0.0, 1.0)
            ).average().coerceIn(0.0, 1.0)
            val currentSigned = currentWeights[candidate.symbol] ?: 0.0
            val assumedWeight = assumedTargetWeightFraction(
                confidence = provisionalConfidence,
                defaults = portfolioDefaults
            )
            val signedAssumedTarget = signedWeight(direction, assumedWeight)
            val expectedResidualReturnBps = empiricalScore * 10_000.0
            val expectedEntryCostBps = estimateTransactionCostBps(
                weightDelta = assumedWeight,
                bar = candidate,
                confidence = provisionalConfidence,
                config = config
            )
            val expectedTurnoverPenaltyBps = estimateTurnoverPenaltyBps(
                currentSigned = currentSigned,
                targetSigned = signedAssumedTarget,
                defaults = portfolioDefaults
            )
            val fundingOverlayMultiplier = features.overlaySizingMultiplier(config)
            val expectedNetEdgeBps = expectedResidualReturnBps -
                config.expectedCostPenaltyWeight * expectedEntryCostBps -
                config.turnoverPenaltyWeight * expectedTurnoverPenaltyBps
            candidate.symbol to SignalEdgeEstimate(
                empiricalScore = empiricalScore,
                residualRank = residualRank,
                expectedResidualReturnBps = expectedResidualReturnBps,
                expectedEntryCostBps = expectedEntryCostBps,
                expectedTurnoverPenaltyBps = expectedTurnoverPenaltyBps,
                expectedNetEdgeBps = expectedNetEdgeBps,
                executionSupport = executionSupport,
                provisionalConfidence = provisionalConfidence,
                fundingOverlayMultiplier = fundingOverlayMultiplier
            )
        }
        val expectedEdgeScores = edgeEstimates.mapValues { (_, estimate) -> estimate.expectedNetEdgeBps }
        val edgeRanks = centeredRanks(expectedEdgeScores)
        val sortedScores = expectedEdgeScores.values.sorted()
        val lowerBound = quantile(sortedScores, config.selectionQuantile)
        val upperBound = quantile(sortedScores, 1.0 - config.selectionQuantile)

        val signals = raw.map { candidate ->
            val features = featureVectors.getValue(candidate.symbol)
            val estimate = edgeEstimates.getValue(candidate.symbol)
            val totalScore = estimate.expectedNetEdgeBps
            val direction = if (totalScore >= 0.0) AlphaDirection.LONG else AlphaDirection.SHORT
            val confidence = listOf(
                abs(edgeRanks.getValue(candidate.symbol)).coerceIn(0.0, 1.0),
                estimate.provisionalConfidence,
                features.trendAgreement.coerceAtLeast(0.0),
                features.pullback.coerceIn(0.0, 1.0),
                features.expansion.coerceIn(0.0, 1.0),
                features.liquidity.coerceIn(0.0, 1.0),
                estimate.executionSupport.coerceIn(0.0, 1.0)
            ).average().coerceIn(0.0, 1.0)
            InterdaySignalSnapshot(
                symbol = candidate.symbol,
                direction = direction,
                score = totalScore,
                empiricalScore = estimate.empiricalScore,
                residualRank = estimate.residualRank,
                confidence = confidence,
                liquidityScore = candidate.liquidityRank.coerceIn(0.1, 1.0),
                trendScore = features.trend,
                trendAgreement = features.trendAgreement,
                pullbackScore = features.pullback,
                fundingScore = when (config.fundingOverlayMode) {
                    InterdayFundingOverlayMode.BOUNDED_REINFORCEMENT,
                    InterdayFundingOverlayMode.CROWDING_GUARD -> features.fundingAlignment
                    else -> features.fundingCarry
                },
                openInterestScore = features.openInterest,
                expansionScore = features.expansion,
                reversalRiskScore = features.reversalRisk,
                fundingOverlayMultiplier = estimate.fundingOverlayMultiplier,
                marketBeta = structuralState.marketBetaBySymbol[candidate.symbol] ?: 0.0,
                upperBound = upperBound,
                lowerBound = lowerBound,
                expectedResidualReturnBps = estimate.expectedResidualReturnBps,
                expectedEntryCostBps = estimate.expectedEntryCostBps,
                expectedTurnoverPenaltyBps = estimate.expectedTurnoverPenaltyBps,
                expectedNetEdgeBps = estimate.expectedNetEdgeBps,
                close = candidate.close,
                predictedVolatility = candidate.volatility
            )
        }
        return SignalComputationResult(
            signals = signals,
            structuralState = structuralState
        )
    }

    private fun collectRawSignals(
        panel: InterdayPanel,
        index: Int,
        config: InterdayAlphaConfig,
        indicators: IndicatorWindows,
        liquidityRanks: Map<String, Double>
    ): List<RawSignal> = panel.series.mapNotNull { series ->
        val current = series.bars.getOrNull(index) ?: return@mapNotNull null
        if (config.requireFunding && current.fundingRate == null) return@mapNotNull null
        val fastReturn = scaledReturn(series.bars, index, indicators.fastBars) ?: return@mapNotNull null
        val mediumReturn = scaledReturn(series.bars, index, indicators.mediumBars) ?: return@mapNotNull null
        val slowReturn = scaledReturn(series.bars, index, indicators.slowBars) ?: return@mapNotNull null
        val volatility = rollingVolatility(series.bars, index, indicators.volatilityBars) ?: return@mapNotNull null
        val slope = regressionTStat(series.bars, index, indicators.regressionBars) ?: return@mapNotNull null
        val movingAverageSpread = movingAverageSpread(series.bars, index, indicators.fastBars, indicators.slowBars) ?: return@mapNotNull null
        val adx = computeAdx(series.bars, index, indicators.adxBars) ?: return@mapNotNull null
        val perturbation = perturbationZ(series.bars, index, config.perturbationLookbackBars, volatility) ?: return@mapNotNull null
        val openInterestMomentum = openInterestMomentum(series.bars, index, indicators.mediumBars)
        if (config.requireOpenInterest && openInterestMomentum == null) return@mapNotNull null
        RawSignal(
            symbol = series.symbol,
            close = current.close,
            volatility = volatility,
            fastReturn = fastReturn,
            mediumReturn = mediumReturn,
            slowReturn = slowReturn,
            slope = slope,
            maSpread = movingAverageSpread,
            adx = adx,
            perturbation = perturbation,
            fundingRate = current.fundingRate ?: 0.0,
            openInterestMomentum = openInterestMomentum ?: 0.0,
            spreadBps = current.spreadBps ?: 8.0,
            depthUsd = current.depthUsd ?: 0.0,
            tradeObservedRatio = current.tradeObservedRatio,
            orderbookObservedRatio = current.orderbookObservedRatio,
            assetContextObservedRatio = current.assetContextObservedRatio,
            liquidityRank = liquidityRanks[series.symbol] ?: 0.5
        )
    }

    private fun buildFeatureVectors(
        raw: List<RawSignal>,
        config: InterdayAlphaConfig
    ): Map<String, FeatureVector> {
        val fastRanks = centeredRanks(raw.associate { it.symbol to it.fastReturn })
        val mediumRanks = centeredRanks(raw.associate { it.symbol to it.mediumReturn })
        val slowRanks = centeredRanks(raw.associate { it.symbol to it.slowReturn })
        val slopeRanks = centeredRanks(raw.associate { it.symbol to it.slope })
        val maRanks = centeredRanks(raw.associate { it.symbol to it.maSpread })
        val adxRanks = centeredRanks(raw.associate { it.symbol to it.adx })
        val fundingRanks = centeredRanks(raw.associate { it.symbol to it.fundingRate })
        val oiRanks = centeredRanks(raw.associate { it.symbol to it.openInterestMomentum })
        val spreadRanks = centeredRanks(raw.associate { it.symbol to -(it.spreadBps) })

        return raw.associate { candidate ->
            val slopeWeight = config.slopeWeight.coerceIn(0.0, 1.0)
            val trendCore = listOf(
                fastRanks.getValue(candidate.symbol),
                mediumRanks.getValue(candidate.symbol),
                slowRanks.getValue(candidate.symbol),
                maRanks.getValue(candidate.symbol)
            ).average()
            val adxThreshold = config.adxThreshold.coerceAtLeast(1.0)
            val adxSupport = ((candidate.adx - adxThreshold) / adxThreshold).coerceIn(-1.0, 1.0)
            val trend = (
                trendCore * (1.0 - slopeWeight) +
                    slopeRanks.getValue(candidate.symbol) * slopeWeight +
                    adxSupport * 0.15 +
                    adxRanks.getValue(candidate.symbol) * 0.10
                ).coerceIn(-1.0, 1.0)
            val trendDirection = if (trend >= 0.0) 1.0 else -1.0
            val trendAgreement = listOf(
                trendDirection * fastRanks.getValue(candidate.symbol),
                trendDirection * mediumRanks.getValue(candidate.symbol),
                trendDirection * slowRanks.getValue(candidate.symbol),
                trendDirection * slopeRanks.getValue(candidate.symbol),
                trendDirection * maRanks.getValue(candidate.symbol),
                trendDirection * adxSupport
            ).average().coerceIn(-1.0, 1.0)
            val pullbackSupport = (-trendDirection * candidate.perturbation).coerceAtLeast(0.0)
            val fundingCarry = (-trendDirection * fundingRanks.getValue(candidate.symbol)).coerceIn(-1.0, 1.0)
            val fundingAlignment = (trendDirection * fundingRanks.getValue(candidate.symbol)).coerceIn(-1.0, 1.0)
            val openInterestSupport = (trendDirection * oiRanks.getValue(candidate.symbol)).coerceIn(-1.0, 1.0)
            val expansion = listOf(
                (trendDirection * mediumRanks.getValue(candidate.symbol)).coerceAtLeast(0.0),
                (trendDirection * oiRanks.getValue(candidate.symbol)).coerceAtLeast(0.0),
                adxRanks.getValue(candidate.symbol).coerceAtLeast(0.0),
                adxSupport.coerceAtLeast(0.0)
            ).average().coerceIn(0.0, 1.0)
            val reversalRisk = listOf(
                (trendDirection * candidate.perturbation).coerceAtLeast(0.0),
                (trendDirection * fundingRanks.getValue(candidate.symbol)).coerceAtLeast(0.0),
                (-trendAgreement).coerceAtLeast(0.0)
            ).average().coerceIn(0.0, 1.0)
            val liquidity = listOf(
                candidate.liquidityRank.coerceIn(0.0, 1.0),
                ((spreadRanks.getValue(candidate.symbol) + 1.0) / 2.0).coerceIn(0.0, 1.0)
            ).average()
            candidate.symbol to FeatureVector(
                trend = trend,
                trendAgreement = trendAgreement,
                pullback = (pullbackSupport / config.perturbationThresholdZ.coerceAtLeast(0.25)).coerceIn(0.0, 1.0),
                fundingCarry = fundingCarry,
                fundingAlignment = fundingAlignment,
                openInterest = openInterestSupport,
                expansion = expansion,
                reversalRisk = reversalRisk,
                liquidity = liquidity.coerceIn(0.0, 1.0)
            )
        }
    }

    private fun computeRegime(
        panel: InterdayPanel,
        index: Int,
        config: InterdayAlphaConfig,
        indicators: IndicatorWindows,
        liquidityRanks: Map<String, Double>
    ): RegimeState {
        val raw = collectRawSignals(panel, index, config, indicators, liquidityRanks)
        if (raw.isEmpty()) return RegimeState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val mediumRanks = centeredRanks(raw.associate { it.symbol to it.mediumReturn })
        val slowRanks = centeredRanks(raw.associate { it.symbol to it.slowReturn })
        val breadth = raw.map {
            listOf(
                mediumRanks.getValue(it.symbol),
                slowRanks.getValue(it.symbol)
            ).average()
        }.average().coerceIn(-1.0, 1.0)
        val anchorSymbols = setOf("BTC", "ETH")
        val anchorTrend = raw.filter { it.symbol in anchorSymbols }
            .map { listOf(mediumRanks.getValue(it.symbol), slowRanks.getValue(it.symbol)).average() }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?: 0.0
        val dispersion = standardDeviation(raw.map { it.mediumReturn }).coerceAtMost(5.0) / 5.0
        val score = listOf(
            mediumRanks.values.averageOrZero(),
            slowRanks.values.averageOrZero(),
            breadth,
            anchorTrend.coerceIn(-1.0, 1.0)
        ).average().coerceIn(-1.0, 1.0)
        val realizedVolatility = raw.map { it.volatility }.averageOrZero()
        val liquidityScore = raw.map { it.liquidityRank.coerceIn(0.0, 1.0) }.averageOrZero()
        val fundingPressure = raw.map { it.fundingRate }.averageOrZero()
        val openInterestPressure = raw.map { it.openInterestMomentum }.averageOrZero()
        return RegimeState(
            score = score,
            breadth = breadth,
            anchorTrend = anchorTrend.coerceIn(-1.0, 1.0),
            dispersion = dispersion.coerceIn(0.0, 1.0),
            realizedVolatility = realizedVolatility,
            liquidityScore = liquidityScore,
            fundingPressure = fundingPressure,
            openInterestPressure = openInterestPressure,
            marketTrendScore = anchorTrend.coerceIn(-1.0, 1.0)
        )
    }

    private fun fitEmpiricalWeights(
        panel: InterdayPanel,
        currentIndex: Int,
        evaluationStartIndex: Int,
        rebalanceBars: Int,
        targetHorizonBars: Int,
        config: InterdayAlphaConfig,
        indicators: IndicatorWindows,
        liquidityRanks: Map<String, Double>,
        residualizationEnabled: Boolean
    ): EmpiricalWeights {
        val observations = mutableListOf<FeatureObservation>()
        val upperTrainingIndex = currentIndex - targetHorizonBars
        if (upperTrainingIndex >= evaluationStartIndex) {
            var trainingIndex = evaluationStartIndex
            while (trainingIndex <= upperTrainingIndex) {
                val raw = collectRawSignals(panel, trainingIndex, config, indicators, liquidityRanks)
                if (raw.isNotEmpty()) {
                    val structuralState = buildStructuralState(
                        panel = panel,
                        index = trainingIndex,
                        raw = raw,
                        config = config,
                        indicators = indicators,
                        enabled = residualizationEnabled
                    )
                    val scoringRaw = structuralState.residualizedRaw
                    val vectors = buildFeatureVectors(scoringRaw, config)
                    val futureReturns = if (residualizationEnabled) {
                        futureStructuralResidualReturns(panel, trainingIndex, targetHorizonBars, structuralState)
                    } else {
                        futureResidualReturns(panel, trainingIndex, targetHorizonBars)
                    }
                    vectors.forEach { (symbol, features) ->
                        val target = futureReturns[symbol] ?: return@forEach
                        observations += FeatureObservation(features = features, targetResidualReturn = target)
                    }
                }
                trainingIndex += rebalanceBars
            }
        }
        val prior = bootstrapWeights(config)
        if (observations.isEmpty()) return prior
        val fitted = ridgeFit(observations, lambda = config.empiricalFitRegularization, config = config)
        val blend = (observations.size.toDouble() / config.empiricalMinTrainingObservations.toDouble()).coerceIn(0.0, 1.0)
        val coefficients = DoubleArray(prior.coefficients.size) { index ->
            prior.coefficients[index] * (1.0 - blend) + fitted.coefficients[index] * blend
        }
        return EmpiricalWeights(
            intercept = (prior.intercept * (1.0 - blend) + fitted.intercept * blend).finiteOrZero(),
            coefficients = coefficients.map { it.finiteOrZero() }.toDoubleArray(),
            observations = observations.size
        )
    }

    private fun futureResidualReturns(
        panel: InterdayPanel,
        index: Int,
        horizonBars: Int
    ): Map<String, Double> {
        val futureIndex = index + horizonBars
        val rawReturns = panel.series.mapNotNull { series ->
            val start = series.bars.getOrNull(index) ?: return@mapNotNull null
            val end = series.bars.getOrNull(futureIndex) ?: return@mapNotNull null
            if (start.close <= 0.0 || end.close <= 0.0) return@mapNotNull null
            series.symbol to ln(end.close / start.close)
        }.toMap()
        if (rawReturns.isEmpty()) return emptyMap()
        val baseline = median(rawReturns.values.toList())
        return rawReturns.mapValues { (_, value) -> value - baseline }
    }

    private fun futureStructuralResidualReturns(
        panel: InterdayPanel,
        index: Int,
        horizonBars: Int,
        structuralState: StructuralState
    ): Map<String, Double> {
        if (!structuralState.enabled) return futureResidualReturns(panel, index, horizonBars)
        val futureIndex = index + horizonBars
        if (futureIndex >= panel.timeline.size) return emptyMap()
        val totals = mutableMapOf<String, Double>()
        for (cursor in (index + 1)..futureIndex) {
            val rawReturns = panel.series.mapNotNull { series ->
                logReturnAt(series.bars, cursor)?.let { series.symbol to it }
            }.toMap()
            if (rawReturns.isEmpty()) continue
            val marketReturn = weightedAverage(
                valuesBySymbol = rawReturns,
                weightsBySymbol = structuralState.marketProxyWeightsBySymbol
            )
            structuralState.marketBetaBySymbol.forEach { (symbol, marketBeta) ->
                val symbolReturn = rawReturns[symbol] ?: return@forEach
                val residualReturn = symbolReturn - marketBeta * marketReturn
                totals[symbol] = (totals[symbol] ?: 0.0) + residualReturn
            }
        }
        if (totals.isEmpty()) return emptyMap()
        val baseline = median(totals.values.toList())
        return totals.mapValues { (_, value) -> value - baseline }
    }

    private fun buildCompressionDiagnostics(
        time: Instant,
        signals: List<InterdaySignalSnapshot>,
        structuralState: StructuralState,
        regime: RegimeState,
        panel: InterdayPanel,
        index: Int,
        targetHorizonBars: Int,
        signalBarMinutes: Int,
        rebalanceHours: Int,
        historyByKey: MutableMap<Pair<Int, Int>, MutableList<CompressionDiagnosticRawPoint>>
    ): List<InterdayCompressionDiagnosticPoint> {
        if (!structuralState.enabled || signals.size < 4) return emptyList()
        val futureReturns = futureStructuralResidualReturns(panel, index, targetHorizonBars, structuralState)
        if (futureReturns.isEmpty()) return emptyList()
        val longCandidates = signals.filter { it.direction == AlphaDirection.LONG }.sortedByDescending { it.empiricalScore }
        val shortCandidates = signals.filter { it.direction == AlphaDirection.SHORT }.sortedBy { it.empiricalScore }
        if (longCandidates.size < 2 || shortCandidates.size < 2) return emptyList()
        val historyObservations = max(20, (COMPRESSION_DIAGNOSTIC_HISTORY_DAYS * 24) / rebalanceHours.coerceAtLeast(1))

        return COMPRESSION_DIAGNOSTIC_WINDOWS_DAYS.flatMap { windowDays ->
            val windowBars = barsForDays(windowDays, signalBarMinutes).coerceAtLeast(4)
            COMPRESSION_DIAGNOSTIC_SLEEVE_SIZES.mapNotNull { sleeveSize ->
                val longSymbols = longCandidates.map { it.symbol }
                    .filter { symbol ->
                        val window = structuralState.residualReturnWindowBySymbol[symbol]
                        window != null && window.size >= windowBars && futureReturns.containsKey(symbol)
                    }
                    .take(sleeveSize)
                val shortSymbols = shortCandidates.map { it.symbol }
                    .filter { symbol ->
                        val window = structuralState.residualReturnWindowBySymbol[symbol]
                        window != null && window.size >= windowBars && futureReturns.containsKey(symbol)
                    }
                    .take(sleeveSize)
                if (longSymbols.size < 2 || shortSymbols.size < 2) return@mapNotNull null

                val longSeries = longSymbols.mapNotNull { symbol ->
                    structuralState.residualReturnWindowBySymbol[symbol]?.takeLast(windowBars)?.takeIf { it.size == windowBars }
                }
                val shortSeries = shortSymbols.mapNotNull { symbol ->
                    structuralState.residualReturnWindowBySymbol[symbol]?.takeLast(windowBars)?.takeIf { it.size == windowBars }
                }
                if (longSeries.size < 2 || shortSeries.size < 2) return@mapNotNull null

                val pc1Share = pc1Share(longSeries + shortSeries)
                val coMomentum = 0.5 * (
                    averagePairwiseCorrelation(longSeries) +
                        averagePairwiseCorrelation(shortSeries)
                    )
                val factorReturn = longSymbols.mapNotNull { futureReturns[it] }.averageOrZero() -
                    shortSymbols.mapNotNull { futureReturns[it] }.averageOrZero()
                val key = windowBars to sleeveSize
                val history = historyByKey.getOrPut(key) { mutableListOf() }
                val recentHistory = history.takeLast(historyObservations)
                val pc1ShareZ = robustZScore(recentHistory.map { it.pc1Share }, pc1Share)
                val coMomentumZ = robustZScore(recentHistory.map { it.coMomentum }, coMomentum)
                history += CompressionDiagnosticRawPoint(pc1Share = pc1Share, coMomentum = coMomentum)

                InterdayCompressionDiagnosticPoint(
                    time = time,
                    windowBars = windowBars,
                    sleeveSizePerSide = sleeveSize,
                    pc1Share = pc1Share,
                    coMomentum = coMomentum,
                    pc1ShareZ = pc1ShareZ,
                    coMomentumZ = coMomentumZ,
                    futureFactorReturnBps = factorReturn * 10_000.0,
                    longSleeveSize = longSymbols.size,
                    shortSleeveSize = shortSymbols.size,
                    marketTrendScore = regime.marketTrendScore,
                    breadth = regime.breadth,
                    dispersion = regime.dispersion
                )
            }
        }
    }

    private fun currentCompressionPenaltyState(
        signals: List<InterdaySignalSnapshot>,
        structuralState: StructuralState,
        signalBarMinutes: Int,
        config: InterdayAlphaConfig,
        historyByKey: MutableMap<Pair<Int, Int>, MutableList<CompressionDiagnosticRawPoint>>
    ): CompressionPenaltyState {
        if (config.compressionPenaltyMode == InterdayCompressionPenaltyMode.NONE || !structuralState.enabled) {
            return CompressionPenaltyState.disabled()
        }
        val compressionState = currentCompressionMetricState(
            signals = signals,
            structuralState = structuralState,
            signalBarMinutes = signalBarMinutes,
            windowDays = config.compressionWindowDays,
            sleeveSizePerSide = config.compressionSleeveSizePerSide,
            historyByKey = historyByKey
        )
        if (!compressionState.available) return CompressionPenaltyState.disabled()
        return CompressionPenaltyState(
            mode = config.compressionPenaltyMode,
            zScore = compressionState.zScore,
            scale = compressionPenaltyScale(compressionState.zScore, config),
            windowBars = compressionState.windowBars,
            sleeveSizePerSide = compressionState.sleeveSizePerSide
        )
    }

    private fun currentFlatHazardState(
        regime: RegimeState,
        signals: List<InterdaySignalSnapshot>,
        structuralState: StructuralState,
        signalBarMinutes: Int,
        config: InterdayAlphaConfig,
        historyByKey: MutableMap<Pair<Int, Int>, MutableList<CompressionDiagnosticRawPoint>>
    ): FlatHazardState {
        if (config.flatHazardMode == InterdayFlatHazardMode.NONE) return FlatHazardState.disabled()
        val trendComponent = flatTrendHazardComponent(regime.marketTrendScore, config)
        if (trendComponent <= 1e-9) {
            return FlatHazardState(
                mode = config.flatHazardMode,
                intensity = 0.0,
                grossScale = 1.0,
                trendComponent = 0.0,
                compressionConfirm = 1.0,
                compressionZScore = 0.0,
                windowBars = 0,
                sleeveSizePerSide = 0
            )
        }
        val compressionState = when (config.flatHazardMode) {
            InterdayFlatHazardMode.NONE,
            InterdayFlatHazardMode.MARKET_TREND_ONLY -> CompressionMetricState.disabled()
            InterdayFlatHazardMode.MARKET_TREND_AND_PC1_SHARE -> currentCompressionMetricState(
                signals = signals,
                structuralState = structuralState,
                signalBarMinutes = signalBarMinutes,
                windowDays = config.flatHazardCompressionWindowDays,
                sleeveSizePerSide = config.flatHazardCompressionSleeveSizePerSide,
                historyByKey = historyByKey
            )
        }
        val compressionConfirm = when (config.flatHazardMode) {
            InterdayFlatHazardMode.NONE,
            InterdayFlatHazardMode.MARKET_TREND_ONLY -> 1.0
            InterdayFlatHazardMode.MARKET_TREND_AND_PC1_SHARE ->
                if (!compressionState.available) 0.0 else flatHazardCompressionConfirm(compressionState.zScore, config)
        }
        val intensity = (trendComponent * compressionConfirm).coerceIn(0.0, 1.0)
        return FlatHazardState(
            mode = config.flatHazardMode,
            intensity = intensity,
            grossScale = flatHazardGrossScale(intensity, config),
            trendComponent = trendComponent,
            compressionConfirm = compressionConfirm,
            compressionZScore = compressionState.zScore,
            windowBars = compressionState.windowBars,
            sleeveSizePerSide = compressionState.sleeveSizePerSide
        )
    }

    private fun currentCompressionMetricState(
        signals: List<InterdaySignalSnapshot>,
        structuralState: StructuralState,
        signalBarMinutes: Int,
        windowDays: Int,
        sleeveSizePerSide: Int,
        historyByKey: MutableMap<Pair<Int, Int>, MutableList<CompressionDiagnosticRawPoint>>
    ): CompressionMetricState {
        if (!structuralState.enabled) return CompressionMetricState.disabled()
        val windowBars = barsForDays(windowDays, signalBarMinutes).coerceAtLeast(4)
        val sleeveSize = sleeveSizePerSide.coerceAtLeast(2)
        val rawPoint = compressionObservation(signals, structuralState, windowBars, sleeveSize)
            ?: return CompressionMetricState.disabled()
        val key = windowBars to sleeveSize
        val history = historyByKey.getOrPut(key) { mutableListOf() }
        val historyObservations = max(20, COMPRESSION_DIAGNOSTIC_HISTORY_DAYS)
        val recentHistory = history.takeLast(historyObservations)
        val zScore = robustZScore(recentHistory.map { it.pc1Share }, rawPoint.pc1Share)
        history += rawPoint
        return CompressionMetricState(
            available = true,
            pc1Share = rawPoint.pc1Share,
            zScore = zScore,
            windowBars = windowBars,
            sleeveSizePerSide = sleeveSize
        )
    }

    private fun compressionObservation(
        signals: List<InterdaySignalSnapshot>,
        structuralState: StructuralState,
        windowBars: Int,
        sleeveSizePerSide: Int
    ): CompressionDiagnosticRawPoint? {
        if (!structuralState.enabled || signals.size < 4) return null
        val longCandidates = signals.filter { it.direction == AlphaDirection.LONG }.sortedByDescending { it.empiricalScore }
        val shortCandidates = signals.filter { it.direction == AlphaDirection.SHORT }.sortedBy { it.empiricalScore }
        if (longCandidates.size < 2 || shortCandidates.size < 2) return null
        val longSeries = longCandidates.mapNotNull { signal ->
            structuralState.residualReturnWindowBySymbol[signal.symbol]?.takeLast(windowBars)?.takeIf { it.size == windowBars }
        }.take(sleeveSizePerSide)
        val shortSeries = shortCandidates.mapNotNull { signal ->
            structuralState.residualReturnWindowBySymbol[signal.symbol]?.takeLast(windowBars)?.takeIf { it.size == windowBars }
        }.take(sleeveSizePerSide)
        if (longSeries.size < 2 || shortSeries.size < 2) return null
        return CompressionDiagnosticRawPoint(
            pc1Share = pc1Share(longSeries + shortSeries),
            coMomentum = 0.5 * (averagePairwiseCorrelation(longSeries) + averagePairwiseCorrelation(shortSeries))
        )
    }

    private fun applyCompressionPenalty(
        signal: InterdaySignalSnapshot,
        incumbentSameSide: Boolean,
        state: CompressionPenaltyState
    ): InterdaySignalSnapshot {
        if (incumbentSameSide || state.mode == InterdayCompressionPenaltyMode.NONE || state.scale >= 0.999999) {
            return signal
        }
        return signal.copy(
            score = signal.score * state.scale,
            expectedNetEdgeBps = signal.expectedNetEdgeBps * state.scale
        )
    }

    private fun bootstrapWeights(config: InterdayAlphaConfig): EmpiricalWeights = EmpiricalWeights(
        intercept = 0.0,
        coefficients = doubleArrayOf(
            0.55,
            0.30,
            0.20 * config.pullbackWeight.coerceAtLeast(0.25),
            0.15 * config.fundingWeight.coerceAtLeast(0.25),
            0.15 * config.openInterestWeight.coerceAtLeast(0.25),
            0.20,
            -0.35,
            0.10
        ),
        observations = 0
    )

    private fun ridgeFit(
        observations: List<FeatureObservation>,
        lambda: Double,
        config: InterdayAlphaConfig
    ): EmpiricalWeights {
        val dimension = observations.firstOrNull()?.features?.toArray(config)?.size ?: 0
        if (dimension == 0) return EmpiricalWeights(0.0, DoubleArray(0), observations.size)
        val gram = Array(dimension + 1) { DoubleArray(dimension + 1) }
        val rhs = DoubleArray(dimension + 1)
        observations.forEach { observation ->
            val row = doubleArrayOf(1.0, *observation.features.toArray(config))
            for (left in row.indices) {
                rhs[left] += row[left] * observation.targetResidualReturn
                for (right in row.indices) {
                    gram[left][right] += row[left] * row[right]
                }
            }
        }
        for (index in 1 until gram.size) {
            gram[index][index] += lambda
        }
        val solved = solveLinearSystem(gram, rhs)
        return if (solved == null) {
            EmpiricalWeights(0.0, DoubleArray(dimension), observations.size)
        } else {
            val sanitized = solved.map { it.finiteOrZero() }.toDoubleArray()
            EmpiricalWeights(
                intercept = sanitized.first(),
                coefficients = sanitized.copyOfRange(1, sanitized.size),
                observations = observations.size
            )
        }
    }

    private fun failedEvaluation(
        config: InterdayAlphaConfig,
        error: Throwable
    ): EvaluationBundle {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "unknown error"
        return EvaluationBundle(
            config = config,
            backtest = emptyPerformance("backtest"),
            forward = emptyPerformance("forward"),
            validation = InterdayValidation(
                accepted = false,
                backtestAccepted = false,
                forwardAccepted = false,
                reasons = listOf("evaluation failed: $reason")
            ),
            latestSignals = emptyList(),
            latestTargets = emptyList(),
            trades = emptyList(),
            inspection = null,
            grossLogReturn = 0.0
        )
    }

    private fun survivorOrdering(): Comparator<EvaluationBundle> =
        compareByDescending<EvaluationBundle> { it.validation.accepted }
            .thenByDescending { it.forward.calmar }
            .thenByDescending { it.forward.alignedParticipationRate }
            .thenByDescending { it.forward.avgWinnerLoserRatio }
            .thenByDescending { it.forward.stabilityScore }
            .thenBy { it.forward.maxDrawdownPct }
            .thenBy { it.forward.timeUnderWaterPct }
            .thenBy { it.forward.wrongWayExposurePct }
            .thenBy { it.forward.avgTurnoverPct }

    private fun validate(
        config: InterdayAlphaConfig,
        backtest: InterdayPerformance,
        forward: InterdayPerformance,
        searchPolicy: org.datamancy.trading.policy.AlphaSearchPolicy
    ): InterdayValidation {
        val reasons = mutableListOf<String>()
        val backtestAccepted =
            backtest.tradeCount >= searchPolicy.minBacktestTrades &&
                backtest.edgeAfterCostBps >= searchPolicy.minNetEdgeBps &&
                backtest.maxDrawdownPct <= searchPolicy.maxSearchDrawdownPct
        if (!backtestAccepted) {
            if (backtest.tradeCount < searchPolicy.minBacktestTrades) reasons += "backtest trade count ${backtest.tradeCount} < ${searchPolicy.minBacktestTrades}"
            if (backtest.edgeAfterCostBps < searchPolicy.minNetEdgeBps) reasons += "backtest edgeAfterCostBps ${format(backtest.edgeAfterCostBps)} < ${searchPolicy.minNetEdgeBps}"
            if (backtest.maxDrawdownPct > searchPolicy.maxSearchDrawdownPct) reasons += "backtest drawdown ${format(backtest.maxDrawdownPct)} > ${searchPolicy.maxSearchDrawdownPct}"
        }
        val forwardAccepted =
            forward.tradeCount >= searchPolicy.minForwardTrades &&
                forward.edgeAfterCostBps >= 0.0 &&
                forward.maxDrawdownPct <= searchPolicy.maxSearchDrawdownPct &&
                forward.calmar >= searchPolicy.minForwardCalmar &&
                forward.timeUnderWaterPct <= searchPolicy.maxTimeUnderWaterPct &&
                abs(forward.cvar1dPct) <= searchPolicy.maxCvar1dPct &&
                forward.alignedParticipationRate >= searchPolicy.minAlignedParticipationRate &&
                forward.wrongWayExposurePct <= searchPolicy.maxWrongWayExposurePct &&
                forward.killSwitchUtilizationMax <= searchPolicy.maxKillSwitchUtilization
        if (!forwardAccepted) {
            if (forward.tradeCount < searchPolicy.minForwardTrades) reasons += "forward trade count ${forward.tradeCount} < ${searchPolicy.minForwardTrades}"
            if (forward.edgeAfterCostBps < 0.0) reasons += "forward edgeAfterCostBps ${format(forward.edgeAfterCostBps)} < 0"
            if (forward.maxDrawdownPct > searchPolicy.maxSearchDrawdownPct) reasons += "forward drawdown ${format(forward.maxDrawdownPct)} > ${searchPolicy.maxSearchDrawdownPct}"
            if (forward.calmar < searchPolicy.minForwardCalmar) reasons += "forward calmar ${format(forward.calmar)} < ${searchPolicy.minForwardCalmar}"
            if (forward.timeUnderWaterPct > searchPolicy.maxTimeUnderWaterPct) reasons += "forward timeUnderWaterPct ${format(forward.timeUnderWaterPct)} > ${searchPolicy.maxTimeUnderWaterPct}"
            if (abs(forward.cvar1dPct) > searchPolicy.maxCvar1dPct) reasons += "forward cvar1dPct ${format(forward.cvar1dPct)} exceeds ${searchPolicy.maxCvar1dPct}"
            if (forward.alignedParticipationRate < searchPolicy.minAlignedParticipationRate) reasons += "forward alignedParticipationRate ${format(forward.alignedParticipationRate)} < ${searchPolicy.minAlignedParticipationRate}"
            if (forward.wrongWayExposurePct > searchPolicy.maxWrongWayExposurePct) reasons += "forward wrongWayExposurePct ${format(forward.wrongWayExposurePct)} > ${searchPolicy.maxWrongWayExposurePct}"
            if (forward.killSwitchUtilizationMax > searchPolicy.maxKillSwitchUtilization) reasons += "forward killSwitchUtilizationMax ${format(forward.killSwitchUtilizationMax)} > ${searchPolicy.maxKillSwitchUtilization}"
        }
        if (config.layeredStopFractions.sum() < 0.99) reasons += "layered stop fractions should sum close to 1.0"
        if (config.layeredStopMultipliers.size != config.layeredStopFractions.size) reasons += "layered stop bands and fractions differ in size"
        if (config.exitOverlayMode == InterdayExitOverlayMode.TIME_STOP && config.timeStopBars < 1) reasons += "time stop bars must be >= 1"
        if (config.residualizationBetaMode == InterdayResidualizationBetaMode.EWMA && config.residualizationHalfLifeDays < 1) {
            reasons += "ewma residualization half-life days must be >= 1"
        }
        if (config.flatRegimeMarketTrendThreshold < 0.0) reasons += "flat regime market trend threshold must be >= 0"
        if (config.flatRegimeBreadthThreshold < 0.0) reasons += "flat regime breadth threshold must be >= 0"
        if (config.flatRegimeGrossScale <= 0.0) reasons += "flat regime gross scale must be > 0"
        if (config.flatRegimeEntryEdgeFloorBoostBps < 0.0) reasons += "flat regime entry edge floor boost must be >= 0"
        if (config.flatRegimeMinDispersion < 0.0 || config.flatRegimeMinDispersion > 1.0) {
            reasons += "flat regime min dispersion must be within [0,1]"
        }
        if (config.flatRegimeTrendAgreementBoost < 0.0 || config.flatRegimeTrendAgreementBoost > 1.0) {
            reasons += "flat regime trend agreement boost must be within [0,1]"
        }
        if (config.compressionWindowDays < 4) reasons += "compression window days must be >= 4"
        if (config.compressionSleeveSizePerSide < 2) reasons += "compression sleeve size per side must be >= 2"
        if (config.compressionPenaltyStrength < 0.0 || config.compressionPenaltyStrength > 1.0) {
            reasons += "compression penalty strength must be within [0,1]"
        }
        if (config.flatHazardGrossScaleFloor <= 0.0) reasons += "flat hazard gross scale floor must be > 0"
        if (config.flatHazardCompressionWindowDays < 4) reasons += "flat hazard compression window days must be >= 4"
        if (config.flatHazardCompressionSleeveSizePerSide < 2) reasons += "flat hazard compression sleeve size per side must be >= 2"
        return InterdayValidation(
            accepted = backtestAccepted && forwardAccepted && reasons.isEmpty(),
            backtestAccepted = backtestAccepted,
            forwardAccepted = forwardAccepted,
            reasons = if (reasons.isEmpty()) {
                listOf("Candidate satisfies current search trade-count, edge-after-cost, and drawdown gates.")
            } else {
                reasons
            }
        )
    }

    private fun buildInspection(
        snapshots: List<InterdayPortfolioSnapshot>,
        regimes: List<InterdayRegimeSnapshot>,
        signalHistory: Map<String, MutableList<InterdayInspectionPoint>>,
        compressionDiagnostics: List<InterdayCompressionDiagnosticPoint>,
        weightTimeline: Map<Instant, Map<String, Double>>,
        desiredWeightTimeline: Map<Instant, Map<String, Double>>,
        appliedDeltaTimeline: Map<Instant, Map<String, Double>>,
        topSymbols: List<String>
    ): InterdayInspection {
        val symbolSeries = topSymbols.distinct().mapNotNull { symbol ->
            val points = signalHistory[symbol].orEmpty()
                .takeLast(128)
                .map { point ->
                    point.copy(
                        desiredWeight = desiredWeightTimeline[point.time]?.get(symbol) ?: point.desiredWeight,
                        appliedDelta = appliedDeltaTimeline[point.time]?.get(symbol) ?: point.appliedDelta,
                        positionWeight = weightTimeline[point.time]?.get(symbol) ?: 0.0
                    )
                }
            if (points.isEmpty()) null else InterdaySymbolInspection(symbol = symbol, points = points)
        }
        return InterdayInspection(
            portfolio = snapshots.takeLast(256),
            symbols = symbolSeries,
            regimes = regimes.takeLast(256),
            compressionDiagnostics = compressionDiagnostics.takeLast(4096)
        )
    }

    private fun buildPerformance(
        segment: String,
        snapshots: List<InterdayPortfolioSnapshot>,
        regimes: List<InterdayRegimeSnapshot>,
        trades: List<InterdayTradeRecord>,
        splitTime: Instant?,
        beforeSplit: Boolean,
        signalBarMinutes: Int,
        searchPolicy: org.datamancy.trading.policy.AlphaSearchPolicy,
        regimeStrengthThreshold: Double,
        configuredRegimeSlices: List<String>
    ): InterdayPerformance {
        val segmentSnapshots = snapshots.filter { snapshot ->
            when {
                splitTime == null -> true
                beforeSplit -> snapshot.time <= splitTime
                else -> snapshot.time > splitTime
            }
        }
        val segmentTrades = trades.filter { trade ->
            when {
                splitTime == null -> true
                beforeSplit -> trade.exitTime <= splitTime
                else -> trade.exitTime > splitTime
            }
        }
        val segmentRegimes = regimes.filter { regime ->
            when {
                splitTime == null -> true
                beforeSplit -> regime.time <= splitTime
                else -> regime.time > splitTime
            }
        }
        if (segmentSnapshots.size < 2) {
            return emptyPerformance(
                segment = segment,
                startTime = segmentSnapshots.firstOrNull()?.time,
                endTime = segmentSnapshots.lastOrNull()?.time,
                tradeCount = segmentTrades.size,
                winRate = if (segmentTrades.isEmpty()) 0.0 else segmentTrades.count { it.pnlPct > 0.0 }.toDouble() / segmentTrades.size.toDouble()
            )
        }
        val startEquity = segmentSnapshots.first().equity
        val endEquity = segmentSnapshots.last().equity
        val returns = segmentSnapshots.zipWithNext { left, right -> ln(right.equity / left.equity) }
        val sharpe = annualizedSharpe(returns, signalBarMinutes)
        val maxDrawdownPct = maxDrawdownPct(segmentSnapshots.map { it.equity })
        val annualizedReturnPct = annualizedReturnPct(
            startEquity = startEquity,
            endEquity = endEquity,
            startTime = segmentSnapshots.first().time,
            endTime = segmentSnapshots.last().time
        )
        val avgTurnoverPct = segmentSnapshots.map { it.turnoverFraction }.average() * 100.0
        val netReturnPct = ((endEquity / startEquity) - 1.0) * 100.0
        val bootstrap = bootstrapStatistics(returns, signalBarMinutes)
        val tradeCount = segmentTrades.size
        val winRate = if (segmentTrades.isEmpty()) 0.0 else segmentTrades.count { it.pnlPct > 0.0 }.toDouble() / segmentTrades.size.toDouble()
        val edgeAfterCostBps = if (tradeCount == 0) 0.0 else (netReturnPct * 100.0) / tradeCount.toDouble()
        val calmar = if (maxDrawdownPct <= 1e-9) annualizedReturnPct else annualizedReturnPct / maxDrawdownPct
        val ulcerIndex = ulcerIndex(segmentSnapshots.map { it.equity })
        val timeUnderWaterPct = timeUnderWaterPct(segmentSnapshots.map { it.equity })
        val cvar1dPct = cvar1dPct(returns, signalBarMinutes)
        val alignedParticipationRate = alignedParticipationRate(segmentSnapshots, regimeStrengthThreshold)
        val wrongWayExposurePct = wrongWayExposurePct(segmentSnapshots, regimeStrengthThreshold)
        val profitGivebackPct = averageProfitGivebackPct(segmentTrades)
        val pnlSkew = pnlSkew(segmentTrades.map { it.pnlPct })
        val avgWinnerLoserRatio = avgWinnerLoserRatio(segmentTrades)
        val killSwitchUtilizationMax = killSwitchUtilizationMax(segmentSnapshots, searchPolicy, regimeStrengthThreshold)
        val regimeSlices = buildRegimeSlicePerformance(
            segmentSnapshots = segmentSnapshots,
            segmentRegimes = segmentRegimes,
            segmentTrades = segmentTrades,
            signalBarMinutes = signalBarMinutes,
            configuredRegimeSlices = configuredRegimeSlices
        )
        return InterdayPerformance(
            segment = segment,
            startTime = segmentSnapshots.first().time,
            endTime = segmentSnapshots.last().time,
            netReturnPct = netReturnPct,
            annualizedReturnPct = annualizedReturnPct,
            grossReturnPct = returns.sum() * 100.0,
            sharpe = sharpe,
            maxDrawdownPct = maxDrawdownPct,
            tradeCount = tradeCount,
            winRate = winRate,
            avgTurnoverPct = avgTurnoverPct,
            edgeAfterCostBps = edgeAfterCostBps,
            bootstrapReturnP05Pct = bootstrap.returnP05Pct,
            bootstrapSharpeP05 = bootstrap.sharpeP05,
            stabilityScore = (bootstrap.sharpeP05.coerceAtLeast(0.0) + sharpe.coerceAtLeast(0.0) + winRate) / 3.0,
            calmar = calmar,
            ulcerIndex = ulcerIndex,
            timeUnderWaterPct = timeUnderWaterPct,
            cvar1dPct = cvar1dPct,
            alignedParticipationRate = alignedParticipationRate,
            wrongWayExposurePct = wrongWayExposurePct,
            profitGivebackPct = profitGivebackPct,
            pnlSkew = pnlSkew,
            avgWinnerLoserRatio = avgWinnerLoserRatio,
            killSwitchUtilizationMax = killSwitchUtilizationMax,
            regimeSlices = regimeSlices
        )
    }

    private fun estimatePortfolioImpactBps(
        config: InterdayAlphaConfig,
        signals: List<InterdaySignalSnapshot>,
        latestBars: Map<String, InterdayBar>
    ): Double {
        if (signals.isEmpty()) return 4.0
        val capitalPerSymbol = config.capitalUsd / signals.size.toDouble()
        val impacts = signals.map { signal ->
            val bar = latestBars[signal.symbol]
            if (bar == null) {
                4.0
            } else {
                estimateImpactBps(
                    weightDelta = signal.confidence.coerceIn(0.05, 0.5),
                    capitalUsd = capitalPerSymbol,
                    depthUsd = bar.depthUsd ?: 0.0
                )
            }
        }
        return impacts.average().coerceAtLeast(1.0)
    }

    private fun latestBars(panel: InterdayPanel): Map<String, InterdayBar> =
        panel.series.mapNotNull { series ->
            series.bars.lastOrNull { it != null }?.let { series.symbol to it }
        }.toMap()

    private fun precomputeLiquidityRanks(panel: InterdayPanel): Map<String, Double> {
        val scores = panel.series.associate { series ->
            val valid = series.bars.filterNotNull()
            val avgDepth = valid.mapNotNull { it.depthUsd }.averageOrZero()
            val avgSpread = valid.mapNotNull { it.spreadBps }.averageOrZero().takeIf { it > 0.0 } ?: 8.0
            val avgTurnover = valid.map { it.volume * it.close }.averageOrZero()
            val score = ln(1.0 + avgDepth + avgTurnover / 10.0) - (avgSpread / 20.0)
            series.symbol to score
        }
        val ranks = centeredRanks(scores)
        return ranks.mapValues { (_, value) -> ((value + 1.0) / 2.0).coerceIn(0.1, 1.0) }
    }

    private fun gradualDelta(currentSigned: Double, targetSigned: Double, step: Double): Double {
        if (currentSigned == 0.0) {
            return (targetSigned).coerceIn(-step, step)
        }
        return if (sign(currentSigned) != 0.0 && sign(targetSigned) != 0.0 && sign(currentSigned) != sign(targetSigned)) {
            -currentSigned.coerceIn(-step, step)
        } else {
            (targetSigned - currentSigned).coerceIn(-step, step)
        }
    }

    private fun adjustmentStep(
        baseStep: Double,
        rebalanceBars: Int,
        confidence: Double,
        mode: InterdayAdjustmentMode
    ): Double = when (mode) {
        InterdayAdjustmentMode.REBALANCE_STEP -> baseStep
        InterdayAdjustmentMode.CONTINUOUS_RAMP -> {
            val perBarBase = baseStep / rebalanceBars.coerceAtLeast(1).toDouble()
            val confidenceScaler = 0.35 + 0.65 * confidence.coerceIn(0.0, 1.0)
            (perBarBase * confidenceScaler).coerceAtLeast(0.005)
        }
    }

    private fun estimateTransactionCostFraction(
        weightDelta: Double,
        bar: InterdayBar,
        confidence: Double,
        config: InterdayAlphaConfig
    ): Double = (weightDelta * estimateTransactionCostBps(weightDelta, bar, confidence, config) / 10_000.0).coerceAtLeast(0.0)

    private fun estimateTransactionCostBps(
        weightDelta: Double,
        bar: InterdayBar,
        confidence: Double,
        config: InterdayAlphaConfig
    ): Double = estimateTransactionCostBps(
        weightDelta = weightDelta,
        spreadBps = bar.spreadBps ?: 8.0,
        depthUsd = bar.depthUsd ?: 0.0,
        orderbookObservedRatio = bar.orderbookObservedRatio,
        fundingRate = bar.fundingRate ?: 0.0,
        confidence = confidence,
        config = config
    )

    private fun estimateTransactionCostBps(
        weightDelta: Double,
        bar: RawSignal,
        confidence: Double,
        config: InterdayAlphaConfig
    ): Double = estimateTransactionCostBps(
        weightDelta = weightDelta,
        spreadBps = bar.spreadBps,
        depthUsd = bar.depthUsd,
        orderbookObservedRatio = bar.orderbookObservedRatio,
        fundingRate = bar.fundingRate,
        confidence = confidence,
        config = config
    )

    private fun estimateTransactionCostBps(
        weightDelta: Double,
        spreadBps: Double,
        depthUsd: Double,
        orderbookObservedRatio: Double,
        fundingRate: Double,
        confidence: Double,
        config: InterdayAlphaConfig
    ): Double {
        val defaults = executionPlanner.defaults()
        val makerShare = when {
            confidence >= 0.80 -> 0.80
            confidence >= 0.60 -> 0.55
            else -> 0.35
        }
        val feeBps = defaults.makerFeeBps * makerShare + defaults.takerFeeBps * (1.0 - makerShare)
        val halfSpreadBps = spreadBps * if (confidence >= 0.75) 0.35 else 0.50
        val impactBps = estimateImpactBps(weightDelta, config.capitalUsd, depthUsd)
        val adverseSelectionBps = halfSpreadBps * if (orderbookObservedRatio >= 0.6) 0.08 else 0.16
        val fundingDriftBps = abs(fundingRate) * 10_000.0 * (config.rebalanceCadenceHours / 8.0)
        return feeBps + halfSpreadBps + impactBps + adverseSelectionBps + fundingDriftBps
    }

    private fun estimateTurnoverPenaltyBps(
        currentSigned: Double,
        targetSigned: Double,
        defaults: AlphaPortfolioDefaults
    ): Double {
        val turnoverDelta = abs(targetSigned - currentSigned)
        if (turnoverDelta <= 1e-9) return 0.0
        val unit = defaults.maxWeightPerSymbol.coerceAtLeast(0.01)
        return defaults.turnoverPenaltyBps * (turnoverDelta / unit)
    }

    private fun assumedTargetWeightFraction(
        confidence: Double,
        defaults: AlphaPortfolioDefaults
    ): Double {
        val exposureFraction = (
            defaults.minTargetExposureFraction +
                (defaults.maxTargetExposureFraction - defaults.minTargetExposureFraction) * confidence.coerceIn(0.0, 1.0)
            ).coerceIn(defaults.minTargetExposureFraction, defaults.maxTargetExposureFraction)
        return (defaults.maxWeightPerSymbol * (0.5 + 0.5 * exposureFraction)).coerceAtMost(defaults.maxWeightPerSymbol)
    }

    private fun applyDelta(
        activePositions: MutableMap<String, ActivePosition>,
        currentWeights: MutableMap<String, Double>,
        trades: MutableList<InterdayTradeRecord>,
        symbol: String,
        signedDelta: Double,
        price: Double,
        volatility: Double,
        confidence: Double,
        time: Instant,
        reason: String,
        splitTime: Instant?
    ) {
        if (abs(signedDelta) <= 1e-9) return
        val currentSigned = currentWeights[symbol] ?: 0.0
        val newSigned = currentSigned + signedDelta
        if (currentSigned != 0.0 && sign(currentSigned) != sign(newSigned) && abs(newSigned) > 1e-9) {
            realizeTrade(
                activePositions = activePositions,
                currentWeights = currentWeights,
                trades = trades,
                symbol = symbol,
                signedDelta = -currentSigned,
                price = price,
                time = time,
                reason = "$reason-flatten",
                splitTime = splitTime
            )
            if (abs(newSigned) > 1e-9) {
                openOrIncreasePosition(activePositions, currentWeights, symbol, newSigned, price, volatility, confidence, time)
            }
            return
        }
        if (currentSigned == 0.0 || sign(currentSigned) == sign(signedDelta)) {
            openOrIncreasePosition(activePositions, currentWeights, symbol, signedDelta, price, volatility, confidence, time)
        } else {
            realizeTrade(
                activePositions = activePositions,
                currentWeights = currentWeights,
                trades = trades,
                symbol = symbol,
                signedDelta = signedDelta,
                price = price,
                time = time,
                reason = reason,
                splitTime = splitTime
            )
        }
    }

    private fun openOrIncreasePosition(
        activePositions: MutableMap<String, ActivePosition>,
        currentWeights: MutableMap<String, Double>,
        symbol: String,
        signedDelta: Double,
        price: Double,
        volatility: Double,
        confidence: Double,
        time: Instant
    ) {
        val direction = if (signedDelta >= 0.0) AlphaDirection.LONG else AlphaDirection.SHORT
        val existing = activePositions[symbol]
        if (existing == null) {
            activePositions[symbol] = ActivePosition(
                symbol = symbol,
                direction = direction,
                weightFraction = abs(signedDelta),
                averageEntryPrice = price,
                entryVolatility = volatility.coerceAtLeast(0.001),
                peakPrice = price,
                troughPrice = price,
                confidence = confidence,
                entryTime = time
            )
        } else {
            val newWeight = existing.weightFraction + abs(signedDelta)
            existing.averageEntryPrice = (
                existing.averageEntryPrice * existing.weightFraction +
                    price * abs(signedDelta)
                ) / newWeight.coerceAtLeast(1e-9)
            existing.weightFraction = newWeight
            existing.entryVolatility = ((existing.entryVolatility + volatility) / 2.0).coerceAtLeast(0.001)
            existing.confidence = max(existing.confidence, confidence)
        }
        currentWeights[symbol] = (currentWeights[symbol] ?: 0.0) + signedDelta
    }

    private fun realizeTrade(
        activePositions: MutableMap<String, ActivePosition>,
        currentWeights: MutableMap<String, Double>,
        trades: MutableList<InterdayTradeRecord>,
        symbol: String,
        signedDelta: Double,
        price: Double,
        time: Instant,
        reason: String,
        splitTime: Instant?
    ) {
        val active = activePositions[symbol] ?: return
        val currentSigned = currentWeights[symbol] ?: return
        val closeFraction = min(abs(signedDelta), abs(currentSigned))
        if (closeFraction <= 1e-9) return
        val pnlPct = when (active.direction) {
            AlphaDirection.LONG -> ((price / active.averageEntryPrice) - 1.0) * 100.0
            AlphaDirection.SHORT -> ((active.averageEntryPrice / price) - 1.0) * 100.0
        }
        val maxFavorablePnlPct = active.maxFavorablePnlPct().coerceAtLeast(0.0)
        val givebackPct = if (maxFavorablePnlPct <= 1e-9) {
            0.0
        } else {
            ((maxFavorablePnlPct - pnlPct).coerceAtLeast(0.0) / maxFavorablePnlPct) * 100.0
        }
        val newSigned = currentSigned + signedDelta
        currentWeights[symbol] = if (abs(newSigned) <= 1e-9) 0.0 else newSigned
        active.weightFraction = (active.weightFraction - closeFraction).coerceAtLeast(0.0)
        trades += InterdayTradeRecord(
            symbol = symbol,
            direction = active.direction,
            entryTime = active.entryTime,
            exitTime = time,
            entryPrice = active.averageEntryPrice,
            exitPrice = price,
            weightFraction = closeFraction,
            pnlPct = pnlPct,
            maxFavorablePnlPct = maxFavorablePnlPct,
            profitGivebackPct = givebackPct,
            reason = reason,
            segment = if (splitTime != null && time > splitTime) "forward" else "backtest"
        )
        if (active.weightFraction <= 1e-9 || abs(newSigned) <= 1e-9) {
            activePositions.remove(symbol)
            currentWeights.remove(symbol)
        }
    }

    private fun portfolioSnapshot(
        time: Instant,
        equity: Double,
        peakEquity: Double,
        currentWeights: Map<String, Double>,
        turnoverFraction: Double,
        regime: RegimeState,
        searchPolicy: org.datamancy.trading.policy.AlphaSearchPolicy,
        config: InterdayAlphaConfig
    ): InterdayPortfolioSnapshot {
        val gross = currentWeights.values.sumOf { abs(it) }
        val longExposure = currentWeights.values.filter { it > 0.0 }.sum()
        val shortExposure = currentWeights.values.filter { it < 0.0 }.sumOf { abs(it) }
        val net = currentWeights.values.sum()
        val regimeWeight = regimeParticipationWeight(abs(regime.score), config.regimeStrengthThreshold)
        val signedNetAligned = if (regime.score >= 0.0) net else -net
        val alignedExposure = signedNetAligned.coerceAtLeast(0.0) * regimeWeight
        val wrongWayExposure = (-signedNetAligned).coerceAtLeast(0.0) * regimeWeight
        val drawdownPct = if (peakEquity <= 1e-9) 0.0 else ((peakEquity - equity).coerceAtLeast(0.0) / peakEquity) * 100.0
        val drawdownUtilization = if (searchPolicy.maxSearchDrawdownPct <= 0.0) 0.0 else drawdownPct / searchPolicy.maxSearchDrawdownPct
        val weightedGross = gross * regimeWeight
        val wrongWayPct = if (weightedGross <= 1e-9) 0.0 else wrongWayExposure / weightedGross * 100.0
        val wrongWayUtilization = if (searchPolicy.maxWrongWayExposurePct <= 0.0) 0.0 else wrongWayPct / searchPolicy.maxWrongWayExposurePct
        val killSwitchUtilization = max(drawdownUtilization, wrongWayUtilization).coerceAtLeast(0.0)
        return InterdayPortfolioSnapshot(
            time = time,
            equity = equity,
            grossExposureFraction = gross,
            longExposureFraction = longExposure,
            shortExposureFraction = shortExposure,
            netExposureFraction = net,
            openPositions = currentWeights.count { abs(it.value) > 1e-9 },
            turnoverFraction = turnoverFraction,
            regimeScore = regime.score,
            regimeStrength = abs(regime.score),
            alignedExposureFraction = alignedExposure,
            wrongWayExposureFraction = wrongWayExposure,
            killSwitchUtilization = killSwitchUtilization
        )
    }

    private fun regimeSnapshot(
        time: Instant,
        regime: RegimeState,
        snapshot: InterdayPortfolioSnapshot
    ): InterdayRegimeSnapshot = InterdayRegimeSnapshot(
        time = time,
        regimeScore = regime.score,
        breadth = regime.breadth,
        anchorTrend = regime.anchorTrend,
        dispersion = regime.dispersion,
        realizedVolatility = regime.realizedVolatility,
        liquidityScore = regime.liquidityScore,
        fundingPressure = regime.fundingPressure,
        openInterestPressure = regime.openInterestPressure,
        marketTrendScore = regime.marketTrendScore,
        grossExposureFraction = snapshot.grossExposureFraction,
        longExposureFraction = snapshot.longExposureFraction,
        shortExposureFraction = snapshot.shortExposureFraction,
        netExposureFraction = snapshot.netExposureFraction,
        alignedExposureFraction = snapshot.alignedExposureFraction,
        wrongWayExposureFraction = snapshot.wrongWayExposureFraction,
        killSwitchUtilization = snapshot.killSwitchUtilization
    )

    private fun buildRegimeSlicePerformance(
        segmentSnapshots: List<InterdayPortfolioSnapshot>,
        segmentRegimes: List<InterdayRegimeSnapshot>,
        segmentTrades: List<InterdayTradeRecord>,
        signalBarMinutes: Int,
        configuredRegimeSlices: List<String>
    ): List<InterdayRegimeSlicePerformance> {
        if (segmentSnapshots.size < 2 || segmentRegimes.isEmpty()) return emptyList()
        val regimeByTime = segmentRegimes.associateBy { it.time }
        val observations = segmentSnapshots.zipWithNext().mapNotNull { (left, right) ->
            val regime = regimeByTime[right.time] ?: return@mapNotNull null
            if (left.equity <= 0.0 || right.equity <= 0.0) return@mapNotNull null
            RegimeSliceObservation(
                time = right.time,
                logReturn = ln(right.equity / left.equity),
                turnoverFraction = right.turnoverFraction,
                grossExposureFraction = right.grossExposureFraction,
                regime = regime
            )
        }
        if (observations.isEmpty()) return emptyList()
        val tradesByTime = segmentTrades.groupBy { it.exitTime }
        return configuredRegimeSlices.flatMap { slice ->
            when (slice.trim().lowercase()) {
                "volatility" -> quantileRegimeSlices(slice, observations, tradesByTime, signalBarMinutes) { it.regime.realizedVolatility }
                "liquidity" -> quantileRegimeSlices(slice, observations, tradesByTime, signalBarMinutes) { it.regime.liquidityScore }
                "funding" -> quantileRegimeSlices(slice, observations, tradesByTime, signalBarMinutes) { abs(it.regime.fundingPressure) }
                "open_interest" -> quantileRegimeSlices(slice, observations, tradesByTime, signalBarMinutes) { abs(it.regime.openInterestPressure) }
                "market_trend" -> directionalRegimeSlices(slice, observations, tradesByTime, signalBarMinutes)
                else -> emptyList()
            }
        }
    }

    private fun quantileRegimeSlices(
        slice: String,
        observations: List<RegimeSliceObservation>,
        tradesByTime: Map<Instant, List<InterdayTradeRecord>>,
        signalBarMinutes: Int,
        selector: (RegimeSliceObservation) -> Double
    ): List<InterdayRegimeSlicePerformance> {
        if (observations.isEmpty()) return emptyList()
        val sortedValues = observations.map(selector).sorted()
        val lowCut = quantile(sortedValues, 0.33)
        val highCut = quantile(sortedValues, 0.67)
        return listOf(
            "low" to observations.filter { selector(it) <= lowCut },
            "mid" to observations.filter { selector(it) > lowCut && selector(it) < highCut },
            "high" to observations.filter { selector(it) >= highCut }
        ).mapNotNull { (bucket, bucketObservations) ->
            summarizeRegimeSlice(slice, bucket, bucketObservations, tradesByTime, signalBarMinutes)
        }
    }

    private fun directionalRegimeSlices(
        slice: String,
        observations: List<RegimeSliceObservation>,
        tradesByTime: Map<Instant, List<InterdayTradeRecord>>,
        signalBarMinutes: Int
    ): List<InterdayRegimeSlicePerformance> = listOf(
        "down" to observations.filter { it.regime.marketTrendScore <= -0.15 },
        "flat" to observations.filter { it.regime.marketTrendScore > -0.15 && it.regime.marketTrendScore < 0.15 },
        "up" to observations.filter { it.regime.marketTrendScore >= 0.15 }
    ).mapNotNull { (bucket, bucketObservations) ->
        summarizeRegimeSlice(slice, bucket, bucketObservations, tradesByTime, signalBarMinutes)
    }

    private fun summarizeRegimeSlice(
        slice: String,
        bucket: String,
        observations: List<RegimeSliceObservation>,
        tradesByTime: Map<Instant, List<InterdayTradeRecord>>,
        signalBarMinutes: Int
    ): InterdayRegimeSlicePerformance? {
        if (observations.isEmpty()) return null
        val tradeCount = observations.sumOf { tradesByTime[it.time]?.size ?: 0 }
        val returns = observations.map { it.logReturn }
        val netReturnPct = (exp(returns.sum()) - 1.0) * 100.0
        return InterdayRegimeSlicePerformance(
            slice = slice,
            bucket = bucket,
            sampleCount = observations.size,
            tradeCount = tradeCount,
            netReturnPct = netReturnPct,
            edgeAfterCostBps = if (tradeCount == 0) 0.0 else (netReturnPct * 100.0) / tradeCount.toDouble(),
            sharpe = annualizedSharpe(returns, signalBarMinutes),
            avgTurnoverPct = observations.map { it.turnoverFraction }.average() * 100.0,
            avgGrossExposureFraction = observations.map { it.grossExposureFraction }.average(),
            positiveReturnRate = observations.count { it.logReturn > 0.0 }.toDouble() / observations.size.toDouble()
        )
    }

    private fun requiredHistoryHours(config: InterdayAlphaConfig): Int {
        val indicatorHours = max(
            config.lookbackHours + config.forwardHours,
            max(max(config.slowTrendDays, config.regressionDays), max(config.volatilityDays, config.adxDays)) * 24
        )
        val signalBarHours = max(1, config.signalBarMinutes / 60)
        val historyBufferHours = max(config.rebalanceCadenceHours * 3, signalBarHours * 10)
        return indicatorHours + historyBufferHours
    }

    private fun emptyPerformance(
        segment: String,
        startTime: Instant? = null,
        endTime: Instant? = null,
        tradeCount: Int = 0,
        winRate: Double = 0.0
    ): InterdayPerformance = InterdayPerformance(
        segment = segment,
        startTime = startTime,
        endTime = endTime,
        netReturnPct = 0.0,
        annualizedReturnPct = 0.0,
        grossReturnPct = 0.0,
        sharpe = 0.0,
        maxDrawdownPct = 0.0,
        tradeCount = tradeCount,
        winRate = winRate,
        avgTurnoverPct = 0.0,
        edgeAfterCostBps = 0.0,
        bootstrapReturnP05Pct = 0.0,
        bootstrapSharpeP05 = 0.0,
        stabilityScore = 0.0,
        calmar = 0.0,
        ulcerIndex = 0.0,
        timeUnderWaterPct = 0.0,
        cvar1dPct = 0.0,
        alignedParticipationRate = 0.0,
        wrongWayExposurePct = 0.0,
        profitGivebackPct = 0.0,
        pnlSkew = 0.0,
        avgWinnerLoserRatio = 0.0,
        killSwitchUtilizationMax = 0.0,
        regimeSlices = emptyList()
    )

    private data class PanelCacheKey(
        val exchange: String,
        val signalBarMinutes: Int,
        val requiredHistoryHours: Int,
        val maxSymbols: Int
    )

    private data class RegimeSliceObservation(
        val time: Instant,
        val logReturn: Double,
        val turnoverFraction: Double,
        val grossExposureFraction: Double,
        val regime: InterdayRegimeSnapshot
    )

    data class IndicatorWindows(
        val fastBars: Int,
        val mediumBars: Int,
        val slowBars: Int,
        val regressionBars: Int,
        val volatilityBars: Int,
        val adxBars: Int,
        val requiredBars: Int
    ) {
        companion object {
            fun fromConfig(config: InterdayAlphaConfig): IndicatorWindows {
                val fastBars = barsForDays(config.fastTrendDays, config.signalBarMinutes)
                val mediumBars = barsForDays(config.mediumTrendDays, config.signalBarMinutes)
                val slowBars = barsForDays(config.slowTrendDays, config.signalBarMinutes)
                val regressionBars = barsForDays(config.regressionDays, config.signalBarMinutes)
                val volatilityBars = barsForDays(config.volatilityDays, config.signalBarMinutes)
                val adxBars = barsForDays(config.adxDays, config.signalBarMinutes)
                return IndicatorWindows(
                    fastBars = fastBars,
                    mediumBars = mediumBars,
                    slowBars = slowBars,
                    regressionBars = regressionBars,
                    volatilityBars = volatilityBars,
                    adxBars = adxBars,
                    requiredBars = max(slowBars, max(regressionBars, max(volatilityBars, adxBars))) + config.perturbationLookbackBars + 2
                )
            }
        }
    }

    data class RawSignal(
        val symbol: String,
        val close: Double,
        val volatility: Double,
        val fastReturn: Double,
        val mediumReturn: Double,
        val slowReturn: Double,
        val slope: Double,
        val maSpread: Double,
        val adx: Double,
        val perturbation: Double,
        val fundingRate: Double,
        val openInterestMomentum: Double,
        val spreadBps: Double,
        val depthUsd: Double,
        val tradeObservedRatio: Double,
        val orderbookObservedRatio: Double,
        val assetContextObservedRatio: Double,
        val liquidityRank: Double
    )

    private data class SignalTotals(
        val trend: Double,
        val trendAgreement: Double,
        val pullbackSupport: Double,
        val pullback: Double,
        val funding: Double,
        val openInterest: Double,
        val liquidity: Double,
        val total: Double
    )

    private data class FeatureVector(
        val trend: Double,
        val trendAgreement: Double,
        val pullback: Double,
        val fundingCarry: Double,
        val fundingAlignment: Double,
        val openInterest: Double,
        val expansion: Double,
        val reversalRisk: Double,
        val liquidity: Double
    ) {
        fun toArray(config: InterdayAlphaConfig): DoubleArray = doubleArrayOf(
            trend,
            trendAgreement,
            pullback * config.pullbackWeight.coerceAtLeast(0.05),
            linearFundingContribution(config),
            openInterest * config.openInterestWeight.coerceAtLeast(0.05),
            expansion,
            reversalRisk,
            liquidity
        )

        private fun linearFundingContribution(config: InterdayAlphaConfig): Double =
            if (config.fundingOverlayMode == InterdayFundingOverlayMode.LINEAR_FACTOR) {
                fundingCarry * config.fundingWeight.coerceAtLeast(0.05)
            } else {
                0.0
            }

        fun overlaySizingMultiplier(config: InterdayAlphaConfig): Double {
            val strength = config.fundingWeight.coerceIn(0.0, 1.0)
            if (strength <= 1e-9) return 1.0
            return when (config.fundingOverlayMode) {
                InterdayFundingOverlayMode.NONE,
                InterdayFundingOverlayMode.LINEAR_FACTOR -> 1.0
                InterdayFundingOverlayMode.BOUNDED_REINFORCEMENT ->
                    (1.0 + strength * fundingAlignment).coerceIn(0.70, 1.30)
                InterdayFundingOverlayMode.CROWDING_GUARD ->
                    (1.0 - strength * fundingAlignment.coerceAtLeast(0.0)).coerceIn(0.55, 1.0)
            }
        }
    }

    private data class EmpiricalWeights(
        val intercept: Double,
        val coefficients: DoubleArray,
        val observations: Int
    ) {
        fun score(features: FeatureVector, config: InterdayAlphaConfig): Double {
            val vector = features.toArray(config)
            var total = intercept
            for (index in coefficients.indices) {
                total += coefficients[index] * vector[index]
            }
            return total
        }
    }

    private data class FeatureObservation(
        val features: FeatureVector,
        val targetResidualReturn: Double
    )

    private data class SignalEdgeEstimate(
        val empiricalScore: Double,
        val residualRank: Double,
        val expectedResidualReturnBps: Double,
        val expectedEntryCostBps: Double,
        val expectedTurnoverPenaltyBps: Double,
        val expectedNetEdgeBps: Double,
        val executionSupport: Double,
        val provisionalConfidence: Double,
        val fundingOverlayMultiplier: Double
    )

    data class StructuralState(
        val enabled: Boolean,
        val residualizedRaw: List<RawSignal>,
        val marketBetaBySymbol: Map<String, Double>,
        val factorLookbackBars: Int,
        val marketProxyWeightsBySymbol: Map<String, Double>,
        val residualReturnWindowBySymbol: Map<String, List<Double>>
    ) {
        companion object {
            fun disabled(raw: List<RawSignal>): StructuralState = StructuralState(
                enabled = false,
                residualizedRaw = raw,
                marketBetaBySymbol = raw.associate { it.symbol to 0.0 },
                factorLookbackBars = 0,
                marketProxyWeightsBySymbol = raw.associate { it.symbol to 1.0 },
                residualReturnWindowBySymbol = raw.associate { it.symbol to emptyList<Double>() }
            )
        }
    }

    private data class SignalComputationResult(
        val signals: List<InterdaySignalSnapshot>,
        val structuralState: StructuralState
    )

    data class DerivedTrendSignal(
        val volatility: Double,
        val fastReturn: Double,
        val mediumReturn: Double,
        val slowReturn: Double,
        val slope: Double,
        val maSpread: Double,
        val adx: Double,
        val perturbation: Double
    )

    private data class RegimeState(
        val score: Double,
        val breadth: Double,
        val anchorTrend: Double,
        val dispersion: Double,
        val realizedVolatility: Double,
        val liquidityScore: Double,
        val fundingPressure: Double,
        val openInterestPressure: Double,
        val marketTrendScore: Double
    )

    private data class CompressionDiagnosticRawPoint(
        val pc1Share: Double,
        val coMomentum: Double
    )

    private data class CompressionPenaltyState(
        val mode: InterdayCompressionPenaltyMode,
        val zScore: Double,
        val scale: Double,
        val windowBars: Int,
        val sleeveSizePerSide: Int
    ) {
        companion object {
            fun disabled(): CompressionPenaltyState = CompressionPenaltyState(
                mode = InterdayCompressionPenaltyMode.NONE,
                zScore = 0.0,
                scale = 1.0,
                windowBars = 0,
                sleeveSizePerSide = 0
            )
        }
    }

    private data class CompressionMetricState(
        val available: Boolean,
        val pc1Share: Double,
        val zScore: Double,
        val windowBars: Int,
        val sleeveSizePerSide: Int
    ) {
        companion object {
            fun disabled(): CompressionMetricState = CompressionMetricState(
                available = false,
                pc1Share = 0.0,
                zScore = 0.0,
                windowBars = 0,
                sleeveSizePerSide = 0
            )
        }
    }

    private data class FlatHazardState(
        val mode: InterdayFlatHazardMode,
        val intensity: Double,
        val grossScale: Double,
        val trendComponent: Double,
        val compressionConfirm: Double,
        val compressionZScore: Double,
        val windowBars: Int,
        val sleeveSizePerSide: Int
    ) {
        companion object {
            fun disabled(): FlatHazardState = FlatHazardState(
                mode = InterdayFlatHazardMode.NONE,
                intensity = 0.0,
                grossScale = 1.0,
                trendComponent = 0.0,
                compressionConfirm = 1.0,
                compressionZScore = 0.0,
                windowBars = 0,
                sleeveSizePerSide = 0
            )
        }
    }

    private data class PositionAdjustment(
        val symbol: String,
        val deltaWeight: Double,
        val reason: String
    )

    private data class EvaluationBundle(
        val config: InterdayAlphaConfig,
        val backtest: InterdayPerformance,
        val forward: InterdayPerformance,
        val validation: InterdayValidation,
        val latestSignals: List<InterdaySignalSnapshot>,
        val latestTargets: List<AlphaPortfolioTarget>,
        val trades: List<InterdayTradeRecord>,
        val inspection: InterdayInspection?,
        val grossLogReturn: Double
    )

    private data class ActivePosition(
        val symbol: String,
        val direction: AlphaDirection,
        var weightFraction: Double,
        var averageEntryPrice: Double,
        var entryVolatility: Double,
        var peakPrice: Double,
        var troughPrice: Double,
        var confidence: Double,
        val entryTime: Instant,
        val stopTriggered: MutableSet<Int> = mutableSetOf(),
        val takeProfitTriggered: MutableSet<Int> = mutableSetOf()
    ) {
        fun refresh(bar: InterdayBar) {
            peakPrice = max(peakPrice, bar.high)
            troughPrice = min(troughPrice, bar.low)
        }

        fun maxFavorablePnlPct(): Double = when (direction) {
            AlphaDirection.LONG -> ((peakPrice / averageEntryPrice) - 1.0) * 100.0
            AlphaDirection.SHORT -> ((averageEntryPrice / troughPrice.coerceAtLeast(1e-9)) - 1.0) * 100.0
        }

        fun overlayAdjustments(config: InterdayAlphaConfig, bar: InterdayBar, time: Instant): List<PositionAdjustment> {
            val adjustments = mutableListOf<PositionAdjustment>()
            when (config.exitOverlayMode) {
                InterdayExitOverlayMode.NONE -> return emptyList()
                InterdayExitOverlayMode.TRAILING_ONLY,
                InterdayExitOverlayMode.TRAILING_AND_TAKE_PROFIT -> {
                    config.layeredStopFractions.forEachIndexed { index, fraction ->
                        if (index in stopTriggered) return@forEachIndexed
                        val trigger = config.trailingStopVolMultiple * config.layeredStopMultipliers.getOrElse(index) { 1.0 }
                        val drawdownVol = when (direction) {
                            AlphaDirection.LONG -> ((peakPrice - bar.close) / peakPrice.coerceAtLeast(1e-9)) / entryVolatility
                            AlphaDirection.SHORT -> ((bar.close - troughPrice) / troughPrice.coerceAtLeast(1e-9)) / entryVolatility
                        }
                        if (drawdownVol.coerceAtLeast(0.0) >= trigger) {
                            stopTriggered += index
                            adjustments += PositionAdjustment(symbol, weightFraction * fraction, "trailing-stop-$index@$time")
                        }
                    }
                }
                InterdayExitOverlayMode.TIME_STOP -> {
                    val elapsedBars = Duration.between(entryTime, time).toMinutes().toDouble() / config.signalBarMinutes.toDouble().coerceAtLeast(1.0)
                    val favorableMoveVol = maxFavorableMoveVol()
                    if (elapsedBars >= config.timeStopBars.coerceAtLeast(1) && favorableMoveVol < config.timeStopMinProgressVol.coerceAtLeast(0.0)) {
                        adjustments += PositionAdjustment(symbol, weightFraction, "time-stop@$time")
                    }
                }
                InterdayExitOverlayMode.TREND_BREAK -> Unit
            }
            if (config.exitOverlayMode == InterdayExitOverlayMode.TRAILING_AND_TAKE_PROFIT) {
                config.takeProfitFractions.forEachIndexed { index, fraction ->
                    if (index in takeProfitTriggered) return@forEachIndexed
                    val trigger = config.takeProfitVolMultiple * config.takeProfitMultipliers.getOrElse(index) { 1.0 }
                    val favorableMoveVol = maxFavorableMoveVol()
                    if (favorableMoveVol >= trigger) {
                        takeProfitTriggered += index
                        adjustments += PositionAdjustment(symbol, weightFraction * fraction, "take-profit-$index@$time")
                    }
                }
            }
            return adjustments
        }

        fun maxFavorableMoveVol(): Double = when (direction) {
            AlphaDirection.LONG -> ((peakPrice / averageEntryPrice) - 1.0) / entryVolatility.coerceAtLeast(1e-6)
            AlphaDirection.SHORT -> ((averageEntryPrice / troughPrice.coerceAtLeast(1e-9)) - 1.0) / entryVolatility.coerceAtLeast(1e-6)
        }
    }
}

private fun regimeTargetNetFraction(
    regimeScore: Double,
    config: InterdayAlphaConfig,
    scaledGrossFraction: Double
): Double {
    val regimeWeight = regimeParticipationWeight(abs(regimeScore), config.regimeStrengthThreshold)
    val signedBias = regimeScore.coerceIn(-1.0, 1.0) * regimeWeight * config.regimeNetBiasScale.coerceIn(0.0, 1.0)
    return scaledGrossFraction * signedBias
}

private fun scaledTargetGrossFraction(
    defaults: AlphaPortfolioDefaults,
    config: InterdayAlphaConfig
): Double = defaults.targetGrossFraction * config.targetGrossFractionScale.coerceIn(0.05, 1.0)

private val COMPRESSION_DIAGNOSTIC_WINDOWS_DAYS = listOf(7, 10, 14)
private val COMPRESSION_DIAGNOSTIC_SLEEVE_SIZES = listOf(10, 12, 16)
private const val COMPRESSION_DIAGNOSTIC_HISTORY_DAYS = 180

private fun averagePairwiseCorrelation(series: List<List<Double>>): Double {
    if (series.size < 2) return 0.0
    val correlations = mutableListOf<Double>()
    for (leftIndex in 0 until series.lastIndex) {
        for (rightIndex in (leftIndex + 1) until series.size) {
            correlations += correlation(series[leftIndex], series[rightIndex])
        }
    }
    return correlations.averageOrZero()
}

private fun correlation(left: List<Double>, right: List<Double>): Double {
    val size = min(left.size, right.size)
    if (size < 4) return 0.0
    val leftWindow = left.takeLast(size)
    val rightWindow = right.takeLast(size)
    val leftMean = leftWindow.averageOrZero()
    val rightMean = rightWindow.averageOrZero()
    var covariance = 0.0
    var leftVariance = 0.0
    var rightVariance = 0.0
    for (offset in 0 until size) {
        val leftCentered = leftWindow[offset] - leftMean
        val rightCentered = rightWindow[offset] - rightMean
        covariance += leftCentered * rightCentered
        leftVariance += leftCentered * leftCentered
        rightVariance += rightCentered * rightCentered
    }
    val denominator = sqrt(leftVariance * rightVariance)
    if (denominator <= 1e-12) return 0.0
    return (covariance / denominator).coerceIn(-1.0, 1.0)
}

private fun pc1Share(series: List<List<Double>>): Double {
    if (series.size < 2) return 0.0
    val size = series.minOfOrNull { it.size } ?: return 0.0
    if (size < 4) return 0.0
    val centered = series.map { values ->
        val window = values.takeLast(size)
        val mean = window.averageOrZero()
        DoubleArray(size) { offset -> window[offset] - mean }
    }
    val covariance = Array(centered.size) { row ->
        DoubleArray(centered.size) { column ->
            centered[row].indices.sumOf { offset -> centered[row][offset] * centered[column][offset] } / size.toDouble()
        }
    }
    val trace = covariance.indices.sumOf { covariance[it][it] }.coerceAtLeast(1e-12)
    val largestEigenvalue = largestEigenvalue(covariance)
    return (largestEigenvalue / trace).coerceIn(0.0, 1.0)
}

private fun largestEigenvalue(matrix: Array<DoubleArray>): Double {
    if (matrix.isEmpty()) return 0.0
    var vector = DoubleArray(matrix.size) { 1.0 / matrix.size.toDouble().coerceAtLeast(1.0) }
    repeat(32) {
        val next = DoubleArray(matrix.size) { row ->
            matrix[row].indices.sumOf { column -> matrix[row][column] * vector[column] }
        }
        val norm = sqrt(next.sumOf { it * it }).coerceAtLeast(1e-12)
        vector = DoubleArray(matrix.size) { index -> next[index] / norm }
    }
    return vector.indices.sumOf { row ->
        vector[row] * matrix[row].indices.sumOf { column -> matrix[row][column] * vector[column] }
    }.coerceAtLeast(0.0)
}

private fun robustZScore(history: List<Double>, value: Double): Double {
    if (history.size < 8) return 0.0
    val center = median(history)
    val scale = medianAbsoluteDeviation(history, center).coerceAtLeast(1e-6)
    return ((value - center) / scale).coerceIn(-10.0, 10.0)
}

private fun compressionPenaltyScale(zScore: Double, config: InterdayAlphaConfig): Double {
    if (config.compressionPenaltyMode == InterdayCompressionPenaltyMode.NONE) return 1.0
    val strength = config.compressionPenaltyStrength.coerceIn(0.0, 1.0)
    if (strength <= 1e-9) return 1.0
    val threshold = config.compressionThresholdZ
    val logistic = 1.0 / (1.0 + exp(-(zScore - threshold)))
    return (1.0 - strength * logistic).coerceIn(0.05, 1.0)
}

private fun flatTrendHazardComponent(
    marketTrendScore: Double,
    config: InterdayAlphaConfig
): Double {
    if (config.flatHazardMode == InterdayFlatHazardMode.NONE) return 0.0
    val threshold = config.flatRegimeMarketTrendThreshold.coerceIn(0.0, 1.0)
    if (threshold <= 1e-9) return 0.0
    return (1.0 - (abs(marketTrendScore) / threshold)).coerceIn(0.0, 1.0)
}

private fun flatHazardCompressionConfirm(
    zScore: Double,
    config: InterdayAlphaConfig
): Double {
    if (config.flatHazardMode != InterdayFlatHazardMode.MARKET_TREND_AND_PC1_SHARE) return 1.0
    return 1.0 / (1.0 + exp(-(zScore - config.flatHazardCompressionThresholdZ)))
}

private fun flatHazardGrossScale(
    intensity: Double,
    config: InterdayAlphaConfig
): Double {
    if (config.flatHazardMode == InterdayFlatHazardMode.NONE) return 1.0
    val floor = config.flatHazardGrossScaleFloor.coerceIn(0.05, 1.0)
    return (1.0 - intensity.coerceIn(0.0, 1.0) * (1.0 - floor)).coerceIn(floor, 1.0)
}

private fun flatRegimeGateActive(
    marketTrendScore: Double,
    breadth: Double,
    config: InterdayAlphaConfig
): Boolean {
    if (config.flatRegimeGateMode == InterdayFlatRegimeGateMode.NONE) return false
    return isFlatRegimeState(marketTrendScore, breadth, config)
}

private fun flatRegimeGrossScale(
    marketTrendScore: Double,
    breadth: Double,
    config: InterdayAlphaConfig
): Double {
    if (!flatRegimeGateActive(marketTrendScore, breadth, config)) return 1.0
    return when (config.flatRegimeGateMode) {
        InterdayFlatRegimeGateMode.GROSS_THROTTLE,
        InterdayFlatRegimeGateMode.COMBINED -> config.flatRegimeGrossScale.coerceIn(0.05, 1.0)
        else -> 1.0
    }
}

private fun flatRegimeEntryEdgeBoostBps(
    marketTrendScore: Double,
    breadth: Double,
    config: InterdayAlphaConfig
): Double {
    if (!flatRegimeGateActive(marketTrendScore, breadth, config)) return 0.0
    return when (config.flatRegimeGateMode) {
        InterdayFlatRegimeGateMode.ENTRY_EDGE_BOOST,
        InterdayFlatRegimeGateMode.COMBINED -> config.flatRegimeEntryEdgeFloorBoostBps.coerceAtLeast(0.0)
        else -> 0.0
    }
}

private fun isFlatRegimeState(
    marketTrendScore: Double,
    breadth: Double,
    config: InterdayAlphaConfig
): Boolean {
    val trendThreshold = config.flatRegimeMarketTrendThreshold.coerceIn(0.0, 1.0)
    val breadthThreshold = config.flatRegimeBreadthThreshold.coerceIn(0.0, 1.0)
    return abs(marketTrendScore) <= trendThreshold && abs(breadth) <= breadthThreshold
}

private fun flatRegimeEntryControlActive(
    marketTrendScore: Double,
    breadth: Double,
    config: InterdayAlphaConfig
): Boolean {
    if (config.flatRegimeEntryControlMode == InterdayFlatRegimeEntryControlMode.NONE) return false
    return isFlatRegimeState(marketTrendScore, breadth, config)
}

private fun flatRegimeDispersionAllowed(
    marketTrendScore: Double,
    breadth: Double,
    dispersion: Double,
    incumbentSameSide: Boolean,
    config: InterdayAlphaConfig
): Boolean {
    if (incumbentSameSide || !flatRegimeEntryControlActive(marketTrendScore, breadth, config)) return true
    return when (config.flatRegimeEntryControlMode) {
        InterdayFlatRegimeEntryControlMode.DISPERSION_GUARD,
        InterdayFlatRegimeEntryControlMode.COMBINED ->
            dispersion >= config.flatRegimeMinDispersion.coerceIn(0.0, 1.0)
        else -> true
    }
}

private fun flatRegimeTrendAgreementFloor(
    marketTrendScore: Double,
    breadth: Double,
    incumbentSameSide: Boolean,
    config: InterdayAlphaConfig
): Double {
    if (incumbentSameSide || !flatRegimeEntryControlActive(marketTrendScore, breadth, config)) {
        return config.minTrendAgreement
    }
    return when (config.flatRegimeEntryControlMode) {
        InterdayFlatRegimeEntryControlMode.CONFIRMATION_BOOST,
        InterdayFlatRegimeEntryControlMode.COMBINED ->
            (config.minTrendAgreement + config.flatRegimeTrendAgreementBoost).coerceIn(0.0, 1.0)
        else -> config.minTrendAgreement
    }
}

private fun isDirectionAllowedByRegime(
    direction: AlphaDirection,
    regimeScore: Double,
    config: InterdayAlphaConfig
): Boolean {
    val threshold = config.regimeDirectionalSuppressionThreshold.coerceIn(0.0, 1.0)
    if (abs(regimeScore) < threshold) return true
    return when {
        regimeScore > 0.0 -> direction != AlphaDirection.SHORT
        regimeScore < 0.0 -> direction != AlphaDirection.LONG
        else -> true
    }
}

private fun signedWeight(direction: AlphaDirection, weightFraction: Double): Double =
    if (direction == AlphaDirection.LONG) weightFraction else -weightFraction

private fun holdsSignalDirection(currentSigned: Double, direction: AlphaDirection): Boolean =
    when (direction) {
        AlphaDirection.LONG -> currentSigned > 1e-9
        AlphaDirection.SHORT -> currentSigned < -1e-9
    }

private fun directionalEdgeBps(signal: InterdaySignalSnapshot): Double =
    when (signal.direction) {
        AlphaDirection.LONG -> signal.expectedNetEdgeBps
        AlphaDirection.SHORT -> -signal.expectedNetEdgeBps
    }

private fun satisfiesEntryStyle(
    signal: InterdaySignalSnapshot,
    config: InterdayAlphaConfig
): Boolean {
    val pullbackEntry = signal.pullbackScore >= 1.0
    val continuationEntry =
        signal.expansionScore >= 0.55 &&
            signal.trendAgreement >= max(config.minTrendAgreement, 0.25)
    return pullbackEntry || continuationEntry
}

private fun withinDirectionalTail(
    signal: InterdaySignalSnapshot,
    plateauToleranceBps: Double
): Boolean {
    val directionalBound = when (signal.direction) {
        AlphaDirection.LONG -> signal.upperBound
        AlphaDirection.SHORT -> -signal.lowerBound
    }
    return directionalEdgeBps(signal) + plateauToleranceBps.coerceAtLeast(0.0) >= directionalBound
}

private fun shouldForceFlattenByRegime(
    currentSigned: Double,
    regimeScore: Double,
    config: InterdayAlphaConfig
): Boolean {
    if (abs(currentSigned) <= 1e-9) return false
    val threshold = config.regimeDirectionalSuppressionThreshold.coerceIn(0.0, 1.0)
    if (abs(regimeScore) < threshold) return false
    return (regimeScore > 0.0 && currentSigned < 0.0) || (regimeScore < 0.0 && currentSigned > 0.0)
}

private fun shouldForceFlattenByExitOverlay(
    currentSigned: Double,
    positionEntryTime: Instant?,
    maxFavorableMoveVol: Double?,
    signal: InterdaySignalSnapshot?,
    config: InterdayAlphaConfig,
    time: Instant
): Boolean {
    if (abs(currentSigned) <= 1e-9 || positionEntryTime == null) return false
    return when (config.exitOverlayMode) {
        InterdayExitOverlayMode.TREND_BREAK -> {
            signal == null ||
                !holdsSignalDirection(currentSigned, signal.direction) ||
                directionalEdgeBps(signal) < config.holdEdgeFloorBps ||
                signal.trendAgreement < config.minTrendAgreement
        }
        InterdayExitOverlayMode.TIME_STOP -> {
            val elapsedBars = Duration.between(positionEntryTime, time).toMinutes().toDouble() / config.signalBarMinutes.toDouble().coerceAtLeast(1.0)
            elapsedBars >= config.timeStopBars.coerceAtLeast(1) &&
                (maxFavorableMoveVol ?: 0.0) < config.timeStopMinProgressVol.coerceAtLeast(0.0)
        }
        else -> false
    }
}

private fun normalizedRegimeSlices(configuredRegimeSlices: List<String>): List<String> =
    (configuredRegimeSlices + "market_trend")
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()

private fun residualizationActive(config: InterdayAlphaConfig): Boolean =
    config.residualizationMode != InterdayResidualizationMode.NONE

private fun buildStructuralState(
    panel: InterdayPanel,
    index: Int,
    raw: List<InterdaySearchEngine.RawSignal>,
    config: InterdayAlphaConfig,
    indicators: InterdaySearchEngine.IndicatorWindows,
    enabled: Boolean
): InterdaySearchEngine.StructuralState {
    fun disabledState(): InterdaySearchEngine.StructuralState =
        InterdaySearchEngine.StructuralState.disabled(raw)

    if (!enabled || raw.size < 4) return disabledState()

    val lookbackReturns = structuralLookbackBars(index, config, indicators)
    if (lookbackReturns < minimumStructuralLookbackBars(indicators, config.perturbationLookbackBars)) return disabledState()

    val seriesBySymbol = panel.seriesBySymbol()
    val returnsBySymbol = raw.mapNotNull { candidate ->
        val returns = rollingReturnWindow(seriesBySymbol[candidate.symbol]?.bars, index, lookbackReturns) ?: return@mapNotNull null
        candidate.symbol to returns
    }.toMap()
    if (returnsBySymbol.size < 4) return disabledState()

    val marketProxyWeightsBySymbol = raw.associate { candidate -> candidate.symbol to liquidityProxyWeight(candidate) }
    val marketSeries = averageSeries(
        series = returnsBySymbol.map { (symbol, returns) -> symbol to returns },
        proxyMode = config.residualizationMarketProxyMode,
        liquidityWeightBySymbol = marketProxyWeightsBySymbol
    )
    if (marketSeries.size < 4) return disabledState()

    val marketBetaBySymbol = returnsBySymbol.mapValues { (_, returns) ->
        beta(
            left = returns,
            right = marketSeries,
            mode = config.residualizationBetaMode,
            halfLifeBars = barsForDays(config.residualizationHalfLifeDays, config.signalBarMinutes)
        ).coerceIn(-3.0, 3.0)
    }

    val derivedBySymbol = mutableMapOf<String, InterdaySearchEngine.DerivedTrendSignal>()
    raw.forEach { candidate ->
        val symbolReturns = returnsBySymbol[candidate.symbol] ?: return@forEach
        val marketBeta = marketBetaBySymbol[candidate.symbol] ?: 0.0
        val residualSeries = when (config.residualizationMode) {
            InterdayResidualizationMode.NONE -> symbolReturns
            InterdayResidualizationMode.MARKET -> subtractSeries(symbolReturns, marketSeries, marketBeta)
        }
        deriveTrendSignal(
            returns = residualSeries,
            indicators = indicators,
            perturbationLookbackBars = config.perturbationLookbackBars
        )?.let { derived -> derivedBySymbol[candidate.symbol] = derived }
    }

    val residualizedRaw = raw.map { candidate ->
        val derived = derivedBySymbol[candidate.symbol]
        if (derived == null) {
            candidate
        } else {
            candidate.copy(
                volatility = derived.volatility,
                fastReturn = derived.fastReturn,
                mediumReturn = derived.mediumReturn,
                slowReturn = derived.slowReturn,
                slope = derived.slope,
                maSpread = derived.maSpread,
                adx = derived.adx,
                perturbation = derived.perturbation
            )
        }
    }
    return InterdaySearchEngine.StructuralState(
        enabled = true,
        residualizedRaw = residualizedRaw,
        marketBetaBySymbol = raw.associate { candidate -> candidate.symbol to (marketBetaBySymbol[candidate.symbol] ?: 0.0) },
        factorLookbackBars = lookbackReturns,
        marketProxyWeightsBySymbol = marketProxyWeightsBySymbol,
        residualReturnWindowBySymbol = raw.associate { candidate ->
            candidate.symbol to when (config.residualizationMode) {
                InterdayResidualizationMode.NONE ->
                    returnsBySymbol[candidate.symbol].orEmpty()
                InterdayResidualizationMode.MARKET -> {
                    val symbolReturns = returnsBySymbol[candidate.symbol].orEmpty()
                    val marketBeta = marketBetaBySymbol[candidate.symbol] ?: 0.0
                    subtractSeries(symbolReturns, marketSeries, marketBeta)
                }
            }
        }
    )
}

private fun logReturnAt(bars: List<InterdayBar?>, index: Int): Double? {
    if (index <= 0) return null
    val current = bars.getOrNull(index) ?: return null
    val previous = bars.getOrNull(index - 1) ?: return null
    if (current.close <= 0.0 || previous.close <= 0.0) return null
    return ln(current.close / previous.close)
}

private fun rollingReturnWindow(
    bars: List<InterdayBar?>?,
    index: Int,
    lookbackReturns: Int
): List<Double>? {
    val safeBars = bars ?: return null
    val start = index - lookbackReturns + 1
    if (start < 1) return null
    return (start..index).map { cursor ->
        logReturnAt(safeBars, cursor) ?: return null
    }
}

private fun structuralLookbackBars(
    index: Int,
    config: InterdayAlphaConfig,
    indicators: InterdaySearchEngine.IndicatorWindows
): Int = min(
    index,
    max(
        barsForDays(config.factorLookbackDays, config.signalBarMinutes),
        minimumStructuralLookbackBars(indicators, config.perturbationLookbackBars)
    )
)

private fun minimumStructuralLookbackBars(
    indicators: InterdaySearchEngine.IndicatorWindows,
    perturbationLookbackBars: Int
): Int = max(
    max(indicators.slowBars, max(indicators.regressionBars, max(indicators.volatilityBars, indicators.adxBars))),
    max(perturbationLookbackBars, 4)
)

private fun averageSeries(
    series: List<Pair<String, List<Double>>>,
    proxyMode: InterdayResidualizationMarketProxyMode,
    liquidityWeightBySymbol: Map<String, Double>
): List<Double> {
    val valid = series.filter { it.second.isNotEmpty() }
    if (valid.isEmpty()) return emptyList()
    val size = valid.minOf { it.second.size }
    return List(size) { offset ->
        when (proxyMode) {
            InterdayResidualizationMarketProxyMode.EQUAL_WEIGHT ->
                valid.map { it.second[offset] }.averageOrZero()
            InterdayResidualizationMarketProxyMode.LIQUIDITY_WEIGHTED -> {
                val weighted = valid.map { (symbol, values) ->
                    val weight = liquidityWeightBySymbol[symbol]?.coerceAtLeast(1e-6) ?: 1.0
                    values[offset] to weight
                }
                val denominator = weighted.sumOf { it.second }.coerceAtLeast(1e-6)
                weighted.sumOf { it.first * it.second } / denominator
            }
        }
    }
}

private fun weightedAverage(
    valuesBySymbol: Map<String, Double>,
    weightsBySymbol: Map<String, Double>
): Double {
    if (valuesBySymbol.isEmpty()) return 0.0
    val denominator = valuesBySymbol.keys.sumOf { symbol -> weightsBySymbol[symbol]?.coerceAtLeast(1e-6) ?: 1.0 }.coerceAtLeast(1e-6)
    return valuesBySymbol.entries.sumOf { (symbol, value) ->
        value * (weightsBySymbol[symbol]?.coerceAtLeast(1e-6) ?: 1.0)
    } / denominator
}

private fun subtractSeries(left: List<Double>, right: List<Double>, beta: Double): List<Double> {
    val size = min(left.size, right.size)
    return List(size) { offset -> left[offset] - beta * right[offset] }
}

private fun beta(
    left: List<Double>,
    right: List<Double>,
    mode: InterdayResidualizationBetaMode,
    halfLifeBars: Int
): Double {
    val size = min(left.size, right.size)
    if (size < 4) return 0.0
    val leftWindow = left.takeLast(size)
    val rightWindow = right.takeLast(size)
    val weights = when (mode) {
        InterdayResidualizationBetaMode.SIMPLE -> List(size) { 1.0 }
        InterdayResidualizationBetaMode.EWMA -> ewmaWeights(size, halfLifeBars)
    }
    val totalWeight = weights.sum().coerceAtLeast(1e-9)
    val leftMean = leftWindow.indices.sumOf { offset -> leftWindow[offset] * weights[offset] } / totalWeight
    val rightMean = rightWindow.indices.sumOf { offset -> rightWindow[offset] * weights[offset] } / totalWeight
    val covariance = leftWindow.indices.sumOf { offset ->
        weights[offset] * (leftWindow[offset] - leftMean) * (rightWindow[offset] - rightMean)
    } / totalWeight
    val variance = rightWindow.indices.sumOf { offset ->
        val centered = rightWindow[offset] - rightMean
        weights[offset] * centered * centered
    } / totalWeight
    if (variance <= 1e-10) return 0.0
    return covariance / variance
}

private fun ewmaWeights(size: Int, halfLifeBars: Int): List<Double> {
    val safeHalfLife = halfLifeBars.coerceAtLeast(1).toDouble()
    return List(size) { offset ->
        val age = (size - 1 - offset).toDouble()
        exp(-ln(2.0) * age / safeHalfLife)
    }
}

private fun liquidityProxyWeight(candidate: InterdaySearchEngine.RawSignal): Double =
    (candidate.depthUsd.coerceAtLeast(1.0).pow(0.25) * (0.25 + candidate.liquidityRank.coerceIn(0.0, 1.0))).coerceAtLeast(1e-6)

private fun deriveTrendSignal(
    returns: List<Double>,
    indicators: InterdaySearchEngine.IndicatorWindows,
    perturbationLookbackBars: Int
): InterdaySearchEngine.DerivedTrendSignal? {
    val requiredReturns = max(
        indicators.slowBars,
        max(indicators.regressionBars, max(indicators.volatilityBars, indicators.adxBars))
    )
    if (returns.size < max(requiredReturns, perturbationLookbackBars + 2)) return null
    val volatility = rollingVolatility(returns, indicators.volatilityBars) ?: return null
    val closes = cumulativeCloseSeries(returns)
    val fastReturn = scaledReturn(returns, indicators.fastBars) ?: return null
    val mediumReturn = scaledReturn(returns, indicators.mediumBars) ?: return null
    val slowReturn = scaledReturn(returns, indicators.slowBars) ?: return null
    val slope = regressionTStat(closes, indicators.regressionBars) ?: return null
    val maSpread = movingAverageSpread(closes, indicators.fastBars, indicators.slowBars) ?: return null
    val adx = directionalPersistence(returns, indicators.adxBars) ?: return null
    val perturbation = perturbationZ(returns, perturbationLookbackBars, volatility) ?: return null
    return InterdaySearchEngine.DerivedTrendSignal(
        volatility = volatility,
        fastReturn = fastReturn,
        mediumReturn = mediumReturn,
        slowReturn = slowReturn,
        slope = slope,
        maSpread = maSpread,
        adx = adx,
        perturbation = perturbation
    )
}

private fun factorTrendScore(
    returns: List<Double>,
    indicators: InterdaySearchEngine.IndicatorWindows,
    config: InterdayAlphaConfig
): Double {
    val derived = deriveTrendSignal(returns, indicators, config.perturbationLookbackBars) ?: return 0.0
    val slopeWeight = config.slopeWeight.coerceIn(0.0, 1.0)
    val trendCore = listOf(
        squash(derived.fastReturn, 2.0),
        squash(derived.mediumReturn, 2.0),
        squash(derived.slowReturn, 2.0),
        squash(derived.maSpread / derived.volatility.coerceAtLeast(0.0001), 2.0)
    ).average()
    val adxThreshold = config.adxThreshold.coerceAtLeast(1.0)
    val adxSupport = ((derived.adx - adxThreshold) / adxThreshold).coerceIn(-1.0, 1.0)
    return (
        trendCore * (1.0 - slopeWeight) +
            squash(derived.slope, 3.0) * slopeWeight +
            adxSupport * 0.15
        ).coerceIn(-1.0, 1.0)
}

private fun barsForDays(days: Int, signalBarMinutes: Int): Int =
    max(1, ceil((days * 24.0 * 60.0) / signalBarMinutes.toDouble()).toInt())

private fun barsForHours(hours: Int, signalBarMinutes: Int): Int =
    max(1, ceil((hours * 60.0) / signalBarMinutes.toDouble()).toInt())

private fun trainingTargetBars(config: InterdayAlphaConfig): Int =
    barsForHours(config.forwardHours, config.signalBarMinutes).coerceAtLeast(1)

private fun scaledReturn(bars: List<InterdayBar?>, index: Int, lookbackBars: Int): Double? {
    if (index - lookbackBars < 0) return null
    val current = bars[index] ?: return null
    val past = bars[index - lookbackBars] ?: return null
    if (current.close <= 0.0 || past.close <= 0.0) return null
    val volatility = rollingVolatility(bars, index, max(lookbackBars / 2, 3)) ?: return null
    return ln(current.close / past.close) / volatility.coerceAtLeast(0.0001)
}

private fun rollingVolatility(bars: List<InterdayBar?>, index: Int, lookbackBars: Int): Double? {
    if (index - lookbackBars < 0) return null
    val returns = mutableListOf<Double>()
    for (cursor in (index - lookbackBars + 1)..index) {
        val current = bars[cursor] ?: return null
        val previous = bars[cursor - 1] ?: return null
        if (current.close <= 0.0 || previous.close <= 0.0) return null
        returns += ln(current.close / previous.close)
    }
    if (returns.isEmpty()) return null
    val variance = returns.map { it * it }.average().coerceAtLeast(1e-8)
    return sqrt(variance)
}

private fun movingAverageSpread(
    bars: List<InterdayBar?>,
    index: Int,
    fastBars: Int,
    slowBars: Int
): Double? {
    val fast = averageClose(bars, index, fastBars) ?: return null
    val slow = averageClose(bars, index, slowBars) ?: return null
    if (slow <= 0.0) return null
    return (fast / slow) - 1.0
}

private fun averageClose(bars: List<InterdayBar?>, index: Int, lookbackBars: Int): Double? {
    if (index - lookbackBars + 1 < 0) return null
    val window = (index - lookbackBars + 1..index).map { bars[it]?.close ?: return null }
    return window.average()
}

private fun regressionTStat(bars: List<InterdayBar?>, index: Int, lookbackBars: Int): Double? {
    if (index - lookbackBars + 1 < 0) return null
    val values = (index - lookbackBars + 1..index).map { cursor ->
        val close = bars[cursor]?.close ?: return null
        if (close <= 0.0) return null
        ln(close)
    }
    val xMean = (lookbackBars - 1) / 2.0
    val yMean = values.average()
    var sxx = 0.0
    var sxy = 0.0
    values.forEachIndexed { offset, value ->
        val x = offset.toDouble()
        sxx += (x - xMean).pow(2)
        sxy += (x - xMean) * (value - yMean)
    }
    if (sxx <= 0.0) return null
    val slope = sxy / sxx
    val residuals = values.mapIndexed { offset, value ->
        val fitted = yMean + slope * (offset.toDouble() - xMean)
        value - fitted
    }
    val sigma2 = residuals.sumOf { it * it } / max(lookbackBars - 2, 1).toDouble()
    val standardError = sqrt((sigma2 / sxx).coerceAtLeast(1e-12))
    return slope / standardError
}

private fun computeAdx(bars: List<InterdayBar?>, index: Int, lookbackBars: Int): Double? {
    if (index - lookbackBars < 0) return null
    val trs = mutableListOf<Double>()
    val plusDm = mutableListOf<Double>()
    val minusDm = mutableListOf<Double>()
    for (cursor in (index - lookbackBars + 1)..index) {
        val current = bars[cursor] ?: return null
        val previous = bars[cursor - 1] ?: return null
        val upMove = current.high - previous.high
        val downMove = previous.low - current.low
        plusDm += if (upMove > downMove && upMove > 0.0) upMove else 0.0
        minusDm += if (downMove > upMove && downMove > 0.0) downMove else 0.0
        trs += max(
            current.high - current.low,
            max(abs(current.high - previous.close), abs(current.low - previous.close))
        )
    }
    val tr = trs.average().coerceAtLeast(1e-9)
    val plusDi = 100.0 * plusDm.average() / tr
    val minusDi = 100.0 * minusDm.average() / tr
    val denominator = (plusDi + minusDi).coerceAtLeast(1e-9)
    return 100.0 * abs(plusDi - minusDi) / denominator
}

private fun perturbationZ(
    bars: List<InterdayBar?>,
    index: Int,
    lookbackBars: Int,
    volatility: Double
): Double? {
    if (index - lookbackBars < 0) return null
    val current = bars[index] ?: return null
    val past = bars[index - lookbackBars] ?: return null
    if (current.close <= 0.0 || past.close <= 0.0) return null
    return ln(current.close / past.close) / volatility.coerceAtLeast(0.0001)
}

private fun openInterestMomentum(bars: List<InterdayBar?>, index: Int, lookbackBars: Int): Double? {
    if (index - lookbackBars < 0) return null
    val current = bars[index]?.openInterest ?: return null
    val past = bars[index - lookbackBars]?.openInterest ?: return null
    if (current <= 0.0 || past <= 0.0) return null
    return ln(current / past)
}

private fun scaledReturn(returns: List<Double>, lookbackBars: Int): Double? {
    if (returns.size < lookbackBars) return null
    val volatility = rollingVolatility(returns, max(lookbackBars / 2, 3)) ?: return null
    return returns.takeLast(lookbackBars).sum() / volatility.coerceAtLeast(0.0001)
}

private fun rollingVolatility(returns: List<Double>, lookbackBars: Int): Double? {
    if (returns.size < lookbackBars) return null
    val variance = returns.takeLast(lookbackBars).map { it * it }.average().coerceAtLeast(1e-8)
    return sqrt(variance)
}

private fun cumulativeCloseSeries(returns: List<Double>): List<Double> {
    val closes = ArrayList<Double>(returns.size + 1)
    var level = 1.0
    closes += level
    returns.forEach { value ->
        level *= kotlin.math.exp(value)
        closes += level
    }
    return closes
}

private fun movingAverageSpread(closes: List<Double>, fastBars: Int, slowBars: Int): Double? {
    if (closes.size < slowBars + 1) return null
    val fast = closes.takeLast(fastBars).averageOrZero()
    val slow = closes.takeLast(slowBars).averageOrZero()
    if (slow <= 0.0) return null
    return (fast / slow) - 1.0
}

private fun regressionTStat(closes: List<Double>, lookbackBars: Int): Double? {
    if (closes.size < lookbackBars + 1) return null
    val values = closes.takeLast(lookbackBars + 1).map { close ->
        if (close <= 0.0) return null
        ln(close)
    }
    val xMean = (values.size - 1) / 2.0
    val yMean = values.average()
    var sxx = 0.0
    var sxy = 0.0
    values.forEachIndexed { offset, value ->
        val x = offset.toDouble()
        sxx += (x - xMean).pow(2)
        sxy += (x - xMean) * (value - yMean)
    }
    if (sxx <= 0.0) return null
    val slope = sxy / sxx
    val residuals = values.mapIndexed { offset, value ->
        val fitted = yMean + slope * (offset.toDouble() - xMean)
        value - fitted
    }
    val sigma2 = residuals.sumOf { it * it } / max(values.size - 2, 1).toDouble()
    val standardError = sqrt((sigma2 / sxx).coerceAtLeast(1e-12))
    return slope / standardError
}

private fun directionalPersistence(returns: List<Double>, lookbackBars: Int): Double? {
    if (returns.size < lookbackBars) return null
    val window = returns.takeLast(lookbackBars)
    val upPressure = window.map { max(it, 0.0) }.averageOrZero()
    val downPressure = window.map { max(-it, 0.0) }.averageOrZero()
    val denominator = (upPressure + downPressure).coerceAtLeast(1e-9)
    return 100.0 * abs(upPressure - downPressure) / denominator
}

private fun perturbationZ(
    returns: List<Double>,
    lookbackBars: Int,
    volatility: Double
): Double? {
    if (returns.size < lookbackBars) return null
    return returns.takeLast(lookbackBars).sum() / volatility.coerceAtLeast(0.0001)
}

private fun squash(value: Double, scale: Double): Double {
    val scaled = value / scale.coerceAtLeast(1e-9)
    return scaled / sqrt(1.0 + scaled * scaled)
}

private fun centeredRanks(values: Map<String, Double>): Map<String, Double> {
    if (values.isEmpty()) return emptyMap()
    val sorted = values.entries.sortedBy { it.value }
    val denominator = max(sorted.size - 1, 1).toDouble()
    return sorted.mapIndexed { index, entry ->
        val centered = (index.toDouble() / denominator) * 2.0 - 1.0
        entry.key to centered
    }.toMap()
}

private fun quantile(sortedValues: List<Double>, probability: Double): Double {
    if (sortedValues.isEmpty()) return 0.0
    val clipped = probability.coerceIn(0.0, 1.0)
    val index = ((sortedValues.size - 1) * clipped).toInt().coerceIn(0, sortedValues.lastIndex)
    return sortedValues[index]
}

private fun annualizedSharpe(logReturns: List<Double>, signalBarMinutes: Int): Double {
    if (logReturns.size < 2) return 0.0
    val mean = logReturns.average()
    val variance = logReturns.map { (it - mean).pow(2) }.average()
    if (variance <= 1e-12) return 0.0
    val annualization = sqrt((365.0 * 24.0 * 60.0) / signalBarMinutes.toDouble())
    return (mean / sqrt(variance)) * annualization
}

private fun maxDrawdownPct(equityCurve: List<Double>): Double {
    var peak = equityCurve.firstOrNull() ?: return 0.0
    var maxDrawdown = 0.0
    equityCurve.forEach { equity ->
        peak = max(peak, equity)
        if (peak > 0.0) {
            maxDrawdown = max(maxDrawdown, (peak - equity) / peak)
        }
    }
    return maxDrawdown * 100.0
}

private fun annualizedReturnPct(
    startEquity: Double,
    endEquity: Double,
    startTime: Instant,
    endTime: Instant
): Double {
    if (startEquity <= 0.0 || endEquity <= 0.0 || !endTime.isAfter(startTime)) return 0.0
    val years = Duration.between(startTime, endTime).seconds.toDouble() / (365.0 * 24.0 * 3_600.0)
    if (years <= 0.0) return 0.0
    return (endEquity / startEquity).pow(1.0 / years).minus(1.0) * 100.0
}

private fun ulcerIndex(equityCurve: List<Double>): Double {
    if (equityCurve.isEmpty()) return 0.0
    var peak = equityCurve.first()
    val squaredDrawdowns = equityCurve.map { equity ->
        peak = max(peak, equity)
        val drawdownPct = if (peak <= 0.0) 0.0 else ((peak - equity).coerceAtLeast(0.0) / peak) * 100.0
        drawdownPct.pow(2)
    }
    return sqrt(squaredDrawdowns.average())
}

private fun timeUnderWaterPct(equityCurve: List<Double>): Double {
    if (equityCurve.isEmpty()) return 0.0
    var peak = equityCurve.first()
    var underwater = 0
    equityCurve.forEach { equity ->
        peak = max(peak, equity)
        if (equity + 1e-9 < peak) underwater += 1
    }
    return underwater.toDouble() / equityCurve.size.toDouble() * 100.0
}

private fun cvar1dPct(logReturns: List<Double>, signalBarMinutes: Int): Double {
    if (logReturns.isEmpty()) return 0.0
    val barsPerDay = barsForHours(24, signalBarMinutes).coerceAtLeast(1)
    val dailyReturns = logReturns.chunked(barsPerDay).map { chunk -> (kotlin.math.exp(chunk.sum()) - 1.0) * 100.0 }
    if (dailyReturns.isEmpty()) return 0.0
    val sorted = dailyReturns.sorted()
    val tailCount = max(1, ceil(sorted.size * 0.05).toInt())
    return sorted.take(tailCount).average()
}

private fun alignedParticipationRate(
    snapshots: List<InterdayPortfolioSnapshot>,
    regimeStrengthThreshold: Double
): Double {
    val weightedGross = snapshots.sumOf {
        it.grossExposureFraction * regimeParticipationWeight(it.regimeStrength, regimeStrengthThreshold)
    }.coerceAtLeast(1e-9)
    val aligned = snapshots.sumOf { it.alignedExposureFraction }
    return (aligned / weightedGross).coerceIn(0.0, 1.0)
}

private fun wrongWayExposurePct(
    snapshots: List<InterdayPortfolioSnapshot>,
    regimeStrengthThreshold: Double
): Double {
    val gross = snapshots.sumOf {
        it.grossExposureFraction * regimeParticipationWeight(it.regimeStrength, regimeStrengthThreshold)
    }.coerceAtLeast(1e-9)
    val wrongWay = snapshots.sumOf { it.wrongWayExposureFraction }
    return (wrongWay / gross * 100.0).coerceAtLeast(0.0)
}

private fun averageProfitGivebackPct(trades: List<InterdayTradeRecord>): Double =
    trades.map { it.profitGivebackPct }.averageOrZero()

private fun pnlSkew(values: List<Double>): Double {
    if (values.size < 3) return 0.0
    val mean = values.average()
    val centered = values.map { it - mean }
    val variance = centered.map { it * it }.average()
    if (variance <= 1e-12) return 0.0
    val sigma = sqrt(variance)
    return centered.map { (it / sigma).pow(3) }.average()
}

private fun avgWinnerLoserRatio(trades: List<InterdayTradeRecord>): Double {
    val winners = trades.map { it.pnlPct }.filter { it > 0.0 }
    val losers = trades.map { it.pnlPct }.filter { it < 0.0 }
    if (winners.isEmpty() || losers.isEmpty()) return 0.0
    return winners.average() / abs(losers.average()).coerceAtLeast(1e-9)
}

private fun killSwitchUtilizationMax(
    snapshots: List<InterdayPortfolioSnapshot>,
    searchPolicy: org.datamancy.trading.policy.AlphaSearchPolicy,
    regimeStrengthThreshold: Double
): Double {
    if (snapshots.isEmpty()) return 0.0
    val wrongWayCeiling = searchPolicy.maxWrongWayExposurePct.coerceAtLeast(1.0)
    val drawdownCeiling = searchPolicy.maxSearchDrawdownPct.coerceAtLeast(1.0)
    var peak = snapshots.first().equity
    return snapshots.maxOf { snapshot ->
        peak = max(peak, snapshot.equity)
        val drawdownPct = if (peak <= 0.0) 0.0 else ((peak - snapshot.equity).coerceAtLeast(0.0) / peak) * 100.0
        val weightedGross = snapshot.grossExposureFraction * regimeParticipationWeight(snapshot.regimeStrength, regimeStrengthThreshold)
        val wrongWayPct = if (weightedGross <= 1e-9) 0.0 else snapshot.wrongWayExposureFraction / weightedGross * 100.0
        max(drawdownPct / drawdownCeiling, wrongWayPct / wrongWayCeiling)
    }
}

private fun regimeParticipationWeight(regimeStrength: Double, regimeStrengthThreshold: Double): Double {
    val denominator = regimeStrengthThreshold.coerceAtLeast(0.05)
    return (regimeStrength / denominator).coerceIn(0.0, 1.0)
}

private fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
    val size = rhs.size
    val augmented = Array(size) { row ->
        DoubleArray(size + 1).also { columns ->
            for (column in 0 until size) {
                columns[column] = matrix[row][column]
            }
            columns[size] = rhs[row]
        }
    }
    for (pivot in 0 until size) {
        var bestRow = pivot
        var bestAbs = abs(augmented[pivot][pivot])
        for (candidate in pivot + 1 until size) {
            val candidateAbs = abs(augmented[candidate][pivot])
            if (candidateAbs > bestAbs) {
                bestAbs = candidateAbs
                bestRow = candidate
            }
        }
        if (bestAbs <= 1e-12) return null
        if (bestRow != pivot) {
            val tmp = augmented[pivot]
            augmented[pivot] = augmented[bestRow]
            augmented[bestRow] = tmp
        }
        val pivotValue = augmented[pivot][pivot]
        for (column in pivot until size + 1) {
            augmented[pivot][column] /= pivotValue
        }
        for (row in 0 until size) {
            if (row == pivot) continue
            val factor = augmented[row][pivot]
            if (abs(factor) <= 1e-12) continue
            for (column in pivot until size + 1) {
                augmented[row][column] -= factor * augmented[pivot][column]
            }
        }
    }
    return DoubleArray(size) { index -> augmented[index][size] }
}

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun medianAbsoluteDeviation(values: List<Double>, center: Double = median(values)): Double =
    if (values.isEmpty()) {
        0.0
    } else {
        median(values.map { abs(it - center) })
    }

private fun standardDeviation(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    return sqrt(values.map { (it - mean).pow(2) }.average())
}

private fun bootstrapStatistics(logReturns: List<Double>, signalBarMinutes: Int): BootstrapSummary {
    if (logReturns.isEmpty()) return BootstrapSummary(0.0, 0.0)
    val random = Random(42)
    val total = 200
    val returnSamples = DoubleArray(total)
    val sharpeSamples = DoubleArray(total)
    repeat(total) { iteration ->
        val sampled = List(logReturns.size) { logReturns[random.nextInt(logReturns.size)] }
        returnSamples[iteration] = sampled.sum() * 100.0
        sharpeSamples[iteration] = annualizedSharpe(sampled, signalBarMinutes)
    }
    returnSamples.sort()
    sharpeSamples.sort()
    val returnP05 = returnSamples[(total * 0.05).toInt().coerceIn(0, total - 1)]
    val sharpeP05 = sharpeSamples[(total * 0.05).toInt().coerceIn(0, total - 1)]
    return BootstrapSummary(returnP05Pct = returnP05, sharpeP05 = sharpeP05)
}

private fun estimateImpactBps(weightDelta: Double, capitalUsd: Double, depthUsd: Double): Double {
    if (depthUsd <= 0.0) return 6.0
    val notional = abs(weightDelta) * capitalUsd
    val depthRatio = (notional / depthUsd).coerceAtLeast(0.0)
    return (2.0 + 18.0 * depthRatio).coerceAtMost(40.0)
}

private fun format(value: Double): String = "%.4f".format(value)

private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

private fun Iterable<Double>.averageOrZero(): Double {
    val values = toList()
    return if (values.isEmpty()) 0.0 else values.average()
}

private fun InterdayPanel.seriesBySymbol(): Map<String, InterdaySymbolSeries> = series.associateBy { it.symbol }

private fun prioritizeValues(values: List<Int>, anchor: Int): List<Int> =
    values.distinct().sortedWith(compareBy<Int> { abs(it - anchor) }.thenBy { it })

private fun <T> prioritizeAnchored(values: List<T>, anchor: T): List<T> =
    listOf(anchor) + values.filter { it != anchor }.distinct()

private fun prioritizeDoubles(values: List<Double>, anchor: Double): List<Double> =
    values.distinct().sortedWith(compareBy<Double> { abs(it - anchor) }.thenBy { it })

private data class BootstrapSummary(
    val returnP05Pct: Double,
    val sharpeP05: Double
)
