# Grafana Dashboard Queries for Unified Market Data

## Overview

All market data is stored in two unified tables:
- **`market_data`** - Trades, candles (all timeframes), funding rates
- **`orderbook_data`** - Orderbook snapshots with pre-calculated metrics

## Market Data Queries

### 1. Real-Time Trade Volume (Last 5 minutes)

```sql
SELECT
  time_bucket('1 minute', time) AS bucket,
  symbol,
  SUM(size) as volume,
  COUNT(*) as num_trades
FROM market_data
WHERE
  data_type = 'trade'
  AND time >= NOW() - INTERVAL '5 minutes'
  AND $__timeFilter(time)
GROUP BY bucket, symbol
ORDER BY bucket DESC
```

**Grafana Settings:**
- Visualization: Time series
- Y-axis: Volume
- Legend: `{{symbol}}`

---

### 2. OHLCV Candles (Any Timeframe)

```sql
SELECT
  time AS "time",
  symbol,
  open,
  high,
  low,
  close,
  volume
FROM market_data
WHERE
  data_type = 'candle_1h'  -- Change to candle_1m, candle_5m, candle_1d, etc.
  AND symbol = 'BTC-PERP'
  AND $__timeFilter(time)
ORDER BY time DESC
```

**Grafana Settings:**
- Visualization: Candlestick
- Fields: open, high, low, close
- Volume: separate panel

---

### 3. Multi-Symbol Price Comparison

```sql
SELECT
  time AS "time",
  symbol,
  close as price
FROM market_data
WHERE
  data_type = 'candle_1h'
  AND symbol IN ('BTC-PERP', 'ETH-PERP', 'SOL-PERP')
  AND $__timeFilter(time)
ORDER BY time DESC
```

**Grafana Settings:**
- Visualization: Time series
- Legend: `{{symbol}}`
- Y-axis: Price (USD)

---

### 4. Trade Distribution (Buy vs Sell)

```sql
SELECT
  time_bucket('5 minutes', time) AS bucket,
  symbol,
  SUM(CASE WHEN side = 'buy' THEN size ELSE 0 END) as buy_volume,
  SUM(CASE WHEN side = 'sell' THEN size ELSE 0 END) as sell_volume,
  SUM(CASE WHEN side = 'buy' THEN size ELSE 0 END) * 100.0 / SUM(size) as buy_pct
FROM market_data
WHERE
  data_type = 'trade'
  AND symbol = 'BTC-PERP'
  AND $__timeFilter(time)
GROUP BY bucket, symbol
ORDER BY bucket DESC
```

**Grafana Settings:**
- Visualization: Time series
- Fields: buy_volume (green), sell_volume (red)
- Second Y-axis: buy_pct (%)

---

### 5. Liquidations Heatmap

```sql
SELECT
  time_bucket('1 minute', time) AS bucket,
  symbol,
  SUM(size) as liquidation_volume,
  COUNT(*) as num_liquidations,
  AVG(price) as avg_price
FROM market_data
WHERE
  data_type = 'trade'
  AND is_liquidation = TRUE
  AND $__timeFilter(time)
GROUP BY bucket, symbol
ORDER BY bucket DESC
```

**Grafana Settings:**
- Visualization: Heatmap
- Color scheme: Red (high liquidations)

---

### 6. Volume-Weighted Average Price (VWAP)

```sql
SELECT
  time_bucket('1 hour', time) AS bucket,
  symbol,
  SUM(price * size) / SUM(size) as vwap,
  SUM(size) as volume
FROM market_data
WHERE
  data_type = 'trade'
  AND symbol = 'BTC-PERP'
  AND $__timeFilter(time)
GROUP BY bucket, symbol
ORDER BY bucket DESC
```

---

## Orderbook Queries

### 7. Spread Analysis

```sql
SELECT
  time AS "time",
  symbol,
  spread,
  spread_pct,
  best_bid,
  best_ask,
  mid_price
FROM orderbook_data
WHERE
  symbol = 'BTC-PERP'
  AND $__timeFilter(time)
ORDER BY time DESC
```

**Grafana Settings:**
- Visualization: Time series
- Y-axis: Spread (absolute)
- Second Y-axis: spread_pct (%)

---

### 8. Order Book Depth Imbalance

```sql
SELECT
  time AS "time",
  symbol,
  bid_depth_10,
  ask_depth_10,
  (bid_depth_10 - ask_depth_10) / (bid_depth_10 + ask_depth_10) * 100 as imbalance_pct
FROM orderbook_data
WHERE
  symbol = 'BTC-PERP'
  AND $__timeFilter(time)
ORDER BY time DESC
```

**Grafana Settings:**
- Visualization: Time series
- Positive imbalance: More bid depth (buying pressure)
- Negative imbalance: More ask depth (selling pressure)

---

### 9. Best Bid/Ask Visualization

```sql
SELECT
  time AS "time",
  symbol,
  best_bid,
  best_ask,
  mid_price,
  (best_ask - best_bid) as spread_absolute
FROM orderbook_data
WHERE
  symbol = 'BTC-PERP'
  AND $__timeFilter(time)
ORDER BY time DESC
LIMIT 1000
```

**Grafana Settings:**
- Visualization: Time series
- Fields: best_bid (green), best_ask (red), mid_price (yellow)

---

## Advanced Analytics Queries

### 10. Rolling Volume (24h)

```sql
SELECT
  time AS "time",
  symbol,
  SUM(volume) OVER (
    PARTITION BY symbol
    ORDER BY time
    ROWS BETWEEN 23 PRECEDING AND CURRENT ROW
  ) as rolling_24h_volume
FROM market_data
WHERE
  data_type = 'candle_1h'
  AND symbol = 'BTC-PERP'
  AND $__timeFilter(time)
ORDER BY time DESC
```

---

### 11. Price Change % (Multi-Timeframe)

```sql
SELECT
  time AS "time",
  symbol,
  close,
  (close - LAG(close, 1) OVER (PARTITION BY symbol ORDER BY time)) / LAG(close, 1) OVER (PARTITION BY symbol ORDER BY time) * 100 as change_1h,
  (close - LAG(close, 24) OVER (PARTITION BY symbol ORDER BY time)) / LAG(close, 24) OVER (PARTITION BY symbol ORDER BY time) * 100 as change_24h
FROM market_data
WHERE
  data_type = 'candle_1h'
  AND symbol = 'BTC-PERP'
  AND $__timeFilter(time)
ORDER BY time DESC
```

---

### 12. Top Traded Symbols (Last Hour)

```sql
SELECT
  symbol,
  SUM(size) as total_volume,
  COUNT(*) as num_trades,
  AVG(price) as avg_price
FROM market_data
WHERE
  data_type = 'trade'
  AND time >= NOW() - INTERVAL '1 hour'
GROUP BY symbol
ORDER BY total_volume DESC
LIMIT 10
```

**Grafana Settings:**
- Visualization: Bar chart
- X-axis: Symbol
- Y-axis: Volume

---

## Dashboard Variables

Add these variables to your Grafana dashboard:

### Variable: `symbol`
```sql
SELECT DISTINCT symbol
FROM market_data
WHERE time >= NOW() - INTERVAL '24 hours'
ORDER BY symbol
```

### Variable: `data_type`
```sql
SELECT DISTINCT data_type
FROM market_data
WHERE data_type LIKE 'candle_%'
ORDER BY data_type
```

### Variable: `interval`
```
Options: 1m, 5m, 15m, 1h, 4h, 1d, 1w
```

---

## Jupyter Notebook Examples

### Connect to Database

```python
import pandas as pd
from sqlalchemy import create_engine

engine = create_engine('postgresql://user:pass@host:5432/datamancy')

# Query candles
df = pd.read_sql("""
    SELECT time, symbol, open, high, low, close, volume
    FROM market_data
    WHERE data_type = 'candle_1h'
      AND symbol = 'BTC-PERP'
      AND time >= NOW() - INTERVAL '7 days'
    ORDER BY time
""", engine, parse_dates=['time'])

df.set_index('time', inplace=True)
```

### Plot OHLC

```python
import plotly.graph_objects as go

fig = go.Figure(data=[go.Candlestick(
    x=df.index,
    open=df['open'],
    high=df['high'],
    low=df['low'],
    close=df['close']
)])
fig.show()
```

### Volume Analysis

```python
# Get all trades for analysis
trades = pd.read_sql("""
    SELECT time, symbol, price, size, side, is_liquidation
    FROM market_data
    WHERE data_type = 'trade'
      AND symbol = 'BTC-PERP'
      AND time >= NOW() - INTERVAL '1 day'
""", engine, parse_dates=['time'])

# Buy/sell volume
buy_vol = trades[trades['side'] == 'buy']['size'].sum()
sell_vol = trades[trades['side'] == 'sell']['size'].sum()

print(f"Buy Volume: {buy_vol:.2f}")
print(f"Sell Volume: {sell_vol:.2f}")
print(f"Buy/Sell Ratio: {buy_vol/sell_vol:.2f}")
```

---

## Tips for Optimization

1. **Use time_bucket() for aggregations** - Much faster than GROUP BY time
2. **Add indexes on commonly filtered columns** - We already have indexes on (symbol, data_type, time)
3. **Limit result sets** - Always use LIMIT or time ranges
4. **Use materialized views for expensive queries** - Create periodic rollups
5. **Partition tables by time** - Consider partitioning by month for very large datasets

---

## Next Steps

1. **Install TimescaleDB extension** for hypertables and continuous aggregates
2. **Create materialized views** for common aggregations (daily volumes, etc.)
3. **Set up compression policies** to save 90% disk space on old data
4. **Add retention policies** to auto-delete data older than X months

This unified schema makes it super easy to build ANY market data visualization you need! 🔥
