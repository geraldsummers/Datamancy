-- ClickHouse schema for knowledge base / agent resources
-- This file defines tables for market data and economic indicators

-- Market data time-series (crypto prices, volumes)
CREATE TABLE IF NOT EXISTS default.market_data (
    symbol String,
    price Float64,
    volume Nullable(Float64),
    timestamp DateTime,
    source String,
    metadata String,
    ingested_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (symbol, timestamp)
PARTITION BY toYYYYMM(timestamp)
TTL timestamp + INTERVAL 5 YEAR;

-- Economic indicators time-series (GDP, unemployment, CPI, etc.)
CREATE TABLE IF NOT EXISTS default.economic_indicators (
    series_id String,
    series_name String,
    value Float64,
    timestamp DateTime,
    source String,
    frequency String,
    metadata String,
    ingested_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (series_id, timestamp)
PARTITION BY toYYYYMM(timestamp)
TTL timestamp + INTERVAL 10 YEAR;

-- Indexes for faster queries
ALTER TABLE default.market_data ADD INDEX idx_symbol symbol TYPE bloom_filter GRANULARITY 4;
ALTER TABLE default.economic_indicators ADD INDEX idx_series_id series_id TYPE bloom_filter GRANULARITY 4;

-- Create materialized views for common queries
CREATE MATERIALIZED VIEW IF NOT EXISTS default.market_data_daily
ENGINE = SummaryMerge()
AS SELECT
    symbol,
    toDate(timestamp) as date,
    source,
    argMax(price, timestamp) as close_price,
    max(price) as high_price,
    min(price) as low_price,
    argMin(price, timestamp) as open_price,
    sum(volume) as total_volume
FROM default.market_data
GROUP BY symbol, date, source;

-- View for latest economic indicators
CREATE MATERIALIZED VIEW IF NOT EXISTS default.economic_indicators_latest
ENGINE = ReplacingMergeTree()
ORDER BY series_id
AS SELECT
    series_id,
    series_name,
    source,
    frequency,
    argMax(value, timestamp) as latest_value,
    max(timestamp) as latest_timestamp
FROM default.economic_indicators
GROUP BY series_id, series_name, source, frequency;
