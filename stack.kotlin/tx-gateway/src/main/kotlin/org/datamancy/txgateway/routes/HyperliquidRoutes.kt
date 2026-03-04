package org.datamancy.txgateway.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.datamancy.txgateway.models.OrderRequest
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
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token"))
                return@post
            }

            val jwt = authService.validateToken(token)
            if (jwt == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return@post
            }

            val username = authService.extractUsername(jwt)
            if (username == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token claims"))
                return@post
            }

            val userInfo = ldapService.getUserInfo(username)
            if (userInfo == null) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "User not found"))
                return@post
            }

            if ("hyperliquid" !in userInfo.allowedExchanges) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Hyperliquid not allowed"))
                return@post
            }

            if (!dbService.checkRateLimit(username, userInfo.maxTxPerHour)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                return@post
            }

            val orderRequest = call.receive<OrderRequest>()
            val requestJson = Json.encodeToString(orderRequest)

            // Extract ephemeral credentials from headers
            val hyperliquidKey = call.request.headers["X-Credential-hyperliquid"]
            if (hyperliquidKey == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Hyperliquid credentials"))
                return@post
            }

            try {
                val payload: Map<String, Any> = mapOf(
                    "username" to username,
                    "symbol" to orderRequest.symbol,
                    "side" to orderRequest.side,
                    "type" to orderRequest.type,
                    "size" to orderRequest.size,
                    "price" to (orderRequest.price ?: ""),
                    "reduceOnly" to orderRequest.reduceOnly,
                    "postOnly" to orderRequest.postOnly,
                    "hyperliquidKey" to hyperliquidKey
                )

                val result = workerClient.submitHyperliquidOrder(payload)
                val responseJson = Json.encodeToString(result)

                dbService.logTransaction(
                    username = username,
                    txType = "hyperliquid_order",
                    request = requestJson,
                    response = responseJson,
                    status = "success"
                )

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Order submission failed", e)
                dbService.logTransaction(
                    username = username,
                    txType = "hyperliquid_order",
                    request = requestJson,
                    response = null,
                    status = "error",
                    errorMessage = e.message
                )
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
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

            try {
                val result = workerClient.cancelHyperliquidOrder(orderId)
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

            try {
                val positions = workerClient.getHyperliquidPositions(username)
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

            try {
                val balance = workerClient.getHyperliquidBalance(username)
                call.respond(HttpStatusCode.OK, balance)
            } catch (e: Exception) {
                logger.error("Failed to get balance", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }
    }
}
