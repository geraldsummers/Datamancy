package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy
import kotlin.math.abs

class AlphaDiscoveryPlanner(
    private val policyProvider: () -> TradingPolicy
) {
    fun defaults(): AlphaDiscoveryDefaults = AlphaDefaultsFactory.discoveryDefaults(policyProvider())

    fun candidateTemplates(request: AlphaDiscoveryCandidateRequest): List<AlphaDiscoveryCandidate> {
        val policy = policyProvider()
        val defaults = AlphaDefaultsFactory.discoveryDefaults(policy)
        val requestedBars = request.signalBarMinutes.ifEmpty { defaults.supportedSignalBarMinutes }
        val requestedCadences = request.rebalanceCadenceHours.ifEmpty { defaults.rebalanceCadenceHours }
        val requestedQuantiles = request.selectionQuantiles.ifEmpty { defaults.selectionQuantiles }
        val strategyFamily = request.strategyFamily?.ifBlank { null } ?: defaults.strategyFamily

        return requestedBars
            .flatMap { bar ->
                requestedCadences.flatMap { cadence ->
                    requestedQuantiles.map { quantile ->
                        val notes = mutableListOf<String>()
                        if (bar >= 240) notes += "Interday bar selected to emphasize persistent multi-day trend formation."
                        if (cadence >= 72) notes += "Rebalance cadence targets slower, more stable interday moves."
                        if (quantile <= 0.10) notes += "Tighter tails favor stronger cross-sectional separation."
                        AlphaDiscoveryCandidate(
                            candidateId = buildString {
                                append(strategyFamily)
                                append("-")
                                append(bar)
                                append("m-")
                                append(cadence)
                                append("h-q")
                                append((quantile * 100.0).toInt())
                            },
                            strategyFamily = strategyFamily,
                            signalBarMinutes = bar,
                            lookbackHours = defaults.defaultLookbackHours,
                            forwardHours = defaults.defaultForwardHours,
                            rebalanceCadenceHours = cadence,
                            selectionQuantile = quantile,
                            enabledFeatures = defaults.enabledFeatures,
                            notes = notes.ifEmpty {
                                listOf("Candidate adheres to the default interday relative-strength discovery contract.")
                            }
                        )
                    }
                }
            }
            .sortedWith(
                compareBy<AlphaDiscoveryCandidate> {
                    abs(it.signalBarMinutes - defaults.defaultSignalBarMinutes)
                }
                    .thenBy { abs(it.rebalanceCadenceHours - 72) }
                    .thenBy { abs(it.selectionQuantile - 0.10) }
            )
            .take(request.maxCandidates.coerceIn(1, 256))
    }
}
