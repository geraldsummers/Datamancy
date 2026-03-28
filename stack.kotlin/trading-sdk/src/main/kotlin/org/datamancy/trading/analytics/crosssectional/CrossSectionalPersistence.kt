package org.datamancy.trading.analytics.crosssectional

import java.sql.Connection
import java.sql.Timestamp
import kotlin.math.max

fun ensureAnalyticsTables(conn: Connection) {
    conn.createStatement().use { stmt ->
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_backtest_runs (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                start_time TIMESTAMPTZ NOT NULL,
                end_time TIMESTAMPTZ NOT NULL,
                trades INTEGER NOT NULL DEFAULT 0,
                win_rate DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                sharpe DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                notes TEXT,
                metrics JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_backtest_run_at ON strategy_backtest_runs (run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_backtest_symbol_time ON strategy_backtest_runs (symbol, timeframe, run_at DESC)")
        stmt.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_strategy_backtest_dedupe
            ON strategy_backtest_runs (strategy_name, symbol, timeframe, start_time, end_time)
            """.trimIndent()
        )
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_latency_metrics (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                decision_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
                submit_to_ack_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
                submit_to_fill_ms DOUBLE PRECISION,
                p50_roundtrip_ms DOUBLE PRECISION,
                p95_roundtrip_ms DOUBLE PRECISION,
                p99_roundtrip_ms DOUBLE PRECISION,
                jitter_ms DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_latency_time ON strategy_latency_metrics (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_latency_strategy_time ON strategy_latency_metrics (strategy_name, observed_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_execution_costs (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                fee_tier TEXT NOT NULL DEFAULT 'retail',
                fee_tier_adjustment_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                maker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                taker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                spread_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                slippage_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                impact_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                adverse_selection_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                funding_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                basis_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                total_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                edge_after_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                estimated_fee_usd DOUBLE PRECISION,
                estimated_cost_usd DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_time ON strategy_execution_costs (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_strategy_time ON strategy_execution_costs (strategy_name, observed_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_live_backtest_drift (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                live_edge_bps DOUBLE PRECISION,
                backtest_edge_bps DOUBLE PRECISION,
                fill_quality_delta_bps DOUBLE PRECISION,
                slippage_drift_bps DOUBLE PRECISION,
                latency_drift_ms DOUBLE PRECISION,
                drift_score DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_drift_time ON strategy_live_backtest_drift (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_drift_strategy_time ON strategy_live_backtest_drift (strategy_name, observed_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_universe_profiles (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT 'research',
                timeframe TEXT NOT NULL,
                candidate_symbols INTEGER NOT NULL DEFAULT 0,
                selected_symbols INTEGER NOT NULL DEFAULT 0,
                benchmark_symbols INTEGER NOT NULL DEFAULT 0,
                candidate_avg_tradable_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_tradable_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_observed_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_observed_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_depth_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_depth_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_volume_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_volume_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_observed_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_observed_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_tradable_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_tradable_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                deep_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                core_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                tradable_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                fragile_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("ALTER TABLE strategy_universe_profiles ADD COLUMN IF NOT EXISTS candidate_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_universe_profiles ADD COLUMN IF NOT EXISTS selected_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_run_at ON strategy_universe_profiles (run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_strategy_time ON strategy_universe_profiles (strategy_name, run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_stage_time ON strategy_universe_profiles (stage, run_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_portfolio_profiles (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                strategy_kind TEXT NOT NULL,
                stage TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                policy_max_concurrent_positions INTEGER NOT NULL DEFAULT 0,
                policy_max_concurrent_longs INTEGER NOT NULL DEFAULT 0,
                policy_max_concurrent_shorts INTEGER NOT NULL DEFAULT 0,
                policy_max_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                policy_max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                policy_max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_concurrent_positions INTEGER NOT NULL DEFAULT 0,
                max_concurrent_longs INTEGER NOT NULL DEFAULT 0,
                max_concurrent_shorts INTEGER NOT NULL DEFAULT 0,
                avg_concurrent_positions DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_concurrent_longs DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_concurrent_shorts DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_gross_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_gross_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_net_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_net_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_capacity_utilization DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_capacity_utilization DOUBLE PRECISION NOT NULL DEFAULT 0,
                trades INTEGER NOT NULL DEFAULT 0,
                candidate_entries INTEGER NOT NULL DEFAULT 0,
                accepted_entries INTEGER NOT NULL DEFAULT 0,
                rejected_open_symbol INTEGER NOT NULL DEFAULT 0,
                rejected_gross_limit INTEGER NOT NULL DEFAULT 0,
                rejected_long_limit INTEGER NOT NULL DEFAULT 0,
                rejected_short_limit INTEGER NOT NULL DEFAULT 0,
                rejected_net_limit INTEGER NOT NULL DEFAULT 0,
                rejected_beta_limit INTEGER NOT NULL DEFAULT 0,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_positions INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_longs INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_shorts INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_run_at ON strategy_portfolio_profiles (run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_strategy_time ON strategy_portfolio_profiles (strategy_name, run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_stage_time ON strategy_portfolio_profiles (stage, run_at DESC)")
    }
}

fun persistBacktestSummaries(summaries: List<StrategySummary>) {
    if (summaries.isEmpty()) return
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.prepareStatement(
            """
            INSERT INTO strategy_backtest_runs (
                strategy_name, symbol, timeframe, start_time, end_time,
                trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (strategy_name, symbol, timeframe, start_time, end_time)
            DO UPDATE SET
                trades = EXCLUDED.trades,
                win_rate = EXCLUDED.win_rate,
                net_return_pct = EXCLUDED.net_return_pct,
                max_drawdown_pct = EXCLUDED.max_drawdown_pct,
                sharpe = EXCLUDED.sharpe,
                notes = EXCLUDED.notes,
                metrics = EXCLUDED.metrics,
                run_at = NOW()
            """.trimIndent()
        ).use { stmt ->
            summaries.forEach { summary ->
                stmt.setString(1, summary.strategyName)
                stmt.setString(2, summary.symbol)
                stmt.setString(3, summary.timeframe)
                stmt.setTimestamp(4, Timestamp.from(summary.startTime))
                stmt.setTimestamp(5, Timestamp.from(summary.endTime))
                stmt.setInt(6, summary.trades)
                stmt.setDouble(7, summary.winRate)
                stmt.setDouble(8, summary.netReturnPct)
                stmt.setDouble(9, summary.maxDrawdownPct)
                stmt.setDouble(10, summary.sharpe)
                stmt.setString(11, summary.notes)
                stmt.setString(12, summary.metricsJson)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }
}

fun persistUniverseProfiles(
    config: ResearchConfig,
    timeframe: String,
    strategyNames: Collection<String>,
    profiles: List<UniverseProfileSnapshot>,
    stage: String = "research"
) {
    if (strategyNames.isEmpty() || profiles.isEmpty()) return
    val fingerprint = researchConfigFingerprint(config)
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.prepareStatement(
            """
            INSERT INTO strategy_universe_profiles (
                strategy_name, exchange, stage, timeframe,
                candidate_symbols, selected_symbols, benchmark_symbols,
                candidate_avg_tradable_ratio, selected_avg_tradable_ratio,
                candidate_avg_observed_ratio, selected_avg_observed_ratio,
                candidate_avg_spread_bps, selected_avg_spread_bps,
                candidate_median_spread_bps, selected_median_spread_bps,
                candidate_avg_depth_usd, selected_avg_depth_usd,
                candidate_avg_volume_usd, selected_avg_volume_usd,
                candidate_observed_execution_share, selected_observed_execution_share,
                candidate_tradable_execution_share, selected_tradable_execution_share,
                deep_liquidity_symbols, core_liquidity_symbols,
                tradable_liquidity_symbols, fragile_liquidity_symbols,
                metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { stmt ->
            strategyNames.forEach { strategyName ->
                profiles.forEach { profile ->
                    val bucketMap = profile.liquidityBuckets.associateBy { it.label }
                    val metadataJson = gson.toJson(
                        mapOf(
                            "config_fingerprint" to fingerprint,
                            "candidate_scan_limit" to config.discoveryMaxSymbols,
                            "selected_universe_limit" to config.maxSymbols,
                            "selected_universe" to profile.selectedUniverse,
                            "top_candidates" to profile.topCandidates,
                            "liquidity_buckets" to profile.liquidityBuckets
                        )
                    )
                    stmt.setString(1, strategyName)
                    stmt.setString(2, profile.exchange)
                    stmt.setString(3, stage)
                    stmt.setString(4, timeframe)
                    stmt.setInt(5, profile.candidateSymbols)
                    stmt.setInt(6, profile.selectedSymbols)
                    stmt.setInt(7, profile.benchmarkSymbols)
                    stmt.setDouble(8, profile.candidateAvgRecentTradableRatio)
                    stmt.setDouble(9, profile.selectedAvgRecentTradableRatio)
                    stmt.setDouble(10, profile.candidateAvgRecentObservedRatio)
                    stmt.setDouble(11, profile.selectedAvgRecentObservedRatio)
                    stmt.setDouble(12, profile.candidateAvgRecentSpreadBps)
                    stmt.setDouble(13, profile.selectedAvgRecentSpreadBps)
                    stmt.setDouble(14, profile.candidateMedianRecentSpreadBps)
                    stmt.setDouble(15, profile.selectedMedianRecentSpreadBps)
                    stmt.setDouble(16, profile.candidateAvgRecentDepthUsd)
                    stmt.setDouble(17, profile.selectedAvgRecentDepthUsd)
                    stmt.setDouble(18, profile.candidateAvgRecentVolumeUsd)
                    stmt.setDouble(19, profile.selectedAvgRecentVolumeUsd)
                    stmt.setDouble(20, profile.candidateObservedExecutionShare)
                    stmt.setDouble(21, profile.selectedObservedExecutionShare)
                    stmt.setDouble(22, profile.candidateTradableExecutionShare)
                    stmt.setDouble(23, profile.selectedTradableExecutionShare)
                    stmt.setInt(24, bucketMap["deep"]?.symbols ?: 0)
                    stmt.setInt(25, bucketMap["core"]?.symbols ?: 0)
                    stmt.setInt(26, bucketMap["tradable"]?.symbols ?: 0)
                    stmt.setInt(27, bucketMap["fragile"]?.symbols ?: 0)
                    stmt.setString(28, metadataJson)
                    stmt.addBatch()
                }
            }
            stmt.executeBatch()
        }
    }
}

fun persistPortfolioProfiles(
    config: ResearchConfig,
    timeframe: String,
    strategyNames: Map<String, String>,
    profiles: Map<String, PortfolioProfileSnapshot>
) {
    if (strategyNames.isEmpty() || profiles.isEmpty()) return
    val fingerprint = researchConfigFingerprint(config)
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.prepareStatement(
            """
            INSERT INTO strategy_portfolio_profiles (
                strategy_name, strategy_kind, stage, timeframe,
                policy_max_concurrent_positions, policy_max_concurrent_longs, policy_max_concurrent_shorts,
                policy_max_net_exposure_fraction, policy_max_abs_beta_btc, policy_max_abs_beta_eth,
                max_concurrent_positions, max_concurrent_longs, max_concurrent_shorts,
                avg_concurrent_positions, avg_concurrent_longs, avg_concurrent_shorts,
                max_gross_exposure_usd, avg_gross_exposure_usd,
                max_net_exposure_usd, avg_net_exposure_usd,
                max_abs_net_exposure_fraction, avg_abs_net_exposure_fraction,
                max_abs_beta_btc, avg_abs_beta_btc,
                max_abs_beta_eth, avg_abs_beta_eth,
                avg_capacity_utilization, max_capacity_utilization,
                trades, candidate_entries, accepted_entries,
                rejected_open_symbol, rejected_gross_limit,
                rejected_long_limit, rejected_short_limit,
                rejected_net_limit, rejected_beta_limit,
                metadata
            ) VALUES (
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?, ?,
                ?, ?,
                ?, ?, ?, ?,
                CAST(? AS jsonb)
            )
            """.trimIndent()
        ).use { stmt ->
            profiles.forEach { (kind, profile) ->
                val strategyName = strategyNames[kind] ?: return@forEach
                val metadataJson = gson.toJson(
                    mapOf(
                        "config_fingerprint" to fingerprint,
                        "exchanges" to profile.exchanges,
                        "policy" to mapOf(
                            "max_concurrent_positions" to profile.policyMaxConcurrentPositions,
                            "max_concurrent_longs" to profile.policyMaxConcurrentLongs,
                            "max_concurrent_shorts" to profile.policyMaxConcurrentShorts,
                            "max_net_exposure_fraction" to profile.policyMaxNetExposureFraction,
                            "max_abs_beta_btc" to profile.policyMaxAbsBetaBtc,
                            "max_abs_beta_eth" to profile.policyMaxAbsBetaEth
                        )
                    )
                )
                stmt.setString(1, strategyName)
                stmt.setString(2, profile.strategyKind)
                stmt.setString(3, profile.stage)
                stmt.setString(4, timeframe)
                stmt.setInt(5, profile.policyMaxConcurrentPositions)
                stmt.setInt(6, profile.policyMaxConcurrentLongs)
                stmt.setInt(7, profile.policyMaxConcurrentShorts)
                stmt.setDouble(8, profile.policyMaxNetExposureFraction)
                stmt.setDouble(9, profile.policyMaxAbsBetaBtc)
                stmt.setDouble(10, profile.policyMaxAbsBetaEth)
                stmt.setInt(11, profile.maxConcurrentPositions)
                stmt.setInt(12, profile.maxConcurrentLongs)
                stmt.setInt(13, profile.maxConcurrentShorts)
                stmt.setDouble(14, profile.avgConcurrentPositions)
                stmt.setDouble(15, profile.avgConcurrentLongs)
                stmt.setDouble(16, profile.avgConcurrentShorts)
                stmt.setDouble(17, profile.maxGrossExposureUsd)
                stmt.setDouble(18, profile.avgGrossExposureUsd)
                stmt.setDouble(19, profile.maxNetExposureUsd)
                stmt.setDouble(20, profile.avgNetExposureUsd)
                stmt.setDouble(21, profile.maxAbsNetExposureFraction)
                stmt.setDouble(22, profile.avgAbsNetExposureFraction)
                stmt.setDouble(23, profile.maxAbsBetaBtc)
                stmt.setDouble(24, profile.avgAbsBetaBtc)
                stmt.setDouble(25, profile.maxAbsBetaEth)
                stmt.setDouble(26, profile.avgAbsBetaEth)
                stmt.setDouble(27, profile.avgCapacityUtilization)
                stmt.setDouble(28, profile.maxCapacityUtilization)
                stmt.setInt(29, profile.trades)
                stmt.setInt(30, profile.entryConstraints.candidateEntries)
                stmt.setInt(31, profile.entryConstraints.acceptedEntries)
                stmt.setInt(32, profile.entryConstraints.rejectedOpenSymbol)
                stmt.setInt(33, profile.entryConstraints.rejectedGrossLimit)
                stmt.setInt(34, profile.entryConstraints.rejectedLongLimit)
                stmt.setInt(35, profile.entryConstraints.rejectedShortLimit)
                stmt.setInt(36, profile.entryConstraints.rejectedNetLimit)
                stmt.setInt(37, profile.entryConstraints.rejectedBetaLimit)
                stmt.setString(38, metadataJson)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }
}

fun persistForwardTelemetry(
    config: ResearchConfig,
    trades: List<TradeRecord>,
    baselines: Map<Triple<String, String, String>, StrategySummary>,
    source: String
) {
    if (trades.isEmpty()) return
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.autoCommit = false

        val grouped = trades.groupBy { Triple(it.strategyName, it.exchange, it.symbol) }
        grouped.forEach { (scope, bucket) ->
            val firstObservedAt = bucket.minOf { it.entryTime }
            val lastObservedAt = bucket.maxOf { it.entryTime }
            conn.prepareStatement(
                """
                DELETE FROM strategy_execution_costs
                WHERE strategy_name = ? AND exchange = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.second)
                stmt.setString(3, scope.third)
                stmt.setTimestamp(4, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(5, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                """
                DELETE FROM strategy_latency_metrics
                WHERE strategy_name = ? AND exchange = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.second)
                stmt.setString(3, scope.third)
                stmt.setTimestamp(4, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(5, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                """
                DELETE FROM strategy_live_backtest_drift
                WHERE strategy_name = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.third)
                stmt.setTimestamp(3, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(4, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
        }

        conn.prepareStatement(
            """
            INSERT INTO strategy_latency_metrics (
                observed_at, strategy_name, exchange, symbol,
                decision_latency_ms, submit_to_ack_ms, submit_to_fill_ms,
                p50_roundtrip_ms, p95_roundtrip_ms, p99_roundtrip_ms,
                jitter_ms, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { latencyStmt ->
            conn.prepareStatement(
                """
                INSERT INTO strategy_execution_costs (
                    observed_at, strategy_name, exchange, symbol, side,
                    fee_bps, fee_tier, fee_tier_adjustment_bps,
                    maker_fee_bps, taker_fee_bps,
                    spread_cost_bps, slippage_bps, impact_bps,
                    adverse_selection_bps, funding_drift_bps, basis_drift_bps,
                    total_cost_bps, edge_after_cost_bps,
                    estimated_fee_usd, estimated_cost_usd, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """.trimIndent()
            ).use { costStmt ->
                conn.prepareStatement(
                    """
                    INSERT INTO strategy_live_backtest_drift (
                        observed_at, strategy_name, symbol,
                        live_edge_bps, backtest_edge_bps,
                        fill_quality_delta_bps, slippage_drift_bps,
                        latency_drift_ms, drift_score, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """.trimIndent()
                ).use { driftStmt ->
                    trades.forEach { trade ->
                        val baseline = baselines[Triple(trade.strategyName, trade.exchange, trade.symbol)]
                            ?: baselines[Triple(trade.strategyName, trade.exchange, "ALL")]
                        val metadataJson = gson.toJson(
                            mapOf(
                                "source" to source,
                                "strategyKind" to trade.strategyKind,
                                "barMinutes" to config.barMinutes,
                                "fillRatio" to trade.fillRatio.round(4),
                                "betaBtc" to trade.betaBtc.round(4),
                                "betaEth" to trade.betaEth.round(4),
                                "entryTrendScore" to trade.entryTrendScore.round(4),
                                "entryResidualZ" to trade.entryResidualZ.round(4),
                                "expectedGrossEdgeBps" to trade.expectedGrossEdgeBps.round(4),
                                "expectedRoundTripCostBps" to trade.expectedRoundTripCostBps.round(4),
                                "expectedNetEdgeBps" to trade.expectedNetEdgeBps.round(4),
                                "calibrationSamples" to trade.calibrationSamples,
                                "calibrationWinRate" to trade.calibrationWinRate.round(4),
                                "calibrationLowerBoundBps" to trade.calibrationLowerBoundBps.round(4),
                                "calibrationScope" to trade.calibrationScope,
                                "entryImbalance" to trade.entryImbalance.round(4),
                                "entryFlowSignal" to trade.entryFlowSignal.round(4),
                                "entryVolumeRatio" to trade.entryVolumeRatio.round(4),
                                "entryVolRegime" to trade.entryVolRegime.round(4),
                                "executionMode" to config.paperExecutionMode
                            )
                        )

                        latencyStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        latencyStmt.setString(2, trade.strategyName)
                        latencyStmt.setString(3, trade.exchange)
                        latencyStmt.setString(4, trade.symbol)
                        latencyStmt.setDouble(5, trade.decisionLatencyMs)
                        latencyStmt.setDouble(6, trade.submitToAckMs)
                        latencyStmt.setDouble(7, trade.submitToFillMs)
                        latencyStmt.setDouble(8, trade.p50RoundtripMs)
                        latencyStmt.setDouble(9, trade.p95RoundtripMs)
                        latencyStmt.setDouble(10, trade.p99RoundtripMs)
                        latencyStmt.setDouble(11, trade.jitterMs)
                        latencyStmt.setString(12, metadataJson)
                        latencyStmt.addBatch()

                        costStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        costStmt.setString(2, trade.strategyName)
                        costStmt.setString(3, trade.exchange)
                        costStmt.setString(4, trade.symbol)
                        costStmt.setString(5, trade.side)
                        costStmt.setDouble(6, trade.feeBps)
                        costStmt.setString(7, trade.feeTier)
                        costStmt.setDouble(8, trade.feeTierAdjustmentBps)
                        costStmt.setDouble(9, trade.makerFeeBps)
                        costStmt.setDouble(10, trade.takerFeeBps)
                        costStmt.setDouble(11, trade.spreadCostBps)
                        costStmt.setDouble(12, trade.slippageBps)
                        costStmt.setDouble(13, trade.impactBps)
                        costStmt.setDouble(14, trade.adverseSelectionBps)
                        costStmt.setDouble(15, trade.fundingDriftBps)
                        costStmt.setDouble(16, trade.basisDriftBps)
                        costStmt.setDouble(17, trade.totalCostBps)
                        costStmt.setDouble(18, trade.edgeAfterCostBps)
                        costStmt.setDouble(19, trade.estimatedFeeUsd)
                        costStmt.setDouble(20, trade.estimatedCostUsd)
                        costStmt.setString(21, metadataJson)
                        costStmt.addBatch()

                        val fillQualityDelta = ((baseline?.avgFillRatio ?: trade.fillRatio) - trade.fillRatio) * 10000.0
                        val slippageDrift = trade.slippageBps - (baseline?.avgSlippageBps ?: trade.slippageBps)
                        val latencyDrift = trade.submitToFillMs - (baseline?.avgSubmitToFillMs ?: trade.submitToFillMs)
                        val edgeDecay = if (baseline == null) 0.0 else max(0.0, baseline.avgEdgeAfterCostBps - trade.edgeAfterCostBps)
                        val predictionMiss = max(0.0, trade.expectedNetEdgeBps - trade.edgeAfterCostBps)
                        val driftScore = max(0.0, fillQualityDelta) +
                            max(0.0, slippageDrift) +
                            max(0.0, latencyDrift) / 10.0 +
                            edgeDecay +
                            predictionMiss

                        driftStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        driftStmt.setString(2, trade.strategyName)
                        driftStmt.setString(3, trade.symbol)
                        driftStmt.setDouble(4, trade.edgeAfterCostBps)
                        driftStmt.setObject(5, baseline?.avgEdgeAfterCostBps)
                        driftStmt.setDouble(6, fillQualityDelta)
                        driftStmt.setDouble(7, slippageDrift)
                        driftStmt.setDouble(8, latencyDrift)
                        driftStmt.setDouble(9, driftScore)
                        driftStmt.setString(10, metadataJson)
                        driftStmt.addBatch()
                    }

                    latencyStmt.executeBatch()
                    costStmt.executeBatch()
                    driftStmt.executeBatch()
                }
            }
        }

        conn.commit()
    }
}
