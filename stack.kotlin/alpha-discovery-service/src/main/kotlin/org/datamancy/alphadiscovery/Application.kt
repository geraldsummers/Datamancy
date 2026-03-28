package org.datamancy.alphadiscovery

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.datamancy.trading.alpha.AlphaDiscoveryCandidateRequest
import org.datamancy.trading.alpha.AlphaDiscoveryPlanner
import org.datamancy.trading.alpha.AlphaExecutionSubmitRequest
import org.datamancy.trading.alpha.AlphaExecutionSubmission
import org.datamancy.trading.alpha.AlphaExecutionSubmitter
import org.datamancy.trading.alpha.AlphaRunMode
import org.datamancy.trading.alpha.http.AlphaServiceError
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.datamancy.trading.alpha.InterdayAlphaLeaderboardResponse
import org.datamancy.trading.alpha.InterdayAlphaRunRequest
import org.datamancy.trading.alpha.InterdayAlphaSearchRequest
import org.datamancy.trading.alpha.InterdayAlphaSearchResponse
import org.datamancy.trading.alpha.InterdaySearchEngine
import org.datamancy.trading.alpha.ResearchFeaturePanelSource
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.storage.MarketDataDataSourceFactory
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("AlphaDiscoveryApplication")
private val gson = AlphaServiceJson.gson

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureAlphaDiscoveryApp()
    }.start(wait = true)
}

fun Application.configureAlphaDiscoveryApp(
    planner: AlphaDiscoveryPlanner = AlphaDiscoveryPlanner(ActiveTradingPolicy::current),
    engine: InterdaySearchEngine = InterdaySearchEngine(
        panelSource = ResearchFeaturePanelSource(MarketDataDataSourceFactory.fromEnvironment("alpha-discovery-service")),
        policyProvider = ActiveTradingPolicy::current
    ),
    submitter: AlphaExecutionSubmitter = AlphaExecutionSubmitter {
        System.getenv("TX_GATEWAY_URL")?.trim()?.ifEmpty { null } ?: "http://tx-gateway:8080"
    },
    latestSearch: AtomicReference<InterdayAlphaSearchResponse?> = AtomicReference(null)
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
                        endpoints = listOf(
                            "/health",
                            "/api/v1/discovery/defaults",
                            "/api/v1/discovery/candidates",
                            "/api/v1/discovery/search",
                            "/api/v1/discovery/run",
                            "/api/v1/discovery/leaderboard"
                        )
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
        post("/api/v1/discovery/search") {
            val request = runCatching { gson.fromJson(call.receiveText(), InterdayAlphaSearchRequest::class.java) }
                .onFailure { logger.warn("Discovery search request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid discovery search request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = runCatching { engine.search(request) }
                .onFailure { logger.warn("Discovery search failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("discovery search failed: ${it.message}")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            latestSearch.set(response)
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
        post("/api/v1/discovery/run") {
            val request = runCatching { gson.fromJson(call.receiveText(), InterdayAlphaRunRequest::class.java) }
                .onFailure { logger.warn("Discovery run request parse failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("invalid discovery run request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val baseResponse = runCatching { engine.run(request) }
                .onFailure { logger.warn("Discovery run failed", it) }
                .getOrElse {
                    call.respondText(gson.toJson(AlphaServiceError("discovery run failed: ${it.message}")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val response = if (request.submitOrders && request.mode != AlphaRunMode.OFFLINE_BACKTEST && request.mode != AlphaRunMode.WALK_FORWARD) {
                val bearerToken = call.request.header("Authorization")
                    ?.removePrefix("Bearer")
                    ?.trim()
                    .orEmpty()
                if (bearerToken.isBlank()) {
                    call.respondText(
                        gson.toJson(AlphaServiceError("missing bearer token for tx-gateway forwarding")),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val credentials = call.request.headers
                    .entries()
                    .filter { (name, _) -> name.startsWith("X-Credential-", ignoreCase = true) }
                    .associate { (name, values) ->
                        name.removePrefix("X-Credential-").lowercase() to values.last()
                    }
                val submissions = submitTargets(
                    request = request,
                    response = baseResponse,
                    submitter = submitter,
                    bearerToken = bearerToken,
                    credentials = credentials
                )
                baseResponse.copy(
                    executionPreview = baseResponse.executionPreview.copy(
                        submissions = submissions,
                        notes = baseResponse.executionPreview.notes + "Selected targets were forwarded to tx-gateway using the requested execution mode."
                    )
                )
            } else {
                baseResponse
            }
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/api/v1/discovery/leaderboard") {
            val cached = latestSearch.get()
            val response = InterdayAlphaLeaderboardResponse(
                generatedAt = Instant.now(),
                leaderboard = cached?.leaderboard.orEmpty(),
                sourceSearchGeneratedAt = cached?.generatedAt,
                notes = if (cached == null) {
                    listOf("No discovery search has been run since this service instance started.")
                } else {
                    listOf("Leaderboard reflects the latest completed discovery search executed by this service instance.")
                }
            )
            call.respondText(gson.toJson(response), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

private suspend fun submitTargets(
    request: InterdayAlphaRunRequest,
    response: org.datamancy.trading.alpha.InterdayAlphaRunResponse,
    submitter: AlphaExecutionSubmitter,
    bearerToken: String,
    credentials: Map<String, String>
): List<AlphaExecutionSubmission> {
    val priceBySymbol = response.selectedSignals.associateBy({ it.symbol }, { it.close })
    val targetLimit = if (request.submitTopTargets > 0) request.submitTopTargets else response.targets.size
    return response.targets
        .take(targetLimit)
        .mapNotNull { target ->
            val close = priceBySymbol[target.symbol] ?: return@mapNotNull null
            if (close <= 0.0) return@mapNotNull null
            val size = (target.weightFraction * request.config.capitalUsd * target.leverageMultiplier) / close
            if (size <= 0.0) return@mapNotNull null
            submitter.submit(
                AlphaExecutionSubmitRequest(
                    exchange = executionVenueApiName(request.config.exchange),
                    symbol = target.symbol,
                    direction = target.direction,
                    orderType = "MARKET",
                    size = size,
                    mode = request.mode,
                    maxSlippageBps = 35.0
                ),
                bearerToken = bearerToken,
                credentials = credentials
            )
        }
}

private fun executionVenueApiName(exchange: String): String {
    val normalized = exchange.trim().lowercase()
    return when {
        normalized.contains("hyperliquid") -> "hyperliquid"
        "_" in normalized -> normalized.substringBefore("_")
        else -> normalized
    }
}
