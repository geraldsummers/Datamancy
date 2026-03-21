package org.datamancy.txgateway.services

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class TradingTelemetryMetrics(
    private val meterRegistry: MeterRegistry
) {
    private val slippageDriftGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val fillQualityDecayGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val latencyDriftGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val driftScoreGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val impactCostGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val adverseSelectionCostGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val fundingDriftCostGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val basisDriftCostGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val feeTierAdjustmentGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val edgeAfterCostGauge = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val submitToFillSummary = ConcurrentHashMap<String, DistributionSummary>()
    private val totalCostSummary = ConcurrentHashMap<String, DistributionSummary>()

    init {
        val defaultTags = tags("unknown", "unknown", "unknown")
        val defaultKey = defaultTags.toString()
        updateGauge(
            store = slippageDriftGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_slippage_drift_bps",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = fillQualityDecayGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_fill_quality_decay_bps",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = latencyDriftGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_latency_drift_ms",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = driftScoreGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_drift_score",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = impactCostGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_impact_bps",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = adverseSelectionCostGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_adverse_selection_bps",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = fundingDriftCostGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_funding_drift_bps",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = basisDriftCostGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_basis_drift_bps",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = feeTierAdjustmentGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_fee_tier_adjustment_bps",
            tags = defaultTags,
            value = 0.0
        )
        updateGauge(
            store = edgeAfterCostGauge,
            key = defaultKey,
            metricName = "tx_gateway_trading_edge_after_cost_bps",
            tags = defaultTags,
            value = 0.0
        )
        summary(
            store = submitToFillSummary,
            key = defaultKey,
            metricName = "tx_gateway_trading_submit_to_fill_ms",
            tags = defaultTags,
            baseUnit = "milliseconds"
        )
        summary(
            store = totalCostSummary,
            key = defaultKey,
            metricName = "tx_gateway_trading_total_cost_bps",
            tags = defaultTags,
            baseUnit = "bps"
        )
    }

    fun recordDrift(
        strategyName: String,
        exchange: String,
        executionMode: String,
        slippageDriftBps: Double?,
        fillQualityDecayBps: Double?,
        latencyDriftMs: Double?,
        driftScore: Double?,
        submitToFillMs: Double?,
        totalCostBps: Double?,
        impactBps: Double?,
        adverseSelectionBps: Double?,
        fundingDriftBps: Double?,
        basisDriftBps: Double?,
        feeTierAdjustmentBps: Double?,
        edgeAfterCostBps: Double?
    ) {
        val tags = tags(strategyName, exchange, executionMode)
        val key = tags.toString()

        updateGauge(
            store = slippageDriftGauge,
            key = key,
            metricName = "tx_gateway_trading_slippage_drift_bps",
            tags = tags,
            value = slippageDriftBps
        )
        updateGauge(
            store = fillQualityDecayGauge,
            key = key,
            metricName = "tx_gateway_trading_fill_quality_decay_bps",
            tags = tags,
            value = fillQualityDecayBps
        )
        updateGauge(
            store = latencyDriftGauge,
            key = key,
            metricName = "tx_gateway_trading_latency_drift_ms",
            tags = tags,
            value = latencyDriftMs
        )
        updateGauge(
            store = driftScoreGauge,
            key = key,
            metricName = "tx_gateway_trading_drift_score",
            tags = tags,
            value = driftScore
        )
        updateGauge(
            store = impactCostGauge,
            key = key,
            metricName = "tx_gateway_trading_impact_bps",
            tags = tags,
            value = impactBps
        )
        updateGauge(
            store = adverseSelectionCostGauge,
            key = key,
            metricName = "tx_gateway_trading_adverse_selection_bps",
            tags = tags,
            value = adverseSelectionBps
        )
        updateGauge(
            store = fundingDriftCostGauge,
            key = key,
            metricName = "tx_gateway_trading_funding_drift_bps",
            tags = tags,
            value = fundingDriftBps
        )
        updateGauge(
            store = basisDriftCostGauge,
            key = key,
            metricName = "tx_gateway_trading_basis_drift_bps",
            tags = tags,
            value = basisDriftBps
        )
        updateGauge(
            store = feeTierAdjustmentGauge,
            key = key,
            metricName = "tx_gateway_trading_fee_tier_adjustment_bps",
            tags = tags,
            value = feeTierAdjustmentBps
        )
        updateGauge(
            store = edgeAfterCostGauge,
            key = key,
            metricName = "tx_gateway_trading_edge_after_cost_bps",
            tags = tags,
            value = edgeAfterCostBps
        )

        if (submitToFillMs != null && submitToFillMs.isFinite() && submitToFillMs >= 0.0) {
            summary(
                store = submitToFillSummary,
                key = key,
                metricName = "tx_gateway_trading_submit_to_fill_ms",
                tags = tags,
                baseUnit = "milliseconds"
            ).record(submitToFillMs)
        }
        if (totalCostBps != null && totalCostBps.isFinite()) {
            summary(
                store = totalCostSummary,
                key = key,
                metricName = "tx_gateway_trading_total_cost_bps",
                tags = tags,
                baseUnit = "bps"
            ).record(totalCostBps)
        }
    }

    private fun tags(strategyName: String, exchange: String, executionMode: String): Tags {
        return Tags.of(
            "strategy", strategyName.trim().ifBlank { "unknown" },
            "exchange", exchange.trim().lowercase().ifBlank { "unknown" },
            "execution_mode", executionMode.trim().lowercase().ifBlank { "unknown" }
        )
    }

    private fun updateGauge(
        store: ConcurrentHashMap<String, AtomicReference<Double>>,
        key: String,
        metricName: String,
        tags: Tags,
        value: Double?
    ) {
        if (value == null || !value.isFinite()) return
        val ref = store.computeIfAbsent(key) {
            val holder = AtomicReference(0.0)
            meterRegistry.gauge(metricName, tags, holder) { it.get() } ?: holder
        }
        ref.set(value)
    }

    private fun summary(
        store: ConcurrentHashMap<String, DistributionSummary>,
        key: String,
        metricName: String,
        tags: Tags,
        baseUnit: String
    ): DistributionSummary {
        return store.computeIfAbsent(key) {
            DistributionSummary.builder(metricName)
                .baseUnit(baseUnit)
                .tags(tags)
                .register(meterRegistry)
        }
    }
}
