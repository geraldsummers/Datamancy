# Datamancy Data Fetcher Service

A standalone microservice for automatically fetching data from various external sources on configurable schedules.

## Features

- **Scheduled Fetching**: Cron-like scheduling for automatic data retrieval
- **Multiple Data Sources**:
  - Wiki projects (Wikipedia, Wikidata, Wiktionary)
  - Market data (CoinGecko, Yahoo Finance)
  - RSS feeds
  - Weather data (OpenWeatherMap)
  - Economic indicators (IMF, World Bank, OECD, FRED)
  - Google search results
  - Legal documents (Australian legislation)
  - Torrents.csv
  - Linux/Debian documentation

- **Storage Backends**:
  - PostgreSQL: Structured metadata
  - ClickHouse: Time-series data (market data, metrics)
  - Filesystem: Raw data files
  - Qdrant: Vector embeddings (via RAG gateway)

- **REST API**:
  - `GET /health` - Health check
  - `GET /status` - View all fetch job statuses
  - `GET /status/{job}` - View specific job status
  - `POST /trigger/{job}` - Manually trigger a fetch
  - `POST /trigger-all` - Trigger all enabled fetches
  - `GET /dry-run/{job}` - Verify configuration for a specific job (no data fetched)
  - `GET /dry-run-all` - Verify all enabled jobs (no data fetched)

## Configuration

### Schedules (`schedules.yaml`)

Define when each data source should be fetched:

```yaml
schedules:
  rss_feeds:
    cron: "*/30 * * * *"  # Every 30 minutes
    enabled: true

  market_data:
    cron: "*/15 * * * *"  # Every 15 minutes
    enabled: true
```

### Sources (`sources.yaml`)

Configure what data to fetch:

```yaml
rss:
  feeds:
    - url: "https://news.ycombinator.com/rss"
      category: "tech"

marketData:
  symbols:
    - "BTC"
    - "ETH"
```

### Environment Variables

```bash
# Database connections
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
CLICKHOUSE_HOST=clickhouse
CLICKHOUSE_PORT=8123

# API keys (optional)
OPENWEATHER_API_KEY=your_key_here
COINGECKO_API_KEY=your_key_here
SERP_API_KEY=your_key_here
FRED_API_KEY=your_key_here
```

## Development

Build locally:
```bash
cd src/data-fetcher
./gradlew shadowJar
```

Run:
```bash
java -jar build/libs/data-fetcher.jar
```

## Docker Deployment

The service is integrated into docker-compose.yml:

```bash
docker-compose up -d data-fetcher
```

View logs:
```bash
docker logs -f data-fetcher
```

## Usage Examples

Check service status:
```bash
curl http://data-fetcher:8095/status
```

**Dry-run mode** - Verify configuration without fetching data:
```bash
# Test a specific fetcher (checks URLs, API keys, DB connections, directories)
curl http://data-fetcher:8095/dry-run/rss_feeds

# Test all enabled fetchers
curl http://data-fetcher:8095/dry-run-all
```

Example dry-run output:
```json
{
  "job": "rss_feeds",
  "success": true,
  "summary": "Dry run: 9/9 checks passed",
  "checks": [
    {
      "name": "Directory: RSS data directory",
      "passed": true,
      "message": "Directory exists and is writable",
      "details": {"path": "/app/data/rss"}
    },
    {
      "name": "URL: RSS feed: tech",
      "passed": true,
      "message": "HTTP 200",
      "details": {"url": "https://news.ycombinator.com/rss", "statusCode": "200"}
    },
    {
      "name": "Database: PostgreSQL (metadata)",
      "passed": true,
      "message": "Connection successful",
      "details": {"jdbcUrl": "jdbc:postgresql://postgres:5432/datamancy", "user": "datamancer"}
    }
  ]
}
```

Manually trigger RSS fetch:
```bash
curl -X POST http://data-fetcher:8095/trigger/rss_feeds
```

Trigger all enabled fetches:
```bash
curl -X POST http://data-fetcher:8095/trigger-all
```

## Adding New Fetchers

1. Create a new fetcher class in `src/main/kotlin/org/datamancy/datafetcher/fetchers/`
2. Implement the `Fetcher` interface
3. Add to `FetchScheduler.kt` in the `executeFetch()` function
4. Add schedule and source config to YAML files

Example:

```kotlin
class MyDataFetcher(private val config: MyDataConfig) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // Implement fetch logic
        return FetchResult.Success("Fetched N items", N)
    }
}
```

## Database Schema

PostgreSQL tables are auto-created on first run:

```sql
CREATE TABLE fetch_history (
    id SERIAL PRIMARY KEY,
    source VARCHAR(100) NOT NULL,
    category VARCHAR(100) NOT NULL,
    item_count INTEGER NOT NULL DEFAULT 0,
    fetched_at TIMESTAMP NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

ClickHouse tables are also auto-created:

```sql
CREATE TABLE market_data (
    timestamp DateTime64(3),
    symbol String,
    price Float64,
    volume Float64,
    source String,
    metadata String
) ENGINE = MergeTree()
ORDER BY (symbol, timestamp);
```

## Architecture

```
┌─────────────────┐
│  Data Fetcher   │
│   (Ktor + Go)   │
└────────┬────────┘
         │
    ┌────┴─────┬────────────┬──────────┐
    │          │            │          │
┌───▼───┐  ┌──▼──────┐  ┌──▼────┐  ┌─▼──────┐
│Postgres│  │ClickHouse│  │FileS │  │ Qdrant │
│metadata│  │time-series│  │ raw  │  │vectors │
└────────┘  └──────────┘  └──────┘  └────────┘
```

## License

Part of the Datamancy stack.
