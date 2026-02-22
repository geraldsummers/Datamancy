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
    is_liquidation BOOLEAN DEFAULT FALSE,

    PRIMARY KEY (time, symbol, exchange, data_type)
);

-- Create index for efficient time-based queries
CREATE INDEX IF NOT EXISTS idx_market_data_time ON market_data (time DESC);

-- Create index for symbol lookups
CREATE INDEX IF NOT EXISTS idx_market_data_symbol ON market_data (symbol, exchange, data_type, time DESC);

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

-- Grant permissions to test runner
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'test_runner') THEN
        GRANT SELECT, INSERT ON market_data TO test_runner;
        GRANT SELECT, INSERT ON orderbook_data TO test_runner;
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
