# Pipeline Storage Architecture

## Current Implementation: File-Based Storage

The pipeline uses **file-based storage** for both deduplication and metadata tracking:

### Deduplication (`DeduplicationStore`)
- **Location**: `/app/data/dedup` (flat file)
- **Format**: Tab-separated `hash\tmetadata` per line
- **Implementation**: In-memory `ConcurrentHashMap` + periodic flush
- **Performance**: O(1) lookups, efficient for single-instance deployments
- **Persistence**: File survives container restarts (if volume mounted)

### Metadata Tracking (`SourceMetadataStore`)
- **Location**: `/tmp/datamancy/metadata/*.json` (one file per source)
- **Format**: Pretty-printed JSON
- **Data Tracked**:
  - `lastSuccessfulRun` / `lastAttemptedRun` - ISO timestamps
  - `totalItemsProcessed` / `totalItemsFailed` - Counters
  - `consecutiveFailures` - Error tracking for retry logic
  - `checkpointData` - Source-specific resume points (e.g., `nextLine`, `nextIndex`)
- **Examples**:
  ```json
  {
    "sourceName": "torrents",
    "lastSuccessfulRun": "2026-01-26T04:34:09Z",
    "totalItemsProcessed": 172669,
    "totalItemsFailed": 0,
    "consecutiveFailures": 0,
    "checkpointData": {}
  }
  ```

---

## PostgreSQL Tables: UNUSED/LEGACY

### Schema Exists But Not Used

The PostgreSQL database contains two tables that are **NOT actively used** by the pipeline:

```sql
-- Created by schema migrations but unused in production code
CREATE TABLE dedupe_records (
    id SERIAL PRIMARY KEY,
    source VARCHAR(255) NOT NULL,
    item_id VARCHAR(500) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen_run_id VARCHAR(100),
    fetch_type VARCHAR(100),
    UNIQUE(source, item_id)
);

CREATE TABLE fetch_history (
    id SERIAL PRIMARY KEY,
    source VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    item_count INTEGER,
    fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    metadata TEXT,
    fetch_type VARCHAR(100),
    status VARCHAR(50),
    record_count INTEGER,
    error_message TEXT,
    execution_time_ms INTEGER
);
```

### Why They're Unused

1. **File-based storage was chosen for simplicity** - No DB connection needed during pipeline execution
2. **Single-instance deployment** - No need for distributed locking/coordination
3. **Performance** - In-memory hash lookups are faster than DB queries
4. **Tables may have been**:
   - Created by old migration scripts
   - Left behind from a previous implementation
   - Created for testing but never integrated

### Should I Use Them?

**Current setup (file-based) is fine for**:
- Single-instance pipeline deployments
- Container restarts (if volumes mounted)
- Up to ~10M dedupe records (fits in memory)

**Consider PostgreSQL if**:
- Running multiple pipeline instances (need distributed deduplication)
- Need atomic transactions across dedup + vector insert
- Want persistent history/audit trail
- Running on ephemeral compute (no reliable local storage)

---

## Migration Path to PostgreSQL (Future)

If PostgreSQL-backed storage becomes necessary:

1. **Implement `PostgresDeduplicationStore`**:
   ```kotlin
   class PostgresDeduplicationStore(
       private val dataSource: DataSource
   ) : DeduplicationStore {
       // Use existing dedupe_records table
   }
   ```

2. **Implement `PostgresMetadataStore`**:
   ```kotlin
   class PostgresMetadataStore(
       private val dataSource: DataSource
   ) : SourceMetadataStore {
       // Use existing fetch_history table
   }
   ```

3. **Update Main.kt** to inject PostgreSQL implementations:
   ```kotlin
   val dedupStore = if (config.usePostgres) {
       PostgresDeduplicationStore(dataSource)
   } else {
       DeduplicationStore()
   }
   ```

4. **Add distributed locking** (for multi-instance):
   ```sql
   SELECT pg_advisory_lock(hashtext('source_name'));
   -- ... do work ...
   SELECT pg_advisory_unlock(hashtext('source_name'));
   ```

---

## Testing Notes

Integration tests check for metadata tracking via the `/status` API endpoint, which now includes `checkpointData`. Tests should expect:
- `checkpointData` is always present (may be empty map)
- Empty map is normal for sources that completed full ingestion
- File-based storage means no PostgreSQL rows will exist

See `DataPipelineTests.kt` for checkpoint/dedup test expectations.
