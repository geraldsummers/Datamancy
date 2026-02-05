package org.datamancy.pipeline.monitoring

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.SourceMetadataStore
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}


class MonitoringServer(
    private val port: Int = 8090,
    private val metadataStore: SourceMetadataStore,
    private val stagingStore: DocumentStagingStore? = null,
    private val apiKey: String? = System.getenv("MONITORING_API_KEY")  
) {
    private val server = AtomicReference<NettyApplicationEngine?>()

    
    private suspend fun ApplicationCall.requireAuth(): Boolean {
        if (apiKey.isNullOrBlank()) {
            return true  
        }

        val providedKey = request.header("X-API-Key") ?: request.header("Authorization")?.removePrefix("Bearer ")

        if (providedKey != apiKey) {
            logger.warn { "Unauthorized monitoring access attempt from ${request.local.remoteHost}" }
            respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
            return false
        }

        return true
    }

    fun start() {
        if (apiKey.isNullOrBlank()) {
            logger.warn { "⚠️  Monitoring server starting WITHOUT authentication - set MONITORING_API_KEY for production!" }
        } else {
            logger.info { "🔒 Monitoring server starting WITH authentication enabled" }
        }
        logger.info { "Starting monitoring server on port $port" }

        val engine = embeddedServer(Netty, host = "0.0.0.0", port = port) {
            install(ContentNegotiation) {
                json()
            }

            routing {
                get("/health") {
                    if (!call.requireAuth()) return@get
                    call.respond(HealthResponse(status = "ok", message = "Pipeline service running"))
                }

                get("/status") {
                    if (!call.requireAuth()) return@get

                    
                    val enabledSources = mapOf(
                        "rss" to (System.getenv("RSS_ENABLED")?.toBoolean() ?: true),
                        "cve" to (System.getenv("CVE_ENABLED")?.toBoolean() ?: true),
                        "torrents" to (System.getenv("TORRENTS_ENABLED")?.toBoolean() ?: true),
                        "wikipedia" to (System.getenv("WIKIPEDIA_ENABLED")?.toBoolean() ?: true),
                        "australian_laws" to (System.getenv("AUSTRALIAN_LAWS_ENABLED")?.toBoolean() ?: true),
                        "linux_docs" to (System.getenv("LINUX_DOCS_ENABLED")?.toBoolean() ?: true),
                        "debian_wiki" to (System.getenv("WIKI_ENABLED")?.toBoolean() ?: true),
                        "arch_wiki" to (System.getenv("WIKI_ENABLED")?.toBoolean() ?: true)
                    )

                    val statuses = enabledSources.map { (sourceName, enabled) ->
                        val metadata = metadataStore.load(sourceName)
                        SourceStatus(
                            source = sourceName,
                            enabled = enabled,
                            totalProcessed = metadata.totalItemsProcessed,
                            totalFailed = metadata.totalItemsFailed,
                            lastRunTime = metadata.lastSuccessfulRun ?: metadata.lastAttemptedRun ?: "never",
                            consecutiveFailures = metadata.consecutiveFailures,
                            status = if (metadata.consecutiveFailures > 3) "degraded" else "healthy",
                            checkpointData = metadata.checkpointData
                        )
                    }

                    call.respond(StatusResponse(
                        uptime = System.currentTimeMillis() / 1000, 
                        sources = statuses
                    ))
                }

                get("/sources") {
                    if (!call.requireAuth()) return@get
                    val sources = listOf(
                        SourceInfo("rss", "RSS Feeds", "Aggregates news and blog feeds"),
                        SourceInfo("cve", "CVE Database", "Security vulnerabilities from NVD"),
                        SourceInfo("torrents", "Torrents CSV", "Torrent metadata from DHT"),
                        SourceInfo("wikipedia", "Wikipedia", "Wikipedia article dumps"),
                        SourceInfo("australian_laws", "Australian Laws", "Legal documents from legislation.gov.au"),
                        SourceInfo("linux_docs", "Linux Documentation", "Kernel and system documentation"),
                        SourceInfo("debian_wiki", "Debian Wiki", "Debian community documentation"),
                        SourceInfo("arch_wiki", "Arch Wiki", "Arch Linux documentation")
                    )

                    call.respond(SourcesResponse(sources = sources))
                }

                
                get("/queue") {
                    if (!call.requireAuth()) return@get
                    if (stagingStore == null) {
                        call.respond(QueueStatusResponse(
                            available = false,
                            message = "Queue monitoring not available (staging store not configured)"
                        ))
                        return@get
                    }

                    val stats = runBlocking { stagingStore.getStats() }

                    call.respond(QueueStatusResponse(
                        available = true,
                        pending = stats["pending"] ?: 0,
                        inProgress = stats["in_progress"] ?: 0,
                        completed = stats["completed"] ?: 0,
                        failed = stats["failed"] ?: 0,
                        message = "Embedding queue status"
                    ))
                }

                
                get("/queue/{source}") {
                    if (!call.requireAuth()) return@get
                    val source = call.parameters["source"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Source parameter required")
                    )

                    if (stagingStore == null) {
                        call.respond(SourceQueueStatusResponse(
                            source = source,
                            available = false,
                            message = "Queue monitoring not available"
                        ))
                        return@get
                    }

                    val stats = runBlocking { stagingStore.getStatsBySource(source) }

                    call.respond(SourceQueueStatusResponse(
                        source = source,
                        available = true,
                        pending = stats["pending"] ?: 0,
                        inProgress = stats["in_progress"] ?: 0,
                        completed = stats["completed"] ?: 0,
                        failed = stats["failed"] ?: 0,
                        message = "Queue status for $source"
                    ))
                }

                get("/") {
                    call.respondText(contentType = io.ktor.http.ContentType.Text.Html) {
                        """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <title>Datamancy Pipeline Monitor</title>
                            <meta charset="utf-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <style>
                                * { margin: 0; padding: 0; box-sizing: border-box; }
                                body {
                                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                    background: #0f0f23;
                                    color: #cccccc;
                                    padding: 20px;
                                    line-height: 1.6;
                                }
                                .container { max-width: 1200px; margin: 0 auto; }
                                h1 {
                                    color: #00ff41;
                                    margin-bottom: 10px;
                                    font-size: 2em;
                                    text-shadow: 0 0 10px rgba(0, 255, 65, 0.5);
                                }
                                .subtitle {
                                    color: #888;
                                    margin-bottom: 30px;
                                    font-size: 0.9em;
                                }
                                .stats-grid {
                                    display: grid;
                                    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                                    gap: 15px;
                                    margin-bottom: 30px;
                                }
                                .stat-card {
                                    background: #1a1a2e;
                                    border: 1px solid #2a2a3e;
                                    border-radius: 8px;
                                    padding: 20px;
                                }
                                .stat-label {
                                    color: #888;
                                    font-size: 0.85em;
                                    text-transform: uppercase;
                                    margin-bottom: 8px;
                                }
                                .stat-value {
                                    font-size: 2em;
                                    font-weight: bold;
                                    color: #00ff41;
                                }
                                .sources-table {
                                    background: #1a1a2e;
                                    border: 1px solid #2a2a3e;
                                    border-radius: 8px;
                                    overflow: hidden;
                                }
                                table {
                                    width: 100%;
                                    border-collapse: collapse;
                                }
                                th {
                                    background: #16213e;
                                    color: #00ff41;
                                    text-align: left;
                                    padding: 15px;
                                    font-weight: 600;
                                    text-transform: uppercase;
                                    font-size: 0.85em;
                                }
                                td {
                                    padding: 15px;
                                    border-top: 1px solid #2a2a3e;
                                }
                                .status-badge {
                                    display: inline-block;
                                    padding: 4px 12px;
                                    border-radius: 12px;
                                    font-size: 0.85em;
                                    font-weight: 600;
                                }
                                .status-healthy { background: rgba(0, 255, 65, 0.2); color: #00ff41; }
                                .status-degraded { background: rgba(255, 165, 0, 0.2); color: #ffaa00; }
                                .status-error { background: rgba(255, 65, 54, 0.2); color: #ff4136; }
                                .metric { color: #00ff41; font-weight: 600; }
                                .metric-failed { color: #ff4136; font-weight: 600; }
                                .last-run { color: #888; font-size: 0.9em; }
                                .footer {
                                    margin-top: 30px;
                                    text-align: center;
                                    color: #555;
                                    font-size: 0.85em;
                                }
                                .loading {
                                    text-align: center;
                                    color: #888;
                                    padding: 40px;
                                }
                                @keyframes pulse {
                                    0%, 100% { opacity: 1; }
                                    50% { opacity: 0.5; }
                                }
                                .pulse { animation: pulse 2s ease-in-out infinite; }
                            </style>
                        </head>
                        <body>
                            <div class="container">
                                <h1>🔥 Datamancy Pipeline Monitor</h1>
                                <div class="subtitle">Real-time ingestion statistics</div>

                                <div class="stats-grid" id="statsGrid">
                                    <div class="loading pulse">Loading statistics...</div>
                                </div>

                                <div class="sources-table">
                                    <table>
                                        <thead>
                                            <tr>
                                                <th>Source</th>
                                                <th>Status</th>
                                                <th>Processed</th>
                                                <th>Failed</th>
                                                <th>Last Run</th>
                                            </tr>
                                        </thead>
                                        <tbody id="sourcesTable">
                                            <tr><td colspan="5" class="loading pulse">Loading sources...</td></tr>
                                        </tbody>
                                    </table>
                                </div>

                                <div class="footer">
                                    Auto-refreshing every 5 seconds • <a href="/status" style="color: #00ff41;">JSON API</a>
                                </div>
                            </div>

                            <script>
                                async function updateStats() {
                                    try {
                                        const response = await fetch('/status');
                                        const data = await response.json();

                                        // Calculate totals
                                        const totalProcessed = data.sources.reduce((sum, s) => sum + s.totalProcessed, 0);
                                        const totalFailed = data.sources.reduce((sum, s) => sum + s.totalFailed, 0);
                                        const healthySources = data.sources.filter(s => s.status === 'healthy').length;

                                        // Update stats grid
                                        document.getElementById('statsGrid').innerHTML = `
                                            <div class="stat-card">
                                                <div class="stat-label">Total Processed</div>
                                                <div class="stat-value">${'$'}{totalProcessed.toLocaleString()}</div>
                                            </div>
                                            <div class="stat-card">
                                                <div class="stat-label">Total Failed</div>
                                                <div class="stat-value" style="color: ${'$'}{totalFailed > 0 ? '#ff4136' : '#00ff41'}">${'$'}{totalFailed.toLocaleString()}</div>
                                            </div>
                                            <div class="stat-card">
                                                <div class="stat-label">Active Sources</div>
                                                <div class="stat-value">${'$'}{data.sources.length}</div>
                                            </div>
                                            <div class="stat-card">
                                                <div class="stat-label">Healthy Sources</div>
                                                <div class="stat-value">${'$'}{healthySources} / ${'$'}{data.sources.length}</div>
                                            </div>
                                        `;

                                        // Update sources table
                                        document.getElementById('sourcesTable').innerHTML = data.sources.map(source => {
                                            const statusClass = source.status === 'healthy' ? 'status-healthy' :
                                                              source.status === 'degraded' ? 'status-degraded' : 'status-error';
                                            const failureRate = source.totalProcessed + source.totalFailed > 0
                                                ? (source.totalFailed / (source.totalProcessed + source.totalFailed) * 100).toFixed(1)
                                                : 0;

                                            return `
                                                <tr>
                                                    <td><strong>${'$'}{source.source}</strong></td>
                                                    <td><span class="status-badge ${'$'}{statusClass}">${'$'}{source.status}</span></td>
                                                    <td><span class="metric">${'$'}{source.totalProcessed.toLocaleString()}</span></td>
                                                    <td>
                                                        <span class="metric-failed">${'$'}{source.totalFailed.toLocaleString()}</span>
                                                        ${'$'}{source.totalFailed > 0 ? `<span style="color: #888;"> (${'$'}{failureRate}%)</span>` : ''}
                                                    </td>
                                                    <td><span class="last-run">${'$'}{source.lastRunTime}</span></td>
                                                </tr>
                                            `;
                                        }).join('');

                                    } catch (error) {
                                        console.error('Failed to fetch stats:', error);
                                    }
                                }

                                // Update immediately and every 5 seconds
                                updateStats();
                                setInterval(updateStats, 5000);
                            </script>
                        </body>
                        </html>
                        """.trimIndent()
                    }
                }
            }
        }

        engine.start(wait = false)
        server.set(engine)
        logger.info { "Monitoring server started on http://0.0.0.0:$port" }
    }

    fun stop() {
        server.get()?.stop(1000, 2000)
        logger.info { "Monitoring server stopped" }
    }
}

@Serializable
data class HealthResponse(
    val status: String,
    val message: String
)

@Serializable
data class StatusResponse(
    val uptime: Long,
    val sources: List<SourceStatus>
)

@Serializable
data class SourceStatus(
    val source: String,
    val enabled: Boolean,
    val totalProcessed: Long,
    val totalFailed: Long,
    val lastRunTime: String,
    val consecutiveFailures: Int,
    val status: String,
    val checkpointData: Map<String, String> = emptyMap()
)

@Serializable
data class SourcesResponse(
    val sources: List<SourceInfo>
)

@Serializable
data class SourceInfo(
    val id: String,
    val name: String,
    val description: String
)

@Serializable
data class QueueStatusResponse(
    val available: Boolean,
    val pending: Long = 0,
    val inProgress: Long = 0,
    val completed: Long = 0,
    val failed: Long = 0,
    val message: String
)

@Serializable
data class SourceQueueStatusResponse(
    val source: String,
    val available: Boolean,
    val pending: Long = 0,
    val inProgress: Long = 0,
    val completed: Long = 0,
    val failed: Long = 0,
    val message: String
)
