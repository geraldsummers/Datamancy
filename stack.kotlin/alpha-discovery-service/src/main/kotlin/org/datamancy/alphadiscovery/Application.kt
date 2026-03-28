package org.datamancy.alphadiscovery

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
import org.datamancy.trading.alpha.AlphaDiscoveryCandidateRequest
import org.datamancy.trading.alpha.AlphaDiscoveryPlanner
import org.datamancy.trading.alpha.http.AlphaServiceError
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AlphaDiscoveryApplication")
private val gson = AlphaServiceJson.gson

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureAlphaDiscoveryApp()
    }.start(wait = true)
}

fun Application.configureAlphaDiscoveryApp(
    planner: AlphaDiscoveryPlanner = AlphaDiscoveryPlanner(ActiveTradingPolicy::current)
) {
    routing {
        get("/health") {
            call.respondText(gson.toJson(mapOf("status" to "healthy", "service" to "alpha-discovery-service")), ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/") {
            call.respondText(
                gson.toJson(
                    AlphaServiceJson.root(
                        service = "alpha-discovery-service",
                        endpoints = listOf("/health", "/api/v1/discovery/defaults", "/api/v1/discovery/candidates")
                    )
                ),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
        get("/api/v1/discovery/defaults") {
            call.respondText(gson.toJson(planner.defaults()), ContentType.Application.Json, HttpStatusCode.OK)
        }
        post("/api/v1/discovery/candidates") {
            val request = runCatching { gson.fromJson(call.receiveText(), AlphaDiscoveryCandidateRequest::class.java) }
                .onFailure { logger.warn("Discovery candidate request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid discovery candidate request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = planner.candidateTemplates(request)
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
