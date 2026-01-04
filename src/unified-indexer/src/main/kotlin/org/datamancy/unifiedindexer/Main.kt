package org.datamancy.unifiedindexer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.datamancy.config.ServicePorts
import java.util.UUID

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting Unified Indexing Service..." }

    // Environment configuration
    val postgresHost = System.getenv("POSTGRES_HOST") ?: "postgres"
    val postgresPort = System.getenv("POSTGRES_PORT") ?: "5432"
    val postgresDb = System.getenv("POSTGRES_DB") ?: "datamancy"
    val postgresUser = System.getenv("POSTGRES_USER") ?: "default"
    val postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: ""

    val bookstackUrl = System.getenv("BOOKSTACK_URL") ?: "http://bookstack:80"
    val bookstackToken = System.getenv("BOOKSTACK_API_TOKEN_ID") ?: ""
    val bookstackSecret = System.getenv("BOOKSTACK_API_TOKEN_SECRET") ?: ""

    val qdrantUrl = System.getenv("QDRANT_URL") ?: "http://qdrant:6334"
    val clickhouseUrl = System.getenv("CLICKHOUSE_URL") ?: "http://clickhouse:8123"
    val embeddingUrl = System.getenv("EMBEDDING_SERVICE_URL") ?: "http://embedding-service:8080"

    // Initialize database
    val database = Database(
        jdbcUrl = "jdbc:postgresql://$postgresHost:$postgresPort/$postgresDb",
        username = postgresUser,
        password = postgresPassword
    )

    // Initialize source adapters
    val bookstackAdapter = BookStackAdapter(bookstackUrl, bookstackToken, bookstackSecret)
    val clickhouseAdapter = ClickHouseSourceAdapter(clickhouseUrl)

    // Initialize indexer with ClickHouse as default source adapter
    val indexer = UnifiedIndexer(
        database = database,
        sourceAdapter = clickhouseAdapter,  // ClickHouse is now the primary source
        qdrantUrl = qdrantUrl,
        clickhouseUrl = clickhouseUrl,
        embeddingServiceUrl = embeddingUrl
    )

    // Keep BookStack indexer for backward compatibility
    val bookstackIndexer = UnifiedIndexer(
        database = database,
        sourceAdapter = bookstackAdapter,
        qdrantUrl = qdrantUrl,
        clickhouseUrl = clickhouseUrl,
        embeddingServiceUrl = embeddingUrl
    )

    val port = System.getenv("INDEXER_PORT")?.toIntOrNull() ?: ServicePorts.UnifiedIndexer.INTERNAL
    val server = embeddedServer(Netty, port = port) {
        configureServer(database, indexer, bookstackIndexer)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down Unified Indexing Service..." }
        database.close()
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

fun Application.configureServer(database: Database, indexer: UnifiedIndexer, bookstackIndexer: UnifiedIndexer? = null) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(SSE)

    routing {
        get("/") {
            call.respondText("Unified Indexing Service v1.0.0", ContentType.Text.Plain)
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        // Web UI Dashboard
        get("/dashboard") {
            call.respondHtml {
                head {
                    title("Unified Indexer Dashboard")
                    style {
                        unsafe {
                            raw("""
                                body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }
                                .container { max-width: 1200px; margin: 0 auto; }
                                h1 { color: #333; }
                                .controls { margin: 20px 0; padding: 20px; background: white; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                                .controls button { padding: 10px 20px; margin-right: 10px; background: #007bff; color: white; border: none; border-radius: 4px; cursor: pointer; }
                                .controls button:hover { background: #0056b3; }
                                .job { padding: 15px; margin: 10px 0; background: white; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                                .job.running { border-left: 4px solid #28a745; }
                                .job.completed { border-left: 4px solid #17a2b8; }
                                .job.failed { border-left: 4px solid #dc3545; }
                                .progress-bar { width: 100%; height: 20px; background: #e9ecef; border-radius: 4px; overflow: hidden; margin: 10px 0; }
                                .progress-fill { height: 100%; background: #28a745; transition: width 0.3s; }
                                .status { display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }
                                .status.running { background: #28a745; color: white; }
                                .status.completed { background: #17a2b8; color: white; }
                                .status.failed { background: #dc3545; color: white; }
                                .metadata { color: #666; font-size: 14px; margin-top: 5px; }
                            """)
                        }
                    }
                }
                body {
                    div("container") {
                        h1 { +"Unified Indexer Dashboard" }

                        div("controls") {
                            h2 { +"Quick Actions" }
                            button {
                                onClick = "indexCollection('legislation_federal', false)"
                                +"Index Federal Legislation (Incremental)"
                            }
                            button {
                                onClick = "indexCollection('legislation_federal', true)"
                                +"Full Reindex Federal"
                            }
                            button {
                                onClick = "indexAll()"
                                +"Index All Collections"
                            }
                        }

                        div {
                            id = "jobs"
                            h2 { +"Active and Recent Jobs" }
                            p { +"Loading..." }
                        }
                    }

                    script {
                        unsafe {
                            raw("""
                                const eventSource = new EventSource('/events');

                                eventSource.onmessage = (event) => {
                                    const jobs = JSON.parse(event.data);
                                    updateJobsDisplay(jobs);
                                };

                                function updateJobsDisplay(jobs) {
                                    const container = document.getElementById('jobs');
                                    if (jobs.length === 0) {
                                        container.innerHTML = '<h2>Active and Recent Jobs</h2><p>No jobs found</p>';
                                        return;
                                    }

                                    let html = '<h2>Active and Recent Jobs</h2>';
                                    jobs.forEach(job => {
                                        const progress = job.totalPages > 0 ? (job.indexedPages / job.totalPages * 100).toFixed(1) : 0;
                                        html += `
                                            <div class="job ${'$'}{job.status}">
                                                <strong>${'$'}{job.collectionName}</strong>
                                                <span class="status ${'$'}{job.status}">${'$'}{job.status.toUpperCase()}</span>
                                                <div class="progress-bar">
                                                    <div class="progress-fill" style="width: ${'$'}{progress}%"></div>
                                                </div>
                                                <div class="metadata">
                                                    Progress: ${'$'}{job.indexedPages}/${'$'}{job.totalPages} pages (${'$'}{progress}%)
                                                    | Started: ${'$'}{new Date(job.startedAt).toLocaleString()}
                                                    ${'$'}{job.completedAt ? '| Completed: ' + new Date(job.completedAt).toLocaleString() : ''}
                                                    ${'$'}{job.errorMessage ? '<br>Error: ' + job.errorMessage : ''}
                                                </div>
                                            </div>
                                        `;
                                    });
                                    container.innerHTML = html;
                                }

                                async function indexCollection(collection, fullReindex) {
                                    const url = `/index/collection/${'$'}{collection}?fullReindex=${'$'}{fullReindex}`;
                                    const response = await fetch(url, { method: 'POST' });
                                    const data = await response.json();
                                    alert(data.message);
                                }

                                async function indexAll() {
                                    const response = await fetch('/index/all', { method: 'POST' });
                                    const data = await response.json();
                                    alert(data.message);
                                }
                            """)
                        }
                    }
                }
            }
        }

        // Server-Sent Events for real-time updates
        sse("/events") {
            while (true) {
                val jobs = database.getAllJobs()
                val jsonArray = buildJsonArray {
                    jobs.forEach { job ->
                        addJsonObject {
                            put("jobId", job.jobId.toString())
                            put("collectionName", job.collectionName)
                            put("status", job.status)
                            put("startedAt", job.startedAt ?: 0)
                            put("completedAt", job.completedAt ?: 0)
                            put("totalPages", job.totalPages)
                            put("indexedPages", job.indexedPages)
                            put("failedPages", job.failedPages)
                            put("errorMessage", job.errorMessage ?: "")
                        }
                    }
                }
                send(io.ktor.sse.ServerSentEvent(data = jsonArray.toString()))
                delay(1000)
            }
        }

        // Index a specific collection
        post("/index/collection/{collection}") {
            val collection = call.parameters["collection"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Collection name required")
            )

            val fullReindex = call.request.queryParameters["fullReindex"]?.toBoolean() ?: false

            launch {
                try {
                    indexer.indexCollection(collection, fullReindex)
                    application.log.info("Indexed collection: $collection")
                } catch (e: Exception) {
                    application.log.error("Failed to index collection: $collection", e)
                }
            }

            call.respond(
                HttpStatusCode.Accepted,
                mapOf("message" to "Indexing started for collection: $collection (fullReindex=$fullReindex)")
            )
        }

        // Index all collections
        post("/index/all") {
            val collections = listOf(
                "legal-federal", "legal-nsw", "legal-vic",
                "legal-qld", "legal-wa", "legal-sa",
                "legal-tas", "legal-act", "legal-nt"
            )

            collections.forEach { collection ->
                launch {
                    try {
                        indexer.indexCollection(collection, fullReindex = false)
                        application.log.info("Indexed collection: $collection")
                    } catch (e: Exception) {
                        application.log.error("Failed to index collection: $collection", e)
                    }
                }
            }

            call.respond(
                HttpStatusCode.Accepted,
                mapOf("message" to "Indexing started for ${collections.size} collections")
            )
        }

        // List available collections
        get("/api/indexer/collections") {
            try {
                val adapter = indexer.javaClass.getDeclaredField("sourceAdapter").let { field ->
                    field.isAccessible = true
                    field.get(indexer)
                }

                val collections = when (adapter) {
                    is ClickHouseSourceAdapter -> adapter.listCollections()
                    else -> emptyList()
                }

                call.respond(collections)
            } catch (e: Exception) {
                application.log.error("Failed to list collections", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        // Get job status
        get("/jobs/{jobId}") {
            val jobId = try {
                UUID.fromString(call.parameters["jobId"])
            } catch (e: Exception) {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            }

            val job = database.getJob(jobId)
            if (job == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            } else {
                call.respond(job)
            }
        }

        // Get job errors
        get("/jobs/{jobId}/errors") {
            val jobId = try {
                UUID.fromString(call.parameters["jobId"])
            } catch (e: Exception) {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            }

            val errors = database.getJobErrors(jobId)
            call.respond(errors)
        }

        // Get all jobs
        get("/jobs") {
            val jobs = database.getAllJobs()
            call.respond(jobs)
        }

        // Ingestion control endpoints
        route("/api/ingestion") {
            // Check if ingestion should be accepted (always true for now)
            get("/should-accept-data") {
                call.respond(IngestionAcceptResponse(
                    shouldAccept = true,
                    reason = "Ingestion is active"
                ))
            }

            // Get ingestion status
            get("/status") {
                call.respond(IngestionStatusResponse(
                    isRunning = true,
                    lastStateChange = System.currentTimeMillis(),
                    message = "Ingestion is active"
                ))
            }

            // Start ingestion (no-op for now)
            post("/start") {
                call.respond(IngestionControlResponse(
                    success = true,
                    message = "Ingestion is always active",
                    previousState = true
                ))
            }

            // Stop ingestion (no-op for now)
            post("/stop") {
                call.respond(IngestionControlResponse(
                    success = true,
                    message = "Ingestion control not implemented",
                    previousState = true
                ))
            }
        }
    }
}

@Serializable
data class IngestionAcceptResponse(
    val shouldAccept: Boolean,
    val reason: String
)

@Serializable
data class IngestionStatusResponse(
    val isRunning: Boolean,
    val lastStateChange: Long,
    val message: String
)

@Serializable
data class IngestionControlResponse(
    val success: Boolean,
    val message: String,
    val previousState: Boolean
)
