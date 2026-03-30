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
);

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
);

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
);

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
);

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
);

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
);

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
);

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
);

CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_generated_at
    ON alpha_research_runs(generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_strategy_time
    ON alpha_research_runs(strategy_family, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_mode_time
    ON alpha_research_runs(mode, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_exchange_time
    ON alpha_research_runs(exchange, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_validation_time
    ON alpha_research_runs(validation_accepted, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_signals_run_rank
    ON alpha_research_run_signals(run_id, signal_rank);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_signals_run_symbol
    ON alpha_research_run_signals(run_id, symbol);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_targets_run_rank
    ON alpha_research_run_targets(run_id, target_rank);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_targets_run_symbol
    ON alpha_research_run_targets(run_id, symbol);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_trades_run_symbol_entry
    ON alpha_research_run_trades(run_id, symbol, entry_time);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_trades_run_segment
    ON alpha_research_run_trades(run_id, segment);
CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_symbol_points_dedupe
    ON alpha_research_run_symbol_points(run_id, symbol, time);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_symbol_points_run_symbol_time
    ON alpha_research_run_symbol_points(run_id, symbol, time);
CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_portfolio_snapshots_dedupe
    ON alpha_research_run_portfolio_snapshots(run_id, time);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_portfolio_snapshots_run_time
    ON alpha_research_run_portfolio_snapshots(run_id, time);
CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_regimes_dedupe
    ON alpha_research_run_regimes(run_id, time);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_regimes_run_time
    ON alpha_research_run_regimes(run_id, time);
CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_compression_diag_dedupe
    ON alpha_research_run_compression_diagnostics(run_id, time, window_bars, sleeve_size_per_side);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_compression_diag_run_time
    ON alpha_research_run_compression_diagnostics(run_id, time);
