-- Market Data Schema Migration
-- Creates tables for storing market data from the data pipeline
-- This schema is used by both the pipeline (writes) and trading SDK (reads)

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
ALTER TABLE research_features_1m ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

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

-- Align ownership with the datamancy application role so pipeline_user can evolve schema safely.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_user') THEN
        ALTER TABLE IF EXISTS market_data OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS orderbook_data OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS research_features_1m OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS rss_sentiment_signals OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_backtest_runs OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_latency_metrics OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_execution_costs OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_walkforward_runs OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_sensitivity_sweeps OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_live_backtest_drift OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_universe_profiles OWNER TO pipeline_user;
        ALTER TABLE IF EXISTS strategy_portfolio_profiles OWNER TO pipeline_user;

        ALTER SEQUENCE IF EXISTS rss_sentiment_signals_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_backtest_runs_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_latency_metrics_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_execution_costs_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_walkforward_runs_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_sensitivity_sweeps_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_live_backtest_drift_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_universe_profiles_id_seq OWNER TO pipeline_user;
        ALTER SEQUENCE IF EXISTS strategy_portfolio_profiles_id_seq OWNER TO pipeline_user;
    END IF;
END $$;

-- Grant permissions to test runner
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test_runner_user') THEN
        GRANT SELECT, INSERT ON market_data TO test_runner_user;
        GRANT SELECT, INSERT ON orderbook_data TO test_runner_user;
        GRANT SELECT, INSERT ON research_features_1m TO test_runner_user;
        GRANT SELECT, INSERT ON rss_sentiment_signals TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_backtest_runs TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_latency_metrics TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_execution_costs TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_walkforward_runs TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_sensitivity_sweeps TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_live_backtest_drift TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_universe_profiles TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_portfolio_profiles TO test_runner_user;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO test_runner_user;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_user') THEN
        GRANT SELECT, INSERT, UPDATE ON market_data TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON orderbook_data TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON research_features_1m TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON rss_sentiment_signals TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_backtest_runs TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_latency_metrics TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_execution_costs TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_walkforward_runs TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_sensitivity_sweeps TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_live_backtest_drift TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_universe_profiles TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_portfolio_profiles TO pipeline_user;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pipeline_user;
    END IF;
END $$;

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- Convert tables to TimescaleDB hypertables for optimized time-series storage
SELECT create_hypertable('market_data', 'time', if_not_exists => TRUE);
SELECT create_hypertable('orderbook_data', 'time', if_not_exists => TRUE);
SELECT create_hypertable('research_features_1m', 'time', if_not_exists => TRUE);

-- Create the candle-first discovery index after hypertable conversion.
-- CONCURRENTLY is not supported on Timescale hypertables during reconcile runs,
-- so use Timescale's per-chunk indexing mode instead.
CREATE INDEX IF NOT EXISTS idx_market_data_candle_exchange_type_time_symbol
    ON market_data (exchange, data_type, time DESC, symbol)
    INCLUDE (close, volume)
    WITH (timescaledb.transaction_per_chunk)
    WHERE data_type LIKE 'candle_%';

-- Create a covering orderbook index for bar-bucket joins so depth/spread reads
-- can stay inside the index during research scans.
CREATE INDEX IF NOT EXISTS idx_orderbook_data_symbol_exchange_time_covering
    ON orderbook_data (symbol, exchange, time DESC)
    INCLUDE (spread_pct, bid_depth_10, ask_depth_10, mid_price)
    WITH (timescaledb.transaction_per_chunk);

-- Full-exchange orderbook scans drive minute feature aggregation and need an exchange-first index.
CREATE INDEX IF NOT EXISTS idx_orderbook_data_exchange_time_symbol_covering
    ON orderbook_data (exchange, time DESC, symbol)
    INCLUDE (spread_pct, bid_depth_10, ask_depth_10, mid_price)
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

ALTER TABLE research_features_1m SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'symbol, exchange',
  timescaledb.compress_orderby = 'time DESC'
);

-- Add compression policy: compress chunks older than 7 days
SELECT add_compression_policy('market_data', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('orderbook_data', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('research_features_1m', INTERVAL '7 days', if_not_exists => TRUE);
