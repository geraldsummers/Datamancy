package org.datamancy.datafetcher

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.datamancy.datafetcher.api.configureHealthEndpoints
import org.datamancy.datafetcher.api.configureStatusEndpoints
import org.datamancy.datafetcher.api.configureTriggerEndpoints
import org.datamancy.datafetcher.api.configureDryRunEndpoints
import org.datamancy.datafetcher.api.configureMarkdownEndpoints
import org.datamancy.datafetcher.config.FetchConfig
import org.datamancy.datafetcher.scheduler.FetchScheduler
import java.io.File

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting Datamancy Data Fetcher Service..." }

    // Load configuration
    val configPath = System.getenv("FETCH_SCHEDULES_PATH") ?: "/app/config/schedules.yaml"
    val sourcesPath = System.getenv("FETCH_SOURCES_PATH") ?: "/app/config/sources.yaml"

    val config = try {
        FetchConfig.load(configPath, sourcesPath)
    } catch (e: Exception) {
        logger.error(e) { "Failed to load configuration from $configPath and $sourcesPath" }
        FetchConfig.default()
    }

    // Initialize scheduler
    val scheduler = FetchScheduler(config)

    // Start Ktor server
    val port = System.getenv("DATAFETCHER_PORT")?.toIntOrNull() ?: 8095
    val server = embeddedServer(Netty, port = port) {
        configureServer(scheduler, config)
    }

    // Graceful shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down Data Fetcher Service..." }
        scheduler.stop()
        server.stop(1000, 5000)
    })

    // Start scheduler and server
    scheduler.start()
    server.start(wait = true)
}

fun Application.configureServer(scheduler: FetchScheduler, config: FetchConfig) {
    // JSON serialization
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // Routes
    routing {
        // Root endpoint
        get("/") {
            call.respondText("Datamancy Data Fetcher Service v1.0.0", ContentType.Text.Plain)
        }

        // Health check endpoints
        configureHealthEndpoints(scheduler)

        // Status endpoints
        configureStatusEndpoints(scheduler)

        // Manual trigger endpoints
        configureTriggerEndpoints(scheduler)

        // Dry-run endpoints
        configureDryRunEndpoints(scheduler)

        // Markdown fetch endpoints
        configureMarkdownEndpoints(config)
    }
}
