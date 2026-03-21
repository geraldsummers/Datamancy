CREATE TABLE IF NOT EXISTS rss_sentiment_signals (
    id BIGSERIAL PRIMARY KEY,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    symbol TEXT NOT NULL,
    source TEXT NOT NULL,
    article_title TEXT,
    article_url TEXT,
    sentiment_score DOUBLE PRECISION NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    sentiment_label TEXT NOT NULL DEFAULT 'neutral',
    provider TEXT,
    explanation TEXT,
    model_name TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

ALTER TABLE rss_sentiment_signals ADD COLUMN IF NOT EXISTS sentiment_label TEXT NOT NULL DEFAULT 'neutral';
ALTER TABLE rss_sentiment_signals ADD COLUMN IF NOT EXISTS provider TEXT;
ALTER TABLE rss_sentiment_signals ADD COLUMN IF NOT EXISTS explanation TEXT;

CREATE INDEX IF NOT EXISTS idx_rss_sentiment_signals_time
    ON rss_sentiment_signals(observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_rss_sentiment_signals_symbol_time
    ON rss_sentiment_signals(symbol, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_rss_sentiment_signals_source_time
    ON rss_sentiment_signals(source, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_rss_sentiment_signals_label_time
    ON rss_sentiment_signals(sentiment_label, observed_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rss_sentiment_signals_dedupe
    ON rss_sentiment_signals (
        source,
        symbol,
        COALESCE(article_url, ''),
        COALESCE(article_title, ''),
        observed_at
    );

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
);

CREATE INDEX IF NOT EXISTS idx_strategy_backtest_runs_run_at
    ON strategy_backtest_runs(run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_backtest_runs_symbol_time
    ON strategy_backtest_runs(symbol, timeframe, run_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_strategy_backtest_runs_dedupe
    ON strategy_backtest_runs (strategy_name, symbol, timeframe, start_time, end_time);

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
);
CREATE INDEX IF NOT EXISTS idx_strategy_latency_metrics_time
    ON strategy_latency_metrics(observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_latency_metrics_strategy_time
    ON strategy_latency_metrics(strategy_name, observed_at DESC);

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
);
CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_time
    ON strategy_execution_costs(observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_strategy_time
    ON strategy_execution_costs(strategy_name, observed_at DESC);

CREATE TABLE IF NOT EXISTS strategy_walkforward_runs (
    id BIGSERIAL PRIMARY KEY,
    run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    strategy_name TEXT NOT NULL,
    symbol TEXT NOT NULL,
    train_start TIMESTAMPTZ NOT NULL,
    train_end TIMESTAMPTZ NOT NULL,
    test_start TIMESTAMPTZ NOT NULL,
    test_end TIMESTAMPTZ NOT NULL,
    net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
    sharpe DOUBLE PRECISION NOT NULL DEFAULT 0,
    max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
    win_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
    trades INTEGER NOT NULL DEFAULT 0,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_strategy_walkforward_runs_time
    ON strategy_walkforward_runs(run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_walkforward_runs_strategy_time
    ON strategy_walkforward_runs(strategy_name, run_at DESC);

CREATE TABLE IF NOT EXISTS strategy_sensitivity_sweeps (
    id BIGSERIAL PRIMARY KEY,
    run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    strategy_name TEXT NOT NULL,
    symbol TEXT NOT NULL,
    parameter_name TEXT NOT NULL,
    parameter_value TEXT NOT NULL,
    net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
    sharpe DOUBLE PRECISION NOT NULL DEFAULT 0,
    max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
    trades INTEGER NOT NULL DEFAULT 0,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_strategy_sensitivity_sweeps_time
    ON strategy_sensitivity_sweeps(run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_sensitivity_sweeps_strategy_time
    ON strategy_sensitivity_sweeps(strategy_name, run_at DESC);

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
);
CREATE INDEX IF NOT EXISTS idx_strategy_live_backtest_drift_time
    ON strategy_live_backtest_drift(observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_live_backtest_drift_strategy_time
    ON strategy_live_backtest_drift(strategy_name, observed_at DESC);
