# Market Data Ingestion Pipeline

Real-time market data ingestion from cryptocurrency exchanges into TimescaleDB for trading strategy execution and backtesting.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Market Data Ingestion Flow                       │
└─────────────────────────────────────────────────────────────────────┘

┌──────────────────┐         ┌─────────────────┐         ┌────────────────┐
│ Hyperliquid WS   │────────▶│ HyperliquidSource│────────▶│ MarketDataSink │
│ api.hyperliquid  │         │  (pipeline)      │         │  (pipeline)    │
│    .xyz/ws       │         └─────────────────┘         └────────────────┘
└──────────────────┘                  │                           │
                                      │                           ▼
                          ┌───────────┴──────────┐    ┌──────────────────────┐
                          │  HyperliquidMarketData│   │  TimescaleDB         │
                          │  • Trades             │   │  • market_data       │
                          │  • Candles (OHLCV)    │   │  • orderbook_data    │
                          │  • Orderbooks (L2)    │   └──────────────────────┘
                          └──────────────────────┘              │
                                                                 │
                                                                 ▼
                                                    ┌─────────────────────────┐
                                                    │ MarketDataRepository    │
                                                    │   (trading-sdk)         │
                                                    │  ✓ getCandles()         │
                                                    │  ✓ getTrades()          │
                                                    │  ✓ getVolumeStats()     │
                                                    └─────────────────────────┘
                                                                 │
                                                                 ▼
                                                    ┌─────────────────────────┐
                                                    │ Trading Strategies      │
                                                    │  • Indicators (RSI,SMA) │
                                                    │  • Risk Management      │
                                                    │  • Strategy Execution   │
                                                    └─────────────────────────┘
```

## Components

### 1. **HyperliquidSource** (`sources/HyperliquidSource.kt`)

WebSocket-based market data source for Hyperliquid perpetual futures exchange.

**Features:**
- Real-time trade execution data
- Multiple candle intervals (1m, 5m, 15m, 1h, 4h, 1d)
- Level 2 order book snapshots
- Automatic reconnection with exponential backoff
- Configurable subscriptions

**Configuration:**
```kotlin
val source = HyperliquidSource(
    symbols = listOf("BTC", "ETH", "SOL"),
    subscribeToTrades = true,
    subscribeToCandles = true,
    candleIntervals = listOf("1m", "5m", "15m", "1h"),
    subscribeToOrderbook = false
)
```

**Data Models:**
- `HyperliquidTrade` - Individual trade with price, size, side
- `HyperliquidCandle` - OHLCV data with volume and trade count
- `HyperliquidOrderbook` - Bids/asks with price levels

### 2. **MarketDataSink** (`sinks/MarketDataSink.kt`)

TimescaleDB sink for persisting market data with high-throughput batch writes.

**Features:**
- Batch writes for performance (default: 1000 items)
- Duplicate-safe writes (ON CONFLICT handling)
- Automatic flushing on shutdown
- Separate tables for different data types
- Statistics tracking

**Database Schema:**
```sql
-- Trades and candles
CREATE TABLE market_data (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    data_type TEXT NOT NULL,  -- 'trade', 'candle_1m', 'candle_5m', etc.

    -- Trade fields
    trade_id TEXT,
    price DECIMAL,
    size DECIMAL,
    side TEXT,
    is_liquidation BOOLEAN,

    -- Candle fields
    open DECIMAL,
    high DECIMAL,
    low DECIMAL,
    close DECIMAL,
    volume DECIMAL,
    num_trades INTEGER,

    PRIMARY KEY (time, symbol, exchange, data_type, COALESCE(trade_id, ''))
);

-- Orderbook snapshots
CREATE TABLE orderbook_data (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    bids JSONB NOT NULL,  -- [{"price": 50000, "size": 1.5}, ...]
    asks JSONB NOT NULL,  -- [{"price": 50001, "size": 2.0}, ...]

    PRIMARY KEY (time, symbol, exchange)
);
```

**Performance:**
- TimescaleDB hypertables for automatic partitioning
- Compression for historical data (>1 week old)
- Indexes on `(symbol, exchange, time)` for fast queries
- Batch writes reduce DB round-trips by 10-100x

### 3. **MarketDataIngestionRunner** (`runners/MarketDataIngestionRunner.kt`)

Continuous ingestion runner that wires HyperliquidSource → MarketDataSink.

**Features:**
- Automatic reconnection on WebSocket failures
- Graceful shutdown with data flush
- Statistics logging every 60 seconds
- Environment-based configuration
- Health monitoring

**Environment Variables:**
```bash
# Database
POSTGRES_HOST=postgres          # TimescaleDB host
POSTGRES_PORT=5432              # TimescaleDB port
POSTGRES_DB=datamancy           # Database name
POSTGRES_USER=pipeline          # Database user
POSTGRES_PASSWORD=secret        # Database password

# Hyperliquid Configuration
HYPERLIQUID_SYMBOLS=BTC,ETH,SOL     # Symbols to ingest
CANDLE_INTERVALS=1m,5m,15m,1h       # Candle intervals
ENABLE_ORDERBOOK=false              # Orderbook ingestion (high volume!)
```

**Usage:**
```kotlin
val runner = MarketDataIngestionRunner()

// Add shutdown hook
Runtime.getRuntime().addShutdownHook(Thread {
    runner.stop()
})

runner.start()
```

## Running the Pipeline

### Option 1: Standalone JAR

```bash
# Build
cd stack.kotlin/market-data-ingestion
./gradlew shadowJar

# Run
export POSTGRES_HOST=postgres
export POSTGRES_PASSWORD=secret
export HYPERLIQUID_SYMBOLS=BTC,ETH,SOL

java -jar build/libs/market-data-ingestion-1.0.0-all.jar \
    org.datamancy.pipeline.runners.MarketDataIngestionRunnerKt
```

### Option 2: Docker Compose

```yaml
# docker-compose.yml
services:
  market-data-ingestion:
    image: datamancy/market-data-ingestion:latest
    command: ["java", "-jar", "/app/market-data-ingestion.jar",
              "org.datamancy.pipeline.runners.MarketDataIngestionRunnerKt"]
    environment:
      POSTGRES_HOST: postgres
      POSTGRES_PORT: 5432
      POSTGRES_DB: datamancy
      POSTGRES_USER: pipeline
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      HYPERLIQUID_SYMBOLS: BTC,ETH,SOL,AVAX,ARB,OP,MATIC
      CANDLE_INTERVALS: 1m,5m,15m,1h,4h,1d
      ENABLE_ORDERBOOK: false
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped
    networks:
      - postgres
```

### Option 3: Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: market-data-ingestion
spec:
  replicas: 1
  template:
    spec:
      containers:
      - name: market-data-ingestion
        image: datamancy/market-data-ingestion:latest
        args: ["org.datamancy.pipeline.runners.MarketDataIngestionRunnerKt"]
        env:
        - name: POSTGRES_HOST
          value: "timescaledb.database.svc.cluster.local"
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
        - name: HYPERLIQUID_SYMBOLS
          value: "BTC,ETH,SOL"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
```

## Trading SDK Integration

The `MarketDataRepository` in trading-sdk provides **READ-ONLY** access to ingested data.

### Example: Querying Historical Candles

```kotlin
val dataSource = createDataSource(jdbcUrl, user, password)
val repo = MarketDataRepository(dataSource)

// Get last 100 1-minute candles for BTC
val candles = repo.getCandles(
    symbol = "BTC",
    interval = "1m",
    from = Instant.now().minusSeconds(6000),
    to = Instant.now(),
    exchange = "hyperliquid",
    limit = 100
)

// Calculate indicators
val indicators = indicators {
    sma(20)
    rsi(14)
}

candles.forEach { candle ->
    indicators.update(candle)
}

println("RSI: ${indicators.rsi(14).value}")
```

### Example: Volume Statistics

```kotlin
val stats = repo.getVolumeStats(
    symbol = "ETH",
    from = Instant.now().minusSeconds(3600),
    to = Instant.now(),
    exchange = "hyperliquid"
)

println("Total trades: ${stats.numTrades}")
println("Total volume: ${stats.totalVolume} ETH")
println("Buy/Sell ratio: ${stats.buyPercent}% / ${stats.sellPercent}%")
```

## Monitoring

### Ingestion Statistics

The pipeline logs statistics every 60 seconds:

```
════════════════════════════════════════════════════════════════════════════════
Market Data Ingestion Statistics
────────────────────────────────────────────────────────────────────────────────
Total Ingested:  1,234,567
  Trades:        987,654
  Candles:       246,913
  Orderbooks:    0
────────────────────────────────────────────────────────────────────────────────
Pending:         250
  Trades:        150
  Candles:       100
  Orderbooks:    0
════════════════════════════════════════════════════════════════════════════════
```

### Grafana Dashboards

Query TimescaleDB for real-time metrics:

```sql
-- Ingestion rate (trades per minute)
SELECT
    time_bucket('1 minute', time) AS bucket,
    COUNT(*) as trades_per_minute
FROM market_data
WHERE data_type = 'trade'
    AND time > NOW() - INTERVAL '1 hour'
GROUP BY bucket
ORDER BY bucket DESC;

-- Latest candles
SELECT
    symbol,
    close,
    volume,
    time
FROM market_data
WHERE data_type = 'candle_1m'
    AND exchange = 'hyperliquid'
    AND time > NOW() - INTERVAL '5 minutes'
ORDER BY time DESC;

-- Market spread
SELECT
    symbol,
    (asks->>0)::json->>'price' as best_ask,
    (bids->>0)::json->>'price' as best_bid,
    ((asks->>0)::json->>'price')::decimal - ((bids->>0)::json->>'price')::decimal as spread
FROM orderbook_data
WHERE time > NOW() - INTERVAL '1 minute'
ORDER BY time DESC
LIMIT 10;
```

## Performance Tuning

### Batch Size

Adjust batch size based on ingestion rate:
```kotlin
// High frequency (trades)
MarketDataSink(dataSource, batchSize = 5000)

// Low frequency (hourly candles)
MarketDataSink(dataSource, batchSize = 100)
```

### TimescaleDB Compression

Enable compression for historical data:
```sql
ALTER TABLE market_data SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol,exchange,data_type',
    timescaledb.compress_orderby = 'time DESC'
);

SELECT add_compression_policy('market_data', INTERVAL '7 days');
SELECT add_compression_policy('orderbook_data', INTERVAL '1 day');
```

### Continuous Aggregates

Pre-compute common queries:
```sql
CREATE MATERIALIZED VIEW market_data_5m
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('5 minutes', time) AS bucket,
    symbol,
    exchange,
    FIRST(open, time) as open,
    MAX(high) as high,
    MIN(low) as low,
    LAST(close, time) as close,
    SUM(volume) as volume
FROM market_data
WHERE data_type LIKE 'candle_%'
GROUP BY bucket, symbol, exchange;

SELECT add_continuous_aggregate_policy('market_data_5m',
    start_offset => INTERVAL '1 hour',
    end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');
```

## Testing

### Unit Tests

```bash
cd stack.kotlin/market-data-ingestion

# Run tests (disabled by default - require live connections)
./gradlew test

# Run specific test
./gradlew test --tests "HyperliquidSourceTest"

# Enable live tests
./gradlew test -Denable.live.tests=true \
    -DTEST_POSTGRES_HOST=localhost \
    -DTEST_POSTGRES_DB=datamancy_test
```

### Integration Tests

See `tests.kotlin/test-runner` for full E2E tests that verify:
- Pipeline writes to TimescaleDB
- Trading SDK reads from TimescaleDB
- Data freshness and accuracy
- Performance under load

## Troubleshooting

### WebSocket Connection Failures

```
[ERROR] WebSocket connection failed: Connection refused
```
**Solution:** Check network connectivity to Hyperliquid API. May need proxy or VPN.

### Duplicate Key Violations

```
[ERROR] duplicate key value violates unique constraint
```
**Solution:** Already handled by `ON CONFLICT` - this should not appear. Check database schema.

### Memory Issues

```
[ERROR] OutOfMemoryError: Java heap space
```
**Solution:** Reduce batch size or increase heap: `java -Xmx2G -jar market-data-ingestion.jar`

### Slow Queries

```
[WARN] Query took 5000ms
```
**Solution:**
1. Add indexes: `CREATE INDEX ON market_data (symbol, time DESC);`
2. Enable compression for historical data
3. Use continuous aggregates for common queries

## Future Enhancements

- **Multiple Exchanges:** BinanceSource, CoinbaseSource, KrakenSource
- **Real-time Pub/Sub:** PostgreSQL NOTIFY/LISTEN for live updates
- **Data Quality:** Anomaly detection and gap filling
- **High Availability:** Multi-instance ingestion with leader election
- **Backfill:** Historical data ingestion from REST APIs

---

**Documentation:** https://docs.datamancy.net/pipeline/market-data
**Support:** https://github.com/datamancy/datamancy/issues
