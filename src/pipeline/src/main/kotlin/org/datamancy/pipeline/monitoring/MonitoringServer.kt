package org.datamancy.pipeline.monitoring

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.datamancy.pipeline.storage.SourceMetadataStore
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Lightweight HTTP server for monitoring pipeline health and status
 */
class MonitoringServer(
    private val port: Int = 8090,
    private val metadataStore: SourceMetadataStore
) {
    private val server = AtomicReference<NettyApplicationEngine?>()

    fun start() {
        logger.info { "Starting monitoring server on port $port" }

        val engine = embeddedServer(Netty, host = "0.0.0.0", port = port) {
            install(ContentNegotiation) {
                json()
            }

            routing {
                get("/health") {
                    call.respond(HealthResponse(status = "ok", message = "Pipeline service running"))
                }

                get("/status") {
                    val sources = listOf(
                        "rss", "cve", "torrents", "wikipedia",
                        "australian_laws", "linux_docs"
                    )

                    val statuses = sources.map { sourceName ->
                        val metadata = metadataStore.load(sourceName)
                        SourceStatus(
                            source = sourceName,
                            enabled = true, // Could read from config
                            totalProcessed = metadata.totalItemsProcessed,
                            totalFailed = metadata.totalItemsFailed,
                            lastRunTime = metadata.lastSuccessfulRun ?: metadata.lastAttemptedRun ?: "never",
                            consecutiveFailures = metadata.consecutiveFailures,
                            status = if (metadata.consecutiveFailures > 3) "degraded" else "healthy"
                        )
                    }

                    call.respond(StatusResponse(
                        uptime = System.currentTimeMillis() / 1000, // Simple uptime in seconds
                        sources = statuses
                    ))
                }

                get("/sources") {
                    val sources = listOf(
                        SourceInfo("rss", "RSS Feeds", "Aggregates news and blog feeds"),
                        SourceInfo("cve", "CVE Database", "Security vulnerabilities from NVD"),
                        SourceInfo("torrents", "Torrents CSV", "Torrent metadata from DHT"),
                        SourceInfo("wikipedia", "Wikipedia", "Wikipedia article dumps"),
                        SourceInfo("australian_laws", "Australian Laws", "Legal documents from legislation.gov.au"),
                        SourceInfo("linux_docs", "Linux Documentation", "Kernel and system documentation")
                    )

                    call.respond(SourcesResponse(sources = sources))
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
    val status: String
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
