package org.datamancy.trading.analytics.crosssectional

import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
        var latestFundingRate: Double? = null
        var latestOpenInterest: Double? = null
        var previousFundingRate = 0.0
        var previousOpenInterest = 0.0
        var previousOiChange = 0.0
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
            if (bar.assetContextObserved) {
                if (bar.fundingRate != null) latestFundingRate = bar.fundingRate
                if (bar.openInterest != null) latestOpenInterest = max(bar.openInterest, 0.0)
            }
            val fundingRate = latestFundingRate ?: 0.0
            val openInterest = max(latestOpenInterest ?: 0.0, 0.0)
            val openInterestNotionalUsd = openInterest * max(midPrice, 0.0)
            val oiChange = if (previousOpenInterest > 0.0 && openInterest > 0.0) {
                clamp((openInterest / previousOpenInterest) - 1.0, -0.95, 8.0)
            } else {
                0.0
            }
            val oiAcceleration = clamp(oiChange - previousOiChange, -1.5, 1.5)
            val spreadBps = if (executionObserved) rawSpreadBps else executionProxy.spreadBps
            val spreadPct = if (executionObserved) max(bar.spreadPct, 0.0) else executionProxy.spreadBps / 10000.0
            val bidDepth10 = if (executionObserved) max(bar.bidDepth10, 0.0) else proxyDepthUnits / 2.0
            val askDepth10 = if (executionObserved) max(bar.askDepth10, 0.0) else proxyDepthUnits / 2.0
            val depthUsd = if (executionObserved) rawDepthUsd else proxyDepthUsd
            val fundingChange = clamp(fundingRate - previousFundingRate, -0.02, 0.02)
            previousFundingRate = fundingRate
            previousOpenInterest = openInterest
            previousOiChange = oiChange
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
                executionObserved = executionObserved,
                fundingRate = fundingRate,
                fundingChange = fundingChange,
                openInterest = openInterest,
                openInterestNotionalUsd = openInterestNotionalUsd,
                oiChange = oiChange,
                oiAcceleration = oiAcceleration,
                assetContextObserved = bar.assetContextObserved
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
                executionObserved = point.executionObserved,
                fundingRate = point.fundingRate,
                fundingChange = point.fundingChange,
                openInterest = point.openInterest,
                openInterestNotionalUsd = point.openInterestNotionalUsd,
                oiChange = point.oiChange,
                oiAcceleration = point.oiAcceleration,
                assetContextObserved = point.assetContextObserved
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
        val crowdingMetricsByRow = computeCrowdingContextMetrics(
            bucket = bucket,
            comparableCohorts = comparableCohorts,
            minimumCohortSize = minimumCohortSize
        )

        fun trendReference(row: UnrankedFeature): Double =
            if (abs(row.mediumTrendScore) > abs(row.rawTrend)) row.mediumTrendScore else row.rawTrend

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
            val reference = trendReference(row)
            val flowAlignment = direction(reference) * row.flowSignal
            val breadthAlignment = direction(reference) * breadthTilt
            val crowdingMetrics = crowdingMetricsByRow[row] ?: CrowdingContextMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val participation = crowdingMetrics.participationScore
            val crowding = crowdingMetrics.crowdingScore
            row.rawTrend +
                (row.mediumTrendScore * 0.85) +
                (mediumDirection * max(0.0, row.trendPersistence - 0.5) * 0.70) +
                (mediumDirection * min(row.trendPullback, 1.25) * 0.18) +
                (breadthAlignment * 0.9) +
                (max(0.0, flowAlignment) * 0.65) -
                (max(0.0, -flowAlignment) * 1.0) +
                (max(0.0, participation) * 0.58) -
                (max(0.0, -participation) * 0.82) +
                (max(0.0, crowding) * 0.28) -
                (max(0.0, -crowding) * 0.45) +
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
            val breadthAlignment = direction(trendReference(row)) * breadthTilt
            val crowdingMetrics = crowdingMetricsByRow[row] ?: CrowdingContextMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val participation = crowdingMetrics.participationScore
            val crowding = crowdingMetrics.crowdingScore
            clamp(
                row.trendExpectedGrossEdgeBps +
                    (max(0.0, participation) * 6.5) -
                    (max(0.0, -participation) * 8.0) +
                    (max(0.0, crowding) * 3.5) -
                    (max(0.0, -crowding) * 5.0) +
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
                executionObserved = row.executionObserved,
                fundingRate = row.fundingRate,
                fundingZ = (crowdingMetricsByRow[row]?.fundingZ ?: 0.0).round(6),
                fundingChangeZ = (crowdingMetricsByRow[row]?.fundingChangeZ ?: 0.0).round(6),
                openInterest = row.openInterest,
                openInterestNotionalUsd = row.openInterestNotionalUsd.round(4),
                oiChange = row.oiChange.round(6),
                oiChangeZ = (crowdingMetricsByRow[row]?.oiChangeZ ?: 0.0).round(6),
                oiAccelerationZ = (crowdingMetricsByRow[row]?.oiAccelerationZ ?: 0.0).round(6),
                oiNotionalZ = (crowdingMetricsByRow[row]?.oiNotionalZ ?: 0.0).round(6),
                crowdingScore = (crowdingMetricsByRow[row]?.crowdingScore ?: 0.0).round(6),
                participationScore = (crowdingMetricsByRow[row]?.participationScore ?: 0.0).round(6),
                assetContextObserved = row.assetContextObserved
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

