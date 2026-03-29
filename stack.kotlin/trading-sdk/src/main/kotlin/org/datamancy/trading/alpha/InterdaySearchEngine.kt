package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
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
    private val executionPlanner: AlphaExecutionPlanner = AlphaExecutionPlanner(policyProvider)
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
        val ranked = evaluations
            .sortedWith(survivorOrdering())
            .take(leaderboardSize)
            .mapIndexed { index, bundle ->
                InterdayCandidateEvaluation(
                    rank = index + 1,
                    config = bundle.config,
                    backtest = bundle.backtest,
                    forward = bundle.forward,
                    validation = bundle.validation,
                    selectedSignals = bundle.latestSignals.take(16),
                    targets = bundle.latestTargets
                )
            }

        return InterdayAlphaSearchResponse(
            generatedAt = Instant.now(),
            defaults = discoveryDefaults,
            searchRequest = request.copy(baseConfig = baseConfig),
            evaluatedConfigs = evaluations.size,
            leaderboard = ranked,
            notes = listOf(
                "Signals are ranked cross-sectionally relative to the active universe instead of using absolute token price levels.",
                "Hierarchical decomposition separates market regime, cohort drift, and symbol residual so discovery does not confuse broad tape motion with idiosyncratic edge.",
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
                "Alpha is decomposed into market regime, structural cohort drift, and symbol residual so the engine can stay diversified without mistaking beta for edge.",
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

    private fun generateConfigs(
        base: InterdayAlphaConfig,
        searchSpace: InterdaySearchSpace,
        maxEvaluations: Int
    ): List<InterdayAlphaConfig> {
        val adjustmentModes = prioritizeAnchored(
            searchSpace.adjustmentModes.ifEmpty { listOf(base.adjustmentMode) },
            base.adjustmentMode
        )
        val signalBars = prioritizeValues(searchSpace.signalBarMinutes.ifEmpty { listOf(base.signalBarMinutes) }, base.signalBarMinutes)
        val lookbacks = prioritizeValues(searchSpace.lookbackHours.ifEmpty { listOf(base.lookbackHours) }, base.lookbackHours)
        val forwards = prioritizeValues(searchSpace.forwardHours.ifEmpty { listOf(base.forwardHours) }, base.forwardHours)
        val cadences = prioritizeValues(searchSpace.rebalanceCadenceHours.ifEmpty { listOf(base.rebalanceCadenceHours) }, base.rebalanceCadenceHours)
        val quantiles = prioritizeDoubles(searchSpace.selectionQuantiles.ifEmpty { listOf(base.selectionQuantile) }, base.selectionQuantile)
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
        val openInterestWeights = prioritizeDoubles(searchSpace.openInterestWeight.ifEmpty { listOf(base.openInterestWeight) }, base.openInterestWeight)
        val pullbackWeights = prioritizeDoubles(searchSpace.pullbackWeight.ifEmpty { listOf(base.pullbackWeight) }, base.pullbackWeight)
        val minTrendAgreements = prioritizeDoubles(searchSpace.minTrendAgreement.ifEmpty { listOf(base.minTrendAgreement) }, base.minTrendAgreement)
        val adxThresholds = prioritizeDoubles(searchSpace.adxThreshold.ifEmpty { listOf(base.adxThreshold) }, base.adxThreshold)
        val minConfidences = prioritizeDoubles(searchSpace.minConfidence.ifEmpty { listOf(base.minConfidence) }, base.minConfidence)
        val trailingStops = prioritizeDoubles(searchSpace.trailingStopVolMultiple.ifEmpty { listOf(base.trailingStopVolMultiple) }, base.trailingStopVolMultiple)
        val takeProfits = prioritizeDoubles(searchSpace.takeProfitVolMultiple.ifEmpty { listOf(base.takeProfitVolMultiple) }, base.takeProfitVolMultiple)
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
        val hierarchyMarketWeights = prioritizeDoubles(
            searchSpace.hierarchyMarketWeight.ifEmpty { listOf(base.hierarchyMarketWeight) },
            base.hierarchyMarketWeight
        )
        val hierarchyCohortWeights = prioritizeDoubles(
            searchSpace.hierarchyCohortWeight.ifEmpty { listOf(base.hierarchyCohortWeight) },
            base.hierarchyCohortWeight
        )
        val hierarchyResidualWeights = prioritizeDoubles(
            searchSpace.hierarchyResidualWeight.ifEmpty { listOf(base.hierarchyResidualWeight) },
            base.hierarchyResidualWeight
        )

        val generated = mutableListOf<InterdayAlphaConfig>()
        outer@ for (adjustmentMode in adjustmentModes) {
            for (signalBar in signalBars) {
                for (lookback in lookbacks) {
                    for (forward in forwards) {
                        for (cadence in cadences) {
                            for (quantile in quantiles) {
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
                                                                        for (openInterestWeight in openInterestWeights) {
                                                                            for (pullbackWeight in pullbackWeights) {
                                                                                for (minTrendAgreement in minTrendAgreements) {
                                                                                    for (adxThreshold in adxThresholds) {
                                                                                        for (minConfidence in minConfidences) {
                                                                                            for (trailing in trailingStops) {
                                                                                                for (takeProfit in takeProfits) {
                                                                                                    for (executionWindow in executionWindows) {
                                                                                                        for (targetGrossFractionScale in targetGrossFractionScales) {
                                                                                                            for (expectedCostPenaltyWeight in expectedCostPenaltyWeights) {
                                                                                                                for (turnoverPenaltyWeight in turnoverPenaltyWeights) {
                                                                                                                    for (entryEdgeFloor in entryEdgeFloorBps) {
                                                                                                                        for (holdEdgeFloor in holdEdgeFloorBps) {
                                                                                                                            for (regimeDirectionalSuppressionThreshold in regimeDirectionalSuppressionThresholds) {
                                                                                                                                for (regimeNetBiasScale in regimeNetBiasScales) {
                                                                                                                                    for (hierarchyMarketWeight in hierarchyMarketWeights) {
                                                                                                                                        for (hierarchyCohortWeight in hierarchyCohortWeights) {
                                                                                                                                            for (hierarchyResidualWeight in hierarchyResidualWeights) {
                                                                                                                                                generated += base.copy(
                                                                                                                                        adjustmentMode = adjustmentMode,
                                                                                                                                        signalBarMinutes = signalBar,
                                                                                                                                        lookbackHours = lookback,
                                                                                                                                        forwardHours = forward,
                                                                                                                                        rebalanceCadenceHours = cadence,
                                                                                                                                        selectionQuantile = quantile,
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
                                                                                                                                        openInterestWeight = openInterestWeight,
                                                                                                                                        pullbackWeight = pullbackWeight,
                                                                                                                                        minTrendAgreement = minTrendAgreement,
                                                                                                                                        adxThreshold = adxThreshold,
                                                                                                                                        minConfidence = minConfidence,
                                                                                                                                        trailingStopVolMultiple = trailing,
                                                                                                                                        takeProfitVolMultiple = takeProfit,
                                                                                                                                        executionWindowMinutes = executionWindow,
                                                                                                                                        targetGrossFractionScale = targetGrossFractionScale,
                                                                                                                                        expectedCostPenaltyWeight = expectedCostPenaltyWeight,
                                                                                                                                        turnoverPenaltyWeight = turnoverPenaltyWeight,
                                                                                                                                        entryEdgeFloorBps = entryEdgeFloor,
                                                                                                                                        holdEdgeFloorBps = holdEdgeFloor,
                                                                                                                                        regimeDirectionalSuppressionThreshold = regimeDirectionalSuppressionThreshold,
                                                                                                                                        regimeNetBiasScale = regimeNetBiasScale,
                                                                                                                                        hierarchyMarketWeight = hierarchyMarketWeight,
                                                                                                                                        hierarchyCohortWeight = hierarchyCohortWeight,
                                                                                                                                        hierarchyResidualWeight = hierarchyResidualWeight
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
        var latestRegime = RegimeState(score = 0.0, breadth = 0.0, anchorTrend = 0.0, dispersion = 0.0)

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
                    adjustments += position.trailingAdjustments(config, bar, time)
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
                val signals = computeSignals(
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
                latestSignals = signals
                    .sortedByDescending { abs(it.score) }
                    .take(32)
                latestSignalsBySymbol = signals.associateBy { it.symbol }
                val eligibleBySymbol = signals.associate { signal ->
                    val trendAgreementOk = signal.trendAgreement >= config.minTrendAgreement
                    val incumbentSameSide = holdsSignalDirection(currentWeights[signal.symbol] ?: 0.0, signal.direction)
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
                    val edgeFloorOk = directionalEdgeBps(signal) >= if (incumbentSameSide) config.holdEdgeFloorBps else config.entryEdgeFloorBps
                    signal.symbol to (
                        signal.confidence >= config.minConfidence &&
                            trendAgreementOk &&
                            entryStyleOk &&
                            regimeAllowed &&
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
                        currentWeightFraction = currentWeights[it.symbol] ?: 0.0
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
                    val scaledGrossFraction = scaledTargetGrossFraction(portfolioDefaults, config)
                    val constructed = portfolioConstructor.construct(
                        AlphaPortfolioRequest(
                            signals = portfolioSignals,
                            selectionQuantile = config.selectionQuantile,
                            respectProvidedSignalSet = true,
                            targetGrossFraction = scaledGrossFraction,
                            currentWeightsBySymbol = currentWeights.toMap(),
                            minExpectedNetEdgeBps = config.entryEdgeFloorBps,
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
                    val plannedDelta = if (forcedFlatten) {
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
            trades = trades,
            splitTime = splitTime,
            beforeSplit = true,
            signalBarMinutes = config.signalBarMinutes,
            searchPolicy = searchPolicy,
            regimeStrengthThreshold = config.regimeStrengthThreshold
        )
        val forward = buildPerformance(
            segment = "forward",
            snapshots = snapshots,
            trades = trades,
            splitTime = splitTime,
            beforeSplit = false,
            signalBarMinutes = config.signalBarMinutes,
            searchPolicy = searchPolicy,
            regimeStrengthThreshold = config.regimeStrengthThreshold
        )
        val validation = validate(config, backtest, forward, searchPolicy)
        val inspection = if (includeInspection) {
            buildInspection(
                snapshots = snapshots,
                regimes = regimeSnapshots,
                signalHistory = signalHistory,
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
    ): List<InterdaySignalSnapshot> {
        val raw = collectRawSignals(panel, index, config, indicators, liquidityRanks)
        if (raw.isEmpty()) return emptyList()
        val featureVectors = buildFeatureVectors(raw, config)
        val anchorBetas = computeAnchorBetas(panel, index, indicators.mediumBars)
        val empiricalWeights = fitEmpiricalWeights(
            panel = panel,
            currentIndex = index,
            evaluationStartIndex = evaluationStartIndex,
            rebalanceBars = rebalanceBars,
            targetHorizonBars = targetHorizonBars,
            config = config,
            indicators = indicators,
            liquidityRanks = liquidityRanks
        )
        val empiricalScores = raw.associate { candidate ->
            val features = featureVectors.getValue(candidate.symbol)
            candidate.symbol to empiricalWeights.score(features, config)
        }
        val cohortMemberships = buildCohortMemberships(
            raw = raw,
            anchorBetas = anchorBetas,
            empiricalScores = empiricalScores
        )
        val hierarchyWeights = calibrateHierarchyWeights(
            panel = panel,
            currentIndex = index,
            evaluationStartIndex = evaluationStartIndex,
            rebalanceBars = rebalanceBars,
            targetHorizonBars = targetHorizonBars,
            config = config,
            indicators = indicators,
            liquidityRanks = liquidityRanks,
            portfolioDefaults = portfolioDefaults,
            empiricalWeights = empiricalWeights
        )
        val marketScale = median(empiricalScores.values.map { abs(it) }.filter { it.isFinite() && it > 1e-9 }.ifEmpty { listOf(0.0) })
        val empiricalRanks = centeredRanks(empiricalScores)
        val spreadRanks = centeredRanks(raw.associate { it.symbol to -(it.spreadBps) })
        val edgeEstimates = raw.associate { candidate ->
            val features = featureVectors.getValue(candidate.symbol)
            val empiricalScore = empiricalScores.getValue(candidate.symbol)
            val cohortMembership = cohortMemberships.getValue(candidate.symbol)
            val hierarchicalScore = hierarchicalScore(
                empiricalScore = empiricalScore,
                cohortMeanScore = cohortMembership.meanScore,
                trend = features.trend,
                trendAgreement = features.trendAgreement,
                regimeScore = regime.score,
                marketScale = marketScale,
                marketWeight = hierarchyWeights.marketWeight,
                cohortWeight = hierarchyWeights.cohortWeight,
                residualWeight = hierarchyWeights.residualWeight
            )
            val direction = if (hierarchicalScore.totalScore >= 0.0) AlphaDirection.LONG else AlphaDirection.SHORT
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
            val expectedResidualReturnBps = hierarchicalScore.totalScore * 10_000.0
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
            val expectedNetEdgeBps = expectedResidualReturnBps -
                config.expectedCostPenaltyWeight * expectedEntryCostBps -
                config.turnoverPenaltyWeight * expectedTurnoverPenaltyBps
            candidate.symbol to SignalEdgeEstimate(
                empiricalScore = empiricalScore,
                expectedResidualReturnBps = expectedResidualReturnBps,
                expectedEntryCostBps = expectedEntryCostBps,
                expectedTurnoverPenaltyBps = expectedTurnoverPenaltyBps,
                expectedNetEdgeBps = expectedNetEdgeBps,
                executionSupport = executionSupport,
                provisionalConfidence = provisionalConfidence,
                cohortId = cohortMembership.id,
                marketComponentBps = hierarchicalScore.marketComponent * 10_000.0,
                cohortComponentBps = hierarchicalScore.cohortComponent * 10_000.0,
                residualComponentBps = hierarchicalScore.residualComponent * 10_000.0
            )
        }
        val expectedEdgeScores = edgeEstimates.mapValues { (_, estimate) -> estimate.expectedNetEdgeBps }
        val edgeRanks = centeredRanks(expectedEdgeScores)
        val sortedScores = expectedEdgeScores.values.sorted()
        val lowerBound = quantile(sortedScores, config.selectionQuantile)
        val upperBound = quantile(sortedScores, 1.0 - config.selectionQuantile)

        return raw.map { candidate ->
            val features = featureVectors.getValue(candidate.symbol)
            val estimate = edgeEstimates.getValue(candidate.symbol)
            val cohortMembership = cohortMemberships.getValue(candidate.symbol)
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
                confidence = confidence,
                liquidityScore = candidate.liquidityRank.coerceIn(0.1, 1.0),
                trendScore = features.trend,
                trendAgreement = features.trendAgreement,
                pullbackScore = features.pullback,
                fundingScore = features.funding,
                openInterestScore = features.openInterest,
                expansionScore = features.expansion,
                reversalRiskScore = features.reversalRisk,
                cohortId = cohortMembership.id,
                marketComponentBps = estimate.marketComponentBps,
                cohortComponentBps = estimate.cohortComponentBps,
                residualComponentBps = estimate.residualComponentBps,
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
            val fundingSupport = (-trendDirection * fundingRanks.getValue(candidate.symbol)).coerceIn(-1.0, 1.0)
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
                funding = fundingSupport,
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
        if (raw.isEmpty()) return RegimeState(0.0, 0.0, 0.0, 0.0)
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
        return RegimeState(
            score = score,
            breadth = breadth,
            anchorTrend = anchorTrend.coerceIn(-1.0, 1.0),
            dispersion = dispersion.coerceIn(0.0, 1.0)
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
        liquidityRanks: Map<String, Double>
    ): EmpiricalWeights {
        val observations = mutableListOf<FeatureObservation>()
        val upperTrainingIndex = currentIndex - targetHorizonBars
        if (upperTrainingIndex >= evaluationStartIndex) {
            var trainingIndex = evaluationStartIndex
            while (trainingIndex <= upperTrainingIndex) {
                val raw = collectRawSignals(panel, trainingIndex, config, indicators, liquidityRanks)
                if (raw.isNotEmpty()) {
                    val vectors = buildFeatureVectors(raw, config)
                    val futureReturns = futureResidualReturns(panel, trainingIndex, targetHorizonBars)
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

    private fun calibrateHierarchyWeights(
        panel: InterdayPanel,
        currentIndex: Int,
        evaluationStartIndex: Int,
        rebalanceBars: Int,
        targetHorizonBars: Int,
        config: InterdayAlphaConfig,
        indicators: IndicatorWindows,
        liquidityRanks: Map<String, Double>,
        portfolioDefaults: AlphaPortfolioDefaults,
        empiricalWeights: EmpiricalWeights
    ): HierarchyWeights {
        val prior = normalizeHierarchyWeights(
            config.hierarchyMarketWeight,
            config.hierarchyCohortWeight,
            config.hierarchyResidualWeight
        )
        if (prior.marketWeight <= 1e-9 && prior.cohortWeight <= 1e-9) return prior
        val upperTrainingIndex = currentIndex - targetHorizonBars
        if (upperTrainingIndex < evaluationStartIndex) return prior

        val observations = mutableListOf<HierarchyObservation>()
        var trainingIndex = evaluationStartIndex
        while (trainingIndex <= upperTrainingIndex) {
            val raw = collectRawSignals(panel, trainingIndex, config, indicators, liquidityRanks)
            if (raw.isNotEmpty()) {
                val features = buildFeatureVectors(raw, config)
                val empiricalScores = raw.associate { candidate ->
                    candidate.symbol to empiricalWeights.score(features.getValue(candidate.symbol), config)
                }
                val anchorBetas = computeAnchorBetas(panel, trainingIndex, indicators.mediumBars)
                val cohorts = buildCohortMemberships(raw, anchorBetas, empiricalScores)
                val regime = computeRegime(panel, trainingIndex, config, indicators, liquidityRanks)
                val marketScale = median(empiricalScores.values.map { abs(it) }.filter { it.isFinite() && it > 1e-9 }.ifEmpty { listOf(0.0) })
                val empiricalRanks = centeredRanks(empiricalScores)
                val spreadRanks = centeredRanks(raw.associate { it.symbol to -(it.spreadBps) })
                val futureReturns = futureResidualReturns(panel, trainingIndex, targetHorizonBars)
                raw.forEach { candidate ->
                    val futureReturn = futureReturns[candidate.symbol] ?: return@forEach
                    val featureVector = features.getValue(candidate.symbol)
                    val hierarchy = hierarchyComponents(
                        empiricalScore = empiricalScores.getValue(candidate.symbol),
                        cohortMeanScore = cohorts.getValue(candidate.symbol).meanScore,
                        trend = featureVector.trend,
                        trendAgreement = featureVector.trendAgreement,
                        regimeScore = regime.score,
                        marketScale = marketScale
                    )
                    val provisionalConfidence = listOf(
                        abs(empiricalRanks.getValue(candidate.symbol)).coerceIn(0.0, 1.0),
                        featureVector.trendAgreement.coerceAtLeast(0.0),
                        featureVector.pullback.coerceIn(0.0, 1.0),
                        featureVector.expansion.coerceIn(0.0, 1.0),
                        featureVector.liquidity.coerceIn(0.0, 1.0),
                        ((spreadRanks.getValue(candidate.symbol) + 1.0) / 2.0).coerceIn(0.0, 1.0)
                    ).average().coerceIn(0.0, 1.0)
                    val assumedWeight = assumedTargetWeightFraction(provisionalConfidence, portfolioDefaults)
                    val entryCostFraction = estimateTransactionCostBps(
                        weightDelta = assumedWeight,
                        bar = candidate,
                        confidence = provisionalConfidence,
                        config = config
                    ) / 10_000.0
                    observations += HierarchyObservation(
                        marketComponent = hierarchy.marketComponent,
                        cohortComponent = hierarchy.cohortComponent,
                        residualComponent = hierarchy.residualComponent,
                        targetNetResidualReturn = futureReturn - entryCostFraction
                    )
                }
            }
            trainingIndex += rebalanceBars
        }

        if (observations.isEmpty()) return prior
        val fitted = fitHierarchyWeights(observations, config.empiricalFitRegularization, prior)
        val blend = (observations.size.toDouble() / config.empiricalMinTrainingObservations.toDouble()).coerceIn(0.0, 1.0)
        return normalizeHierarchyWeights(
            marketWeight = prior.marketWeight * (1.0 - blend) + fitted.marketWeight * blend,
            cohortWeight = prior.cohortWeight * (1.0 - blend) + fitted.cohortWeight * blend,
            residualWeight = prior.residualWeight * (1.0 - blend) + fitted.residualWeight * blend,
            observations = observations.size
        )
    }

    private fun fitHierarchyWeights(
        observations: List<HierarchyObservation>,
        lambda: Double,
        prior: HierarchyWeights
    ): HierarchyWeights {
        val gram = Array(3) { DoubleArray(3) }
        val rhs = DoubleArray(3)
        observations.forEach { observation ->
            val row = doubleArrayOf(
                observation.marketComponent,
                observation.cohortComponent,
                observation.residualComponent
            )
            for (left in row.indices) {
                rhs[left] += row[left] * observation.targetNetResidualReturn
                for (right in row.indices) {
                    gram[left][right] += row[left] * row[right]
                }
            }
        }
        val priorVector = doubleArrayOf(prior.marketWeight, prior.cohortWeight, prior.residualWeight)
        for (index in 0 until 3) {
            gram[index][index] += lambda
            rhs[index] += lambda * priorVector[index]
        }
        val solved = solveLinearSystem(gram, rhs) ?: return prior
        return normalizeHierarchyWeights(
            marketWeight = solved.getOrElse(0) { 0.0 }.coerceAtLeast(0.0),
            cohortWeight = solved.getOrElse(1) { 0.0 }.coerceAtLeast(0.0),
            residualWeight = solved.getOrElse(2) { 0.0 }.coerceAtLeast(0.0),
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
            regimes = regimes.takeLast(256)
        )
    }

    private fun buildPerformance(
        segment: String,
        snapshots: List<InterdayPortfolioSnapshot>,
        trades: List<InterdayTradeRecord>,
        splitTime: Instant?,
        beforeSplit: Boolean,
        signalBarMinutes: Int,
        searchPolicy: org.datamancy.trading.policy.AlphaSearchPolicy,
        regimeStrengthThreshold: Double
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
            killSwitchUtilizationMax = killSwitchUtilizationMax
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
        grossExposureFraction = snapshot.grossExposureFraction,
        longExposureFraction = snapshot.longExposureFraction,
        shortExposureFraction = snapshot.shortExposureFraction,
        netExposureFraction = snapshot.netExposureFraction,
        alignedExposureFraction = snapshot.alignedExposureFraction,
        wrongWayExposureFraction = snapshot.wrongWayExposureFraction,
        killSwitchUtilization = snapshot.killSwitchUtilization
    )

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
        killSwitchUtilizationMax = 0.0
    )

    private data class PanelCacheKey(
        val exchange: String,
        val signalBarMinutes: Int,
        val requiredHistoryHours: Int,
        val maxSymbols: Int
    )

    private data class IndicatorWindows(
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
        val funding: Double,
        val openInterest: Double,
        val expansion: Double,
        val reversalRisk: Double,
        val liquidity: Double
    ) {
        fun toArray(config: InterdayAlphaConfig): DoubleArray = doubleArrayOf(
            trend,
            trendAgreement,
            pullback * config.pullbackWeight.coerceAtLeast(0.05),
            funding * config.fundingWeight.coerceAtLeast(0.05),
            openInterest * config.openInterestWeight.coerceAtLeast(0.05),
            expansion,
            reversalRisk,
            liquidity
        )
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
        val expectedResidualReturnBps: Double,
        val expectedEntryCostBps: Double,
        val expectedTurnoverPenaltyBps: Double,
        val expectedNetEdgeBps: Double,
        val executionSupport: Double,
        val provisionalConfidence: Double,
        val cohortId: String,
        val marketComponentBps: Double,
        val cohortComponentBps: Double,
        val residualComponentBps: Double
    )

    data class CohortMembership(
        val id: String,
        val meanScore: Double
    )

    data class HierarchicalScore(
        val marketComponent: Double,
        val cohortComponent: Double,
        val residualComponent: Double,
        val totalScore: Double
    )

    data class HierarchyWeights(
        val marketWeight: Double,
        val cohortWeight: Double,
        val residualWeight: Double,
        val observations: Int = 0
    )

    data class HierarchyObservation(
        val marketComponent: Double,
        val cohortComponent: Double,
        val residualComponent: Double,
        val targetNetResidualReturn: Double
    )

    private data class RegimeState(
        val score: Double,
        val breadth: Double,
        val anchorTrend: Double,
        val dispersion: Double
    )

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

        fun trailingAdjustments(config: InterdayAlphaConfig, bar: InterdayBar, time: Instant): List<PositionAdjustment> {
            val adjustments = mutableListOf<PositionAdjustment>()
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
            config.takeProfitFractions.forEachIndexed { index, fraction ->
                if (index in takeProfitTriggered) return@forEachIndexed
                val trigger = config.takeProfitVolMultiple * config.takeProfitMultipliers.getOrElse(index) { 1.0 }
                val favorableMoveVol = when (direction) {
                    AlphaDirection.LONG -> ((peakPrice / averageEntryPrice) - 1.0) / entryVolatility
                    AlphaDirection.SHORT -> ((averageEntryPrice / troughPrice.coerceAtLeast(1e-9)) - 1.0) / entryVolatility
                }
                if (favorableMoveVol >= trigger) {
                    takeProfitTriggered += index
                    adjustments += PositionAdjustment(symbol, weightFraction * fraction, "take-profit-$index@$time")
                }
            }
            return adjustments
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

private fun computeAnchorBetas(
    panel: InterdayPanel,
    index: Int,
    lookbackBars: Int
): Map<String, Double> {
    val anchorSymbols = setOf("BTC", "ETH")
    val anchorSeries = panel.series.filter { it.symbol in anchorSymbols }
    if (anchorSeries.isEmpty()) return panel.series.associate { it.symbol to 0.0 }
    val start = (index - lookbackBars + 1).coerceAtLeast(1)
    val anchorReturns = (start..index).mapNotNull { cursor ->
        anchorSeries.mapNotNull { series -> logReturnAt(series.bars, cursor) }
            .takeIf { it.isNotEmpty() }
            ?.average()
    }
    if (anchorReturns.size < 4) return panel.series.associate { it.symbol to 0.0 }
    return panel.series.associate { series ->
        series.symbol to rollingBeta(series.bars, index, lookbackBars, anchorReturns)
    }
}

private fun logReturnAt(bars: List<InterdayBar?>, index: Int): Double? {
    if (index <= 0) return null
    val current = bars.getOrNull(index) ?: return null
    val previous = bars.getOrNull(index - 1) ?: return null
    if (current.close <= 0.0 || previous.close <= 0.0) return null
    return ln(current.close / previous.close)
}

private fun rollingBeta(
    bars: List<InterdayBar?>,
    index: Int,
    lookbackBars: Int,
    anchorReturns: List<Double>
): Double {
    val start = (index - lookbackBars + 1).coerceAtLeast(1)
    val pairs = (start..index).mapNotNull { cursor ->
        val symbolReturn = logReturnAt(bars, cursor) ?: return@mapNotNull null
        val anchorOffset = cursor - start
        val anchorReturn = anchorReturns.getOrNull(anchorOffset) ?: return@mapNotNull null
        symbolReturn to anchorReturn
    }
    if (pairs.size < 4) return 0.0
    val symbolMean = pairs.map { it.first }.average()
    val anchorMean = pairs.map { it.second }.average()
    val covariance = pairs.sumOf { (symbolValue, anchorValue) ->
        (symbolValue - symbolMean) * (anchorValue - anchorMean)
    } / pairs.size.toDouble()
    val variance = pairs.sumOf { (_, anchorValue) ->
        val centered = anchorValue - anchorMean
        centered * centered
    } / pairs.size.toDouble()
    if (variance <= 1e-10) return 0.0
    return (covariance / variance).coerceIn(-3.0, 3.0)
}

private fun buildCohortMemberships(
    raw: List<InterdaySearchEngine.RawSignal>,
    anchorBetas: Map<String, Double>,
    empiricalScores: Map<String, Double>
): Map<String, InterdaySearchEngine.CohortMembership> {
    val betaRanks = centeredRanks(raw.associate { it.symbol to (anchorBetas[it.symbol] ?: 0.0) })
    val volRanks = centeredRanks(raw.associate { it.symbol to it.volatility })
    val globalMean = empiricalScores.values.averageOrZero()
    val cohortIds = raw.associate { candidate ->
        val betaBucket = ternaryBucket(betaRanks.getValue(candidate.symbol))
        val volBucket = if (volRanks.getValue(candidate.symbol) >= 0.0) "hiVol" else "loVol"
        val liquidityBucket = if (candidate.liquidityRank >= 0.5) "liq" else "thin"
        candidate.symbol to "$betaBucket:$volBucket:$liquidityBucket"
    }
    val groupedScores = cohortIds.entries.groupBy({ it.value }, { empiricalScores.getValue(it.key) })
    val groupedCounts = cohortIds.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.size }
    return cohortIds.mapValues { (_, cohortId) ->
        val cohortMean = groupedScores[cohortId].orEmpty().averageOrZero()
        val count = groupedCounts[cohortId] ?: 0
        val shrinkage = count.toDouble() / (count.toDouble() + 3.0)
        InterdaySearchEngine.CohortMembership(
            id = cohortId,
            meanScore = globalMean + shrinkage * (cohortMean - globalMean)
        )
    }
}

private fun hierarchyComponents(
    empiricalScore: Double,
    cohortMeanScore: Double,
    trend: Double,
    trendAgreement: Double,
    regimeScore: Double,
    marketScale: Double
): InterdaySearchEngine.HierarchicalScore {
    val trendSign = when {
        trend > 1e-9 -> 1.0
        trend < -1e-9 -> -1.0
        else -> 0.0
    }
    val agreementScaler = 0.35 + 0.65 * trendAgreement.coerceIn(0.0, 1.0)
    val marketComponent = trendSign * regimeScore * marketScale * agreementScaler
    val cohortComponent = cohortMeanScore
    val residualComponent = empiricalScore - cohortMeanScore
    return InterdaySearchEngine.HierarchicalScore(
        marketComponent = marketComponent,
        cohortComponent = cohortComponent,
        residualComponent = residualComponent,
        totalScore = empiricalScore
    )
}

private fun hierarchicalScore(
    empiricalScore: Double,
    cohortMeanScore: Double,
    trend: Double,
    trendAgreement: Double,
    regimeScore: Double,
    marketScale: Double,
    marketWeight: Double,
    cohortWeight: Double,
    residualWeight: Double
): InterdaySearchEngine.HierarchicalScore {
    val components = hierarchyComponents(
        empiricalScore = empiricalScore,
        cohortMeanScore = cohortMeanScore,
        trend = trend,
        trendAgreement = trendAgreement,
        regimeScore = regimeScore,
        marketScale = marketScale
    )
    val totalScore = if (marketWeight <= 1e-9 && cohortWeight <= 1e-9) {
        empiricalScore
    } else {
        marketWeight * components.marketComponent +
            cohortWeight * components.cohortComponent +
            residualWeight * components.residualComponent
    }
    return InterdaySearchEngine.HierarchicalScore(
        marketComponent = components.marketComponent,
        cohortComponent = components.cohortComponent,
        residualComponent = if (marketWeight <= 1e-9 && cohortWeight <= 1e-9) empiricalScore else components.residualComponent,
        totalScore = totalScore
    )
}

private fun normalizeHierarchyWeights(
    marketWeight: Double,
    cohortWeight: Double,
    residualWeight: Double,
    observations: Int = 0
): InterdaySearchEngine.HierarchyWeights {
    val positiveMarket = marketWeight.coerceAtLeast(0.0)
    val positiveCohort = cohortWeight.coerceAtLeast(0.0)
    val positiveResidual = residualWeight.coerceAtLeast(0.0)
    val total = positiveMarket + positiveCohort + positiveResidual
    return if (total <= 1e-9) {
        InterdaySearchEngine.HierarchyWeights(0.0, 0.0, 1.0, observations)
    } else {
        InterdaySearchEngine.HierarchyWeights(
            marketWeight = positiveMarket / total,
            cohortWeight = positiveCohort / total,
            residualWeight = positiveResidual / total,
            observations = observations
        )
    }
}

private fun ternaryBucket(value: Double): String = when {
    value <= -0.33 -> "lowBeta"
    value >= 0.33 -> "highBeta"
    else -> "midBeta"
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
