package org.datamancy.trading.alpha

import org.datamancy.trading.policy.AlphaSearchPolicy
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class InterdayThresholdCalibrator {
    fun calibrate(
        evaluations: List<InterdayCandidateEvaluation>,
        currentPolicy: AlphaSearchPolicy,
        request: InterdayThresholdCalibrationRequest
    ): InterdayThresholdCalibrationResult {
        val valid = evaluations.filterNot(::evaluationFailed)
        if (valid.isEmpty()) {
            return InterdayThresholdCalibrationResult(
                currentPolicy = currentPolicy,
                recommendedPolicy = currentPolicy,
                selectedMinNetEdgeBps = currentPolicy.minNetEdgeBps,
                sourceCandidates = 0,
                acceptedCandidatesAtSelectedThreshold = 0,
                thresholdSweep = emptyList(),
                notes = listOf("Calibration skipped because no completed candidate evaluations were available.")
            )
        }

        val thresholds = thresholdGrid(valid, currentPolicy, request)
        val sweep = thresholds.map { threshold ->
            summarizeThreshold(
                threshold = threshold,
                evaluations = valid,
                currentPolicy = currentPolicy,
                request = request
            )
        }
        val selected = sweep
            .filter { it.feasible }
            .maxWithOrNull(
                compareBy<InterdayThresholdCalibrationPoint>(
                    { it.medianForwardEdgeBps },
                    { it.positiveForwardRatio },
                    { it.acceptedCandidates },
                    { it.minNetEdgeBps }
                )
            )

        val recommendedThreshold = selected?.minNetEdgeBps ?: currentPolicy.minNetEdgeBps
        val recommendedPolicy = currentPolicy.copy(minNetEdgeBps = recommendedThreshold)
        val notes = mutableListOf<String>()
        if (selected == null) {
            notes += "No threshold on the evaluated grid satisfied the minimum accepted-candidate and forward-quality requirements; keeping the current minNetEdgeBps."
        } else {
            notes += "Recommended minNetEdgeBps is chosen from the evaluated threshold grid using median forward edge as the primary objective, then forward positive ratio, accepted breadth, and stricter threshold as tie-breakers."
            notes += "Calibration changes only minNetEdgeBps. All other forward and risk gates remain frozen."
        }
        notes += "Calibration should be run on prior search windows and then frozen into policy for the next search/promotion cycle."

        return InterdayThresholdCalibrationResult(
            currentPolicy = currentPolicy,
            recommendedPolicy = recommendedPolicy,
            selectedMinNetEdgeBps = recommendedThreshold,
            sourceCandidates = valid.size,
            acceptedCandidatesAtSelectedThreshold = selected?.acceptedCandidates ?: 0,
            thresholdSweep = sweep,
            notes = notes
        )
    }

    private fun thresholdGrid(
        evaluations: List<InterdayCandidateEvaluation>,
        currentPolicy: AlphaSearchPolicy,
        request: InterdayThresholdCalibrationRequest
    ): List<Double> {
        val explicit = request.thresholdGridBps
            .mapNotNull { value -> value.takeIf { it.isFinite() && it >= 0.0 }?.let(::roundBps) }
            .sorted()
            .distinct()
        if (explicit.isNotEmpty()) {
            return (explicit + roundBps(currentPolicy.minNetEdgeBps)).distinct().sorted()
        }
        val step = request.thresholdStepBps.coerceAtLeast(0.05)
        val observedMin = evaluations.minOf { it.backtest.edgeAfterCostBps }
        val observedMax = evaluations.maxOf { it.backtest.edgeAfterCostBps }
        val lower = request.minThresholdBps ?: floorToStep(min(observedMin, currentPolicy.minNetEdgeBps), step).coerceAtLeast(0.0)
        val upper = request.maxThresholdBps ?: ceilToStep(max(observedMax, currentPolicy.minNetEdgeBps), step)
        if (upper < lower) {
            return listOf(roundBps(currentPolicy.minNetEdgeBps))
        }
        val thresholds = mutableListOf<Double>()
        var cursor = lower
        while (cursor <= upper + 1e-9) {
            thresholds += roundBps(cursor)
            cursor += step
        }
        thresholds += roundBps(currentPolicy.minNetEdgeBps)
        return thresholds.distinct().sorted()
    }

    private fun summarizeThreshold(
        threshold: Double,
        evaluations: List<InterdayCandidateEvaluation>,
        currentPolicy: AlphaSearchPolicy,
        request: InterdayThresholdCalibrationRequest
    ): InterdayThresholdCalibrationPoint {
        val edgeEligible = evaluations.filter {
            passesBacktestNonEdgeGates(it.backtest, currentPolicy) &&
                passesForwardGates(it.forward, currentPolicy)
        }
        val accepted = edgeEligible.filter { it.backtest.edgeAfterCostBps >= threshold }
        val positiveForwardRatio = if (accepted.isEmpty()) 0.0 else {
            accepted.count { it.forward.edgeAfterCostBps >= 0.0 }.toDouble() / accepted.size.toDouble()
        }
        val feasible =
            accepted.size >= request.minAcceptedCandidates &&
                positiveForwardRatio >= request.minForwardPositiveRatio &&
                median(accepted.map { it.forward.edgeAfterCostBps }) >= request.minMedianForwardEdgeBps
        return InterdayThresholdCalibrationPoint(
            minNetEdgeBps = threshold,
            acceptedCandidates = accepted.size,
            edgeOnlyRejectCount = edgeEligible.size - accepted.size,
            positiveForwardRatio = positiveForwardRatio,
            medianBacktestEdgeBps = median(accepted.map { it.backtest.edgeAfterCostBps }),
            medianForwardEdgeBps = median(accepted.map { it.forward.edgeAfterCostBps }),
            medianForwardCalmar = median(accepted.map { it.forward.calmar }),
            medianForwardTrades = median(accepted.map { it.forward.tradeCount.toDouble() }),
            feasible = feasible
        )
    }

    private fun passesBacktestNonEdgeGates(
        backtest: InterdayPerformance,
        policy: AlphaSearchPolicy
    ): Boolean =
        backtest.tradeCount >= policy.minBacktestTrades &&
            backtest.maxDrawdownPct <= policy.maxSearchDrawdownPct

    private fun passesForwardGates(
        forward: InterdayPerformance,
        policy: AlphaSearchPolicy
    ): Boolean =
        forward.tradeCount >= policy.minForwardTrades &&
            forward.edgeAfterCostBps >= 0.0 &&
            forward.maxDrawdownPct <= policy.maxSearchDrawdownPct &&
            forward.calmar >= policy.minForwardCalmar &&
            forward.timeUnderWaterPct <= policy.maxTimeUnderWaterPct &&
            kotlin.math.abs(forward.cvar1dPct) <= policy.maxCvar1dPct &&
            forward.alignedParticipationRate >= policy.minAlignedParticipationRate &&
            forward.wrongWayExposurePct <= policy.maxWrongWayExposurePct &&
            forward.killSwitchUtilizationMax <= policy.maxKillSwitchUtilization

    private fun evaluationFailed(candidate: InterdayCandidateEvaluation): Boolean =
        candidate.validation.reasons.any { it.startsWith("evaluation failed:") }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun floorToStep(value: Double, step: Double): Double = floor(value / step) * step

    private fun ceilToStep(value: Double, step: Double): Double = ceil(value / step) * step

    private fun roundBps(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
}
