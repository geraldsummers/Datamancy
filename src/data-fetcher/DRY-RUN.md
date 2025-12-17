# Dry-Run Mode

The data-fetcher service includes a comprehensive dry-run mode that verifies your configuration and connectivity **without fetching any actual data**.

## What Dry-Run Checks

For each fetcher, dry-run performs these verification checks:

### 1. **Filesystem Checks**
- Verifies data directories exist
- Creates directories if missing
- Checks write permissions
- Returns errors if not writable

### 2. **URL Accessibility**
- Sends HTTP HEAD requests to verify URLs are reachable
- Checks HTTP status codes (2xx/3xx = success)
- Times out quickly (5 seconds) to fail fast
- Does NOT download any content

### 3. **API Key Validation**
- Checks if required API keys are configured
- Verifies key length and format
- Tests API endpoints with authentication
- Returns clear errors for invalid/missing keys

### 4. **Database Connectivity**
- Tests connections to PostgreSQL, ClickHouse
- Validates credentials
- Checks if databases are reachable
- Does NOT create tables or modify data

### 5. **Configuration Validation**
- Verifies required config values are present
- Checks arrays/lists have at least one item
- Validates format of configuration

## API Endpoints

### Single Job Dry-Run
```bash
GET /dry-run/{job}
```

Example:
```bash
curl http://data-fetcher:8095/dry-run/rss_feeds | jq
```

Response:
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
      "details": {
        "path": "/app/data/rss"
      }
    },
    {
      "name": "URL: RSS feed: tech",
      "passed": true,
      "message": "HTTP 200",
      "details": {
        "url": "https://news.ycombinator.com/rss",
        "statusCode": "200"
      }
    },
    {
      "name": "Database: PostgreSQL (metadata)",
      "passed": true,
      "message": "Connection successful",
      "details": {
        "jdbcUrl": "jdbc:postgresql://postgres:5432/datamancy",
        "user": "datamancer"
      }
    }
  ]
}
```

### All Jobs Dry-Run
```bash
GET /dry-run-all
```

Example:
```bash
curl http://data-fetcher:8095/dry-run-all | jq
```

Response:
```json
{
  "jobs": {
    "rss_feeds": {
      "job": "rss_feeds",
      "success": true,
      "summary": "Dry run: 9/9 checks passed",
      "checks": [...]
    },
    "market_data": {
      "job": "market_data",
      "success": true,
      "summary": "Dry run: 4/4 checks passed",
      "checks": [...]
    },
    "weather": {
      "job": "weather",
      "success": false,
      "summary": "Dry run: 2/4 checks passed",
      "checks": [
        {
          "name": "API Key: OpenWeatherMap",
          "passed": false,
          "message": "API key not configured"
        }
      ]
    }
  },
  "totalChecks": 17,
  "passedChecks": 15,
  "failedChecks": 2
}
```

## Use Cases

### Before Deployment
Run dry-run to verify all configuration before starting scheduled fetches:

```bash
# Test all fetchers
curl http://data-fetcher:8095/dry-run-all

# Check if any failed
curl -s http://data-fetcher:8095/dry-run-all | jq '.failedChecks'
```

### Debugging Configuration
When a fetcher isn't working, dry-run identifies the exact problem:

```bash
# Test specific fetcher
curl http://data-fetcher:8095/dry-run/weather | jq '.checks[] | select(.passed == false)'
```

### CI/CD Integration
Add to deployment pipeline:

```bash
#!/bin/bash
# Wait for service to start
sleep 10

# Run dry-run verification
RESULT=$(curl -s http://data-fetcher:8095/dry-run-all)
FAILED=$(echo $RESULT | jq '.failedChecks')

if [ "$FAILED" -gt 0 ]; then
  echo "❌ Dry-run failed: $FAILED checks failed"
  echo $RESULT | jq '.jobs | to_entries[] | select(.value.success == false)'
  exit 1
else
  echo "✅ All dry-run checks passed"
  exit 0
fi
```

### Health Monitoring
Periodically verify external services are still reachable:

```bash
# Cron job: check every hour
0 * * * * curl -s http://data-fetcher:8095/dry-run-all | jq -r 'if .failedChecks > 0 then "ALERT: Data fetcher dry-run failed" else empty end' | mail -s "Data Fetcher Alert" ops@example.com
```

## Per-Fetcher Checks

### RSS Fetcher
- ✅ `/app/data/rss` directory writable
- ✅ Each RSS feed URL accessible
- ✅ PostgreSQL connection

### Market Data Fetcher
- ✅ `/app/data/market_data/crypto` directory writable
- ✅ CoinGecko API accessible
- ✅ At least one symbol configured
- ✅ ClickHouse ping endpoint

### Weather Fetcher
- ✅ `/app/data/weather` directory writable
- ✅ OpenWeatherMap API key configured
- ✅ API key valid (test request)
- ✅ At least one location configured

### Wiki Fetcher
- ✅ Wikipedia dumps URL accessible
- ✅ Wikipedia API accessible
- ✅ Wikidata API accessible
- ✅ `/app/data/wiki` directory writable

### Economic Data Fetcher
- ✅ FRED API key (optional)
- ✅ World Bank accessible
- ✅ IMF accessible
- ✅ OECD accessible
- ✅ `/app/data/economic` directory writable

## Implementation Details

All checks are:
- **Fast**: 5-second timeouts
- **Non-invasive**: HTTP HEAD requests only
- **Safe**: No data modification
- **Isolated**: Failures don't affect other checks
- **Detailed**: Clear error messages with context

## Adding Dry-Run to New Fetchers

Implement the `dryRun()` method:

```kotlin
override suspend fun dryRun(): DryRunResult {
    val checks = mutableListOf<DryRunCheck>()

    // Check directory
    checks.add(DryRunUtils.checkDirectory("/app/data/mydata", "My data directory"))

    // Check URL
    checks.add(DryRunUtils.checkUrl("https://api.example.com", "My API"))

    // Check API key
    checks.add(DryRunUtils.checkApiKey(config.apiKey, "My API Key"))

    // Check database
    checks.add(DryRunUtils.checkDatabase("jdbc:...", user, pass, "My DB"))

    return DryRunResult(checks)
}
```
