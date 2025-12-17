# BookStack API Automation

This directory contains scripts for automating BookStack API token generation and testing the search pipeline.

## Scripts

### 1. `generate-api-token.main.kts`
Generates BookStack API tokens for automation services (data-fetcher, search-indexer).

**Usage:**
```bash
kotlin ~/.datamancy/configs/applications/bookstack/generate-api-token.main.kts
```

**Output:**
- Creates an API token named "Datamancy Automation"
- Token expires: 2099-12-31 (far future)
- Prints credentials to add to `.env` file

**Required:**
- BookStack service must be running
- Docker must be accessible

### 2. `test-pipeline.sh`
Tests the complete data flow: Fetch → BookStack → Index → Search

**Usage:**
```bash
# First, generate and export API credentials
kotlin ~/.datamancy/configs/applications/bookstack/generate-api-token.main.kts
export BOOKSTACK_API_TOKEN_ID=<from output>
export BOOKSTACK_API_TOKEN_SECRET=<from output>

# Then run the test
~/.datamancy/configs/applications/bookstack/test-pipeline.sh
```

**What it tests:**
1. **Data Fetcher** → Fetches legislation to BookStack
2. **BookStack** → Verifies content storage
3. **Search Indexer** → Indexes to Qdrant + ClickHouse
4. **Search Gateway** → Performs hybrid search queries

## Setup Instructions

### First Time Setup

1. **Start the stack:**
   ```bash
   ./stack-controller.main.kts up
   ```

2. **Generate API credentials:**
   ```bash
   kotlin ~/.datamancy/configs/applications/bookstack/generate-api-token.main.kts
   ```

3. **Add credentials to `.env` file:**
   ```bash
   echo "BOOKSTACK_API_TOKEN_ID=<token_id>" >> .env
   echo "BOOKSTACK_API_TOKEN_SECRET=<token_secret>" >> .env
   ```

4. **Restart services to pick up credentials:**
   ```bash
   docker compose restart data-fetcher search-indexer
   ```

5. **Run the test pipeline:**
   ```bash
   export BOOKSTACK_API_TOKEN_ID=<token_id>
   export BOOKSTACK_API_TOKEN_SECRET=<token_secret>
   ~/.datamancy/configs/applications/bookstack/test-pipeline.sh
   ```

### After Obliterate

All scripts persist in `~/.datamancy/configs/applications/bookstack/` and are regenerated from templates.

1. Regenerate API token (old token is lost in database):
   ```bash
   kotlin ~/.datamancy/configs/applications/bookstack/generate-api-token.main.kts
   ```

2. Update `.env` with new credentials

3. Re-run tests

## Architecture

```
legislation.gov.au
         ↓
   Data Fetcher (8095)
         ↓
   BookStack (80) ← API Token Auth
         ↓
Search Indexer (8096)
         ↓
   ┌─────┴─────┐
   │           │
Qdrant      ClickHouse
(vector)     (BM25)
   │           │
   └─────┬─────┘
         ↓
Search Gateway (8097)
   ↓ (Hybrid RRF)
  Results
```

## API Endpoints

### Data Fetcher
- `POST http://localhost:8095/trigger/legal_docs` - Trigger fetch

### Search Indexer
- `POST http://localhost:8096/index/all` - Index all collections
- `POST http://localhost:8096/index/collection/{name}` - Index specific collection
- `GET http://localhost:8096/status` - Check indexing status

### Search Gateway
- `POST http://localhost:8097/search` - Hybrid search
  ```json
  {
    "query": "search terms",
    "collections": ["*"],
    "mode": "hybrid",
    "limit": 5
  }
  ```
- `GET http://localhost:8097/collections` - List collections

### BookStack (via Caddy)
- `http://api.bookstack.{{DOMAIN}}` - Internal API (requires token)
- `http://bookstack.{{DOMAIN}}` - Public UI (requires auth)

## Collections

Collections are named by jurisdiction:
- `federal` - Federal legislation
- `nsw` - New South Wales
- `vic` - Victoria
- `qld` - Queensland
- `wa` - Western Australia
- `sa` - South Australia
- `tas` - Tasmania
- `act` - Australian Capital Territory
- `nt` - Northern Territory

Each collection maps to:
- Qdrant collection name
- ClickHouse table name
- BookStack shelf name

## Troubleshooting

**Problem:** API token not working
- **Solution:** Regenerate token, ensure it's exported to environment

**Problem:** No search results
- **Solution:** Wait longer for indexing, check indexer logs

**Problem:** Data fetcher fails
- **Solution:** Check BookStack API endpoint is accessible from container network

**Problem:** Services can't find each other
- **Solution:** Ensure all services are on correct Docker networks (backend, database)
