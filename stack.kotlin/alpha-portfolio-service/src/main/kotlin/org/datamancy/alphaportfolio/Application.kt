package org.datamancy.alphaportfolio

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
import org.datamancy.trading.alpha.AlphaPortfolioConstructor
import org.datamancy.trading.alpha.AlphaPortfolioRequest
import org.datamancy.trading.alpha.http.AlphaServiceError
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AlphaPortfolioApplication")
private val gson = AlphaServiceJson.gson

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureAlphaPortfolioApp()
    }.start(wait = true)
}

fun Application.configureAlphaPortfolioApp(
    constructor: AlphaPortfolioConstructor = AlphaPortfolioConstructor(ActiveTradingPolicy::current)
) {
    routing {
        get("/health") {
            call.respondText(gson.toJson(mapOf("status" to "healthy", "service" to "alpha-portfolio-service")), ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/") {
            call.respondText(
                gson.toJson(
                    AlphaServiceJson.root(
                        service = "alpha-portfolio-service",
                        endpoints = listOf("/health", "/api/v1/portfolio/defaults", "/api/v1/portfolio/construct")
                    )
                ),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
        get("/api/v1/portfolio/defaults") {
            call.respondText(gson.toJson(constructor.defaults()), ContentType.Application.Json, HttpStatusCode.OK)
        }
        post("/api/v1/portfolio/construct") {
            val request = runCatching { gson.fromJson(call.receiveText(), AlphaPortfolioRequest::class.java) }
                .onFailure { logger.warn("Portfolio request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid portfolio request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = runCatching { constructor.construct(request) }
                .onFailure { logger.warn("Portfolio construction failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("portfolio construction failed: ${it.message}")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
