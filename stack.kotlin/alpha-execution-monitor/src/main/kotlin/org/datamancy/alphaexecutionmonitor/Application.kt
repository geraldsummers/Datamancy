package org.datamancy.alphaexecutionmonitor

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
import org.datamancy.trading.alpha.AlphaExecutionMonitor
import org.datamancy.trading.alpha.AlphaExecutionMonitorRequest
import org.datamancy.trading.alpha.http.AlphaServiceError
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AlphaExecutionMonitorApplication")
private val gson = AlphaServiceJson.gson

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureAlphaExecutionMonitorApp()
    }.start(wait = true)
}

fun Application.configureAlphaExecutionMonitorApp(
    monitor: AlphaExecutionMonitor = AlphaExecutionMonitor(ActiveTradingPolicy::current)
) {
    routing {
        get("/health") {
            call.respondText(gson.toJson(mapOf("status" to "healthy", "service" to "alpha-execution-monitor")), ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/") {
            call.respondText(
                gson.toJson(
                    AlphaServiceJson.root(
                        service = "alpha-execution-monitor",
                        endpoints = listOf("/health", "/api/v1/execution-monitor/summarize")
                    )
                ),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
        post("/api/v1/execution-monitor/summarize") {
            val request = runCatching { gson.fromJson(call.receiveText(), AlphaExecutionMonitorRequest::class.java) }
                .onFailure { logger.warn("Execution monitor request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid execution monitor request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = runCatching { monitor.summarize(request) }
                .onFailure { logger.warn("Execution monitoring failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("execution monitoring failed: ${it.message}")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
