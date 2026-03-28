package org.datamancy.marketdataarchiveimporter

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.datamancy.trading.alpha.AlphaArchiveImportPlanner
import org.datamancy.trading.alpha.ArchiveImportPlanRequest
import org.datamancy.trading.alpha.http.AlphaServiceError
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MarketDataArchiveImporterApplication")
private val gson = AlphaServiceJson.gson

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureMarketDataArchiveImporterApp()
    }.start(wait = true)
}

fun Application.configureMarketDataArchiveImporterApp(
    planner: AlphaArchiveImportPlanner = AlphaArchiveImportPlanner()
) {
    routing {
        get("/health") {
            call.respondText(gson.toJson(mapOf("status" to "healthy", "service" to "market-data-archive-importer")), ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/") {
            call.respondText(
                gson.toJson(
                    AlphaServiceJson.root(
                        service = "market-data-archive-importer",
                        endpoints = listOf("/health", "/api/v1/archive-import/plan")
                    )
                ),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
        post("/api/v1/archive-import/plan") {
            val request = runCatching { gson.fromJson(call.receiveText(), ArchiveImportPlanRequest::class.java) }
                .onFailure { logger.warn("Archive import request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid archive import request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = runCatching { planner.plan(request) }
                .onFailure { logger.warn("Archive import planning failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("archive import planning failed: ${it.message}")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
