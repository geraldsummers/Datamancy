-- =============================================================================
-- Unified Market Data Tables
-- =============================================================================
-- This migration creates two unified tables optimized for Grafana/Jupyter:
-- 1. market_data: ALL trades, candles (all intervals), funding rates
-- 2. orderbook_data: Orderbook snapshots with pre-calculated metrics
-- =============================================================================

-- =============================================================================
-- MARKET DATA TABLE - All trades, candles, ticks in one place
-- =============================================================================
CREATE TABLE IF NOT EXISTS market_data (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    data_type TEXT NOT NULL,  -- 'trade', 'candle_1m', 'candle_5m', 'candle_1h', 'candle_1d', 'funding'

    -- Trade fields
    trade_id TEXT,
    price NUMERIC(20, 8),
    size NUMERIC(20, 8),
    side TEXT,  -- 'buy', 'sell'
    is_liquidation BOOLEAN DEFAULT FALSE,

    -- Candle fields (OHLCV)
    open NUMERIC(20, 8),
    high NUMERIC(20, 8),
    low NUMERIC(20, 8),
    close NUMERIC(20, 8),
    volume NUMERIC(20, 8),
    num_trades INTEGER,

    -- Funding rate fields
    funding_rate NUMERIC(20, 8),

    -- Metadata
    inserted_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for fast queries
CREATE INDEX IF NOT EXISTS idx_market_data_time ON market_data (time DESC);
CREATE INDEX IF NOT EXISTS idx_market_data_symbol_time ON market_data (symbol, time DESC);
CREATE INDEX IF NOT EXISTS idx_market_data_type_time ON market_data (data_type, time DESC);
CREATE INDEX IF NOT EXISTS idx_market_data_symbol_type ON market_data (symbol, data_type, time DESC);

-- =============================================================================
-- ORDERBOOK DATA TABLE - All orderbook snapshots
-- =============================================================================
CREATE TABLE IF NOT EXISTS orderbook_data (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,

    -- Bid/Ask arrays (stored as JSON for flexibility)
    bids JSONB NOT NULL,  -- [{price: "50000", size: "1.5"}, ...]
    asks JSONB NOT NULL,  -- [{price: "50001", size: "2.0"}, ...]

    -- Aggregated metrics for quick analysis
    best_bid NUMERIC(20, 8),
    best_ask NUMERIC(20, 8),
    spread NUMERIC(20, 8),
    spread_pct NUMERIC(10, 6),
    mid_price NUMERIC(20, 8),

    -- Depth metrics
    bid_depth_10 NUMERIC(20, 8),  -- Total size in top 10 bids
    ask_depth_10 NUMERIC(20, 8),  -- Total size in top 10 asks

    inserted_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for fast queries
CREATE INDEX IF NOT EXISTS idx_orderbook_time ON orderbook_data (time DESC);
CREATE INDEX IF NOT EXISTS idx_orderbook_symbol_time ON orderbook_data (symbol, time DESC);
CREATE INDEX IF NOT EXISTS idx_orderbook_spread ON orderbook_data (symbol, spread_pct);

-- =============================================================================
-- STRATEGY & POSITION TRACKING TABLES
-- =============================================================================

CREATE TABLE IF NOT EXISTS strategies (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    config JSONB NOT NULL,
    status TEXT NOT NULL,  -- 'active', 'paused', 'stopped'
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS positions (
    id TEXT PRIMARY KEY,
    strategy_id TEXT REFERENCES strategies(id),
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    side TEXT NOT NULL,  -- 'buy', 'sell'
    entry_time TIMESTAMPTZ NOT NULL,
    entry_price NUMERIC(20, 8) NOT NULL,
    size NUMERIC(20, 8) NOT NULL,
    exit_time TIMESTAMPTZ,
    exit_price NUMERIC(20, 8),
    realized_pnl NUMERIC(20, 8),
    status TEXT NOT NULL,  -- 'open', 'closed'
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_positions_strategy ON positions (strategy_id, status);
CREATE INDEX IF NOT EXISTS idx_positions_symbol ON positions (symbol, status);

-- =============================================================================
-- PERFORMANCE TRACKING TABLE
-- =============================================================================

CREATE TABLE IF NOT EXISTS strategy_performance (
    strategy_id TEXT REFERENCES strategies(id),
    date DATE NOT NULL,
    num_trades INTEGER DEFAULT 0,
    winning_trades INTEGER DEFAULT 0,
    losing_trades INTEGER DEFAULT 0,
    total_pnl NUMERIC(20, 8) DEFAULT 0,
    volume_traded NUMERIC(20, 8) DEFAULT 0,
    max_drawdown NUMERIC(20, 8),
    sharpe_ratio NUMERIC(10, 4),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (strategy_id, date)
);

-- =============================================================================
-- HELPFUL COMMENTS
-- =============================================================================

COMMENT ON TABLE market_data IS 'Unified time-series table for all market data: trades, candles, funding rates. Optimized for Grafana/Jupyter queries.';
COMMENT ON TABLE orderbook_data IS 'Orderbook snapshots with pre-calculated metrics for analysis';
COMMENT ON COLUMN market_data.data_type IS 'Type: trade, candle_1m, candle_5m, candle_1h, candle_1d, candle_1w, funding';
COMMENT ON COLUMN market_data.side IS 'Trade side: buy or sell';
COMMENT ON COLUMN orderbook_data.bids IS 'Array of bid levels as JSONB: [{"price": "50000", "size": "1.5"}, ...]';
COMMENT ON COLUMN orderbook_data.asks IS 'Array of ask levels as JSONB: [{"price": "50001", "size": "2.0"}, ...]';

-- =============================================================================
-- NOTES FOR PRODUCTION
-- =============================================================================
--
-- To enable TimescaleDB features (hypertables, compression, continuous aggregates):
--
-- 1. Install TimescaleDB extension:
--    CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;
--
-- 2. Convert to hypertables:
--    SELECT create_hypertable('market_data', 'time', if_not_exists => TRUE);
--    SELECT create_hypertable('orderbook_data', 'time', if_not_exists => TRUE);
--
-- 3. Add compression (saves 90% disk space):
--    ALTER TABLE market_data SET (
--      timescaledb.compress,
--      timescaledb.compress_segmentby = 'symbol, data_type',
--      timescaledb.compress_orderby = 'time DESC'
--    );
--    SELECT add_compression_policy('market_data', INTERVAL '7 days');
--
-- 4. Create continuous aggregates for rollups:
--    CREATE MATERIALIZED VIEW candles_1h WITH (timescaledb.continuous) AS
--    SELECT time_bucket('1 hour', time) AS time, symbol,
--           first(open, time) as open, max(high) as high,
--           min(low) as low, last(close, time) as close
--    FROM market_data WHERE data_type = 'candle_1m'
--    GROUP BY time_bucket('1 hour', time), symbol;
--
-- =============================================================================
