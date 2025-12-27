# AU Law RAG Pipeline Deployment Checklist

## Status: Ready for deployment

The three-way sync implementation is complete and committed to git:
- Commit f0aa287: Three-way sync implementation
- Commit 448f8c0: Compilation fixes

## Prerequisites

1. **Filesystem**: btrfs RAID1 array must be mounted at the correct location
2. **Docker**: Must have access to docker/docker-compose
3. **Services**: ClickHouse, Qdrant, data-fetcher, search-service must be running

## Deployment Steps

### 1. Verify Filesystem Setup

```bash
# Check btrfs RAID1 is mounted
mount | grep btrfs | grep raid

# Verify docker volumes are on RAID1 (not NVMe)
df -h /var/lib/docker

# Check available space
df -h | grep -E '(btrfs|qdrant)'
```

Expected:
- btrfs RAID1: 3.0TB total, ~1.6TB available
- Qdrant SSD: 1TB ext4 dedicated for vectors

### 2. Build and Start Services

```bash
cd ~/.datamancy
docker compose build data-fetcher search-service
docker compose up -d clickhouse qdrant data-fetcher search-service
```

### 3. Verify Services Running

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

Expected services:
- `clickhouse` (port 8123)
- `qdrant` (ports 6333 HTTP, 6334 gRPC)
- `data-fetcher` (port 18095)
- `search-service` (port 18096)
- `embedding-service` (port 8080)
- `vllm` (port 8000)

### 4. Check ClickHouse Schema

The schema will be created automatically on first run, but you can verify:

```bash
docker exec -it clickhouse clickhouse-client -q "SHOW CREATE TABLE default.legal_documents"
```

Expected: ReplacingMergeTree with versioning fields (valid_from, valid_to, superseded_by, last_checked)

### 5. Create Qdrant Collection

```bash
curl -X PUT http://192.168.0.11:6333/collections/legal-federal \
  -H 'Content-Type: application/json' \
  -d '{
    "vectors": {
      "size": 768,
      "distance": "Cosine"
    },
    "optimizers_config": {
      "indexing_threshold": 10000
    }
  }'
```

### 6. Trigger AU Law Ingestion

```bash
# Start federal legislation sync
curl -X POST http://192.168.0.11:18095/trigger/legal_docs_clickhouse

# Monitor progress
docker logs -f data-fetcher
```

### 7. Start Unified Indexer

```bash
# Index from ClickHouse to Qdrant
curl -X POST http://192.168.0.11:18097/index \
  -H 'Content-Type: application/json' \
  -d '{
    "source_type": "clickhouse",
    "collection": "legal-federal",
    "batch_size": 100
  }'

# Monitor progress
docker logs -f unified-indexer
```

## Expected Results

### Data Ingestion (Step 6)
- Federal: ~1000+ Acts, ~50,000+ sections
- State/Territory: ~1000+ Acts per jurisdiction (8 jurisdictions)
- Total: ~10,000 Acts, ~500,000+ sections

### Sync Metrics
- **New**: First-time documents
- **Updated**: Changed content (detected via hash)
- **Skipped**: Unchanged (via Last-Modified header)
- **Repealed**: Removed from legislation.gov.au

### Indexing (Step 7)
- 768-dim embeddings (BAAI/bge-base-en-v1.5)
- ~100 sections/minute indexing speed
- ~5-8 hours for full corpus

## Verification

### Test Search

```bash
curl -X POST http://192.168.0.11:18096/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "What are the requirements for obtaining Australian citizenship?",
    "collections": ["legal-federal"],
    "mode": "hybrid",
    "limit": 5
  }'
```

### Test Tool Calling

```bash
curl -X POST http://192.168.0.11:8000/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "Qwen/Qwen2.5-7B-Instruct-AWQ",
    "messages": [
      {"role": "system", "content": "You are a legal assistant with access to Australian legislation."},
      {"role": "user", "content": "What are the citizenship requirements in Australia?"}
    ],
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "semantic_search",
          "description": "Search Australian legal documents",
          "parameters": {
            "type": "object",
            "properties": {
              "query": {"type": "string"},
              "collection": {"type": "string"}
            }
          }
        }
      }
    ]
  }'
```

## Monitoring

### Storage Usage
```bash
# ClickHouse storage
docker exec clickhouse clickhouse-client -q "
  SELECT
    database,
    table,
    formatReadableSize(sum(bytes)) as size
  FROM system.parts
  WHERE active
  GROUP BY database, table
  ORDER BY sum(bytes) DESC"

# Qdrant storage
du -sh ~/.datamancy/qdrant-data/
```

### Sync Health
```bash
# Last sync time per jurisdiction
docker exec clickhouse clickhouse-client -q "
  SELECT
    jurisdiction,
    max(last_checked) as last_sync,
    count(DISTINCT url) as act_count,
    count(*) as section_count
  FROM legal_documents
  WHERE valid_to IS NULL
  GROUP BY jurisdiction
  ORDER BY jurisdiction"
```

## Troubleshooting

### Build Failures
```bash
# Check gradle build
cd /home/gerald/IdeaProjects/Datamancy
./gradlew :data-fetcher:build -x test

# Check for compilation errors
./gradlew :data-fetcher:compileKotlin
```

### Docker Service Issues
```bash
# Check logs
docker compose logs clickhouse
docker compose logs qdrant
docker compose logs data-fetcher

# Restart services
docker compose restart data-fetcher
```

### Memory Issues (GPU OOM)
```bash
# Check GPU usage
nvidia-smi

# Expected: ~8.6GB / 12GB (70% utilization)
# - vLLM: 8258MB (Qwen2.5-7B-AWQ)
# - Embeddings: 378MB (BAAI/bge-base-en-v1.5)
```

## Next Steps After Deployment

1. **Schedule Regular Syncs**: Set up cron job to run `legal_docs_clickhouse` weekly
2. **Monitor Repeals**: Check for repealed legislation and remove from Qdrant
3. **Expand Coverage**: Add more jurisdictions or document types
4. **Optimize**: Tune batch sizes, rate limits based on actual performance

## Architecture Summary

```
legislation.gov.au
       ↓
  [data-fetcher]
   Three-way sync (new/updated/repealed)
   Content hash comparison
   Last-Modified optimization
       ↓
  [ClickHouse]
   ReplacingMergeTree (versioned data)
   zstd:3 compression
   Single source of truth
       ↓
  [unified-indexer]
   Reads from ClickHouse
   Generates embeddings (768-dim)
       ↓
  [Qdrant]
   Vector search
   HNSW index
       ↓
  [search-service]
   Hybrid search (vector + BM25)
   Reciprocal rank fusion
       ↓
  [vLLM]
   Qwen2.5-7B-AWQ (tool calling)
   Contextual answers
```
