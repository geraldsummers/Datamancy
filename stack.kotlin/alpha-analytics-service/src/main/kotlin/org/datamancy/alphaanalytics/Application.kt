package org.datamancy.alphaanalytics

import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.TradingPolicy
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("AlphaAnalyticsApplication")
private val responseGson = GsonBuilder()
    .setPrettyPrinting()
    .registerTypeAdapter(
        Instant::class.java,
        JsonSerializer<Instant> { value, _, _ -> JsonPrimitive(value?.toString()) }
    )
    .create()

private data class ErrorResponse(
    val error: String,
    val code: String? = null
)

private data class RootResponse(
    val service: String,
    val version: String,
    val endpoints: List<String>
)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureAlphaAnalyticsApp()
    }.start(wait = true)
}

fun Application.configureAlphaAnalyticsApp(
    tradingPolicy: () -> TradingPolicy = ActiveTradingPolicy::current,
    loadDataHealthSummary: suspend (String?, Int) -> DataHealthSummary = { exchange, barMinutes ->
        DataHealthService.fromEnvironment(tradingPolicy).loadSummary(exchange = exchange, barMinutes = barMinutes)
    },
    loadDataHealthIssues: suspend (String?, Int, Int, Boolean, Boolean) -> DataHealthIssuesResponse =
        { exchange, barMinutes, limit, includeInactive, includeHealthy ->
            DataHealthService.fromEnvironment(tradingPolicy).loadIssues(
                exchange = exchange,
                barMinutes = barMinutes,
                limit = limit,
                includeInactive = includeInactive,
                includeHealthy = includeHealthy
            )
        },
    loadVenueSanity: suspend (String?, String, Int) -> DataHealthVenueSanity =
        { exchange, symbol, barMinutes ->
            val service = DataHealthService.fromEnvironment(tradingPolicy)
            val issue = service.loadIssue(
                exchange = exchange,
                symbol = symbol,
                barMinutes = barMinutes
            )
            HyperliquidVenueSanityService(policyProvider = tradingPolicy).check(
                exchange = issue.exchange,
                symbol = issue.symbol,
                localIssue = issue
            )
        }
) {
    routing {
        get("/health") {
            call.respondText(
                responseGson.toJson(mapOf("status" to "healthy", "service" to "alpha-analytics-service")),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/") {
            call.respondText(
                responseGson.toJson(
                    RootResponse(
                        service = "alpha-analytics-service",
                        version = "1.0.0",
                        endpoints = listOf(
                            "/health",
                            "/api/v1/policy/trading",
                            "/api/v1/data-health/summary",
                            "/api/v1/data-health/issues",
                            "/api/v1/data-health/venue-sanity"
                        )
                    )
                ),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/api/v1/policy/trading") {
            call.respondText(
                responseGson.toJson(tradingPolicy()),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/api/v1/data-health/summary") {
            val exchange = call.request.queryParameters["exchange"]?.trim()?.ifEmpty { null }
            val barMinutes = call.request.queryParameters["barMinutes"]?.toIntOrNull() ?: 1

            val result = runCatching { loadDataHealthSummary(exchange, barMinutes) }
                .onFailure { logger.warn("Data health summary load failed", it) }
                .getOrElse { ex ->
                    val status = if (ex is IllegalArgumentException) {
                        HttpStatusCode.BadRequest
                    } else {
                        HttpStatusCode.InternalServerError
                    }
                    call.respondText(
                        responseGson.toJson(ErrorResponse("data health summary failed: ${ex.message ?: ex::class.simpleName}")),
                        ContentType.Application.Json,
                        status
                    )
                    return@get
                }

            call.respondText(
                responseGson.toJson(result),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/api/v1/data-health/issues") {
            val exchange = call.request.queryParameters["exchange"]?.trim()?.ifEmpty { null }
            val barMinutes = call.request.queryParameters["barMinutes"]?.toIntOrNull() ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val includeInactive = call.request.queryParameters["includeInactive"]?.toBooleanStrictOrNull() ?: false
            val includeHealthy = call.request.queryParameters["includeHealthy"]?.toBooleanStrictOrNull() ?: false

            val result = runCatching {
                loadDataHealthIssues(exchange, barMinutes, limit, includeInactive, includeHealthy)
            }
                .onFailure { logger.warn("Data health issues load failed", it) }
                .getOrElse { ex ->
                    val status = if (ex is IllegalArgumentException) {
                        HttpStatusCode.BadRequest
                    } else {
                        HttpStatusCode.InternalServerError
                    }
                    call.respondText(
                        responseGson.toJson(ErrorResponse("data health issues failed: ${ex.message ?: ex::class.simpleName}")),
                        ContentType.Application.Json,
                        status
                    )
                    return@get
                }

            call.respondText(
                responseGson.toJson(result),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/api/v1/data-health/venue-sanity") {
            val exchange = call.request.queryParameters["exchange"]?.trim()?.ifEmpty { null }
            val symbol = call.request.queryParameters["symbol"]?.trim().orEmpty()
            val barMinutes = call.request.queryParameters["barMinutes"]?.toIntOrNull() ?: 1
            if (symbol.isBlank()) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("venue sanity requires symbol query parameter")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@get
            }

            val result = runCatching { loadVenueSanity(exchange, symbol, barMinutes) }
                .onFailure { logger.warn("Data health venue sanity load failed", it) }
                .getOrElse { ex ->
                    val status = if (ex is IllegalArgumentException) {
                        HttpStatusCode.BadRequest
                    } else {
                        HttpStatusCode.InternalServerError
                    }
                    call.respondText(
                        responseGson.toJson(ErrorResponse("data health venue sanity failed: ${ex.message ?: ex::class.simpleName}")),
                        ContentType.Application.Json,
                        status
                    )
                    return@get
                }

            call.respondText(
                responseGson.toJson(result),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
    }
}
