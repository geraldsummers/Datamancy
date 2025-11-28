-- Initial schema for time series ingestion via Benthos

-- Metadata table (optional but useful for joins)
CREATE TABLE IF NOT EXISTS series_meta (
  provider       LowCardinality(String),
  dataset        LowCardinality(String),
  series_id      String,
  title          String,
  units          LowCardinality(String),
  frequency      LowCardinality(String),
  last_updated   DateTime
)
ENGINE = ReplacingMergeTree
ORDER BY (provider, dataset, series_id);

-- Values table: one row per timestamp per series
CREATE TABLE IF NOT EXISTS series_values (
  provider       LowCardinality(String),
  dataset        LowCardinality(String),
  series_id      String,
  ts             DateTime,
  value          Float64,
  revision       UInt32 DEFAULT 0
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (provider, dataset, series_id, ts);
