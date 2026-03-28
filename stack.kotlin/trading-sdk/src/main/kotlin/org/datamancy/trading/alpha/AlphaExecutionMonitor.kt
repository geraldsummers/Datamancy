package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy
import kotlin.math.abs

class AlphaExecutionMonitor(
    private val policyProvider: () -> TradingPolicy
) {
    fun summarize(request: AlphaExecutionMonitorRequest): AlphaExecutionMonitorSummary {
        require(request.fills.isNotEmpty()) { "execution monitoring requires at least one fill" }
        val policy = policyProvider()
        val plannedNotional = request.fills.sumOf { abs(it.plannedWeightFraction) }.coerceAtLeast(0.0001)
        val executedNotional = request.fills.sumOf { abs(it.filledWeightFraction) }
        val fillRatio = executedNotional / plannedNotional
        val slippage = request.fills.map { fill ->
            val raw = when (fill.direction) {
                AlphaDirection.LONG -> (fill.executedPrice / fill.expectedPrice) - 1.0
                AlphaDirection.SHORT -> (fill.expectedPrice / fill.executedPrice) - 1.0
            }
            raw * 10_000.0
        }
        val feeBurnBps = request.fills.map { it.feeBps }.average()
        val latencies = request.fills.map { it.latencyMs }.sorted()
        val alerts = mutableListOf<String>()
        if (fillRatio < policy.risk.fillRatioFloor) {
            alerts += "Fill ratio below floor ${policy.risk.fillRatioFloor}."
        }
        if (slippage.average() > policy.risk.slippageDriftBps) {
            alerts += "Average slippage exceeded drift budget ${policy.risk.slippageDriftBps}bps."
        }
        if (latencyPercentile(latencies, 0.95) > policy.research.readiness.execution.maxLatencyDriftMs) {
            alerts += "Latency p95 exceeded execution readiness drift budget."
        }
        return AlphaExecutionMonitorSummary(
            fillRatio = fillRatio,
            averageSlippageBps = slippage.average(),
            feeBurnBps = feeBurnBps,
            latencyP50Ms = latencyPercentile(latencies, 0.50),
            latencyP95Ms = latencyPercentile(latencies, 0.95),
            executedNotionalFraction = executedNotional,
            alerts = alerts.ifEmpty { listOf("Execution telemetry is within configured drift and fill tolerances.") }
        )
    }

    private fun latencyPercentile(sortedLatencies: List<Long>, percentile: Double): Long {
        if (sortedLatencies.isEmpty()) return 0L
        val index = ((sortedLatencies.size - 1) * percentile).toInt().coerceIn(0, sortedLatencies.lastIndex)
        return sortedLatencies[index]
    }
}
