# Phase 0 Infrastructure - Usage Guide

## Overview

Phase 0 provides foundational infrastructure that all fetchers should use for:
- Deterministic IDs and deduplication
- Incremental checkpointing
- Retry/backoff and rate limiting
- Structured + raw storage
- Detailed observability metrics

## Quick Start

### Basic Fetcher Pattern

```kotlin
class MyFetcher : Fetcher {
    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("my_fetcher") { ctx ->
            // 1. Get checkpoint (for incremental fetching)
            val lastFetchTime = ctx.checkpoint.get("last_fetch_time")

            // 2. Fetch items (use ctx.http for network requests)
            val items = fetchItems(since = lastFetchTime, ctx)

            // 3. Process each item
            items.forEach { item ->
                ctx.markAttempted()

                try {
                    // Compute content hash for dedupe
                    val contentHash = ContentHasher.hash(item.content)

                    // Check if we should upsert this item
                    when (ctx.dedupe.shouldUpsert(item.id, contentHash)) {
                        DedupeResult.NEW -> {
                            insertNewItem(item)
                            ctx.markNew()
                        }
                        DedupeResult.UPDATED -> {
                            updateExistingItem(item)
                            ctx.markUpdated()
                        }
                        DedupeResult.UNCHANGED -> {
                            ctx.markSkipped()
                        }
                    }

                    // Store raw data with canonical path
                    ctx.storage.storeRaw(
                        itemId = item.id,
                        content = item.rawJson.toByteArray(),
                        extension = "json"
                    )

                    ctx.markFetched()
                } catch (e: Exception) {
                    ctx.markFailed()
                    ctx.recordError(
                        errorType = e::class.simpleName ?: "Error",
                        message = e.message ?: "Unknown error",
                        itemId = item.id
                    )
                }
            }

            // 4. Update checkpoint for next run
            ctx.checkpoint.set("last_fetch_time", Clock.System.now().toString())

            // 5. Return summary message
            "Successfully processed ${ctx.metrics.new} new, ${ctx.metrics.updated} updated items"
        }
    }
}
```

## Infrastructure Components

### 1. FetchExecutionContext

The main wrapper that provides everything you need:

```kotlin
FetchExecutionContext.execute("job_name") { ctx ->
    // ctx provides:
    // - ctx.checkpoint: checkpoint storage
    // - ctx.dedupe: deduplication checks
    // - ctx.storage: raw file storage
    // - ctx.http: standardized HTTP client
    // - ctx.metrics: metric counters
    // - ctx.recordError(): error tracking

    "Your success message"
}
```

### 2. Checkpoints

Store resumption state for incremental fetching:

```kotlin
// Get checkpoint
val lastId = ctx.checkpoint.get("last_id")
val lastTimestamp = ctx.checkpoint.get("last_timestamp")

// Set checkpoint
ctx.checkpoint.set("last_id", items.last().id)
ctx.checkpoint.set("last_timestamp", Clock.System.now().toString())

// Get all checkpoints
val allCheckpoints = ctx.checkpoint.getAll()

// Delete checkpoint
ctx.checkpoint.delete("old_key")
```

**Use cases:**
- Last fetch timestamp
- Last page token/cursor
- Last seen item ID
- Any incremental fetching state

### 3. Deduplication

Prevent repeated ingestion of unchanged items:

```kotlin
val contentHash = ContentHasher.hash(item.content)

when (ctx.dedupe.shouldUpsert(item.id, contentHash)) {
    DedupeResult.NEW -> {
        // Item never seen before - insert
        insertItem(item)
        ctx.markNew()
    }
    DedupeResult.UPDATED -> {
        // Item exists but content changed - update
        updateItem(item)
        ctx.markUpdated()
    }
    DedupeResult.UNCHANGED -> {
        // Item exists with same content - skip
        ctx.markSkipped()
    }
}
```

**Content hashing:**
```kotlin
// Simple string hash
val hash1 = ContentHasher.hash("some content")

// JSON hash (normalizes whitespace)
val hash2 = ContentHasher.hashJson("""{"key": "value"}""")
```

### 4. Raw Storage

Store raw responses with canonical paths:

```kotlin
// Store raw bytes
val path = ctx.storage.storeRaw(
    itemId = "article_123",
    content = jsonBytes,
    extension = "json"
)
// Path: raw/my_fetcher/2025/12/17/run_456/article_123.json

// Store raw text
val path = ctx.storage.storeRawText(
    itemId = "page_789",
    content = htmlString,
    extension = "html"
)

// Read raw data later
val bytes = ctx.storage.readRaw(path)

// Check existence
if (ctx.storage.exists(path)) { ... }
```

**Path structure:** `/raw/{source}/{yyyy}/{mm}/{dd}/{runId}/{itemId}.{ext}`

Benefits:
- Time-based organization for cleanup/archival
- Run-level isolation
- Deterministic retrieval paths

### 5. HTTP Client

Standardized client with retry, backoff, and rate limiting:

```kotlin
// GET request
val response = ctx.http.get("https://api.example.com/data")
if (response.isSuccessful) {
    val body = response.body?.string()
    // Process body
}
response.close()

// POST request
val response = ctx.http.post(
    url = "https://api.example.com/data",
    body = """{"query": "value"}""",
    contentType = "application/json"
)

// Custom headers
val response = ctx.http.get(
    url = "https://api.example.com/data",
    headers = mapOf(
        "Authorization" to "Bearer $token",
        "X-Custom-Header" to "value"
    )
)
```

**Features:**
- Automatic retry on 429/5xx with exponential backoff + jitter
- Per-host concurrency limits (default: 5)
- Per-host rate limiting (default: 10 req/s)
- Standard headers (User-Agent, Accept-Encoding)
- Configurable timeouts

### 6. Metrics Tracking

Track what happens during your fetch:

```kotlin
// Mark operations
ctx.markAttempted()  // Increment attempted counter
ctx.markFetched()    // Increment fetched counter
ctx.markNew()        // Increment new items counter
ctx.markUpdated()    // Increment updated items counter
ctx.markSkipped()    // Increment skipped counter
ctx.markFailed()     // Increment failed counter

// Batch operations
ctx.markNew(count = 10)

// Record errors (limited to 10 samples)
ctx.recordError(
    errorType = "NetworkError",
    message = "Connection timeout",
    itemId = "item_123"  // optional
)

// Access current metrics
val metrics = ctx.metrics
println("Attempted: ${metrics.attempted}")
println("New: ${metrics.new}")
println(metrics.summary())  // "attempted=50, new=10, updated=5, skipped=30, failed=5"
```

### 7. FetchResult

Enhanced result with full metadata:

```kotlin
// Success result (auto-generated by FetchExecutionContext.execute)
FetchResult.Success(
    runId = "rss_feeds_1734483200_456",
    startedAt = Instant.parse("2025-12-17T10:00:00Z"),
    endedAt = Instant.parse("2025-12-17T10:02:30Z"),
    jobName = "rss_feeds",
    version = "1.0.0",
    message = "Fetched 25 new articles",
    metrics = FetchMetrics(
        attempted = 100,
        fetched = 75,
        new = 25,
        updated = 20,
        skipped = 30,
        failed = 5
    )
)

// Error result (auto-generated on exception)
FetchResult.Error(
    runId = "rss_feeds_1734483200_456",
    startedAt = ...,
    endedAt = ...,
    jobName = "rss_feeds",
    version = "1.0.0",
    message = "Fetch failed: Connection refused",
    metrics = FetchMetrics(...),
    errorSamples = listOf(
        ErrorSample(
            errorType = "IOException",
            message = "Connection refused",
            itemId = "item_123",
            timestamp = ...
        )
    )
)
```

## Complete Example: RSS Fetcher

```kotlin
package org.datamancy.datafetcher.fetchers

import kotlinx.datetime.Clock
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult

class RssFetcher(private val feeds: List<String>) : Fetcher {

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("rss_feeds", version = "2.0.0") { ctx ->
            feeds.forEach { feedUrl ->
                try {
                    // Fetch RSS feed using standardized HTTP client
                    val response = ctx.http.get(feedUrl)

                    if (!response.isSuccessful) {
                        ctx.recordError(
                            errorType = "HttpError",
                            message = "HTTP ${response.code} for $feedUrl"
                        )
                        response.close()
                        return@forEach
                    }

                    val feedXml = response.body?.string() ?: ""
                    response.close()

                    // Parse RSS
                    val items = parseRssFeed(feedXml)

                    items.forEach { item ->
                        ctx.markAttempted()

                        try {
                            // Create deterministic item ID
                            val itemId = item.guid ?: item.link.hashCode().toString()

                            // Compute content hash
                            val contentHash = ContentHasher.hash(
                                "${item.title}|${item.description}|${item.pubDate}"
                            )

                            // Dedupe check
                            when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                                DedupeResult.NEW -> {
                                    insertRssItem(item)
                                    ctx.markNew()
                                }
                                DedupeResult.UPDATED -> {
                                    updateRssItem(item)
                                    ctx.markUpdated()
                                }
                                DedupeResult.UNCHANGED -> {
                                    ctx.markSkipped()
                                    return@forEach
                                }
                            }

                            // Store raw XML item
                            ctx.storage.storeRawText(
                                itemId = itemId,
                                content = item.rawXml,
                                extension = "xml"
                            )

                            ctx.markFetched()

                        } catch (e: Exception) {
                            ctx.markFailed()
                            ctx.recordError(
                                errorType = e::class.simpleName ?: "Error",
                                message = e.message ?: "Unknown error",
                                itemId = item.link
                            )
                        }
                    }

                } catch (e: Exception) {
                    ctx.recordError(
                        errorType = e::class.simpleName ?: "Error",
                        message = "Failed to fetch $feedUrl: ${e.message}"
                    )
                }
            }

            // Update checkpoint
            ctx.checkpoint.set("last_fetch_time", Clock.System.now().toString())

            "Processed ${feeds.size} feeds: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated"
        }
    }

    override suspend fun dryRun(): DryRunResult {
        // Dry run implementation...
        return DryRunResult(checks = listOf(...))
    }
}
```

## Best Practices

### 1. Always use deterministic item IDs
```kotlin
// Good: Use stable identifiers
val itemId = item.guid ?: item.url

// Bad: Using timestamps or random values
val itemId = Clock.System.now().toString() // ❌
```

### 2. Checkpoint frequently
```kotlin
// Save state so you can resume if interrupted
ctx.checkpoint.set("last_page", currentPage.toString())
ctx.checkpoint.set("last_id", items.last().id)
```

### 3. Handle partial failures gracefully
```kotlin
items.forEach { item ->
    try {
        processItem(item)
    } catch (e: Exception) {
        ctx.markFailed()
        ctx.recordError(...)
        // Continue processing other items
    }
}
```

### 4. Use appropriate content hashing
```kotlin
// For structured data, normalize first
val contentHash = ContentHasher.hashJson(jsonString)

// For text, include key fields only
val contentHash = ContentHasher.hash("${title}|${body}|${timestamp}")
```

### 5. Set realistic budgets
```kotlin
val maxItems = 1000
items.take(maxItems).forEach { item ->
    // Process
}
```

## Testing

Test your fetcher with the infrastructure:

```kotlin
@Test
fun testFetcher() = runBlocking {
    val fetcher = MyFetcher()
    val result = fetcher.fetch()

    assert(result is FetchResult.Success)
    val success = result as FetchResult.Success

    println("Run ID: ${success.runId}")
    println("Metrics: ${success.metrics.summary()}")
    assert(success.metrics.new > 0)
}
```

## Migration from Legacy Code

If you have existing fetchers, migrate incrementally:

1. **Wrap with FetchExecutionContext:**
   ```kotlin
   override suspend fun fetch(): FetchResult {
       return FetchExecutionContext.execute("my_fetcher") { ctx ->
           // Your existing logic here
           "Success message"
       }
   }
   ```

2. **Add dedupe checks:**
   ```kotlin
   when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
       DedupeResult.UNCHANGED -> return@forEach
       else -> processItem()
   }
   ```

3. **Add checkpoints:**
   ```kotlin
   val lastId = ctx.checkpoint.get("last_id")
   // ... fetch since lastId ...
   ctx.checkpoint.set("last_id", newLastId)
   ```

4. **Use standardized HTTP client:**
   ```kotlin
   val response = ctx.http.get(url)  // Instead of OkHttp directly
   ```

5. **Update raw storage:**
   ```kotlin
   ctx.storage.storeRaw(itemId, bytes, "json")  // Instead of fileStore.storeRawData()
   ```

## Summary

Phase 0 provides everything you need for production-grade data fetchers:

✅ **Deterministic IDs + dedupe** - No repeated ingestion
✅ **Incremental checkpoints** - Resume where you left off
✅ **Rate limiting + retries** - Respectful and resilient
✅ **Structured + raw storage** - Queryable data + audit trail
✅ **Observability** - Detailed metrics and error tracking
✅ **Config-driven** - Externalized configuration
✅ **Bounded work** - Built-in limits and budgets

Use `FetchExecutionContext.execute()` as your entry point, and you get all of this for free!
