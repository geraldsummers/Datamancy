-- Market Data Schema Migration
-- Creates tables for storing market data from the data pipeline
-- This schema is used by both the pipeline (writes) and trading SDK (reads)

-- Create market_data table (unified table for candles and trades)
CREATE TABLE IF NOT EXISTS market_data (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    data_type TEXT NOT NULL,  -- 'candle_1m', 'candle_5m', 'trade', etc.

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
    is_liquidation BOOLEAN DEFAULT FALSE
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

-- Create orderbook_data table
CREATE TABLE IF NOT EXISTS orderbook_data (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    bid_price DOUBLE PRECISION,
    bid_size DOUBLE PRECISION,
    ask_price DOUBLE PRECISION,
    ask_size DOUBLE PRECISION,
    PRIMARY KEY (time, symbol, exchange)
);

-- Create index for efficient time-based queries
CREATE INDEX IF NOT EXISTS idx_orderbook_data_time ON orderbook_data (time DESC);

-- Create index for symbol lookups
CREATE INDEX IF NOT EXISTS idx_orderbook_data_symbol ON orderbook_data (symbol, exchange, time DESC);

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
    model_name TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_rss_sentiment_time ON rss_sentiment_signals (observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_rss_sentiment_symbol_time ON rss_sentiment_signals (symbol, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_rss_sentiment_source_time ON rss_sentiment_signals (source, observed_at DESC);
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

-- Grant permissions to test runner
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test_runner_user') THEN
        GRANT SELECT, INSERT ON market_data TO test_runner_user;
        GRANT SELECT, INSERT ON orderbook_data TO test_runner_user;
        GRANT SELECT, INSERT ON rss_sentiment_signals TO test_runner_user;
        GRANT SELECT, INSERT ON strategy_backtest_runs TO test_runner_user;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO test_runner_user;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_user') THEN
        GRANT SELECT, INSERT, UPDATE ON market_data TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON orderbook_data TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON rss_sentiment_signals TO pipeline_user;
        GRANT SELECT, INSERT, UPDATE ON strategy_backtest_runs TO pipeline_user;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pipeline_user;
    END IF;
END $$;

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- Convert tables to TimescaleDB hypertables for optimized time-series storage
SELECT create_hypertable('market_data', 'time', if_not_exists => TRUE);
SELECT create_hypertable('orderbook_data', 'time', if_not_exists => TRUE);

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

-- Add compression policy: compress chunks older than 7 days
SELECT add_compression_policy('market_data', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_compression_policy('orderbook_data', INTERVAL '7 days', if_not_exists => TRUE);
