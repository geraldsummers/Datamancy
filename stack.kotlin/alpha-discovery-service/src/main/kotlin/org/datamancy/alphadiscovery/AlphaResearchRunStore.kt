package org.datamancy.alphadiscovery

import org.datamancy.trading.alpha.InterdayAlphaRunRequest
import org.datamancy.trading.alpha.InterdayAlphaRunResponse
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

data class AlphaResearchRunRecord(
    val runId: String,
    val grafanaPath: String
)

fun interface AlphaResearchRunRecorder {
    fun record(request: InterdayAlphaRunRequest, response: InterdayAlphaRunResponse): AlphaResearchRunRecord
}

class JdbcAlphaResearchRunRecorder(
    private val dataSource: DataSource,
    private val dashboardUid: String = "alpha-run-explorer"
) : AlphaResearchRunRecorder {
    private val gson = AlphaServiceJson.gson
    private val schemaReady = AtomicBoolean(false)

    override fun record(request: InterdayAlphaRunRequest, response: InterdayAlphaRunResponse): AlphaResearchRunRecord {
        ensureSchema()
        val runId = UUID.randomUUID().toString()
        val grafanaPath = "/d/$dashboardUid/alpha-run-explorer?var-alpha_run_id=$runId"

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                persistRun(conn.prepareStatement(insertRunSql), runId, grafanaPath, request, response)
                persistSignals(conn.prepareStatement(insertSignalsSql), runId, response)
                persistTargets(conn.prepareStatement(insertTargetsSql), runId, response)
                persistTrades(conn.prepareStatement(insertTradesSql), runId, response)
                persistInspection(runId, response, conn)
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }

        return AlphaResearchRunRecord(
            runId = runId,
            grafanaPath = grafanaPath
        )
    }

    private fun ensureSchema() {
        if (schemaReady.get()) return
        synchronized(schemaReady) {
            if (schemaReady.get()) return
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    schemaSql.forEach(stmt::execute)
                }
            }
            schemaReady.set(true)
        }
    }

    private fun persistRun(
        stmt: PreparedStatement,
        runId: String,
        grafanaPath: String,
        request: InterdayAlphaRunRequest,
        response: InterdayAlphaRunResponse
    ) {
        stmt.use {
            val config = response.config
            val validation = response.validation
            it.setString(1, runId)
            it.setTimestamp(2, Timestamp.from(response.generatedAt))
            it.setString(3, config.strategyFamily)
            it.setString(4, response.mode.name)
            it.setString(5, config.exchange)
            it.setInt(6, config.signalBarMinutes)
            it.setInt(7, config.forwardHours)
            it.setInt(8, config.rebalanceCadenceHours)
            it.setInt(9, config.factorLookbackDays)
            it.setDouble(10, config.selectionQuantile)
            it.setString(11, config.trendScoreMode.name)
            it.setString(12, config.residualizationMode.name)
            it.setString(13, config.residualizationBetaMode.name)
            it.setString(14, config.residualizationMarketProxyMode.name)
            it.setString(15, config.tailWeightingMode.name)
            it.setString(16, config.fundingOverlayMode.name)
            it.setString(17, config.exitOverlayMode.name)
            it.setBoolean(18, request.submitOrders)
            it.setBoolean(19, request.includeInspection)
            it.setBoolean(20, validation.accepted)
            it.setBoolean(21, validation.backtestAccepted)
            it.setBoolean(22, validation.forwardAccepted)
            it.setDouble(23, response.backtest.edgeAfterCostBps)
            it.setInt(24, response.backtest.tradeCount)
            it.setDouble(25, response.backtest.netReturnPct)
            it.setDouble(26, response.backtest.calmar)
            it.setDouble(27, response.forward.edgeAfterCostBps)
            it.setInt(28, response.forward.tradeCount)
            it.setDouble(29, response.forward.netReturnPct)
            it.setDouble(30, response.forward.calmar)
            it.setString(31, gson.toJson(request))
            it.setString(32, gson.toJson(config))
            it.setString(33, gson.toJson(response.backtest))
            it.setString(34, gson.toJson(response.forward))
            it.setString(35, gson.toJson(validation))
            it.setString(36, gson.toJson(response.executionPreview))
            it.setString(37, gson.toJson(response.notes))
            it.setString(38, grafanaPath)
            it.executeUpdate()
        }
    }

    private fun persistSignals(stmt: PreparedStatement, runId: String, response: InterdayAlphaRunResponse) {
        stmt.use {
            response.selectedSignals.forEachIndexed { index, signal ->
                it.setString(1, runId)
                it.setInt(2, index + 1)
                it.setString(3, signal.symbol)
                it.setString(4, signal.direction.name)
                it.setDouble(5, signal.score)
                it.setDouble(6, signal.empiricalScore)
                it.setDouble(7, signal.residualRank)
                it.setDouble(8, signal.confidence)
                it.setDouble(9, signal.liquidityScore)
                it.setDouble(10, signal.trendScore)
                it.setDouble(11, signal.trendAgreement)
                it.setDouble(12, signal.pullbackScore)
                it.setDouble(13, signal.fundingScore)
                it.setDouble(14, signal.openInterestScore)
                it.setDouble(15, signal.expansionScore)
                it.setDouble(16, signal.reversalRiskScore)
                it.setDouble(17, signal.fundingOverlayMultiplier)
                it.setDouble(18, signal.marketBeta)
                it.setDouble(19, signal.upperBound)
                it.setDouble(20, signal.lowerBound)
                it.setDouble(21, signal.expectedResidualReturnBps)
                it.setDouble(22, signal.expectedEntryCostBps)
                it.setDouble(23, signal.expectedTurnoverPenaltyBps)
                it.setDouble(24, signal.expectedNetEdgeBps)
                it.setDouble(25, signal.close)
                it.setDouble(26, signal.predictedVolatility)
                it.addBatch()
            }
            if (response.selectedSignals.isNotEmpty()) {
                it.executeBatch()
            }
        }
    }

    private fun persistTargets(stmt: PreparedStatement, runId: String, response: InterdayAlphaRunResponse) {
        stmt.use {
            response.targets.forEachIndexed { index, target ->
                it.setString(1, runId)
                it.setInt(2, index + 1)
                it.setString(3, target.symbol)
                it.setString(4, target.direction.name)
                it.setDouble(5, target.weightFraction)
                it.setDouble(6, target.leverageMultiplier)
                it.setDouble(7, target.confidence)
                it.setDouble(8, target.score)
                it.setDouble(9, target.normalizedScore)
                it.setDouble(10, target.expectedNetEdgeBps)
                it.setDouble(11, target.expectedCostBps)
                it.setDouble(12, target.turnoverDeltaFraction)
                it.setDouble(13, target.trailingStopVolMultiple)
                it.setDouble(14, target.takeProfitVolMultiple)
                it.setString(15, target.rationale)
                it.addBatch()
            }
            if (response.targets.isNotEmpty()) {
                it.executeBatch()
            }
        }
    }

    private fun persistTrades(stmt: PreparedStatement, runId: String, response: InterdayAlphaRunResponse) {
        stmt.use {
            response.trades.forEachIndexed { index, trade ->
                it.setString(1, runId)
                it.setInt(2, index + 1)
                it.setString(3, trade.symbol)
                it.setString(4, trade.direction.name)
                it.setTimestamp(5, Timestamp.from(trade.entryTime))
                it.setTimestamp(6, Timestamp.from(trade.exitTime))
                it.setDouble(7, trade.entryPrice)
                it.setDouble(8, trade.exitPrice)
                it.setDouble(9, trade.weightFraction)
                it.setDouble(10, trade.pnlPct)
                it.setDouble(11, trade.maxFavorablePnlPct)
                it.setDouble(12, trade.profitGivebackPct)
                it.setString(13, trade.reason)
                it.setString(14, trade.segment)
                it.addBatch()
            }
            if (response.trades.isNotEmpty()) {
                it.executeBatch()
            }
        }
    }

    private fun persistInspection(runId: String, response: InterdayAlphaRunResponse, conn: java.sql.Connection) {
        val inspection = response.inspection ?: return

        conn.prepareStatement(insertSymbolPointsSql).use { stmt ->
            inspection.symbols.forEach { symbolInspection ->
                symbolInspection.points.forEach { point ->
                    stmt.setString(1, runId)
                    stmt.setString(2, symbolInspection.symbol)
                    stmt.setTimestamp(3, Timestamp.from(point.time))
                    stmt.setDouble(4, point.close)
                    stmt.setDouble(5, point.score)
                    stmt.setDouble(6, point.empiricalScore)
                    stmt.setDouble(7, point.confidence)
                    stmt.setDouble(8, point.regimeScore)
                    stmt.setDouble(9, point.expansionScore)
                    stmt.setDouble(10, point.reversalRiskScore)
                    stmt.setDouble(11, point.upperBound)
                    stmt.setDouble(12, point.lowerBound)
                    stmt.setDouble(13, point.expectedResidualReturnBps)
                    stmt.setDouble(14, point.expectedEntryCostBps)
                    stmt.setDouble(15, point.expectedNetEdgeBps)
                    stmt.setDouble(16, point.desiredWeight)
                    stmt.setDouble(17, point.appliedDelta)
                    stmt.setBoolean(18, point.entryEligible)
                    stmt.setBoolean(19, point.regimeBlocked)
                    stmt.setDouble(20, point.positionWeight)
                    stmt.addBatch()
                }
            }
            if (inspection.symbols.any { it.points.isNotEmpty() }) {
                stmt.executeBatch()
            }
        }

        conn.prepareStatement(insertPortfolioSnapshotsSql).use { stmt ->
            inspection.portfolio.forEach { snapshot ->
                stmt.setString(1, runId)
                stmt.setTimestamp(2, Timestamp.from(snapshot.time))
                stmt.setDouble(3, snapshot.equity)
                stmt.setDouble(4, snapshot.grossExposureFraction)
                stmt.setDouble(5, snapshot.longExposureFraction)
                stmt.setDouble(6, snapshot.shortExposureFraction)
                stmt.setDouble(7, snapshot.netExposureFraction)
                stmt.setInt(8, snapshot.openPositions)
                stmt.setDouble(9, snapshot.turnoverFraction)
                stmt.setDouble(10, snapshot.regimeScore)
                stmt.setDouble(11, snapshot.regimeStrength)
                stmt.setDouble(12, snapshot.alignedExposureFraction)
                stmt.setDouble(13, snapshot.wrongWayExposureFraction)
                stmt.setDouble(14, snapshot.killSwitchUtilization)
                stmt.addBatch()
            }
            if (inspection.portfolio.isNotEmpty()) {
                stmt.executeBatch()
            }
        }

        conn.prepareStatement(insertRegimesSql).use { stmt ->
            inspection.regimes.forEach { regime ->
                stmt.setString(1, runId)
                stmt.setTimestamp(2, Timestamp.from(regime.time))
                stmt.setDouble(3, regime.regimeScore)
                stmt.setDouble(4, regime.breadth)
                stmt.setDouble(5, regime.anchorTrend)
                stmt.setDouble(6, regime.dispersion)
                stmt.setDouble(7, regime.realizedVolatility)
                stmt.setDouble(8, regime.liquidityScore)
                stmt.setDouble(9, regime.fundingPressure)
                stmt.setDouble(10, regime.openInterestPressure)
                stmt.setDouble(11, regime.marketTrendScore)
                stmt.setDouble(12, regime.grossExposureFraction)
                stmt.setDouble(13, regime.longExposureFraction)
                stmt.setDouble(14, regime.shortExposureFraction)
                stmt.setDouble(15, regime.netExposureFraction)
                stmt.setDouble(16, regime.alignedExposureFraction)
                stmt.setDouble(17, regime.wrongWayExposureFraction)
                stmt.setDouble(18, regime.killSwitchUtilization)
                stmt.addBatch()
            }
            if (inspection.regimes.isNotEmpty()) {
                stmt.executeBatch()
            }
        }

        conn.prepareStatement(insertDiagnosticsSql).use { stmt ->
            inspection.compressionDiagnostics.forEach { diagnostic ->
                stmt.setString(1, runId)
                stmt.setTimestamp(2, Timestamp.from(diagnostic.time))
                stmt.setInt(3, diagnostic.windowBars)
                stmt.setInt(4, diagnostic.sleeveSizePerSide)
                stmt.setDouble(5, diagnostic.pc1Share)
                stmt.setDouble(6, diagnostic.coMomentum)
                stmt.setDouble(7, diagnostic.pc1ShareZ)
                stmt.setDouble(8, diagnostic.coMomentumZ)
                stmt.setDouble(9, diagnostic.futureFactorReturnBps)
                stmt.setInt(10, diagnostic.longSleeveSize)
                stmt.setInt(11, diagnostic.shortSleeveSize)
                stmt.setDouble(12, diagnostic.marketTrendScore)
                stmt.setDouble(13, diagnostic.breadth)
                stmt.setDouble(14, diagnostic.dispersion)
                stmt.addBatch()
            }
            if (inspection.compressionDiagnostics.isNotEmpty()) {
                stmt.executeBatch()
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcAlphaResearchRunRecorder::class.java)

        private val insertRunSql = """
            INSERT INTO alpha_research_runs (
                run_id,
                generated_at,
                strategy_family,
                mode,
                exchange,
                signal_bar_minutes,
                forward_hours,
                rebalance_cadence_hours,
                factor_lookback_days,
                selection_quantile,
                trend_score_mode,
                residualization_mode,
                residualization_beta_mode,
                residualization_market_proxy_mode,
                tail_weighting_mode,
                funding_overlay_mode,
                exit_overlay_mode,
                submit_orders,
                include_inspection,
                validation_accepted,
                backtest_accepted,
                forward_accepted,
                backtest_edge_after_cost_bps,
                backtest_trade_count,
                backtest_net_return_pct,
                backtest_calmar,
                forward_edge_after_cost_bps,
                forward_trade_count,
                forward_net_return_pct,
                forward_calmar,
                request_json,
                config_json,
                backtest_json,
                forward_json,
                validation_json,
                execution_preview_json,
                notes_json,
                grafana_path
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?
            )
        """.trimIndent()

        private val insertSignalsSql = """
            INSERT INTO alpha_research_run_signals (
                run_id,
                signal_rank,
                symbol,
                direction,
                score,
                empirical_score,
                residual_rank,
                confidence,
                liquidity_score,
                trend_score,
                trend_agreement,
                pullback_score,
                funding_score,
                open_interest_score,
                expansion_score,
                reversal_risk_score,
                funding_overlay_multiplier,
                market_beta,
                upper_bound,
                lower_bound,
                expected_residual_return_bps,
                expected_entry_cost_bps,
                expected_turnover_penalty_bps,
                expected_net_edge_bps,
                close,
                predicted_volatility
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val insertTargetsSql = """
            INSERT INTO alpha_research_run_targets (
                run_id,
                target_rank,
                symbol,
                direction,
                weight_fraction,
                leverage_multiplier,
                confidence,
                score,
                normalized_score,
                expected_net_edge_bps,
                expected_cost_bps,
                turnover_delta_fraction,
                trailing_stop_vol_multiple,
                take_profit_vol_multiple,
                rationale
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val insertTradesSql = """
            INSERT INTO alpha_research_run_trades (
                run_id,
                trade_index,
                symbol,
                direction,
                entry_time,
                exit_time,
                entry_price,
                exit_price,
                weight_fraction,
                pnl_pct,
                max_favorable_pnl_pct,
                profit_giveback_pct,
                reason,
                segment
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val insertSymbolPointsSql = """
            INSERT INTO alpha_research_run_symbol_points (
                run_id,
                symbol,
                time,
                close,
                score,
                empirical_score,
                confidence,
                regime_score,
                expansion_score,
                reversal_risk_score,
                upper_bound,
                lower_bound,
                expected_residual_return_bps,
                expected_entry_cost_bps,
                expected_net_edge_bps,
                desired_weight,
                applied_delta,
                entry_eligible,
                regime_blocked,
                position_weight
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val insertPortfolioSnapshotsSql = """
            INSERT INTO alpha_research_run_portfolio_snapshots (
                run_id,
                time,
                equity,
                gross_exposure_fraction,
                long_exposure_fraction,
                short_exposure_fraction,
                net_exposure_fraction,
                open_positions,
                turnover_fraction,
                regime_score,
                regime_strength,
                aligned_exposure_fraction,
                wrong_way_exposure_fraction,
                kill_switch_utilization
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val insertRegimesSql = """
            INSERT INTO alpha_research_run_regimes (
                run_id,
                time,
                regime_score,
                breadth,
                anchor_trend,
                dispersion,
                realized_volatility,
                liquidity_score,
                funding_pressure,
                open_interest_pressure,
                market_trend_score,
                gross_exposure_fraction,
                long_exposure_fraction,
                short_exposure_fraction,
                net_exposure_fraction,
                aligned_exposure_fraction,
                wrong_way_exposure_fraction,
                kill_switch_utilization
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val insertDiagnosticsSql = """
            INSERT INTO alpha_research_run_compression_diagnostics (
                run_id,
                time,
                window_bars,
                sleeve_size_per_side,
                pc1_share,
                co_momentum,
                pc1_share_z,
                co_momentum_z,
                future_factor_return_bps,
                long_sleeve_size,
                short_sleeve_size,
                market_trend_score,
                breadth,
                dispersion
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val schemaSql = listOf(
            """
            CREATE TABLE IF NOT EXISTS alpha_research_runs (
                run_id TEXT PRIMARY KEY,
                generated_at TIMESTAMPTZ NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_family TEXT NOT NULL,
                mode TEXT NOT NULL,
                exchange TEXT NOT NULL,
                signal_bar_minutes INTEGER NOT NULL,
                forward_hours INTEGER NOT NULL,
                rebalance_cadence_hours INTEGER NOT NULL,
                factor_lookback_days INTEGER NOT NULL,
                selection_quantile DOUBLE PRECISION NOT NULL,
                trend_score_mode TEXT NOT NULL,
                residualization_mode TEXT NOT NULL,
                residualization_beta_mode TEXT NOT NULL,
                residualization_market_proxy_mode TEXT NOT NULL,
                tail_weighting_mode TEXT NOT NULL,
                funding_overlay_mode TEXT NOT NULL,
                exit_overlay_mode TEXT NOT NULL,
                submit_orders BOOLEAN NOT NULL DEFAULT FALSE,
                include_inspection BOOLEAN NOT NULL DEFAULT TRUE,
                validation_accepted BOOLEAN NOT NULL DEFAULT FALSE,
                backtest_accepted BOOLEAN NOT NULL DEFAULT FALSE,
                forward_accepted BOOLEAN NOT NULL DEFAULT FALSE,
                backtest_edge_after_cost_bps DOUBLE PRECISION,
                backtest_trade_count INTEGER NOT NULL DEFAULT 0,
                backtest_net_return_pct DOUBLE PRECISION,
                backtest_calmar DOUBLE PRECISION,
                forward_edge_after_cost_bps DOUBLE PRECISION,
                forward_trade_count INTEGER NOT NULL DEFAULT 0,
                forward_net_return_pct DOUBLE PRECISION,
                forward_calmar DOUBLE PRECISION,
                request_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                backtest_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                forward_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                validation_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                execution_preview_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                notes_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                grafana_path TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS alpha_research_run_signals (
                id BIGSERIAL PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES alpha_research_runs(run_id) ON DELETE CASCADE,
                signal_rank INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                direction TEXT NOT NULL,
                score DOUBLE PRECISION NOT NULL,
                empirical_score DOUBLE PRECISION NOT NULL,
                residual_rank DOUBLE PRECISION NOT NULL,
                confidence DOUBLE PRECISION NOT NULL,
                liquidity_score DOUBLE PRECISION NOT NULL,
                trend_score DOUBLE PRECISION NOT NULL,
                trend_agreement DOUBLE PRECISION NOT NULL,
                pullback_score DOUBLE PRECISION NOT NULL,
                funding_score DOUBLE PRECISION NOT NULL,
                open_interest_score DOUBLE PRECISION NOT NULL,
                expansion_score DOUBLE PRECISION NOT NULL,
                reversal_risk_score DOUBLE PRECISION NOT NULL,
                funding_overlay_multiplier DOUBLE PRECISION NOT NULL,
                market_beta DOUBLE PRECISION NOT NULL,
                upper_bound DOUBLE PRECISION NOT NULL,
                lower_bound DOUBLE PRECISION NOT NULL,
                expected_residual_return_bps DOUBLE PRECISION NOT NULL,
                expected_entry_cost_bps DOUBLE PRECISION NOT NULL,
                expected_turnover_penalty_bps DOUBLE PRECISION NOT NULL,
                expected_net_edge_bps DOUBLE PRECISION NOT NULL,
                close DOUBLE PRECISION NOT NULL,
                predicted_volatility DOUBLE PRECISION NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS alpha_research_run_targets (
                id BIGSERIAL PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES alpha_research_runs(run_id) ON DELETE CASCADE,
                target_rank INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                direction TEXT NOT NULL,
                weight_fraction DOUBLE PRECISION NOT NULL,
                leverage_multiplier DOUBLE PRECISION NOT NULL,
                confidence DOUBLE PRECISION NOT NULL,
                score DOUBLE PRECISION NOT NULL,
                normalized_score DOUBLE PRECISION NOT NULL,
                expected_net_edge_bps DOUBLE PRECISION NOT NULL,
                expected_cost_bps DOUBLE PRECISION NOT NULL,
                turnover_delta_fraction DOUBLE PRECISION NOT NULL,
                trailing_stop_vol_multiple DOUBLE PRECISION NOT NULL,
                take_profit_vol_multiple DOUBLE PRECISION NOT NULL,
                rationale TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS alpha_research_run_trades (
                id BIGSERIAL PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES alpha_research_runs(run_id) ON DELETE CASCADE,
                trade_index INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                direction TEXT NOT NULL,
                entry_time TIMESTAMPTZ NOT NULL,
                exit_time TIMESTAMPTZ NOT NULL,
                entry_price DOUBLE PRECISION NOT NULL,
                exit_price DOUBLE PRECISION NOT NULL,
                weight_fraction DOUBLE PRECISION NOT NULL,
                pnl_pct DOUBLE PRECISION NOT NULL,
                max_favorable_pnl_pct DOUBLE PRECISION NOT NULL,
                profit_giveback_pct DOUBLE PRECISION NOT NULL,
                reason TEXT NOT NULL,
                segment TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS alpha_research_run_symbol_points (
                id BIGSERIAL PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES alpha_research_runs(run_id) ON DELETE CASCADE,
                symbol TEXT NOT NULL,
                time TIMESTAMPTZ NOT NULL,
                close DOUBLE PRECISION NOT NULL,
                score DOUBLE PRECISION NOT NULL,
                empirical_score DOUBLE PRECISION NOT NULL,
                confidence DOUBLE PRECISION NOT NULL,
                regime_score DOUBLE PRECISION NOT NULL,
                expansion_score DOUBLE PRECISION NOT NULL,
                reversal_risk_score DOUBLE PRECISION NOT NULL,
                upper_bound DOUBLE PRECISION NOT NULL,
                lower_bound DOUBLE PRECISION NOT NULL,
                expected_residual_return_bps DOUBLE PRECISION NOT NULL,
                expected_entry_cost_bps DOUBLE PRECISION NOT NULL,
                expected_net_edge_bps DOUBLE PRECISION NOT NULL,
                desired_weight DOUBLE PRECISION NOT NULL,
                applied_delta DOUBLE PRECISION NOT NULL,
                entry_eligible BOOLEAN NOT NULL DEFAULT FALSE,
                regime_blocked BOOLEAN NOT NULL DEFAULT FALSE,
                position_weight DOUBLE PRECISION NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS alpha_research_run_portfolio_snapshots (
                id BIGSERIAL PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES alpha_research_runs(run_id) ON DELETE CASCADE,
                time TIMESTAMPTZ NOT NULL,
                equity DOUBLE PRECISION NOT NULL,
                gross_exposure_fraction DOUBLE PRECISION NOT NULL,
                long_exposure_fraction DOUBLE PRECISION NOT NULL,
                short_exposure_fraction DOUBLE PRECISION NOT NULL,
                net_exposure_fraction DOUBLE PRECISION NOT NULL,
                open_positions INTEGER NOT NULL,
                turnover_fraction DOUBLE PRECISION NOT NULL,
                regime_score DOUBLE PRECISION NOT NULL,
                regime_strength DOUBLE PRECISION NOT NULL,
                aligned_exposure_fraction DOUBLE PRECISION NOT NULL,
                wrong_way_exposure_fraction DOUBLE PRECISION NOT NULL,
                kill_switch_utilization DOUBLE PRECISION NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS alpha_research_run_regimes (
                id BIGSERIAL PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES alpha_research_runs(run_id) ON DELETE CASCADE,
                time TIMESTAMPTZ NOT NULL,
                regime_score DOUBLE PRECISION NOT NULL,
                breadth DOUBLE PRECISION NOT NULL,
                anchor_trend DOUBLE PRECISION NOT NULL,
                dispersion DOUBLE PRECISION NOT NULL,
                realized_volatility DOUBLE PRECISION NOT NULL,
                liquidity_score DOUBLE PRECISION NOT NULL,
                funding_pressure DOUBLE PRECISION NOT NULL,
                open_interest_pressure DOUBLE PRECISION NOT NULL,
                market_trend_score DOUBLE PRECISION NOT NULL,
                gross_exposure_fraction DOUBLE PRECISION NOT NULL,
                long_exposure_fraction DOUBLE PRECISION NOT NULL,
                short_exposure_fraction DOUBLE PRECISION NOT NULL,
                net_exposure_fraction DOUBLE PRECISION NOT NULL,
                aligned_exposure_fraction DOUBLE PRECISION NOT NULL,
                wrong_way_exposure_fraction DOUBLE PRECISION NOT NULL,
                kill_switch_utilization DOUBLE PRECISION NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS alpha_research_run_compression_diagnostics (
                id BIGSERIAL PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES alpha_research_runs(run_id) ON DELETE CASCADE,
                time TIMESTAMPTZ NOT NULL,
                window_bars INTEGER NOT NULL,
                sleeve_size_per_side INTEGER NOT NULL,
                pc1_share DOUBLE PRECISION NOT NULL,
                co_momentum DOUBLE PRECISION NOT NULL,
                pc1_share_z DOUBLE PRECISION NOT NULL,
                co_momentum_z DOUBLE PRECISION NOT NULL,
                future_factor_return_bps DOUBLE PRECISION NOT NULL,
                long_sleeve_size INTEGER NOT NULL,
                short_sleeve_size INTEGER NOT NULL,
                market_trend_score DOUBLE PRECISION NOT NULL,
                breadth DOUBLE PRECISION NOT NULL,
                dispersion DOUBLE PRECISION NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_generated_at ON alpha_research_runs(generated_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_strategy_time ON alpha_research_runs(strategy_family, generated_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_mode_time ON alpha_research_runs(mode, generated_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_exchange_time ON alpha_research_runs(exchange, generated_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_validation_time ON alpha_research_runs(validation_accepted, generated_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_signals_run_rank ON alpha_research_run_signals(run_id, signal_rank)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_signals_run_symbol ON alpha_research_run_signals(run_id, symbol)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_targets_run_rank ON alpha_research_run_targets(run_id, target_rank)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_targets_run_symbol ON alpha_research_run_targets(run_id, symbol)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_trades_run_symbol_entry ON alpha_research_run_trades(run_id, symbol, entry_time)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_trades_run_segment ON alpha_research_run_trades(run_id, segment)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_symbol_points_dedupe ON alpha_research_run_symbol_points(run_id, symbol, time)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_symbol_points_run_symbol_time ON alpha_research_run_symbol_points(run_id, symbol, time)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_portfolio_snapshots_dedupe ON alpha_research_run_portfolio_snapshots(run_id, time)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_portfolio_snapshots_run_time ON alpha_research_run_portfolio_snapshots(run_id, time)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_regimes_dedupe ON alpha_research_run_regimes(run_id, time)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_regimes_run_time ON alpha_research_run_regimes(run_id, time)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_compression_diag_dedupe ON alpha_research_run_compression_diagnostics(run_id, time, window_bars, sleeve_size_per_side)",
            "CREATE INDEX IF NOT EXISTS idx_alpha_research_run_compression_diag_run_time ON alpha_research_run_compression_diagnostics(run_id, time)",
            """
            DO $$
            BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test_runner_user') THEN
                    GRANT SELECT, INSERT ON alpha_research_runs TO test_runner_user;
                    GRANT SELECT, INSERT ON alpha_research_run_signals TO test_runner_user;
                    GRANT SELECT, INSERT ON alpha_research_run_targets TO test_runner_user;
                    GRANT SELECT, INSERT ON alpha_research_run_trades TO test_runner_user;
                    GRANT SELECT, INSERT ON alpha_research_run_symbol_points TO test_runner_user;
                    GRANT SELECT, INSERT ON alpha_research_run_portfolio_snapshots TO test_runner_user;
                    GRANT SELECT, INSERT ON alpha_research_run_regimes TO test_runner_user;
                    GRANT SELECT, INSERT ON alpha_research_run_compression_diagnostics TO test_runner_user;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_user') THEN
                    GRANT SELECT, INSERT, UPDATE ON alpha_research_runs TO pipeline_user;
                    GRANT SELECT, INSERT, UPDATE ON alpha_research_run_signals TO pipeline_user;
                    GRANT SELECT, INSERT, UPDATE ON alpha_research_run_targets TO pipeline_user;
                    GRANT SELECT, INSERT, UPDATE ON alpha_research_run_trades TO pipeline_user;
                    GRANT SELECT, INSERT, UPDATE ON alpha_research_run_symbol_points TO pipeline_user;
                    GRANT SELECT, INSERT, UPDATE ON alpha_research_run_portfolio_snapshots TO pipeline_user;
                    GRANT SELECT, INSERT, UPDATE ON alpha_research_run_regimes TO pipeline_user;
                    GRANT SELECT, INSERT, UPDATE ON alpha_research_run_compression_diagnostics TO pipeline_user;
                END IF;
            END $$;
            """.trimIndent()
        )
    }
}

private fun PreparedStatement.setNullableDouble(index: Int, value: Double?) {
    if (value == null) {
        setNull(index, Types.DOUBLE)
    } else {
        setDouble(index, value)
    }
}
