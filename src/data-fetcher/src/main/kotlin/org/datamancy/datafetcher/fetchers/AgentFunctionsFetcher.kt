package org.datamancy.datafetcher.fetchers

import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.FileSystemStore
import org.datamancy.datafetcher.storage.PostgresStore
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class AgentFunctionsFetcher : Fetcher {
    private val fsStore = FileSystemStore()
    private val pgStore = PostgresStore()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val agentToolServerUrl = System.getenv("AGENT_TOOL_SERVER_URL")
        ?: "http://agent-tool-server:8081"

    override suspend fun fetch(): FetchResult {
        logger.info { "Fetching agent tool server functions..." }

        try {
            // Fetch function catalog from agent-tool-server
            val url = "$agentToolServerUrl/tools"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return FetchResult.Error("Failed to fetch agent functions: HTTP ${response.code}")
                }

                val body = response.body?.string() ?: return FetchResult.Error("Empty response")
                val json = JsonParser.parseString(body)

                // Store raw response
                val filename = "agent_functions_${Clock.System.now().epochSeconds}.json"
                fsStore.storeRawText("agent_functions", filename, body)

                // Extract function count (structure depends on agent-tool-server API)
                val functionCount = if (json.isJsonArray) {
                    json.asJsonArray.size()
                } else if (json.isJsonObject && json.asJsonObject.has("tools")) {
                    json.asJsonObject.getAsJsonArray("tools").size()
                } else {
                    0
                }

                // Store metadata
                pgStore.storeFetchMetadata(
                    source = "agent_functions",
                    category = "tool_catalog",
                    itemCount = functionCount,
                    fetchedAt = Clock.System.now(),
                    metadata = mapOf(
                        "serverUrl" to agentToolServerUrl,
                        "functionCount" to functionCount
                    )
                )

                logger.info { "Fetched $functionCount agent functions" }
                return FetchResult.Success("Fetched agent function catalog", functionCount)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch agent functions" }
            return FetchResult.Error("Exception: ${e.message}")
        }
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
