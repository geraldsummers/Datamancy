package org.datamancy.alphaexecutionagent

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
import org.datamancy.trading.alpha.AlphaExecutionPlanRequest
import org.datamancy.trading.alpha.AlphaExecutionPlanner
import org.datamancy.trading.alpha.http.AlphaServiceError
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AlphaExecutionAgentApplication")
private val gson = AlphaServiceJson.gson

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureAlphaExecutionAgentApp()
    }.start(wait = true)
}

fun Application.configureAlphaExecutionAgentApp(
    planner: AlphaExecutionPlanner = AlphaExecutionPlanner(ActiveTradingPolicy::current)
) {
    routing {
        get("/health") {
            call.respondText(gson.toJson(mapOf("status" to "healthy", "service" to "alpha-execution-agent")), ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/") {
            call.respondText(
                gson.toJson(
                    AlphaServiceJson.root(
                        service = "alpha-execution-agent",
                        endpoints = listOf("/health", "/api/v1/execution/defaults", "/api/v1/execution/plan")
                    )
                ),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
        get("/api/v1/execution/defaults") {
            call.respondText(gson.toJson(planner.defaults()), ContentType.Application.Json, HttpStatusCode.OK)
        }
        post("/api/v1/execution/plan") {
            val request = runCatching { gson.fromJson(call.receiveText(), AlphaExecutionPlanRequest::class.java) }
                .onFailure { logger.warn("Execution plan request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid execution plan request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = runCatching { planner.plan(request) }
                .onFailure { logger.warn("Execution plan failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("execution planning failed: ${it.message}")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
