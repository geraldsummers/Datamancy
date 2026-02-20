-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ============================================================================
-- MARKET DATA TABLES
-- ============================================================================

-- Trades table
CREATE TABLE trades (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    trade_id TEXT,
    price NUMERIC(20, 8) NOT NULL,
    size NUMERIC(20, 8) NOT NULL,
    side TEXT NOT NULL, -- 'buy' or 'sell'
    is_liquidation BOOLEAN DEFAULT FALSE
);

SELECT create_hypertable('trades', 'time');
CREATE INDEX idx_trades_symbol_time ON trades (symbol, time DESC);

-- Candles table (OHLCV)
CREATE TABLE candles (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    interval TEXT NOT NULL, -- '1m', '5m', '1h', etc.
    open NUMERIC(20, 8) NOT NULL,
    high NUMERIC(20, 8) NOT NULL,
    low NUMERIC(20, 8) NOT NULL,
    close NUMERIC(20, 8) NOT NULL,
    volume NUMERIC(20, 8) NOT NULL,
    num_trades INTEGER DEFAULT 0
);

SELECT create_hypertable('candles', 'time');
CREATE INDEX idx_candles_symbol_interval_time ON candles (symbol, interval, time DESC);

-- Orderbook snapshots
CREATE TABLE orderbook_snapshots (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    bids JSONB NOT NULL, -- [[price, size], ...]
    asks JSONB NOT NULL,
    checksum TEXT
);

SELECT create_hypertable('orderbook_snapshots', 'time');
CREATE INDEX idx_orderbook_symbol_time ON orderbook_snapshots (symbol, time DESC);

-- Funding rates
CREATE TABLE funding_rates (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    rate NUMERIC(20, 12) NOT NULL,
    predicted_rate NUMERIC(20, 12)
);

SELECT create_hypertable('funding_rates', 'time');
CREATE INDEX idx_funding_symbol_time ON funding_rates (symbol, time DESC);

-- Open interest
CREATE TABLE open_interest (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    value NUMERIC(20, 8) NOT NULL
);

SELECT create_hypertable('open_interest', 'time');
CREATE INDEX idx_oi_symbol_time ON open_interest (symbol, time DESC);

-- ============================================================================
-- CONTINUOUS AGGREGATES (Auto-updating materialized views)
-- ============================================================================

-- 1-hour candles from 1-minute candles
CREATE MATERIALIZED VIEW candles_1h
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', time) AS time,
       symbol,
       exchange,
       '1h' as interval,
       first(open, time) as open,
       max(high) as high,
       min(low) as low,
       last(close, time) as close,
       sum(volume) as volume,
       sum(num_trades) as num_trades
FROM candles
WHERE interval = '1m'
GROUP BY time_bucket('1 hour', time), symbol, exchange;

-- Daily candles from 1-hour candles
CREATE MATERIALIZED VIEW candles_1d
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 day', time) AS time,
       symbol,
       exchange,
       '1d' as interval,
       first(open, time) as open,
       max(high) as high,
       min(low) as low,
       last(close, time) as close,
       sum(volume) as volume,
       sum(num_trades) as num_trades
FROM candles_1h
GROUP BY time_bucket('1 day', time), symbol, exchange;

-- Trade aggregates (volume, trade count per minute)
CREATE MATERIALIZED VIEW trade_stats_1m
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 minute', time) AS time,
       symbol,
       exchange,
       count(*) as num_trades,
       sum(size) as volume,
       sum(CASE WHEN side = 'buy' THEN size ELSE 0 END) as buy_volume,
       sum(CASE WHEN side = 'sell' THEN size ELSE 0 END) as sell_volume,
       avg(price) as vwap
FROM trades
GROUP BY time_bucket('1 minute', time), symbol, exchange;

-- ============================================================================
-- STRATEGY & EXECUTION TABLES
-- ============================================================================

-- Strategy positions
CREATE TABLE positions (
    id SERIAL PRIMARY KEY,
    strategy_name TEXT NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    side TEXT NOT NULL, -- 'long' or 'short'
    entry_time TIMESTAMPTZ NOT NULL,
    exit_time TIMESTAMPTZ,
    entry_price NUMERIC(20, 8) NOT NULL,
    exit_price NUMERIC(20, 8),
    size NUMERIC(20, 8) NOT NULL,
    pnl NUMERIC(20, 8),
    pnl_percent NUMERIC(10, 4),
    commission NUMERIC(20, 8) DEFAULT 0,
    slippage NUMERIC(20, 8) DEFAULT 0,
    stop_loss NUMERIC(20, 8),
    take_profit NUMERIC(20, 8),
    status TEXT DEFAULT 'open' -- 'open', 'closed', 'stopped'
);

CREATE INDEX idx_positions_strategy_symbol ON positions (strategy_name, symbol);
CREATE INDEX idx_positions_entry_time ON positions (entry_time DESC);

-- Strategy signals
CREATE TABLE signals (
    time TIMESTAMPTZ NOT NULL,
    strategy_name TEXT NOT NULL,
    symbol TEXT NOT NULL,
    signal TEXT NOT NULL, -- 'long', 'short', 'exit', 'neutral'
    strength NUMERIC(5, 4), -- -1.0 to 1.0
    metadata JSONB
);

SELECT create_hypertable('signals', 'time');
CREATE INDEX idx_signals_strategy_time ON signals (strategy_name, time DESC);

-- Strategy performance metrics
CREATE TABLE strategy_metrics (
    time TIMESTAMPTZ NOT NULL,
    strategy_name TEXT NOT NULL,
    symbol TEXT,
    equity NUMERIC(20, 8),
    drawdown NUMERIC(10, 4),
    sharpe_ratio NUMERIC(10, 4),
    win_rate NUMERIC(5, 4),
    profit_factor NUMERIC(10, 4),
    num_trades INTEGER,
    metadata JSONB
);

SELECT create_hypertable('strategy_metrics', 'time');
CREATE INDEX idx_metrics_strategy_time ON strategy_metrics (strategy_name, time DESC);

-- ============================================================================
-- COMPRESSION POLICIES
-- ============================================================================

-- Compress data older than 7 days
SELECT add_compression_policy('trades', INTERVAL '7 days');
SELECT add_compression_policy('candles', INTERVAL '7 days');
SELECT add_compression_policy('orderbook_snapshots', INTERVAL '3 days');
SELECT add_compression_policy('funding_rates', INTERVAL '30 days');
SELECT add_compression_policy('open_interest', INTERVAL '30 days');
SELECT add_compression_policy('signals', INTERVAL '30 days');
SELECT add_compression_policy('strategy_metrics', INTERVAL '90 days');

-- ============================================================================
-- RETENTION POLICIES (Optional - uncomment to auto-delete old data)
-- ============================================================================

-- SELECT add_retention_policy('trades', INTERVAL '1 year');
-- SELECT add_retention_policy('orderbook_snapshots', INTERVAL '30 days');
