package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy

class AlphaDatasetValidator(
    private val policyProvider: () -> TradingPolicy
) {
    fun validate(request: AlphaDatasetValidationRequest): AlphaDatasetValidation {
        val policy = policyProvider()
        val datasets = policy.research.datasets
        val reasons = mutableListOf<String>()
        if (request.signalBarMinutes !in datasets.supportedSignalBarMinutes) {
            reasons += "signalBarMinutes=${request.signalBarMinutes} is outside supported bars ${datasets.supportedSignalBarMinutes}"
        }
        if (request.lookbackHours <= request.forwardHours) {
            reasons += "lookbackHours must exceed forwardHours for interday training windows"
        }
        if (request.forwardHours <= 0) {
            reasons += "forwardHours must be positive"
        }
        if (request.requireFunding && !datasets.includeFunding) {
            reasons += "funding is not enabled in the interday price dataset"
        }
        if (request.requireOpenInterest && !datasets.includeOpenInterest) {
            reasons += "open interest is not enabled in the interday price dataset"
        }
        if (request.requireExecutionConditioning && !policy.research.readiness.execution.requireOrderbook) {
            reasons += "execution conditioning requested but orderbook readiness is disabled"
        }
        return AlphaDatasetValidation(
            accepted = reasons.isEmpty(),
            exchange = request.exchange?.ifBlank { null } ?: datasets.marketExchange,
            signalBarMinutes = request.signalBarMinutes,
            lookbackHours = request.lookbackHours,
            forwardHours = request.forwardHours,
            requiredFeatureFamilies = buildList {
                add("price_action")
                if (request.requireFunding) add("funding")
                if (request.requireOpenInterest) add("open_interest")
            },
            requiredExecutionConditioning = request.requireExecutionConditioning,
            reasons = reasons.ifEmpty {
                listOf(
                    "Request is compatible with the stored interday price dataset contract.",
                    "Execution realism remains a separate gate through execution readiness and execution monitoring."
                )
            }
        )
    }
}
