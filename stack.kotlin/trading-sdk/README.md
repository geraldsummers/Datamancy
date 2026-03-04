# Trading SDK - Modular DSL Framework

A comprehensive algorithmic trading framework with unified market data storage optimized for Grafana/Jupyter analysis.

## Architecture

### Core Components

1. **Market Data DSL** - Real-time streaming API
2. **Indicator DSL** - Technical indicators (SMA, EMA, RSI, ATR, Bollinger, MACD, VWAP)
3. **Risk Management DSL** - Position sizing, stops, portfolio limits
4. **Strategy DSL** - Unified strategy definition framework
5. **WebSocket Client** - Hyperliquid market data streaming
6. **PostgreSQL Repository** - Unified time-series storage

### Unified Database Schema

All market data flows into **two unified tables** optimized for analytics:

#### `market_data` Table
- **All trades** (with liquidation flags)
- **All candles** (1m, 5m, 15m, 1h, 4h, 1d, 1w)
- **Funding rates**
- Uses `data_type` field for filtering
- Indexed on `(symbol, data_type, time)` for fast queries

#### `orderbook_data` Table
- **Full orderbook snapshots** (JSONB bid/ask arrays)
- **Pre-calculated metrics**: best_bid, best_ask, spread, mid_price
- **Depth indicators**: bid_depth_10, ask_depth_10
- Perfect for spread/liquidity analysis

**Benefits:**
- ✅ Single query for all market data
- ✅ Easy timeframe comparisons
- ✅ Clean Grafana dashboards
- ✅ Simple Jupyter analysis

## Clean Deployment

### 1. Build the SDK

```bash
cd /path/to/Datamancy
./gradlew :trading-sdk:build
```

### 2. Setup Database

Run the migration script to create unified tables:

```bash
./scripts/setup-trading-db.sh
```

This creates:
- `market_data` - Unified time-series table
- `orderbook_data` - Orderbook snapshots
- `strategies` - Strategy definitions
- `positions` - Position tracking
- `strategy_performance` - Performance metrics

### 3. Build Test Runner

```bash
./gradlew :test-runner:shadowJar
```

### 4. Deploy Test Runner

```bash
# Copy JAR to server
scp tests.kotlin/test-runner/build/libs/test-runner-1.0-SNAPSHOT-all.jar user@server:/tmp/test-runner.jar

# Copy to container
ssh user@server "docker cp /tmp/test-runner.jar test-runner-all:/app/test-runner.jar"
```

### 5. Run Integration Tests

```bash
ssh user@server "docker exec test-runner-all java -jar /app/test-runner.jar --suite trading-dsl --env container"
```

**Expected Results:** 12/14 tests passing (86%)

Remaining failures:
- ATR-based sizing calculation (logic issue, not infrastructure)
- Candle data mismatch (test assertion issue)

## Usage Examples

### Market Data Streaming

```kotlin
val stream = marketData.stream("BTC-PERP") {
    trades()
    candles(1.minutes)
    orderbook(depth = 20)
}

stream.onTrade { trade ->
    println("${trade.price} @ ${trade.size}")
}
```

### Indicator Calculations

```kotlin
val indicators = indicators {
    val sma20 = sma(20)
    val sma50 = sma(50)
    val rsi = rsi(14)
    val atr = atr(14)
}

stream.onCandle { candle ->
    indicators.update(candle)

    if (sma20.value!! > sma50.value!!) {
        println("Golden cross!")
    }
}
```

### Risk Management

```kotlin
val risk = riskManagement {
    sizing {
        fixedPercent(2.0)  // 2% per trade
        maxPositionPercent(10.0)
    }

    exits {
        stopLoss {
            atrBased(multiplier = 2.0)
        }
        takeProfit {
            riskReward(ratio = 3.0)
        }
    }
}

val size = risk.calculatePositionSize(
    equity = 100_000.toBigDecimal(),
    entryPrice = 50_000.toBigDecimal(),
    atr = 100.toBigDecimal()
)
```

### Strategy Definition

```kotlin
strategy("TrendFollower") {
    markets {
        hyperliquid("BTC-PERP", "ETH-PERP")
    }

    parameters {
        set("fastPeriod", 20)
        set("slowPeriod", 50)
    }

    indicators {
        val fast = sma(fastPeriod)
        val slow = sma(slowPeriod)
    }

    onCandle {
        if (fast > slow && !hasPosition()) {
            enter {
                side = Side.BUY
                size = risk.calculateSize()
            }
        }
    }
}
```

## Grafana Integration

See [docs/grafana-queries.md](../../docs/grafana-queries.md) for 12+ pre-built queries:

- Real-time trade volume
- OHLCV candlesticks (any timeframe)
- Multi-symbol price comparison
- Buy/Sell volume distribution
- Liquidation heatmaps
- VWAP calculations
- Orderbook spread analysis
- Depth imbalance indicators
- Rolling volume windows
- Price change %
- Top traded symbols

## Jupyter Integration

```python
import pandas as pd
from sqlalchemy import create_engine

engine = create_engine('postgresql://user:pass@host:5432/datamancy')

# Get all candles for analysis
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

## Performance

- **Batch insert:** 100 trades in 227ms (440 trades/sec)
- **Database queries:** < 100ms for typical time ranges
- **WebSocket latency:** < 500ms to connect
- **Total test suite:** 10.3 seconds

## Database Indexes

Already optimized with indexes on:
- `market_data(time DESC)`
- `market_data(symbol, time DESC)`
- `market_data(data_type, time DESC)`
- `market_data(symbol, data_type, time DESC)`
- `orderbook_data(time DESC)`
- `orderbook_data(symbol, time DESC)`
- `orderbook_data(symbol, spread_pct)`

## Future Enhancements

### Enable TimescaleDB (Recommended)

```sql
-- Install extension
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- Convert to hypertables
SELECT create_hypertable('market_data', 'time', if_not_exists => TRUE);
SELECT create_hypertable('orderbook_data', 'time', if_not_exists => TRUE);

-- Add compression (saves 90% disk space)
ALTER TABLE market_data SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'symbol, data_type',
  timescaledb.compress_orderby = 'time DESC'
);

SELECT add_compression_policy('market_data', INTERVAL '7 days');

-- Create continuous aggregates for auto-rollups
CREATE MATERIALIZED VIEW candles_1h
WITH (timescaledb.continuous) AS
SELECT
  time_bucket('1 hour', time) AS time,
  symbol,
  first(open, time) as open,
  max(high) as high,
  min(low) as low,
  last(close, time) as close,
  sum(volume) as volume
FROM market_data
WHERE data_type = 'candle_1m'
GROUP BY time_bucket('1 hour', time), symbol;
```

## Testing

Run all unit tests:
```bash
./gradlew :trading-sdk:test
```

Run E2E integration tests:
```bash
docker exec test-runner-all java -jar /app/test-runner.jar --suite trading-dsl --env container
```

## Dependencies

- Kotlin 2.0.21
- Ktor 3.0.2 (WebSocket client)
- PostgreSQL JDBC driver
- kotlinx.coroutines
- kotlinx.serialization
- kotlinx.datetime

## License

Part of the Datamancy stack.

## Support

See [docs/grafana-queries.md](../../docs/grafana-queries.md) for Grafana/Jupyter examples.
