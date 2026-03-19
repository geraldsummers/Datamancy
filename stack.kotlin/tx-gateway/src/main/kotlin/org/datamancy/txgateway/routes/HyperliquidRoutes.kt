package org.datamancy.txgateway.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.txgateway.services.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("HyperliquidRoutes")

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
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token"))
                return@post
            }

            val jwt = authService.validateToken(token)
            val username = authService.extractUsername(jwt ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return@post
            })

            if (username == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token claims"))
                return@post
            }

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

            val hyperliquidKey = call.request.headers["X-Credential-hyperliquid"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (hyperliquidKey == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Hyperliquid credentials"))
                return@post
            }

            try {
                val result = workerClient.cancelHyperliquidOrder(
                    orderId = orderId,
                    username = username,
                    symbol = symbol,
                    hyperliquidKey = hyperliquidKey
                )
                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Order cancellation failed", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        get("/positions") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token"))
                return@get
            }

            val jwt = authService.validateToken(token)
            val username = authService.extractUsername(jwt ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return@get
            })

            if (username == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token claims"))
                return@get
            }

            val hyperliquidKey = call.request.headers["X-Credential-hyperliquid"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (hyperliquidKey == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Hyperliquid credentials"))
                return@get
            }

            try {
                val positions = workerClient.getHyperliquidPositions(username, hyperliquidKey)
                call.respond(HttpStatusCode.OK, positions)
            } catch (e: Exception) {
                logger.error("Failed to get positions", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        get("/balance") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token"))
                return@get
            }

            val jwt = authService.validateToken(token)
            val username = authService.extractUsername(jwt ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return@get
            })

            if (username == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token claims"))
                return@get
            }

            val hyperliquidKey = call.request.headers["X-Credential-hyperliquid"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (hyperliquidKey == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Hyperliquid credentials"))
                return@get
            }

            try {
                val balance = workerClient.getHyperliquidBalance(username, hyperliquidKey)
                call.respond(HttpStatusCode.OK, balance)
            } catch (e: Exception) {
                logger.error("Failed to get balance", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }
    }
}
