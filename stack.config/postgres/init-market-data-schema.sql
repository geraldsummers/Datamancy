-- Market Data Schema Migration
-- Creates tables for storing market data from the data pipeline
-- This schema is used by both the pipeline (writes) and trading SDK (reads)

CREATE TABLE IF NOT EXISTS market_data_database_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    database_role TEXT NOT NULL,
    canonical_feature_table TEXT NOT NULL,
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO market_data_database_identity (singleton, database_role, canonical_feature_table)
VALUES (TRUE, 'market_data', 'research_features_1m')
ON CONFLICT (singleton) DO UPDATE
SET database_role = EXCLUDED.database_role,
    canonical_feature_table = EXCLUDED.canonical_feature_table,
    initialized_at = NOW();

-- Create market_data table (unified table for candles, trades, funding, and open interest)
CREATE TABLE IF NOT EXISTS market_data (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    data_type TEXT NOT NULL,  -- 'candle_1m', 'candle_5m', 'trade', 'funding', 'open_interest', etc.

    -- Candle fields (populated for data_type = 'candle_*')
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    close DOUBLE PRECISION,
    volume DOUBLE PRECISION,
    num_trades INTEGER,

    -- Trade fields (populated for data_type = 'trade')
    trade_id TEXT,
    price DOUBLE PRECISION,
    size DOUBLE PRECISION,
    side TEXT,  -- 'buy' or 'sell'
    is_liquidation BOOLEAN DEFAULT FALSE,

    -- Scalar market context fields
    funding_rate DOUBLE PRECISION,
    open_interest DOUBLE PRECISION
);

-- Create index for efficient time-based queries
CREATE INDEX IF NOT EXISTS idx_market_data_time ON market_data (time DESC);

-- Create index for symbol lookups
CREATE INDEX IF NOT EXISTS idx_market_data_symbol ON market_data (symbol, exchange, data_type, time DESC);

-- Enforce uniqueness for trade rows without blocking multiple trades at the same timestamp
CREATE UNIQUE INDEX IF NOT EXISTS idx_market_data_trade_unique
    ON market_data (time, symbol, exchange, data_type, trade_id);

-- Enforce uniqueness for candle rows only
CREATE UNIQUE INDEX IF NOT EXISTS idx_market_data_candle_unique
    ON market_data (time, symbol, exchange, data_type)
    WHERE data_type LIKE 'candle_%';

-- Enforce uniqueness for scalar market context rows
CREATE UNIQUE INDEX IF NOT EXISTS idx_market_data_funding_unique
    ON market_data (time, symbol, exchange, data_type)
    WHERE data_type = 'funding';
CREATE UNIQUE INDEX IF NOT EXISTS idx_market_data_open_interest_unique
    ON market_data (time, symbol, exchange, data_type)
    WHERE data_type = 'open_interest';

ALTER TABLE market_data ADD COLUMN IF NOT EXISTS funding_rate DOUBLE PRECISION;
ALTER TABLE market_data ADD COLUMN IF NOT EXISTS open_interest DOUBLE PRECISION;

-- Create orderbook_data table
CREATE TABLE IF NOT EXISTS orderbook_data (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,

    -- Full depth snapshots
    bids JSONB NOT NULL DEFAULT '[]'::jsonb,
    asks JSONB NOT NULL DEFAULT '[]'::jsonb,

    -- Derived top-of-book and spread metrics
    best_bid DOUBLE PRECISION,
    best_ask DOUBLE PRECISION,
    spread DOUBLE PRECISION,
    spread_pct DOUBLE PRECISION,
    mid_price DOUBLE PRECISION,

    -- Derived depth metrics (top-N aggregate sizes)
    bid_depth_10 DOUBLE PRECISION,
    ask_depth_10 DOUBLE PRECISION,
    PRIMARY KEY (time, symbol, exchange)
);

ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS bids JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS asks JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS best_bid DOUBLE PRECISION;
ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS best_ask DOUBLE PRECISION;
ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS spread DOUBLE PRECISION;
ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS spread_pct DOUBLE PRECISION;
ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS mid_price DOUBLE PRECISION;
ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS bid_depth_10 DOUBLE PRECISION;
ALTER TABLE orderbook_data ADD COLUMN IF NOT EXISTS ask_depth_10 DOUBLE PRECISION;

-- Create index for efficient time-based queries
CREATE INDEX IF NOT EXISTS idx_orderbook_data_time ON orderbook_data (time DESC);

-- Create index for symbol lookups
CREATE INDEX IF NOT EXISTS idx_orderbook_data_symbol ON orderbook_data (symbol, exchange, time DESC);
CREATE INDEX IF NOT EXISTS idx_orderbook_data_spread ON orderbook_data (symbol, spread_pct, time DESC);

-- Narrow minute staging tables decouple feature materialization from append-hot raw scans.
CREATE TABLE IF NOT EXISTS minute_trade_stats (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    trade_volume DOUBLE PRECISION NOT NULL DEFAULT 0,
    buy_volume DOUBLE PRECISION NOT NULL DEFAULT 0,
    sell_volume DOUBLE PRECISION NOT NULL DEFAULT 0,
    trade_count INTEGER NOT NULL DEFAULT 0,
    trade_notional DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_event_time TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time, symbol, exchange)
);

ALTER TABLE minute_trade_stats ADD COLUMN IF NOT EXISTS trade_volume DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE minute_trade_stats ADD COLUMN IF NOT EXISTS buy_volume DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE minute_trade_stats ADD COLUMN IF NOT EXISTS sell_volume DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE minute_trade_stats ADD COLUMN IF NOT EXISTS trade_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE minute_trade_stats ADD COLUMN IF NOT EXISTS trade_notional DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE minute_trade_stats ADD COLUMN IF NOT EXISTS last_event_time TIMESTAMPTZ;
-- Avoid non-constant defaults on repeated ALTER runs against compressed hypertables.
-- Fresh rows still populate this column from the aggregation pipeline.
ALTER TABLE minute_trade_stats ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS minute_orderbook_state (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    best_bid DOUBLE PRECISION,
    best_ask DOUBLE PRECISION,
    spread DOUBLE PRECISION,
    spread_pct DOUBLE PRECISION,
    mid_price DOUBLE PRECISION,
    bid_depth_10 DOUBLE PRECISION NOT NULL DEFAULT 0,
    ask_depth_10 DOUBLE PRECISION NOT NULL DEFAULT 0,
    orderbook_samples INTEGER NOT NULL DEFAULT 0,
    last_event_time TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time, symbol, exchange)
);

ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS best_bid DOUBLE PRECISION;
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS best_ask DOUBLE PRECISION;
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS spread DOUBLE PRECISION;
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS spread_pct DOUBLE PRECISION;
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS mid_price DOUBLE PRECISION;
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS bid_depth_10 DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS ask_depth_10 DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS orderbook_samples INTEGER NOT NULL DEFAULT 0;
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS last_event_time TIMESTAMPTZ;
-- Avoid non-constant defaults on repeated ALTER runs against compressed hypertables.
-- Fresh rows still populate this column from the aggregation pipeline.
ALTER TABLE minute_orderbook_state ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS minute_asset_context (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    funding_rate DOUBLE PRECISION,
    open_interest DOUBLE PRECISION,
    mark_price DOUBLE PRECISION,
    oracle_price DOUBLE PRECISION,
    mid_price DOUBLE PRECISION,
    day_notional_volume DOUBLE PRECISION,
    previous_day_price DOUBLE PRECISION,
    last_event_time TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (time, symbol, exchange)
);

ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS funding_rate DOUBLE PRECISION;
ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS open_interest DOUBLE PRECISION;
ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS mark_price DOUBLE PRECISION;
ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS oracle_price DOUBLE PRECISION;
ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS mid_price DOUBLE PRECISION;
ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS day_notional_volume DOUBLE PRECISION;
ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS previous_day_price DOUBLE PRECISION;
ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS last_event_time TIMESTAMPTZ;
-- Avoid non-constant defaults on repeated ALTER runs against compressed hypertables.
-- Fresh rows still populate this column from the aggregation pipeline.
ALTER TABLE minute_asset_context ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMPTZ;

-- Minute-granularity research feature store used by alpha discovery, walk-forward,
-- and execution realism queries. This is populated from raw candles/trades/orderbooks.
CREATE TABLE IF NOT EXISTS research_features_1m (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,

    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    close DOUBLE PRECISION,
    volume DOUBLE PRECISION,
    num_trades INTEGER,

    trade_volume DOUBLE PRECISION NOT NULL DEFAULT 0,
    buy_volume DOUBLE PRECISION NOT NULL DEFAULT 0,
    sell_volume DOUBLE PRECISION NOT NULL DEFAULT 0,
    trade_count INTEGER NOT NULL DEFAULT 0,
    trade_vwap DOUBLE PRECISION,

    best_bid DOUBLE PRECISION,
    best_ask DOUBLE PRECISION,
    spread DOUBLE PRECISION,
    spread_pct DOUBLE PRECISION,
    mid_price DOUBLE PRECISION,
    bid_depth_10 DOUBLE PRECISION,
    ask_depth_10 DOUBLE PRECISION,
    orderbook_samples INTEGER NOT NULL DEFAULT 0,

    funding_rate DOUBLE PRECISION,
    open_interest DOUBLE PRECISION,

    candle_observed BOOLEAN NOT NULL DEFAULT FALSE,
    trade_observed BOOLEAN NOT NULL DEFAULT FALSE,
    orderbook_observed BOOLEAN NOT NULL DEFAULT FALSE,
    asset_context_observed BOOLEAN NOT NULL DEFAULT FALSE,
    is_provisional BOOLEAN NOT NULL DEFAULT TRUE,
    is_finalized BOOLEAN NOT NULL DEFAULT FALSE,
    finalization_due_at TIMESTAMPTZ,
    finalized_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (time, symbol, exchange)
);

ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS open DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS high DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS low DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS close DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS volume DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS num_trades INTEGER;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS trade_volume DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS buy_volume DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS sell_volume DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS trade_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS trade_vwap DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS best_bid DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS best_ask DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS spread DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS spread_pct DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS mid_price DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS bid_depth_10 DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS ask_depth_10 DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS orderbook_samples INTEGER NOT NULL DEFAULT 0;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS funding_rate DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS open_interest DOUBLE PRECISION;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS candle_observed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS trade_observed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS orderbook_observed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS asset_context_observed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS is_provisional BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS is_finalized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS finalization_due_at TIMESTAMPTZ;
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS finalized_at TIMESTAMPTZ;
-- Avoid non-constant defaults on repeated ALTER runs against a compressed hypertable.
-- Fresh rows still populate this column from the aggregation pipeline.
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMPTZ;

-- Persistent raw sync checkpoints per exchange/symbol/channel.
CREATE TABLE IF NOT EXISTS raw_sync_state (
    exchange TEXT NOT NULL,
    symbol TEXT NOT NULL,
    channel TEXT NOT NULL,
    earliest_raw_time TIMESTAMPTZ,
    latest_raw_time TIMESTAMPTZ,
    last_observed_at TIMESTAMPTZ,
    last_persisted_at TIMESTAMPTZ,
    row_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (exchange, symbol, channel)
);

ALTER TABLE raw_sync_state ADD COLUMN IF NOT EXISTS earliest_raw_time TIMESTAMPTZ;
ALTER TABLE raw_sync_state ADD COLUMN IF NOT EXISTS latest_raw_time TIMESTAMPTZ;
ALTER TABLE raw_sync_state ADD COLUMN IF NOT EXISTS last_observed_at TIMESTAMPTZ;
ALTER TABLE raw_sync_state ADD COLUMN IF NOT EXISTS last_persisted_at TIMESTAMPTZ;
ALTER TABLE raw_sync_state ADD COLUMN IF NOT EXISTS row_count BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS feature_materialization_state (
    exchange TEXT NOT NULL,
    symbol TEXT NOT NULL,
    bar_size_minutes INTEGER NOT NULL,
    earliest_feature_time TIMESTAMPTZ,
    latest_feature_time TIMESTAMPTZ,
    finalized_through TIMESTAMPTZ,
    feature_rows BIGINT NOT NULL DEFAULT 0,
    last_materialized_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (exchange, symbol, bar_size_minutes)
);

ALTER TABLE feature_materialization_state ADD COLUMN IF NOT EXISTS earliest_feature_time TIMESTAMPTZ;
ALTER TABLE feature_materialization_state ADD COLUMN IF NOT EXISTS latest_feature_time TIMESTAMPTZ;
ALTER TABLE feature_materialization_state ADD COLUMN IF NOT EXISTS finalized_through TIMESTAMPTZ;
ALTER TABLE feature_materialization_state ADD COLUMN IF NOT EXISTS feature_rows BIGINT NOT NULL DEFAULT 0;
ALTER TABLE feature_materialization_state ADD COLUMN IF NOT EXISTS last_materialized_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE TABLE IF NOT EXISTS feature_coverage_state (
    exchange TEXT NOT NULL,
    symbol TEXT NOT NULL,
    bar_size_minutes INTEGER NOT NULL,
    earliest_raw_time TIMESTAMPTZ,
    latest_raw_time TIMESTAMPTZ,
    earliest_feature_time TIMESTAMPTZ,
    latest_feature_time TIMESTAMPTZ,
    finalized_through TIMESTAMPTZ,
    expected_bars INTEGER NOT NULL DEFAULT 0,
    observed_bars INTEGER NOT NULL DEFAULT 0,
    finalized_bars INTEGER NOT NULL DEFAULT 0,
    coverage_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
    finalized_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (exchange, symbol, bar_size_minutes)
);

ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS earliest_raw_time TIMESTAMPTZ;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS latest_raw_time TIMESTAMPTZ;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS earliest_feature_time TIMESTAMPTZ;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS latest_feature_time TIMESTAMPTZ;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS finalized_through TIMESTAMPTZ;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS expected_bars INTEGER NOT NULL DEFAULT 0;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS observed_bars INTEGER NOT NULL DEFAULT 0;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS finalized_bars INTEGER NOT NULL DEFAULT 0;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS coverage_ratio DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS finalized_ratio DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE feature_coverage_state ADD COLUMN IF NOT EXISTS last_computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Quantified RSS sentiment scores that can be correlated with market moves
CREATE TABLE IF NOT EXISTS rss_sentiment_signals (
    id BIGSERIAL PRIMARY KEY,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    symbol TEXT NOT NULL,
    source TEXT NOT NULL,
    article_title TEXT,
    article_url TEXT,
    sentiment_score DOUBLE PRECISION NOT NULL, -- normalized to [-1, 1]
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

CREATE INDEX IF NOT EXISTS idx_rss_sentiment_time ON rss_sentiment_signals (observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_rss_sentiment_symbol_time ON rss_sentiment_signals (symbol, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_rss_sentiment_source_time ON rss_sentiment_signals (source, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_rss_sentiment_label_time ON rss_sentiment_signals (sentiment_label, observed_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rss_sentiment_dedupe
    ON rss_sentiment_signals (
        source,
        symbol,
        COALESCE(article_url, ''),
        COALESCE(article_title, ''),
        observed_at
    );

-- Backtest runs written by notebooks/services to compare strategy variants over time
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

CREATE INDEX IF NOT EXISTS idx_strategy_backtest_run_at ON strategy_backtest_runs (run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_backtest_symbol_time ON strategy_backtest_runs (symbol, timeframe, run_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_strategy_backtest_dedupe
    ON strategy_backtest_runs (strategy_name, symbol, timeframe, start_time, end_time);

-- Strategy runtime analytics for latency, execution quality, and drift monitoring
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
CREATE INDEX IF NOT EXISTS idx_strategy_latency_time ON strategy_latency_metrics (observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_latency_strategy_time ON strategy_latency_metrics (strategy_name, observed_at DESC);

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
CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_time ON strategy_execution_costs (observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_strategy_time ON strategy_execution_costs (strategy_name, observed_at DESC);

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
CREATE INDEX IF NOT EXISTS idx_strategy_walkforward_time ON strategy_walkforward_runs (run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_walkforward_strategy_time ON strategy_walkforward_runs (strategy_name, run_at DESC);

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
CREATE INDEX IF NOT EXISTS idx_strategy_sensitivity_time ON strategy_sensitivity_sweeps (run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_sensitivity_strategy_time ON strategy_sensitivity_sweeps (strategy_name, run_at DESC);

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
CREATE INDEX IF NOT EXISTS idx_strategy_drift_time ON strategy_live_backtest_drift (observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_drift_strategy_time ON strategy_live_backtest_drift (strategy_name, observed_at DESC);

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
);
CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_run_at ON strategy_universe_profiles (run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_strategy_time ON strategy_universe_profiles (strategy_name, run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_stage_time ON strategy_universe_profiles (stage, run_at DESC);

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
);
CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_run_at ON strategy_portfolio_profiles (run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_strategy_time ON strategy_portfolio_profiles (strategy_name, run_at DESC);
CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_stage_time ON strategy_portfolio_profiles (stage, run_at DESC);

-- Alpha research run explorer tables back each persisted discovery run with signal,
-- target, trade, and inspection detail for Grafana browsing.
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
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_generated_at ON alpha_research_runs (generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_strategy_time ON alpha_research_runs (strategy_family, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_mode_time ON alpha_research_runs (mode, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_exchange_time ON alpha_research_runs (exchange, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_alpha_research_runs_validation_time ON alpha_research_runs (validation_accepted, generated_at DESC);

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
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_signals_run_rank ON alpha_research_run_signals (run_id, signal_rank);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_signals_run_symbol ON alpha_research_run_signals (run_id, symbol);

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
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_targets_run_rank ON alpha_research_run_targets (run_id, target_rank);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_targets_run_symbol ON alpha_research_run_targets (run_id, symbol);

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
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_trades_run_symbol_entry ON alpha_research_run_trades (run_id, symbol, entry_time);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_trades_run_segment ON alpha_research_run_trades (run_id, segment);

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
CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_symbol_points_dedupe
    ON alpha_research_run_symbol_points (run_id, symbol, time);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_symbol_points_run_symbol_time
    ON alpha_research_run_symbol_points (run_id, symbol, time);

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
CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_portfolio_snapshots_dedupe
    ON alpha_research_run_portfolio_snapshots (run_id, time);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_portfolio_snapshots_run_time
    ON alpha_research_run_portfolio_snapshots (run_id, time);

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
CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_regimes_dedupe
    ON alpha_research_run_regimes (run_id, time);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_regimes_run_time
    ON alpha_research_run_regimes (run_id, time);

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
CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_research_run_compression_diag_dedupe
    ON alpha_research_run_compression_diagnostics (run_id, time, window_bars, sleeve_size_per_side);
CREATE INDEX IF NOT EXISTS idx_alpha_research_run_compression_diag_run_time
    ON alpha_research_run_compression_diagnostics (run_id, time);

-- Canonical symbol-level data health contract for the 1m research layer.
DROP VIEW IF EXISTS data_health_exchange_1m;
DROP VIEW IF EXISTS data_health_symbol_1m;

CREATE VIEW data_health_symbol_1m AS
WITH symbol_universe AS (
    SELECT DISTINCT exchange, symbol FROM raw_sync_state
    UNION
    SELECT DISTINCT exchange, symbol FROM feature_materialization_state WHERE bar_size_minutes = 1
    UNION
    SELECT DISTINCT exchange, symbol FROM feature_coverage_state WHERE bar_size_minutes = 1
    UNION
    SELECT DISTINCT exchange, symbol FROM research_features_1m
),
raw_pivot AS (
    SELECT
        exchange,
        symbol,
        MAX(latest_raw_time) FILTER (WHERE channel = 'candle_1m') AS candle_1m_latest_raw_time,
        MAX(latest_raw_time) FILTER (WHERE channel = 'trade') AS trade_latest_raw_time,
        MAX(latest_raw_time) FILTER (WHERE channel = 'orderbook_l2') AS orderbook_l2_latest_raw_time,
        MAX(latest_raw_time) FILTER (WHERE channel = 'funding') AS funding_latest_raw_time,
        MAX(latest_raw_time) FILTER (WHERE channel = 'open_interest') AS open_interest_latest_raw_time,
        MAX(last_observed_at) FILTER (WHERE channel = 'candle_1m') AS candle_1m_last_observed_at,
        MAX(last_observed_at) FILTER (WHERE channel = 'trade') AS trade_last_observed_at,
        MAX(last_observed_at) FILTER (WHERE channel = 'orderbook_l2') AS orderbook_l2_last_observed_at,
        MAX(last_observed_at) FILTER (WHERE channel = 'funding') AS funding_last_observed_at,
        MAX(last_observed_at) FILTER (WHERE channel = 'open_interest') AS open_interest_last_observed_at,
        MAX(last_persisted_at) FILTER (WHERE channel = 'candle_1m') AS candle_1m_last_persisted_at,
        MAX(last_persisted_at) FILTER (WHERE channel = 'trade') AS trade_last_persisted_at,
        MAX(last_persisted_at) FILTER (WHERE channel = 'orderbook_l2') AS orderbook_l2_last_persisted_at,
        MAX(last_persisted_at) FILTER (WHERE channel = 'funding') AS funding_last_persisted_at,
        MAX(last_persisted_at) FILTER (WHERE channel = 'open_interest') AS open_interest_last_persisted_at,
        SUM(row_count) FILTER (WHERE channel = 'candle_1m') AS candle_1m_row_count,
        SUM(row_count) FILTER (WHERE channel = 'trade') AS trade_row_count,
        SUM(row_count) FILTER (WHERE channel = 'orderbook_l2') AS orderbook_l2_row_count,
        SUM(row_count) FILTER (WHERE channel = 'funding') AS funding_row_count,
        SUM(row_count) FILTER (WHERE channel = 'open_interest') AS open_interest_row_count
    FROM raw_sync_state
    GROUP BY exchange, symbol
),
recent_features AS (
    SELECT
        exchange,
        symbol,
        COUNT(*) FILTER (WHERE time >= NOW() - INTERVAL '24 hours') AS recent_feature_rows_24h,
        COUNT(*) FILTER (WHERE time >= NOW() - INTERVAL '24 hours' AND candle_observed) AS recent_candle_observed_rows_24h,
        COUNT(*) FILTER (WHERE time >= NOW() - INTERVAL '24 hours' AND trade_observed) AS recent_trade_observed_rows_24h,
        COUNT(*) FILTER (WHERE time >= NOW() - INTERVAL '24 hours' AND orderbook_observed) AS recent_orderbook_observed_rows_24h,
        COUNT(*) FILTER (
            WHERE time >= NOW() - INTERVAL '24 hours'
              AND orderbook_observed
              AND COALESCE(spread, 0) > 0
              AND (COALESCE(bid_depth_10, 0) > 0 OR COALESCE(ask_depth_10, 0) > 0)
        ) AS recent_execution_observed_rows_24h,
        COUNT(*) FILTER (WHERE time >= NOW() - INTERVAL '24 hours' AND is_finalized) AS recent_finalized_rows_24h
    FROM research_features_1m
    GROUP BY exchange, symbol
),
base AS (
    SELECT
        su.exchange,
        su.symbol,
        rp.candle_1m_latest_raw_time,
        rp.trade_latest_raw_time,
        rp.orderbook_l2_latest_raw_time,
        rp.funding_latest_raw_time,
        rp.open_interest_latest_raw_time,
        rp.candle_1m_last_observed_at,
        rp.trade_last_observed_at,
        rp.orderbook_l2_last_observed_at,
        rp.funding_last_observed_at,
        rp.open_interest_last_observed_at,
        rp.candle_1m_last_persisted_at,
        rp.trade_last_persisted_at,
        rp.orderbook_l2_last_persisted_at,
        rp.funding_last_persisted_at,
        rp.open_interest_last_persisted_at,
        COALESCE(rp.candle_1m_row_count, 0) AS candle_1m_row_count,
        COALESCE(rp.trade_row_count, 0) AS trade_row_count,
        COALESCE(rp.orderbook_l2_row_count, 0) AS orderbook_l2_row_count,
        COALESCE(rp.funding_row_count, 0) AS funding_row_count,
        COALESCE(rp.open_interest_row_count, 0) AS open_interest_row_count,
        fms.earliest_feature_time,
        fms.latest_feature_time,
        fms.finalized_through,
        COALESCE(fms.feature_rows, 0) AS feature_rows,
        fms.last_materialized_at,
        fcs.earliest_raw_time,
        fcs.latest_raw_time,
        fcs.earliest_feature_time AS coverage_earliest_feature_time,
        fcs.latest_feature_time AS coverage_latest_feature_time,
        fcs.finalized_through AS coverage_finalized_through,
        COALESCE(fcs.expected_bars, 0) AS historical_expected_bars,
        COALESCE(fcs.observed_bars, 0) AS historical_observed_bars,
        COALESCE(fcs.finalized_bars, 0) AS historical_finalized_bars,
        COALESCE(fcs.coverage_ratio, 0) AS historical_coverage_ratio,
        COALESCE(fcs.finalized_ratio, 0) AS historical_finalized_ratio,
        CASE
            WHEN rp.candle_1m_latest_raw_time IS NULL THEN 0
            ELSE GREATEST(
                (
                    EXTRACT(
                        EPOCH FROM (
                            date_trunc('minute', rp.candle_1m_latest_raw_time) -
                            GREATEST(
                                date_trunc('minute', COALESCE(fcs.earliest_raw_time, rp.candle_1m_latest_raw_time)),
                                date_trunc('minute', NOW() - INTERVAL '24 hours')
                            )
                        )
                    ) / 60
                )::INTEGER + 1,
                0
            )
        END AS recent_expected_bars_24h,
        fcs.last_computed_at,
        COALESCE(rf.recent_feature_rows_24h, 0) AS recent_feature_rows_24h,
        COALESCE(rf.recent_candle_observed_rows_24h, 0) AS recent_candle_observed_rows_24h,
        COALESCE(rf.recent_trade_observed_rows_24h, 0) AS recent_trade_observed_rows_24h,
        COALESCE(rf.recent_orderbook_observed_rows_24h, 0) AS recent_orderbook_observed_rows_24h,
        COALESCE(rf.recent_execution_observed_rows_24h, 0) AS recent_execution_observed_rows_24h,
        COALESCE(rf.recent_finalized_rows_24h, 0) AS recent_finalized_rows_24h,
        NULLIF(
            GREATEST(
                COALESCE(rp.candle_1m_latest_raw_time, '-infinity'::timestamptz),
                COALESCE(rp.trade_latest_raw_time, '-infinity'::timestamptz),
                COALESCE(rp.orderbook_l2_latest_raw_time, '-infinity'::timestamptz),
                COALESCE(rp.funding_latest_raw_time, '-infinity'::timestamptz),
                COALESCE(rp.open_interest_latest_raw_time, '-infinity'::timestamptz)
            ),
            '-infinity'::timestamptz
        ) AS latest_any_raw_time
    FROM symbol_universe su
    LEFT JOIN raw_pivot rp
        ON rp.exchange = su.exchange
       AND rp.symbol = su.symbol
    LEFT JOIN feature_materialization_state fms
        ON fms.exchange = su.exchange
       AND fms.symbol = su.symbol
       AND fms.bar_size_minutes = 1
    LEFT JOIN feature_coverage_state fcs
        ON fcs.exchange = su.exchange
       AND fcs.symbol = su.symbol
       AND fcs.bar_size_minutes = 1
    LEFT JOIN recent_features rf
        ON rf.exchange = su.exchange
       AND rf.symbol = su.symbol
)
SELECT
    base.*,
    COALESCE(recent_expected_bars_24h, 0) AS expected_bars,
    COALESCE(recent_feature_rows_24h, 0) AS observed_bars,
    COALESCE(recent_finalized_rows_24h, 0) AS finalized_bars,
    CASE
        WHEN COALESCE(recent_expected_bars_24h, 0) <= 0 THEN 0
        ELSE LEAST(recent_feature_rows_24h::DOUBLE PRECISION / recent_expected_bars_24h::DOUBLE PRECISION, 1.0)
    END AS coverage_ratio,
    CASE
        WHEN COALESCE(recent_expected_bars_24h, 0) <= 0 THEN 0
        ELSE LEAST(recent_finalized_rows_24h::DOUBLE PRECISION / recent_expected_bars_24h::DOUBLE PRECISION, 1.0)
    END AS finalized_ratio,
    CASE
        WHEN COALESCE(recent_expected_bars_24h, 0) <= 0 THEN 0
        ELSE LEAST(recent_feature_rows_24h::DOUBLE PRECISION / recent_expected_bars_24h::DOUBLE PRECISION, 1.0)
    END AS recent_coverage_ratio_24h,
    CASE
        WHEN COALESCE(recent_expected_bars_24h, 0) <= 0 THEN 0
        ELSE LEAST(recent_finalized_rows_24h::DOUBLE PRECISION / recent_expected_bars_24h::DOUBLE PRECISION, 1.0)
    END AS recent_finalized_ratio_24h,
    CASE
        WHEN candle_1m_latest_raw_time IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - candle_1m_latest_raw_time)), 0)::BIGINT
    END AS candle_1m_raw_lag_seconds,
    CASE
        WHEN trade_latest_raw_time IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - trade_latest_raw_time)), 0)::BIGINT
    END AS trade_raw_lag_seconds,
    CASE
        WHEN orderbook_l2_latest_raw_time IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - orderbook_l2_latest_raw_time)), 0)::BIGINT
    END AS orderbook_l2_raw_lag_seconds,
    CASE
        WHEN funding_latest_raw_time IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - funding_latest_raw_time)), 0)::BIGINT
    END AS funding_raw_lag_seconds,
    CASE
        WHEN open_interest_latest_raw_time IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - open_interest_latest_raw_time)), 0)::BIGINT
    END AS open_interest_raw_lag_seconds,
    CASE
        WHEN latest_feature_time IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - latest_feature_time)), 0)::BIGINT
    END AS feature_lag_seconds,
    CASE
        WHEN finalized_through IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - finalized_through)) / 60.0, 0)
    END AS finalized_lag_minutes,
    CASE
        WHEN last_materialized_at IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - last_materialized_at)), 0)::BIGINT
    END AS materializer_lag_seconds,
    CASE
        WHEN last_computed_at IS NULL THEN NULL
        ELSE GREATEST(EXTRACT(EPOCH FROM (NOW() - last_computed_at)), 0)::BIGINT
    END AS coverage_state_lag_seconds,
    CASE
        WHEN recent_feature_rows_24h = 0 THEN 0
        ELSE recent_candle_observed_rows_24h::DOUBLE PRECISION / recent_feature_rows_24h::DOUBLE PRECISION
    END AS recent_candle_observed_share_24h,
    CASE
        WHEN recent_feature_rows_24h = 0 THEN 0
        ELSE recent_trade_observed_rows_24h::DOUBLE PRECISION / recent_feature_rows_24h::DOUBLE PRECISION
    END AS recent_trade_observed_share_24h,
    CASE
        WHEN recent_feature_rows_24h = 0 THEN 0
        ELSE recent_orderbook_observed_rows_24h::DOUBLE PRECISION / recent_feature_rows_24h::DOUBLE PRECISION
    END AS recent_orderbook_observed_share_24h,
    CASE
        WHEN recent_feature_rows_24h = 0 THEN 0
        ELSE recent_execution_observed_rows_24h::DOUBLE PRECISION / recent_feature_rows_24h::DOUBLE PRECISION
    END AS recent_execution_observed_share_24h,
    CASE
        WHEN recent_feature_rows_24h = 0 THEN 0
        ELSE recent_finalized_rows_24h::DOUBLE PRECISION / recent_feature_rows_24h::DOUBLE PRECISION
    END AS recent_finalized_share_24h,
    (
        latest_any_raw_time IS NOT NULL
        AND latest_any_raw_time >= NOW() - INTERVAL '6 hours'
    ) AS active_recent
FROM base;

CREATE VIEW data_health_exchange_1m AS
SELECT
    exchange,
    COUNT(*) AS tracked_symbols,
    COUNT(*) FILTER (WHERE active_recent) AS active_symbols,
    COUNT(*) FILTER (WHERE NOT active_recent) AS inactive_symbols,
    COUNT(*) FILTER (WHERE active_recent AND candle_1m_latest_raw_time IS NULL) AS active_symbols_missing_candle_1m,
    COUNT(*) FILTER (WHERE active_recent AND latest_feature_time IS NULL) AS active_symbols_missing_features,
    COUNT(*) FILTER (WHERE active_recent AND recent_feature_rows_24h = 0) AS active_symbols_without_recent_features,
    MAX(candle_1m_raw_lag_seconds) FILTER (WHERE active_recent) AS max_candle_1m_raw_lag_seconds,
    MAX(feature_lag_seconds) FILTER (WHERE active_recent) AS max_feature_lag_seconds,
    AVG(coverage_ratio) FILTER (WHERE active_recent) AS avg_coverage_ratio,
    AVG(finalized_ratio) FILTER (WHERE active_recent) AS avg_finalized_ratio,
    AVG(recent_execution_observed_share_24h) FILTER (WHERE active_recent) AS avg_recent_execution_observed_share_24h,
    AVG(recent_finalized_share_24h) FILTER (WHERE active_recent) AS avg_recent_finalized_share_24h,
    MAX(latest_any_raw_time) FILTER (WHERE active_recent) AS latest_any_raw_time,
    MAX(latest_feature_time) FILTER (WHERE active_recent) AS latest_feature_time,
    MAX(finalized_through) FILTER (WHERE active_recent) AS finalized_through
FROM data_health_symbol_1m
GROUP BY exchange;

-- Align ownership with the datamancy application role so pipeline_user can evolve schema safely.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_user') THEN
        ALTER TABLE IF EXISTS market_data_database_identity OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS market_data OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS orderbook_data OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS minute_trade_stats OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS minute_orderbook_state OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS minute_asset_context OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS research_features_1m OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS raw_sync_state OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS feature_materialization_state OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS feature_coverage_state OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS rss_sentiment_signals OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_backtest_runs OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_latency_metrics OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_execution_costs OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_walkforward_runs OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_sensitivity_sweeps OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_live_backtest_drift OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_universe_profiles OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_portfolio_profiles OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS alpha_research_runs OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS alpha_research_run_signals OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS alpha_research_run_targets OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS alpha_research_run_trades OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS alpha_research_run_symbol_points OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS alpha_research_run_portfolio_snapshots OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS alpha_research_run_regimes OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS alpha_research_run_compression_diagnostics OWNER TO pipeline_user;
        ALTER VIEW IF EXISTS data_health_symbol_1m OWNER TO pipeline_user;
        ALTER VIEW IF EXISTS data_health_exchange_1m OWNER TO pipeline_user;

        ALTER SEQUENCE IF EXISTS rss_sentiment_signals_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_backtest_runs_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_latency_metrics_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_execution_costs_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_walkforward_runs_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_sensitivity_sweeps_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_live_backtest_drift_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_universe_profiles_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_portfolio_profiles_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS alpha_research_run_signals_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS alpha_research_run_targets_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS alpha_research_run_trades_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS alpha_research_run_symbol_points_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS alpha_research_run_portfolio_snapshots_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS alpha_research_run_regimes_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS alpha_research_run_compression_diagnostics_id_seq OWNER TO pipeline_user;
    END IF;
END $$;

-- Grant permissions to test runner
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'search_service_user') THEN
        GRANT SELECT ON market_data_database_identity TO search_service_user;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test_runner_user') THEN
        GRANT SELECT ON market_data_database_identity TO test_runner_user;
        GRANT SELECT, INSERT ON market_data TO test_runner_user;
        GRANT SELECT, INSERT ON orderbook_data TO test_runner_user;
        GRANT SELECT, INSERT ON minute_trade_stats TO test_runner_user;
        GRANT SELECT, INSERT ON minute_orderbook_state TO test_runner_user;
        GRANT SELECT, INSERT ON minute_asset_context TO test_runner_user;
        GRANT SELECT, INSERT ON research_features_1m TO test_runner_user;
        GRANT SELECT, INSERT ON raw_sync_state TO test_runner_user;
        GRANT SELECT, INSERT ON feature_materialization_state TO test_runner_user;
        GRANT SELECT, INSERT ON feature_coverage_state TO test_runner_user;
        GRANT SELECT, INSERT ON rss_sentiment_signals TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_backtest_runs TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_latency_metrics TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_execution_costs TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_walkforward_runs TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_sensitivity_sweeps TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_live_backtest_drift TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_universe_profiles TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_portfolio_profiles TO test_runner_user;
        GRANT SELECT, INSERT ON alpha_research_runs TO test_runner_user;
        GRANT SELECT, INSERT ON alpha_research_run_signals TO test_runner_user;
        GRANT SELECT, INSERT ON alpha_research_run_targets TO test_runner_user;
        GRANT SELECT, INSERT ON alpha_research_run_trades TO test_runner_user;
        GRANT SELECT, INSERT ON alpha_research_run_symbol_points TO test_runner_user;
        GRANT SELECT, INSERT ON alpha_research_run_portfolio_snapshots TO test_runner_user;
        GRANT SELECT, INSERT ON alpha_research_run_regimes TO test_runner_user;
        GRANT SELECT, INSERT ON alpha_research_run_compression_diagnostics TO test_runner_user;
        GRANT SELECT ON data_health_symbol_1m TO test_runner_user;
        GRANT SELECT ON data_health_exchange_1m TO test_runner_user;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO test_runner_user;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_user') THEN
        GRANT SELECT ON market_data_database_identity TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON market_data TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON orderbook_data TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON minute_trade_stats TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON minute_orderbook_state TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON minute_asset_context TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON research_features_1m TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON raw_sync_state TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON feature_materialization_state TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON feature_coverage_state TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON rss_sentiment_signals TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_backtest_runs TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_latency_metrics TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_execution_costs TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_walkforward_runs TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_sensitivity_sweeps TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_live_backtest_drift TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_universe_profiles TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_portfolio_profiles TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON alpha_research_runs TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON alpha_research_run_signals TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON alpha_research_run_targets TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON alpha_research_run_trades TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON alpha_research_run_symbol_points TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON alpha_research_run_portfolio_snapshots TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON alpha_research_run_regimes TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON alpha_research_run_compression_diagnostics TO pipeline_user;
        GRANT SELECT ON data_health_symbol_1m TO pipeline_user;
        GRANT SELECT ON data_health_exchange_1m TO pipeline_user;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pipeline_user;
    END IF;
END $$;

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- Convert tables to TimescaleDB hypertables for optimized time-series storage
SELECT create_hypertable('market_data', 'time', if_not_exists => TRUE);
SELECT create_hypertable('orderbook_data', 'time', if_not_exists => TRUE);
SELECT create_hypertable('minute_trade_stats', 'time', if_not_exists => TRUE);
SELECT create_hypertable('minute_orderbook_state', 'time', if_not_exists => TRUE);
SELECT create_hypertable('minute_asset_context', 'time', if_not_exists => TRUE);
SELECT create_hypertable('research_features_1m', 'time', if_not_exists => TRUE);

-- Create the candle-first discovery index after hypertable conversion.
-- CONCURRENTLY is not supported on Timescale hypertables during reconcile runs,
-- so use Timescale's per-chunk indexing mode instead.
CREATE INDEX IF NOT EXISTS idx_market_data_candle_exchange_type_time_symbol
    ON market_data (exchange, data_type, time DESC, symbol)
    INCLUDE (close, volume)
    WITH (timescaledb.transaction_per_chunk)
    WHERE data_type LIKE 'candle_%';

-- Minute feature aggregation also scans trade and carry rows exchange-first by time range.
CREATE INDEX IF NOT EXISTS idx_market_data_trade_exchange_time_symbol_covering
    ON market_data (exchange, time DESC, symbol)
    INCLUDE (price, size, side)
    WITH (timescaledb.transaction_per_chunk)
    WHERE data_type = 'trade';

CREATE INDEX IF NOT EXISTS idx_market_data_funding_exchange_time_symbol_covering
    ON market_data (exchange, time DESC, symbol)
    INCLUDE (funding_rate)
    WITH (timescaledb.transaction_per_chunk)
    WHERE data_type = 'funding';

CREATE INDEX IF NOT EXISTS idx_market_data_open_interest_exchange_time_symbol_covering
    ON market_data (exchange, time DESC, symbol)
    INCLUDE (open_interest)
    WITH (timescaledb.transaction_per_chunk)
    WHERE data_type = 'open_interest';

-- Create a covering orderbook index for bar-bucket joins so depth/spread reads
-- can stay inside the index during research scans.
CREATE INDEX IF NOT EXISTS idx_orderbook_data_symbol_exchange_time_covering
    ON orderbook_data (symbol, exchange, time DESC)
    INCLUDE (spread_pct, bid_depth_10, ask_depth_10, mid_price)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_orderbook_data_symbol_exchange_time_full_covering
    ON orderbook_data (symbol, exchange, time DESC)
    INCLUDE (best_bid, best_ask, spread, spread_pct, bid_depth_10, ask_depth_10, mid_price)
    WITH (timescaledb.transaction_per_chunk);

-- Full-exchange orderbook scans drive minute feature aggregation and need an exchange-first index.
CREATE INDEX IF NOT EXISTS idx_orderbook_data_exchange_time_symbol_covering
    ON orderbook_data (exchange, time DESC, symbol)
    INCLUDE (spread_pct, bid_depth_10, ask_depth_10, mid_price)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_orderbook_data_exchange_time_symbol_full_covering
    ON orderbook_data (exchange, time DESC, symbol)
    INCLUDE (best_bid, best_ask, spread, spread_pct, bid_depth_10, ask_depth_10, mid_price)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_minute_trade_stats_exchange_time_symbol
    ON minute_trade_stats (exchange, time DESC, symbol)
    INCLUDE (trade_volume, buy_volume, sell_volume, trade_count, trade_notional, last_event_time)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_minute_orderbook_state_exchange_time_symbol
    ON minute_orderbook_state (exchange, time DESC, symbol)
    INCLUDE (best_bid, best_ask, spread, spread_pct, mid_price, bid_depth_10, ask_depth_10, orderbook_samples, last_event_time)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_minute_asset_context_exchange_time_symbol
    ON minute_asset_context (exchange, time DESC, symbol)
    INCLUDE (funding_rate, open_interest, mark_price, oracle_price, mid_price, day_notional_volume, previous_day_price, last_event_time)
    WITH (timescaledb.transaction_per_chunk);

-- research_features_1m is the primary research read path for universe scans and bar loading.
CREATE INDEX IF NOT EXISTS idx_research_features_1m_exchange_time_symbol
    ON research_features_1m (exchange, time DESC, symbol)
    INCLUDE (close, volume, spread_pct, bid_depth_10, ask_depth_10, mid_price, candle_observed, orderbook_observed)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_research_features_1m_symbol_exchange_time
    ON research_features_1m (symbol, exchange, time DESC)
    INCLUDE (close, volume, spread_pct, bid_depth_10, ask_depth_10, mid_price, candle_observed, orderbook_observed)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_research_features_1m_exchange_finalized_time_symbol
    ON research_features_1m (exchange, is_finalized, time DESC, symbol)
    INCLUDE (candle_observed, orderbook_observed, source_updated_at)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_research_features_1m_exchange_finalization_due
    ON research_features_1m (exchange, finalization_due_at, symbol)
    INCLUDE (is_finalized, is_provisional, finalized_at)
    WITH (timescaledb.transaction_per_chunk);

CREATE INDEX IF NOT EXISTS idx_raw_sync_state_exchange_channel_symbol
    ON raw_sync_state (exchange, channel, symbol);

CREATE INDEX IF NOT EXISTS idx_raw_sync_state_exchange_channel_latest
    ON raw_sync_state (exchange, channel, latest_raw_time DESC);

CREATE INDEX IF NOT EXISTS idx_feature_materialization_state_exchange_bar_symbol
    ON feature_materialization_state (exchange, bar_size_minutes, symbol);

CREATE INDEX IF NOT EXISTS idx_feature_materialization_state_exchange_bar_latest
    ON feature_materialization_state (exchange, bar_size_minutes, latest_feature_time DESC);

CREATE INDEX IF NOT EXISTS idx_feature_coverage_state_exchange_bar_symbol
    ON feature_coverage_state (exchange, bar_size_minutes, symbol);

CREATE INDEX IF NOT EXISTS idx_feature_coverage_state_exchange_bar_ratio
    ON feature_coverage_state (exchange, bar_size_minutes, coverage_ratio DESC, finalized_ratio DESC);

-- Enable compression on hypertables (optional, for better storage efficiency)
ALTER TABLE market_data SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'symbol, exchange, data_type',
  timescaledb.compress_orderby = 'time DESC'
);

ALTER TABLE orderbook_data SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'symbol, exchange',
  timescaledb.compress_orderby = 'time DESC'
);

ALTER TABLE minute_trade_stats SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'symbol, exchange',
  timescaledb.compress_orderby = 'time DESC'
);

ALTER TABLE minute_orderbook_state SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'symbol, exchange',
  timescaledb.compress_orderby = 'time DESC'
);

ALTER TABLE minute_asset_context SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'symbol, exchange',
  timescaledb.compress_orderby = 'time DESC'
);

ALTER TABLE research_features_1m SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'symbol, exchange',
  timescaledb.compress_orderby = 'time DESC'
);

-- Add compression policy: compress chunks older than 7 days
SELECT add_compression_policy('market_data', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('orderbook_data', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('minute_trade_stats', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('minute_orderbook_state', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('minute_asset_context', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('research_features_1m', INTERVAL '7 days', if_not_exists => TRUE);
