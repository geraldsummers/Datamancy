# ClickHouse Migration - Remaining Work

## ✅ Completed
- Pipeline: Migrated to PostgreSQL for document staging
- Docker Compose: All references updated
- Config files: Credentials and monitoring updated
- Networks/Volumes: ClickHouse removed

## ⚠️ Still Using ClickHouse (Need Migration)

### 1. search-service (BM25 Full-Text Search)
**Files:**
- `kotlin.src/search-service/src/main/kotlin/org/datamancy/searchservice/SearchGateway.kt`
- `kotlin.src/search-service/src/main/kotlin/org/datamancy/searchservice/Main.kt`

**Current:** Uses ClickHouse for BM25 full-text search
**TODO:** Migrate to PostgreSQL full-text search (tsvector/tsquery) or Elasticsearch

### 2. agent-tool-server (Query Plugin)
**Files:**
- `kotlin.src/agent-tool-server/src/main/kotlin/org/example/plugins/DataSourceQueryPlugin.kt`

**Current:** Provides `query_clickhouse` tool for agents (currently disabled)
**TODO:** Remove ClickHouse support or migrate to PostgreSQL queries

### 3. Documentation Files
**Files:**
- `configs.templates/system/data-analyst.txt` - System prompt mentions ClickHouse
- `configs.templates/caddy/Caddyfile` - May have ClickHouse proxy routes
- `CREDENTIALS.md` - Documents ClickHouse credentials

**TODO:** Update documentation to reflect PostgreSQL migration

## Migration Strategy

### For search-service BM25:
PostgreSQL has excellent full-text search capabilities:
```sql
-- Create GIN index for full-text search
CREATE INDEX idx_documents_fts ON documents USING GIN(to_tsvector('english', text));

-- Query with ranking
SELECT id, text, ts_rank(to_tsvector('english', text), query) as rank
FROM documents, plainto_tsquery('english', 'search terms') query
WHERE to_tsvector('english', text) @@ query
ORDER BY rank DESC
LIMIT 10;
```

Alternatively, use Elasticsearch or Meilisearch for advanced BM25.
