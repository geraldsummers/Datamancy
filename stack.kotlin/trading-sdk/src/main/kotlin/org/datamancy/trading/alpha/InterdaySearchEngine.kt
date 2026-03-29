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
        val panelCache = mutableMapOf<PanelCacheKey, InterdayPanel>()
        val evaluations = configs.map { config ->
            val key = panelKeyFor(config)
            val panel = panelCache[key] ?: loadPanel(config).also { panelCache[key] = it }
            evaluate(config = config, panel = panel, mode = AlphaRunMode.OFFLINE_BACKTEST, includeInspection = false)
        }
        val ranked = evaluations
            .sortedWith(
                compareByDescending<EvaluationBundle> { it.validation.accepted }
                    .thenByDescending { it.forward.edgeAfterCostBps }
                    .thenByDescending { it.forward.netReturnPct }
                    .thenByDescending { it.backtest.sharpe }
                    .thenBy { it.forward.maxDrawdownPct }
            )
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
                "Execution conditioning is attached as a cost and liquidity model so long-range signal research can run beyond shallow orderbook history."
            )
        )
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
                "Entries require the symbol to sit on a universe edge and show a local counter-trend perturbation before size is added."
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
                                                                                                            executionWindowMinutes = executionWindow
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
        val liquidityRanks = precomputeLiquidityRanks(panel)
        val seriesBySymbol = panel.seriesBySymbol()
        val currentWeights = mutableMapOf<String, Double>()
        val activePositions = mutableMapOf<String, ActivePosition>()
        val trades = mutableListOf<InterdayTradeRecord>()
        val snapshots = mutableListOf<InterdayPortfolioSnapshot>()
        val signalHistory = mutableMapOf<String, MutableList<InterdayInspectionPoint>>()
        val weightTimeline = mutableMapOf<Instant, Map<String, Double>>()
        var equity = 1.0
        var grossAccumulator = 0.0
        var latestSignals = emptyList<InterdaySignalSnapshot>()
        var latestTargets = emptyList<AlphaPortfolioTarget>()
        var latestDesiredWeights = emptyMap<String, Double>()
        var latestSignalsBySymbol = emptyMap<String, InterdaySignalSnapshot>()

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
                snapshots += portfolioSnapshot(time, equity, currentWeights, turnoverFraction = 0.0)
                continue
            }

            val rebalanceNow = (index - evaluationStartIndex) % rebalanceBars == 0
            var turnoverFraction = 0.0
            if (rebalanceNow) {
                val signals = computeSignals(panel, index, config, indicators, liquidityRanks)
                latestSignals = signals
                    .sortedByDescending { abs(it.score) }
                    .take(32)
                latestSignalsBySymbol = signals.associateBy { it.symbol }
                if (includeInspection) {
                    signals.forEach { signal ->
                        signalHistory.getOrPut(signal.symbol) { mutableListOf() } += InterdayInspectionPoint(
                            time = time,
                            close = signal.close,
                            score = signal.score,
                            upperBound = signal.upperBound,
                            lowerBound = signal.lowerBound,
                            positionWeight = 0.0
                        )
                    }
                }
                val eligible = signals.filter {
                    val trendAgreementOk = it.trendAgreement >= config.minTrendAgreement
                    val pullbackOk = it.pullbackScore >= config.perturbationThresholdZ
                    when (it.direction) {
                        AlphaDirection.LONG -> it.score >= it.upperBound && it.confidence >= config.minConfidence && trendAgreementOk && pullbackOk
                        AlphaDirection.SHORT -> it.score <= it.lowerBound && it.confidence >= config.minConfidence && trendAgreementOk && pullbackOk
                    }
                }
                val portfolioSignals = eligible.map {
                    AlphaSignalScore(
                        symbol = it.symbol,
                        score = if (it.direction == AlphaDirection.LONG) abs(it.score) else -abs(it.score),
                        confidence = it.confidence,
                        predictedVolatility = it.predictedVolatility.coerceAtLeast(0.0001),
                        liquidityScore = it.liquidityScore
                    )
                }
                latestTargets = if (portfolioSignals.isEmpty()) {
                    emptyList()
                } else {
                    val constructed = portfolioConstructor.construct(
                        AlphaPortfolioRequest(
                            signals = portfolioSignals,
                            selectionQuantile = config.selectionQuantile,
                            respectProvidedSignalSet = true
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
            }

            val shouldAdjustBetweenRebalances = when (config.adjustmentMode) {
                InterdayAdjustmentMode.REBALANCE_STEP -> rebalanceNow
                InterdayAdjustmentMode.CONTINUOUS_RAMP -> latestDesiredWeights.isNotEmpty() || currentWeights.isNotEmpty()
            }
            if (shouldAdjustBetweenRebalances) {
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
                    val plannedDelta = gradualDelta(currentSigned, targetSigned, step)
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
                        reason = if (rebalanceNow) "rebalance" else "continuous-ramp",
                        splitTime = splitTime
                    )
                    turnoverFraction += abs(plannedDelta)
                }
                if (includeInspection) {
                    weightTimeline[time] = currentWeights.toMap()
                }
            }
            snapshots += portfolioSnapshot(time, equity, currentWeights, turnoverFraction)
        }

        val backtest = buildPerformance("backtest", snapshots, trades, splitTime, beforeSplit = true, signalBarMinutes = config.signalBarMinutes)
        val forward = buildPerformance("forward", snapshots, trades, splitTime, beforeSplit = false, signalBarMinutes = config.signalBarMinutes)
        val validation = validate(config, backtest, forward, searchPolicy)
        val inspection = if (includeInspection) {
            buildInspection(
                snapshots = snapshots,
                signalHistory = signalHistory,
                weightTimeline = weightTimeline,
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
        indicators: IndicatorWindows,
        liquidityRanks: Map<String, Double>
    ): List<InterdaySignalSnapshot> {
        val raw = panel.series.mapNotNull { series ->
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
        if (raw.isEmpty()) return emptyList()
        val fastRanks = centeredRanks(raw.associate { it.symbol to it.fastReturn })
        val mediumRanks = centeredRanks(raw.associate { it.symbol to it.mediumReturn })
        val slowRanks = centeredRanks(raw.associate { it.symbol to it.slowReturn })
        val slopeRanks = centeredRanks(raw.associate { it.symbol to it.slope })
        val maRanks = centeredRanks(raw.associate { it.symbol to it.maSpread })
        val adxRanks = centeredRanks(raw.associate { it.symbol to it.adx })
        val fundingRanks = centeredRanks(raw.associate { it.symbol to it.fundingRate })
        val oiRanks = centeredRanks(raw.associate { it.symbol to it.openInterestMomentum })
        val spreadRanks = centeredRanks(raw.associate { it.symbol to -(it.spreadBps) })

        val preScores = raw.associate { candidate ->
            val baseTrend = (
                0.23 * fastRanks.getValue(candidate.symbol) +
                    0.32 * mediumRanks.getValue(candidate.symbol) +
                    0.22 * slowRanks.getValue(candidate.symbol) +
                    config.slopeWeight * slopeRanks.getValue(candidate.symbol) +
                    0.14 * maRanks.getValue(candidate.symbol) +
                    0.09 * adxRanks.getValue(candidate.symbol)
                )
            val trendDirection = if (baseTrend >= 0.0) 1.0 else -1.0
            val pullbackSupport = -trendDirection * candidate.perturbation
            val pullbackAdj = config.pullbackWeight * pullbackSupport.coerceAtLeast(0.0)
            val carryAdj = -config.fundingWeight * fundingRanks.getValue(candidate.symbol)
            val oiAdj = config.openInterestWeight * trendDirection * oiRanks.getValue(candidate.symbol)
            val alignmentInputs = listOf(
                trendDirection * fastRanks.getValue(candidate.symbol),
                trendDirection * mediumRanks.getValue(candidate.symbol),
                trendDirection * slowRanks.getValue(candidate.symbol),
                trendDirection * slopeRanks.getValue(candidate.symbol),
                trendDirection * maRanks.getValue(candidate.symbol)
            )
            val trendAgreement = alignmentInputs.average().coerceIn(-1.0, 1.0)
            candidate.symbol to SignalTotals(
                trend = baseTrend,
                trendAgreement = trendAgreement,
                pullbackSupport = pullbackSupport,
                pullback = pullbackAdj,
                funding = carryAdj,
                openInterest = oiAdj,
                liquidity = (candidate.liquidityRank + spreadRanks.getValue(candidate.symbol)) / 2.0,
                total = baseTrend + 0.12 * trendAgreement.coerceAtLeast(0.0) + pullbackAdj + carryAdj + oiAdj
            )
        }
        val totalRanks = centeredRanks(preScores.mapValues { it.value.total })
        val sortedScores = totalRanks.values.sorted()
        val lowerBound = quantile(sortedScores, config.selectionQuantile)
        val upperBound = quantile(sortedScores, 1.0 - config.selectionQuantile)

        return raw.map { candidate ->
            val totals = preScores.getValue(candidate.symbol)
            val totalScore = totalRanks.getValue(candidate.symbol)
            val direction = if (totalScore >= 0.0) AlphaDirection.LONG else AlphaDirection.SHORT
            val adxSupport = ((candidate.adx - config.adxThreshold) / max(config.adxThreshold, 1.0)).coerceIn(-1.0, 1.0)
            val executionSupport = if (!config.useExecutionConditioning) {
                1.0
            } else {
                (candidate.orderbookObservedRatio * 0.6 + candidate.tradeObservedRatio * 0.4).coerceIn(0.0, 1.0)
            }
            val confidence = (
                0.38 * abs(totalScore).coerceIn(0.0, 1.0) +
                    0.18 * candidate.liquidityRank +
                    0.15 * spreadRanks.getValue(candidate.symbol).coerceIn(-1.0, 1.0).let { (it + 1.0) / 2.0 } +
                    0.12 * totals.trendAgreement.coerceAtLeast(0.0) +
                    0.10 * (totals.pullbackSupport / config.perturbationThresholdZ.coerceAtLeast(0.25)).coerceIn(0.0, 1.0) +
                    0.15 * adxSupport.coerceAtLeast(0.0) +
                    0.07 * executionSupport
                ).coerceIn(0.0, 1.0)
            InterdaySignalSnapshot(
                symbol = candidate.symbol,
                direction = direction,
                score = totalScore,
                confidence = confidence,
                liquidityScore = candidate.liquidityRank.coerceIn(0.1, 1.0),
                trendScore = totals.trend,
                trendAgreement = totals.trendAgreement,
                pullbackScore = totals.pullbackSupport,
                fundingScore = totals.funding,
                openInterestScore = totals.openInterest,
                upperBound = upperBound,
                lowerBound = lowerBound,
                close = candidate.close,
                predictedVolatility = candidate.volatility
            )
        }
    }

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
                forward.maxDrawdownPct <= searchPolicy.maxSearchDrawdownPct
        if (!forwardAccepted) {
            if (forward.tradeCount < searchPolicy.minForwardTrades) reasons += "forward trade count ${forward.tradeCount} < ${searchPolicy.minForwardTrades}"
            if (forward.edgeAfterCostBps < 0.0) reasons += "forward edgeAfterCostBps ${format(forward.edgeAfterCostBps)} < 0"
            if (forward.maxDrawdownPct > searchPolicy.maxSearchDrawdownPct) reasons += "forward drawdown ${format(forward.maxDrawdownPct)} > ${searchPolicy.maxSearchDrawdownPct}"
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
        signalHistory: Map<String, MutableList<InterdayInspectionPoint>>,
        weightTimeline: Map<Instant, Map<String, Double>>,
        topSymbols: List<String>
    ): InterdayInspection {
        val symbolSeries = topSymbols.distinct().mapNotNull { symbol ->
            val points = signalHistory[symbol].orEmpty()
                .takeLast(128)
                .map { point ->
                    point.copy(positionWeight = weightTimeline[point.time]?.get(symbol) ?: 0.0)
                }
            if (points.isEmpty()) null else InterdaySymbolInspection(symbol = symbol, points = points)
        }
        return InterdayInspection(
            portfolio = snapshots.takeLast(256),
            symbols = symbolSeries
        )
    }

    private fun buildPerformance(
        segment: String,
        snapshots: List<InterdayPortfolioSnapshot>,
        trades: List<InterdayTradeRecord>,
        splitTime: Instant?,
        beforeSplit: Boolean,
        signalBarMinutes: Int
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
            return InterdayPerformance(
                segment = segment,
                startTime = segmentSnapshots.firstOrNull()?.time,
                endTime = segmentSnapshots.lastOrNull()?.time,
                netReturnPct = 0.0,
                grossReturnPct = 0.0,
                sharpe = 0.0,
                maxDrawdownPct = 0.0,
                tradeCount = segmentTrades.size,
                winRate = if (segmentTrades.isEmpty()) 0.0 else segmentTrades.count { it.pnlPct > 0.0 }.toDouble() / segmentTrades.size.toDouble(),
                avgTurnoverPct = 0.0,
                edgeAfterCostBps = 0.0,
                bootstrapReturnP05Pct = 0.0,
                bootstrapSharpeP05 = 0.0,
                stabilityScore = 0.0
            )
        }
        val startEquity = segmentSnapshots.first().equity
        val endEquity = segmentSnapshots.last().equity
        val returns = segmentSnapshots.zipWithNext { left, right -> ln(right.equity / left.equity) }
        val sharpe = annualizedSharpe(returns, signalBarMinutes)
        val maxDrawdownPct = maxDrawdownPct(segmentSnapshots.map { it.equity })
        val avgTurnoverPct = segmentSnapshots.map { it.turnoverFraction }.average() * 100.0
        val netReturnPct = ((endEquity / startEquity) - 1.0) * 100.0
        val bootstrap = bootstrapStatistics(returns, signalBarMinutes)
        val tradeCount = segmentTrades.size
        val winRate = if (segmentTrades.isEmpty()) 0.0 else segmentTrades.count { it.pnlPct > 0.0 }.toDouble() / segmentTrades.size.toDouble()
        val edgeAfterCostBps = if (tradeCount == 0) 0.0 else (netReturnPct * 100.0) / tradeCount.toDouble()
        return InterdayPerformance(
            segment = segment,
            startTime = segmentSnapshots.first().time,
            endTime = segmentSnapshots.last().time,
            netReturnPct = netReturnPct,
            grossReturnPct = returns.sum() * 100.0,
            sharpe = sharpe,
            maxDrawdownPct = maxDrawdownPct,
            tradeCount = tradeCount,
            winRate = winRate,
            avgTurnoverPct = avgTurnoverPct,
            edgeAfterCostBps = edgeAfterCostBps,
            bootstrapReturnP05Pct = bootstrap.returnP05Pct,
            bootstrapSharpeP05 = bootstrap.sharpeP05,
            stabilityScore = (bootstrap.sharpeP05.coerceAtLeast(0.0) + sharpe.coerceAtLeast(0.0) + winRate) / 3.0
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
    ): Double {
        val defaults = executionPlanner.defaults()
        val makerShare = when {
            confidence >= 0.80 -> 0.80
            confidence >= 0.60 -> 0.55
            else -> 0.35
        }
        val feeBps = defaults.makerFeeBps * makerShare + defaults.takerFeeBps * (1.0 - makerShare)
        val spreadBps = (bar.spreadBps ?: 8.0) * if (confidence >= 0.75) 0.35 else 0.50
        val impactBps = estimateImpactBps(weightDelta, config.capitalUsd, bar.depthUsd ?: 0.0)
        val adverseSelectionBps = spreadBps * if (bar.orderbookObservedRatio >= 0.6) 0.08 else 0.16
        val fundingDriftBps = abs(bar.fundingRate ?: 0.0) * 10_000.0 * (config.rebalanceCadenceHours / 8.0)
        val totalBps = feeBps + spreadBps + impactBps + adverseSelectionBps + fundingDriftBps
        return (weightDelta * totalBps / 10_000.0).coerceAtLeast(0.0)
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
        currentWeights: Map<String, Double>,
        turnoverFraction: Double
    ): InterdayPortfolioSnapshot {
        val gross = currentWeights.values.sumOf { abs(it) }
        val net = currentWeights.values.sum()
        return InterdayPortfolioSnapshot(
            time = time,
            equity = equity,
            grossExposureFraction = gross,
            netExposureFraction = net,
            openPositions = currentWeights.count { abs(it.value) > 1e-9 },
            turnoverFraction = turnoverFraction
        )
    }

    private fun requiredHistoryHours(config: InterdayAlphaConfig): Int {
        val indicatorHours = max(
            max(config.lookbackHours, config.forwardHours),
            max(max(config.slowTrendDays, config.regressionDays), max(config.volatilityDays, config.adxDays)) * 24
        )
        return indicatorHours + config.rebalanceCadenceHours * 3
    }

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

    private data class RawSignal(
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

private fun barsForDays(days: Int, signalBarMinutes: Int): Int =
    max(1, ceil((days * 24.0 * 60.0) / signalBarMinutes.toDouble()).toInt())

private fun barsForHours(hours: Int, signalBarMinutes: Int): Int =
    max(1, ceil((hours * 60.0) / signalBarMinutes.toDouble()).toInt())

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
