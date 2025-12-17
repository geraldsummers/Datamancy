# Quick Reference: MVP Fetcher Pattern

Use this as a template for upgrading remaining fetchers to MVP standard.

## Standard MVP Fetcher Structure

```kotlin
package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.YourConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore

private val logger = KotlinLogging.logger {}
private val gson = Gson()

class YourFetcher(private val config: YourConfig) : Fetcher {
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("your_fetcher", version = "2.0.0") { ctx ->
            logger.info { "Fetching from your source..." }

            // Process each item
            items.forEach { item ->
                ctx.markAttempted()

                try {
                    // 1. Create deterministic item ID
                    val itemId = item.id // or item.guid, item.url.hashCode(), etc.

                    // 2. Fetch data using standardized HTTP client
                    val response = ctx.http.get(item.url)
                    if (!response.isSuccessful) {
                        ctx.recordError("HTTP_ERROR", "HTTP ${response.code}", itemId)
                        response.close()
                        return@forEach
                    }
                    val body = response.body?.string()
                    response.close()

                    // 3. Create normalized data structure
                    val normalizedData = mapOf(
                        "id" to itemId,
                        "field1" to item.field1,
                        "field2" to item.field2
                    )

                    // 4. Compute content hash
                    val dataJson = gson.toJson(normalizedData)
                    val contentHash = ContentHasher.hashJson(dataJson)

                    // 5. Dedupe check
                    when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                        DedupeResult.NEW -> {
                            // Insert new item into your storage
                            insertItem(normalizedData)

                            // Store raw data
                            ctx.storage.storeRawText(itemId, body, "json")

                            // Store metadata
                            pgStore.storeFetchMetadata(
                                source = "your_source",
                                category = "your_category",
                                itemCount = 1,
                                fetchedAt = Clock.System.now(),
                                metadata = mapOf("key" to "value")
                            )

                            ctx.markNew()
                            ctx.markFetched()
                        }
                        DedupeResult.UPDATED -> {
                            // Update existing item
                            updateItem(normalizedData)
                            ctx.storage.storeRawText(itemId, body, "json")
                            ctx.markUpdated()
                            ctx.markFetched()
                        }
                        DedupeResult.UNCHANGED -> {
                            ctx.markSkipped()
                        }
                    }

                } catch (e: Exception) {
                    logger.error(e) { "Failed to process item: ${item.id}" }
                    ctx.markFailed()
                    ctx.recordError(
                        errorType = e::class.simpleName ?: "Error",
                        message = e.message ?: "Unknown error",
                        itemId = item.id
                    )
                }
            }

            // 6. Update checkpoint for next run
            ctx.checkpoint.set("last_fetch_time", Clock.System.now().toString())

            // 7. Return summary message
            "Processed ${ctx.metrics.attempted} items: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped"
        }
    }

    override suspend fun dryRun(): DryRunResult {
        val checks = mutableListOf<DryRunCheck>()
        // Add your dry-run checks
        return DryRunResult(checks)
    }
}
```

## Key Patterns

### 1. Deterministic Item IDs
```kotlin
// Good: Stable identifiers
val itemId = entry.guid                    // RSS
val itemId = entry.url                     // Web pages
val itemId = "${location}_${timestamp}"    // Weather
val itemId = instrument.symbol             // Market data

// Bad: Non-deterministic
val itemId = Clock.System.now().toString() // ❌ Changes every time
val itemId = Random.nextInt().toString()   // ❌ Not reproducible
```

### 2. Content Hashing
```kotlin
// Normalize data first, then hash
val normalizedData = mapOf(
    "title" to item.title.trim(),
    "content" to item.content,
    // Include only meaningful fields
)
val contentHash = ContentHasher.hashJson(gson.toJson(normalizedData))
```

### 3. Checkpoint Usage
```kotlin
// Get checkpoint at start
val lastFetchTime = ctx.checkpoint.get("last_fetch_time")

// Use for incremental fetching
val itemsSince = if (lastFetchTime != null) {
    fetchItemsSince(Instant.parse(lastFetchTime))
} else {
    fetchAllItems()
}

// Update checkpoint at end
ctx.checkpoint.set("last_fetch_time", Clock.System.now().toString())
```

### 4. Caching Pattern (like Weather's geocoding)
```kotlin
val cacheKey = "some_cache_key"
val cached = ctx.checkpoint.get(cacheKey)

val result = if (cached != null) {
    try {
        val data = gson.fromJson(cached, YourDataClass::class.java)
        if (Clock.System.now() < data.expiresAt) {
            data.value // Use cached
        } else {
            fetchAndCache(cacheKey) // Expired
        }
    } catch (e: Exception) {
        fetchAndCache(cacheKey) // Invalid cache
    }
} else {
    fetchAndCache(cacheKey) // No cache
}
```

### 5. Diff/Change Detection (like Agent Functions)
```kotlin
// Get previous state
val previousItems = ctx.checkpoint.getAll()
    .filterKeys { it.startsWith("item_") }
    .mapKeys { it.key.removePrefix("item_") }

// Compare with current
val added = currentItems.keys - previousItems.keys
val removed = previousItems.keys - currentItems.keys
val modified = (previousItems.keys intersect currentItems.keys).filter { key ->
    previousItems[key] != currentItems[key]
}

// Store diff report
val diffReport = mapOf(
    "added" to added.toList(),
    "removed" to removed.toList(),
    "modified" to modified.toList()
)
ctx.storage.storeRawText("diff_${timestamp}", gson.toJson(diffReport), "json")

// Update checkpoints
currentItems.forEach { (key, value) ->
    ctx.checkpoint.set("item_$key", value)
}
removed.forEach { key ->
    ctx.checkpoint.delete("item_$key")
}
```

## Gradle Verification Steps

```bash
# 1. Compile check
./gradlew :data-fetcher:compileKotlin

# 2. Full check
./gradlew :data-fetcher:check

# 3. Full build
./gradlew :data-fetcher:build

# All should show: BUILD SUCCESSFUL
```

## Common Gotchas

### ❌ Don't use anonymous objects for helpers
```kotlin
// Bad - doesn't compile across files
val storage = object {
    fun storeRaw(...) { ... }
}

// Good - use proper classes (Phase 0 provides these)
val storage: StorageHelper = StorageHelper(...)
```

### ❌ Don't forget to close HTTP responses
```kotlin
// Bad
val response = ctx.http.get(url)
val body = response.body?.string()
// response never closed!

// Good
val response = ctx.http.get(url)
if (!response.isSuccessful) {
    response.close() // Always close
    return
}
val body = response.body?.string()
response.close() // Close after use
```

### ❌ Don't use continue in forEach
```kotlin
// Bad - doesn't compile
items.forEach { item ->
    if (item == null) continue // ❌
}

// Good
items.forEach { item ->
    if (item == null) return@forEach // ✓
}
```

### ❌ Don't hash non-normalized data
```kotlin
// Bad - includes timestamp that always changes
val hash = ContentHasher.hash(item.toString())

// Good - normalize first, exclude volatile fields
val normalized = mapOf(
    "title" to item.title,
    "content" to item.content
    // Don't include: timestamps, view counts, etc.
)
val hash = ContentHasher.hashJson(gson.toJson(normalized))
```

## Storage Locations

- **Postgres** - Metadata, fetch history
- **ClickHouse** - Time-series data (weather, market prices)
- **Filesystem** - Raw artifacts at `/raw/{source}/{yyyy}/{mm}/{dd}/{runId}/{itemId}.{ext}`

## Success Criteria

A fetcher is MVP-ready when:

- ✅ Uses `FetchExecutionContext.execute()`
- ✅ Has deterministic item IDs
- ✅ Uses `ctx.dedupe.shouldUpsert()` for all items
- ✅ Uses `ctx.http.get/post()` for network calls
- ✅ Stores raw data with `ctx.storage.storeRaw()`
- ✅ Updates checkpoint with `ctx.checkpoint.set()`
- ✅ Returns detailed metrics (new/updated/skipped counts)
- ✅ Compiles and passes `gradle check`

## Metrics to Track

Monitor these in your summary message:

```kotlin
"Processed ${ctx.metrics.attempted} items: " +
"${ctx.metrics.new} new, " +
"${ctx.metrics.updated} updated, " +
"${ctx.metrics.skipped} skipped, " +
"${ctx.metrics.failed} failed"
```

Second run should show high `skipped` count (good - dedupe working!).
