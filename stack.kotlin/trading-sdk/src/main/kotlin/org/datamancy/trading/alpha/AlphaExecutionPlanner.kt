package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy
import kotlin.math.abs
import kotlin.math.ceil

class AlphaExecutionPlanner(
    private val policyProvider: () -> TradingPolicy
) {
    fun defaults(): AlphaExecutionDefaults = AlphaDefaultsFactory.executionDefaults(policyProvider())

    fun plan(request: AlphaExecutionPlanRequest): AlphaExecutionPlan {
        require(request.targets.isNotEmpty()) { "execution planning requires at least one portfolio target" }
        val policy = policyProvider()
        val defaults = AlphaDefaultsFactory.executionDefaults(policy)
        val executionWindowMinutes = request.executionWindowMinutes
            ?: policy.research.discovery.executionWindowMinutes.firstOrNull()
            ?: 120
        val participationRate = (request.targetParticipationRate ?: defaults.maxParticipationRate)
            .coerceIn(0.005, defaults.maxParticipationRate)
        val step = defaults.rebalanceTargetExposureStep.coerceAtLeast(0.01)

        val childOrders = request.targets.flatMap { target ->
            val style = chooseStyle(target, request.defaultSpreadBps, request.defaultImpactBps)
            val slices = ceil(abs(target.weightFraction) / step).toInt().coerceAtLeast(1)
            val schedule = sliceSchedule(style, slices)
            schedule.mapIndexed { index, fraction ->
                val makerShare = makerShare(style)
                val feeBps = defaults.makerFeeBps * makerShare + defaults.takerFeeBps * (1.0 - makerShare)
                AlphaExecutionChildOrder(
                    symbol = target.symbol,
                    direction = target.direction,
                    style = style,
                    sequence = index + 1,
                    weightFraction = abs(target.weightFraction) * fraction,
                    expectedFeeBps = feeBps,
                    expectedSpreadCostBps = request.defaultSpreadBps * (if (style == AlphaExecutionStyle.MAKER_RAMP) 0.35 else 0.5),
                    expectedImpactBps = request.defaultImpactBps * (1.0 / slices.toDouble()),
                    triggerMinutesFromStart = ((executionWindowMinutes.toDouble() * index) / slices).toInt()
                )
            }
        }
        val totalWeight = childOrders.sumOf { it.weightFraction }.coerceAtLeast(0.0001)
        val estimatedCostBps = childOrders.sumOf {
            val weight = it.weightFraction / totalWeight
            weight * (it.expectedFeeBps + it.expectedSpreadCostBps + it.expectedImpactBps)
        }
        return AlphaExecutionPlan(
            mode = request.mode,
            executionWindowMinutes = executionWindowMinutes,
            participationRate = participationRate,
            childOrders = childOrders,
            estimatedCostBps = estimatedCostBps,
            estimatedMakerShare = childOrders.map { makerShare(it.style) }.average(),
            notes = listOf(
                "Higher-confidence targets ramp in gradually via larger later slices instead of immediate full leverage.",
                "Execution style is conditioned separately from the alpha signal so historical price-model validation can scale farther back than fill validation."
            )
        )
    }

    private fun chooseStyle(target: AlphaPortfolioTarget, spreadBps: Double, impactBps: Double): AlphaExecutionStyle {
        val confidence = target.confidence.coerceIn(0.0, 1.0)
        return when {
            confidence >= 0.80 && spreadBps <= 6.0 -> AlphaExecutionStyle.MAKER_RAMP
            confidence >= 0.60 && impactBps <= 5.0 -> AlphaExecutionStyle.TWAP
            confidence >= 0.40 -> AlphaExecutionStyle.POV
            else -> AlphaExecutionStyle.VWAP_TRACK
        }
    }

    private fun sliceSchedule(style: AlphaExecutionStyle, slices: Int): List<Double> {
        if (slices == 1) return listOf(1.0)
        return when (style) {
            AlphaExecutionStyle.MAKER_RAMP -> {
                val denom = (1..slices).sum().toDouble()
                (1..slices).map { it / denom }
            }
            AlphaExecutionStyle.TWAP,
            AlphaExecutionStyle.POV,
            AlphaExecutionStyle.VWAP_TRACK -> List(slices) { 1.0 / slices.toDouble() }
        }
    }

    private fun makerShare(style: AlphaExecutionStyle): Double = when (style) {
        AlphaExecutionStyle.MAKER_RAMP -> 0.85
        AlphaExecutionStyle.TWAP -> 0.55
        AlphaExecutionStyle.POV -> 0.35
        AlphaExecutionStyle.VWAP_TRACK -> 0.25
    }
}
