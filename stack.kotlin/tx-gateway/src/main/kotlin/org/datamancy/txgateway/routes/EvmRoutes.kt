package org.datamancy.txgateway.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.datamancy.txgateway.models.TransferRequest
import org.datamancy.txgateway.services.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("EvmRoutes")

fun Route.evmRoutes(
    authService: AuthService,
    ldapService: LdapService,
    workerClient: WorkerClient,
    dbService: DatabaseService
) {
    route("/api/v1/evm") {

        post("/transfer") {
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

            val transferRequest = call.receive<TransferRequest>()

            if (transferRequest.chain.lowercase() !in userInfo.allowedChains) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Chain not allowed"))
                return@post
            }

            if (!dbService.checkRateLimit(username, userInfo.maxTxPerHour)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                return@post
            }

            val requestJson = Json.encodeToString(transferRequest)

            // Resolve toAddress if toUser is specified
            val toAddress = if (transferRequest.toUser != null) {
                ldapService.getEvmAddress(transferRequest.toUser)
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Recipient user not found or has no EVM address"))
                        return@post
                    }
            } else {
                transferRequest.toAddress
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Either toUser or toAddress must be specified"))
                        return@post
                    }
            }

            // Extract ephemeral EVM private key from headers
            val evmKey = call.request.headers["X-Credential-evm"]
            if (evmKey == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing EVM credentials"))
                return@post
            }

            try {
                val payload: Map<String, Any> = mapOf(
                    "username" to username,
                    "toAddress" to toAddress,
                    "amount" to transferRequest.amount,
                    "token" to transferRequest.token,
                    "chain" to transferRequest.chain,
                    "evmPrivateKey" to evmKey
                )

                val result = workerClient.submitEvmTransfer(payload)
                val responseJson = Json.encodeToString(result)

                dbService.logTransaction(
                    username = username,
                    txType = "evm_transfer",
                    request = requestJson,
                    response = responseJson,
                    status = "success"
                )

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                logger.error("Transfer submission failed", e)
                dbService.logTransaction(
                    username = username,
                    txType = "evm_transfer",
                    request = requestJson,
                    response = null,
                    status = "error",
                    errorMessage = e.message
                )
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        get("/addressbook/{user}") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token"))
                return@get
            }

            val jwt = authService.validateToken(token)
            if (jwt == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return@get
            }

            val targetUser = call.parameters["user"]
            if (targetUser == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing user parameter"))
                return@get
            }

            val evmAddress = ldapService.getEvmAddress(targetUser)
            if (evmAddress == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found or has no EVM address"))
                return@get
            }

            call.respond(HttpStatusCode.OK, mapOf("username" to targetUser, "evmAddress" to evmAddress))
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "service" to "tx-gateway"))
        }
    }
}
