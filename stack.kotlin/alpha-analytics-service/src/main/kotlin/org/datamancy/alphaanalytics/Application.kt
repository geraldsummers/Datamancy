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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.datamancy.trading.analytics.crosssectional.CrossSectionalSearchConfig
import org.datamancy.trading.analytics.crosssectional.CrossSectionalSearchResult
import org.datamancy.trading.analytics.crosssectional.CrossSectionalResearchReadiness
import org.datamancy.trading.analytics.crosssectional.CrossSectionalResearchResult
import org.datamancy.trading.analytics.crosssectional.ResearchConfig
import org.datamancy.trading.analytics.crosssectional.UniverseSnapshotCacheStatus
import org.datamancy.trading.analytics.crosssectional.crossSectionalUniverseSnapshotCacheStatus
import org.datamancy.trading.analytics.crosssectional.evaluateCrossSectionalReadiness
import org.datamancy.trading.analytics.crosssectional.runCrossSectionalResearch
import org.datamancy.trading.analytics.crosssectional.searchCrossSectionalResearch
import org.datamancy.trading.analytics.crosssectional.warmCrossSectionalUniverseSnapshots
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
private val requestJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

private data class ErrorResponse(
    val error: String
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
    runAnalysis: suspend (ResearchConfig) -> CrossSectionalResearchResult = ::runCrossSectionalResearch,
    runSearch: suspend (CrossSectionalSearchConfig) -> CrossSectionalSearchResult = ::searchCrossSectionalResearch,
    evaluateReadiness: suspend (ResearchConfig) -> CrossSectionalResearchReadiness = ::evaluateCrossSectionalReadiness,
    cacheStatus: () -> UniverseSnapshotCacheStatus = ::crossSectionalUniverseSnapshotCacheStatus,
    warmCache: suspend (ResearchConfig) -> UniverseSnapshotCacheStatus = ::warmCrossSectionalUniverseSnapshots,
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
                            "/api/v1/alpha/cross-sectional/default-config",
                            "/api/v1/alpha/cross-sectional/readiness",
                            "/api/v1/alpha/cross-sectional/cache/status",
                            "/api/v1/alpha/cross-sectional/cache/warm",
                            "/api/v1/alpha/cross-sectional/run",
                            "/api/v1/alpha/cross-sectional/search/default-config",
                            "/api/v1/alpha/cross-sectional/search/run"
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

        get("/api/v1/alpha/cross-sectional/default-config") {
            call.respondText(
                responseGson.toJson(ResearchConfig()),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        post("/api/v1/alpha/cross-sectional/readiness") {
            val body = call.receiveText().trim()
            val config = try {
                if (body.isBlank()) {
                    ResearchConfig()
                } else {
                    requestJson.decodeFromString<ResearchConfig>(body)
                }
            } catch (e: SerializationException) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("invalid request body: ${e.message ?: "serialization error"}")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            } catch (e: IllegalArgumentException) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("invalid request body: ${e.message ?: "illegal argument"}")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            val result = runCatching { evaluateReadiness(config) }
                .onFailure { logger.warn("Cross-sectional readiness failed", it) }
                .getOrElse { ex ->
                    call.respondText(
                        responseGson.toJson(ErrorResponse("cross-sectional readiness failed: ${ex.message ?: ex::class.simpleName}")),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                    return@post
                }

            call.respondText(
                responseGson.toJson(result),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/api/v1/alpha/cross-sectional/search/default-config") {
            call.respondText(
                responseGson.toJson(CrossSectionalSearchConfig()),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/api/v1/alpha/cross-sectional/cache/status") {
            call.respondText(
                responseGson.toJson(cacheStatus()),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        post("/api/v1/alpha/cross-sectional/cache/warm") {
            val body = call.receiveText().trim()
            val config = try {
                if (body.isBlank()) {
                    ResearchConfig()
                } else {
                    requestJson.decodeFromString<ResearchConfig>(body)
                }
            } catch (e: SerializationException) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("invalid request body: ${e.message ?: "serialization error"}")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            } catch (e: IllegalArgumentException) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("invalid request body: ${e.message ?: "illegal argument"}")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            val result = runCatching { warmCache(config) }
                .onFailure { logger.warn("Cross-sectional cache warm failed", it) }
                .getOrElse { ex ->
                    call.respondText(
                        responseGson.toJson(ErrorResponse("cache warm failed: ${ex.message ?: ex::class.simpleName}")),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                    return@post
                }

            call.respondText(
                responseGson.toJson(result),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        post("/api/v1/alpha/cross-sectional/run") {
            val body = call.receiveText().trim()
            val config = try {
                if (body.isBlank()) {
                    ResearchConfig()
                } else {
                    requestJson.decodeFromString<ResearchConfig>(body)
                }
            } catch (e: SerializationException) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("invalid request body: ${e.message ?: "serialization error"}")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            } catch (e: IllegalArgumentException) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("invalid request body: ${e.message ?: "illegal argument"}")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            val result = runCatching { runAnalysis(config) }
                .onFailure { logger.warn("Cross-sectional analytics run failed", it) }
                .getOrElse { ex ->
                    call.respondText(
                        responseGson.toJson(ErrorResponse("analysis failed: ${ex.message ?: ex::class.simpleName}")),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                    return@post
                }

            call.respondText(
                responseGson.toJson(result),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        post("/api/v1/alpha/cross-sectional/search/run") {
            val body = call.receiveText().trim()
            val config = try {
                if (body.isBlank()) {
                    CrossSectionalSearchConfig()
                } else {
                    requestJson.decodeFromString<CrossSectionalSearchConfig>(body)
                }
            } catch (e: SerializationException) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("invalid request body: ${e.message ?: "serialization error"}")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            } catch (e: IllegalArgumentException) {
                call.respondText(
                    responseGson.toJson(ErrorResponse("invalid request body: ${e.message ?: "illegal argument"}")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            val result = runCatching { runSearch(config) }
                .onFailure { logger.warn("Cross-sectional search run failed", it) }
                .getOrElse { ex ->
                    call.respondText(
                        responseGson.toJson(ErrorResponse("search failed: ${ex.message ?: ex::class.simpleName}")),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                    return@post
                }

            call.respondText(
                responseGson.toJson(result),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
    }
}
