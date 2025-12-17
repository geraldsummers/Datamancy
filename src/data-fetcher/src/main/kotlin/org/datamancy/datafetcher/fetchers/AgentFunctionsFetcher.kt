package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore

private val logger = KotlinLogging.logger {}
private val gson = Gson()

data class CatalogDiff(
    val added: Set<String>,
    val removed: Set<String>,
    val modified: Set<String>
)

class AgentFunctionsFetcher : Fetcher {
    private val pgStore = PostgresStore()
    private val agentToolServerUrl = System.getenv("AGENT_TOOL_SERVER_URL")
        ?: "http://agent-tool-server:8081"

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("agent_functions", version = "2.0.0") { ctx ->
            logger.info { "Fetching agent tool server functions..." }
            ctx.markAttempted()

            // Fetch function catalog using standardized HTTP client
            val url = "$agentToolServerUrl/tools"
            val response = ctx.http.get(url)

            if (!response.isSuccessful) {
                ctx.markFailed()
                ctx.recordError("HTTP_ERROR", "HTTP ${response.code}")
                response.close()
                throw Exception("Failed to fetch agent functions: HTTP ${response.code}")
            }

            val body = response.body?.string()
            response.close()

            if (body == null) {
                ctx.markFailed()
                ctx.recordError("EMPTY_RESPONSE", "Empty response")
                throw Exception("Empty response")
            }

            ctx.markFetched()
            val json = JsonParser.parseString(body)

            // Extract tools array and normalize structure
            val toolsArray = when {
                json.isJsonArray -> json.asJsonArray
                json.isJsonObject && json.asJsonObject.has("tools") ->
                    json.asJsonObject.getAsJsonArray("tools")
                else -> {
                    ctx.recordError("INVALID_FORMAT", "Unexpected response format")
                    throw Exception("Unexpected response format")
                }
            }

            // Build catalog version with individual tool hashes
            val currentTools = mutableMapOf<String, String>()
            toolsArray.forEach { toolElement ->
                val toolObj = toolElement.asJsonObject
                val toolName = toolObj.get("name")?.asString ?: return@forEach
                val toolHash = ContentHasher.hashJson(gson.toJson(toolObj))
                currentTools[toolName] = toolHash
            }

            // Check if catalog changed by comparing with previous version
            val catalogItemId = "catalog_latest"
            val catalogJson = gson.toJson(mapOf(
                "serverUrl" to agentToolServerUrl,
                "toolCount" to currentTools.size,
                "tools" to currentTools,
                "fetchedAt" to Clock.System.now().toString()
            ))
            val catalogHash = ContentHasher.hashJson(catalogJson)

            // Dedupe check
            when (ctx.dedupe.shouldUpsert(catalogItemId, catalogHash)) {
                DedupeResult.NEW -> {
                    // First time seeing this catalog
                    ctx.storage.storeRawText(catalogItemId, body, "json")

                    // Store individual tool entries for diffing
                    currentTools.forEach { (toolName, toolHash) ->
                        ctx.checkpoint.set("tool_$toolName", toolHash)
                    }

                    pgStore.storeFetchMetadata(
                        source = "agent_functions",
                        category = "tool_catalog",
                        itemCount = currentTools.size,
                        fetchedAt = Clock.System.now(),
                        metadata = mapOf(
                            "serverUrl" to agentToolServerUrl,
                            "toolCount" to currentTools.size
                        )
                    )

                    ctx.markNew()
                    logger.info { "New catalog: ${currentTools.size} tools" }
                    "New catalog with ${currentTools.size} tools"
                }

                DedupeResult.UPDATED -> {
                    // Catalog changed - compute diff
                    val previousTools = ctx.checkpoint.getAll()
                        .filterKeys { it.startsWith("tool_") }
                        .mapKeys { it.key.removePrefix("tool_") }

                    val diff = computeDiff(previousTools, currentTools)

                    // Store updated catalog
                    ctx.storage.storeRawText(catalogItemId, body, "json")

                    // Store diff report
                    val diffReport = mapOf(
                        "added" to diff.added.toList(),
                        "removed" to diff.removed.toList(),
                        "modified" to diff.modified.toList(),
                        "timestamp" to Clock.System.now().toString()
                    )
                    val diffId = "diff_${Clock.System.now().epochSeconds}"
                    ctx.storage.storeRawText(diffId, gson.toJson(diffReport), "json")

                    // Update tool checkpoints
                    currentTools.forEach { (toolName, toolHash) ->
                        ctx.checkpoint.set("tool_$toolName", toolHash)
                    }
                    // Remove deleted tools from checkpoints
                    diff.removed.forEach { toolName ->
                        ctx.checkpoint.delete("tool_$toolName")
                    }

                    pgStore.storeFetchMetadata(
                        source = "agent_functions",
                        category = "tool_catalog",
                        itemCount = currentTools.size,
                        fetchedAt = Clock.System.now(),
                        metadata = mapOf(
                            "serverUrl" to agentToolServerUrl,
                            "toolCount" to currentTools.size,
                            "added" to diff.added.size,
                            "removed" to diff.removed.size,
                            "modified" to diff.modified.size
                        )
                    )

                    ctx.markUpdated()
                    logger.info { "Catalog updated: +${diff.added.size} -${diff.removed.size} ~${diff.modified.size}" }
                    "Catalog updated: ${currentTools.size} tools (+${diff.added.size} -${diff.removed.size} ~${diff.modified.size})"
                }

                DedupeResult.UNCHANGED -> {
                    ctx.markSkipped()
                    "Catalog unchanged (${currentTools.size} tools)"
                }
            }
        }
    }

    private fun computeDiff(previous: Map<String, String>, current: Map<String, String>): CatalogDiff {
        val added = current.keys - previous.keys
        val removed = previous.keys - current.keys
        val modified = (previous.keys intersect current.keys).filter { key ->
            previous[key] != current[key]
        }.toSet()

        return CatalogDiff(added, removed, modified)
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying agent tool server..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check agent-tool-server reachability
        checks.add(
            DryRunUtils.checkUrl(
                "$agentToolServerUrl/healthz",
                "Agent Tool Server"
            )
        )

        // Check tools endpoint
        checks.add(
            DryRunUtils.checkUrl(
                "$agentToolServerUrl/tools",
                "Agent Tool Server /tools endpoint"
            )
        )

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/agent_functions", "Agent functions directory"))

        return DryRunResult(checks)
    }
}
