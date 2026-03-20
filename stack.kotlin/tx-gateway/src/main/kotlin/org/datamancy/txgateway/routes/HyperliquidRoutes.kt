package org.datamancy.txgateway.routes

import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.txgateway.services.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("HyperliquidRoutes")
private val gson = Gson()

fun Route.hyperliquidRoutes(
    authService: AuthService,
    ldapService: LdapService,
    workerClient: WorkerClient,
    dbService: DatabaseService
) {
    route("/api/v1/hyperliquid") {

        post("/order") {
            call.respond(
                HttpStatusCode.Gone,
                mapOf(
                    "error" to "Endpoint deprecated",
                    "message" to "Use unified order route /api/v1/exchanges/hyperliquid/order"
                )
            )
        }

        post("/cancel/{orderId}") {
            val username = call.requireAuthenticatedUsername(authService) ?: return@post

            val orderId = call.parameters["orderId"]
            if (orderId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing orderId"))
                return@post
            }

            val symbol = call.request.queryParameters["symbol"]?.trim()
            if (symbol.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol query parameter"))
                return@post
            }

            val hyperliquidKey = call.requireHyperliquidCredential() ?: return@post

            try {
                val result = workerClient.cancelHyperliquidOrder(
                    orderId = orderId,
                    username = username,
                    symbol = symbol,
                    hyperliquidKey = hyperliquidKey
                )
                call.respondJsonPayload(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Order cancellation failed", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        post("/cancel-all") {
            val username = call.requireAuthenticatedUsername(authService) ?: return@post
            val hyperliquidKey = call.requireHyperliquidCredential() ?: return@post
            val symbol = call.request.queryParameters["symbol"]?.trim()?.takeIf { it.isNotEmpty() }

            try {
                val result = workerClient.cancelAllHyperliquidOrders(
                    username = username,
                    hyperliquidKey = hyperliquidKey,
                    symbol = symbol
                )
                call.respondJsonPayload(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Cancel-all failed", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        get("/positions") {
            val username = call.requireAuthenticatedUsername(authService) ?: return@get
            val hyperliquidKey = call.requireHyperliquidCredential() ?: return@get

            try {
                val positions = workerClient.getHyperliquidPositions(username, hyperliquidKey)
                call.respondJsonPayload(HttpStatusCode.OK, positions)
            } catch (e: Exception) {
                logger.error("Failed to get positions", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        get("/orders") {
            val username = call.requireAuthenticatedUsername(authService) ?: return@get
            val hyperliquidKey = call.requireHyperliquidCredential() ?: return@get
            val symbol = call.request.queryParameters["symbol"]?.trim()?.takeIf { it.isNotEmpty() }

            try {
                val orders = workerClient.getHyperliquidOrders(
                    username = username,
                    hyperliquidKey = hyperliquidKey,
                    symbol = symbol
                )
                call.respondJsonPayload(HttpStatusCode.OK, orders)
            } catch (e: Exception) {
                logger.error("Failed to get open orders", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        get("/balance") {
            val username = call.requireAuthenticatedUsername(authService) ?: return@get
            val hyperliquidKey = call.requireHyperliquidCredential() ?: return@get

            try {
                val balance = workerClient.getHyperliquidBalance(username, hyperliquidKey)
                call.respondJsonPayload(HttpStatusCode.OK, balance)
            } catch (e: Exception) {
                logger.error("Failed to get balance", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        post("/close") {
            val username = call.requireAuthenticatedUsername(authService) ?: return@post
            val hyperliquidKey = call.requireHyperliquidCredential() ?: return@post
            val body = runCatching { call.receive<Map<String, String?>>() }.getOrNull().orEmpty()
            val symbol = body["symbol"]?.trim()
            if (symbol.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                return@post
            }

            try {
                val result = workerClient.closeHyperliquidPosition(
                    username = username,
                    hyperliquidKey = hyperliquidKey,
                    symbol = symbol
                )
                call.respondJsonPayload(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Failed to close position", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        post("/close-all") {
            val username = call.requireAuthenticatedUsername(authService) ?: return@post
            val hyperliquidKey = call.requireHyperliquidCredential() ?: return@post
            try {
                val result = workerClient.closeAllHyperliquidPositions(
                    username = username,
                    hyperliquidKey = hyperliquidKey
                )
                call.respondJsonPayload(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Failed to close all positions", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }
    }
}

private suspend fun RoutingCall.requireAuthenticatedUsername(authService: AuthService): String? {
    val token = request.headers["Authorization"]?.removePrefix("Bearer ")
    if (token == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token"))
        return null
    }

    val jwt = authService.validateToken(token)
    val username = authService.extractUsername(jwt ?: run {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        return null
    })

    if (username == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token claims"))
        return null
    }
    return username
}

private suspend fun RoutingCall.requireHyperliquidCredential(): String? {
    val hyperliquidKey = request.headers["X-Credential-hyperliquid"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (hyperliquidKey == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Hyperliquid credentials"))
        return null
    }
    return hyperliquidKey
}

private suspend fun RoutingCall.respondJsonPayload(status: HttpStatusCode, payload: Any) {
    respondText(
        text = gson.toJson(payload),
        contentType = ContentType.Application.Json,
        status = status
    )
}
