package org.datamancy.alphadataset

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
import org.datamancy.trading.alpha.AlphaDatasetValidationRequest
import org.datamancy.trading.alpha.AlphaDatasetValidator
import org.datamancy.trading.alpha.AlphaDefaultsFactory
import org.datamancy.trading.alpha.http.AlphaServiceError
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AlphaDatasetApplication")
private val gson = AlphaServiceJson.gson

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureAlphaDatasetApp()
    }.start(wait = true)
}

fun Application.configureAlphaDatasetApp(
    validator: AlphaDatasetValidator = AlphaDatasetValidator(ActiveTradingPolicy::current)
) {
    routing {
        get("/health") {
            call.respondText(gson.toJson(mapOf("status" to "healthy", "service" to "alpha-dataset-service")), ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/") {
            call.respondText(
                gson.toJson(
                    AlphaServiceJson.root(
                        service = "alpha-dataset-service",
                        endpoints = listOf("/health", "/api/v1/datasets/defaults", "/api/v1/datasets/validate")
                    )
                ),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
        get("/api/v1/datasets/defaults") {
            call.respondText(gson.toJson(AlphaDefaultsFactory.datasetDefaults(ActiveTradingPolicy.current())), ContentType.Application.Json, HttpStatusCode.OK)
        }
        post("/api/v1/datasets/validate") {
            val request = runCatching { gson.fromJson(call.receiveText(), AlphaDatasetValidationRequest::class.java) }
                .onFailure { logger.warn("Dataset validation request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid dataset validation request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = runCatching { validator.validate(request) }
                .onFailure { logger.warn("Dataset validation failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("dataset validation failed: ${it.message}")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
