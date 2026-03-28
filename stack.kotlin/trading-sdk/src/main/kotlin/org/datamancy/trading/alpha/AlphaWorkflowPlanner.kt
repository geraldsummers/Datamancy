package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy

class AlphaWorkflowPlanner(
    private val policyProvider: () -> TradingPolicy
) {
    fun plan(request: AlphaWorkflowPlanRequest): AlphaWorkflowPlan {
        val policy = policyProvider()
        val exchange = request.exchange?.ifBlank { null } ?: policy.research.datasets.marketExchange
        val signalBarMinutes = request.signalBarMinutes ?: policy.research.datasets.defaultSignalBarMinutes
        val forwardHours = request.forwardHours ?: policy.research.datasets.defaultForwardHours
        return AlphaWorkflowPlan(
            exchange = exchange,
            signalBarMinutes = signalBarMinutes,
            forwardHours = forwardHours,
            mode = request.mode,
            stages = listOf(
                AlphaWorkflowStage(
                    name = "signal-readiness",
                    service = "alpha-analytics-service",
                    endpoint = "/api/v1/data-health/summary",
                    required = true,
                    purpose = "Confirm price/funding/open-interest feature freshness for the requested horizon."
                ),
                AlphaWorkflowStage(
                    name = "dataset-validation",
                    service = "alpha-dataset-service",
                    endpoint = "/api/v1/datasets/validate",
                    required = true,
                    purpose = "Check that the requested signal horizon and feature families fit the canonical research contract."
                ),
                AlphaWorkflowStage(
                    name = "discovery-candidates",
                    service = "alpha-discovery-service",
                    endpoint = "/api/v1/discovery/candidates",
                    required = true,
                    purpose = "Enumerate interday relative-strength candidate regions without relaxing readiness."
                ),
                AlphaWorkflowStage(
                    name = "portfolio-construction",
                    service = "alpha-portfolio-service",
                    endpoint = "/api/v1/portfolio/construct",
                    required = true,
                    purpose = "Diversify trend exposure across the universe with confidence-scaled weights."
                ),
                AlphaWorkflowStage(
                    name = "execution-plan",
                    service = "alpha-execution-agent",
                    endpoint = "/api/v1/execution/plan",
                    required = request.mode != AlphaRunMode.OFFLINE_BACKTEST,
                    purpose = "Convert target weights into gradual child-order schedules for paper or testnet execution."
                ),
                AlphaWorkflowStage(
                    name = "execution-monitor",
                    service = "alpha-execution-monitor",
                    endpoint = "/api/v1/execution-monitor/summarize",
                    required = request.mode == AlphaRunMode.FORWARD_PAPER || request.mode == AlphaRunMode.TESTNET_LIVE || request.mode == AlphaRunMode.LIVE,
                    purpose = "Reality-check fill quality, fee burn, and latency drift separately from price-model backtests."
                )
            ),
            notes = listOf(
                "Signal research and execution realism are separated so long-range model testing is not blocked by shallow orderbook history.",
                "Promotion still requires both signal readiness and execution validation before live escalation."
            )
        )
    }
}
