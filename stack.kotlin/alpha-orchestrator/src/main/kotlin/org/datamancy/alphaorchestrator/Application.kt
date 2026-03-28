package org.datamancy.alphaorchestrator

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
import org.datamancy.trading.alpha.AlphaWorkflowPlanRequest
import org.datamancy.trading.alpha.AlphaWorkflowPlanner
import org.datamancy.trading.alpha.http.AlphaServiceError
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AlphaOrchestratorApplication")
private val gson = AlphaServiceJson.gson

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureAlphaOrchestratorApp()
    }.start(wait = true)
}

fun Application.configureAlphaOrchestratorApp(
    planner: AlphaWorkflowPlanner = AlphaWorkflowPlanner(ActiveTradingPolicy::current)
) {
    routing {
        get("/health") {
            call.respondText(gson.toJson(mapOf("status" to "healthy", "service" to "alpha-orchestrator")), ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/") {
            call.respondText(
                gson.toJson(
                    AlphaServiceJson.root(
                        service = "alpha-orchestrator",
                        endpoints = listOf("/health", "/api/v1/orchestrator/plan")
                    )
                ),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
        post("/api/v1/orchestrator/plan") {
            val request = runCatching { gson.fromJson(call.receiveText(), AlphaWorkflowPlanRequest::class.java) }
                .onFailure { logger.warn("Workflow plan request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid orchestrator plan request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = planner.plan(request)
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
