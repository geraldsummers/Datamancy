package org.datamancy.txgateway.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.datamancy.txgateway.services.AuthService
import org.datamancy.txgateway.services.DatabaseService
import org.datamancy.txgateway.services.RiskDecision
import org.datamancy.txgateway.services.RiskEngineService
import org.datamancy.txgateway.services.RiskPolicyDefinition
import org.datamancy.txgateway.services.WalletSignatureService
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Serializable
private data class CreateRiskPolicyRequest(
    val policy: RiskPolicyDefinition,
    val walletAddress: String? = null
)

@Serializable
private data class ActivationChallengeRequest(
    val walletAddress: String
)

@Serializable
private data class ActivateRiskPolicyRequest(
    val walletAddress: String,
    val signature: String,
    val nonce: String,
    val message: String
)

@Serializable
private data class RiskStatePatchRequest(
    val accountEquityUsd: String? = null,
    val realizedPnlUsd: String? = null,
    val unrealizedPnlUsd: String? = null,
    val dailyRealizedPnlUsd: String? = null,
    val dailyUnrealizedPnlUsd: String? = null,
    val openExposureUsd: String? = null,
    val highWaterMarkUsd: String? = null
)

@Serializable
private data class EngageKillSwitchRequest(
    val reason: String,
    val manualAckRequired: Boolean = true
)

@Serializable
private data class AckKillSwitchRequest(
    val note: String? = null
)

@Serializable
private data class RiskOrderPreviewRequest(
    val symbol: String,
    val notionalUsd: String,
    val reduceOnly: Boolean = false
)

@Serializable
private data class RiskDecisionResponse(
    val allowed: Boolean,
    val tier: String,
    val action: String,
    val reason: String,
    val policyId: String? = null,
    val policyVersion: Int? = null,
    val suggestedMaxOrderNotionalUsd: String? = null,
    val unwindSliceSeconds: Int? = null,
    val unwindMaxSlippageBps: Double? = null,
    val metrics: Map<String, String>,
    val sentiment: Map<String, String>? = null
)

private val json = Json { prettyPrint = true; encodeDefaults = true }

fun Route.riskRoutes(
    authService: AuthService,
    dbService: DatabaseService,
    riskEngineService: RiskEngineService,
    walletSignatureService: WalletSignatureService
) {
    route("/api/v1/risk") {
        get("/policy/active") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val active = dbService.getActiveRiskPolicyForUser(username)
            if (active == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No active risk policy"))
                return@get
            }
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "id" to active.id.toString(),
                    "username" to active.username,
                    "walletAddress" to active.walletAddress,
                    "version" to active.version,
                    "status" to active.status,
                    "policy" to active.policyJson,
                    "createdBy" to active.createdBy,
                    "createdAt" to active.createdAt.toString(),
                    "activatedAt" to active.activatedAt?.toString(),
                    "activatedByWallet" to active.activatedByWallet,
                    "isBootstrap" to active.isBootstrap
                )
            )
        }

        get("/policies") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val policies = dbService.listRiskPolicies(username = username, includeBootstrap = true)
            call.respond(
                HttpStatusCode.OK,
                policies.map { policy ->
                    mapOf(
                        "id" to policy.id.toString(),
                        "username" to policy.username,
                        "walletAddress" to policy.walletAddress,
                        "version" to policy.version,
                        "status" to policy.status,
                        "policy" to policy.policyJson,
                        "createdBy" to policy.createdBy,
                        "createdAt" to policy.createdAt.toString(),
                        "activatedAt" to policy.activatedAt?.toString(),
                        "activatedByWallet" to policy.activatedByWallet,
                        "isBootstrap" to policy.isBootstrap
                    )
                }
            )
        }

        post("/policies") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@post
            val request = call.receive<CreateRiskPolicyRequest>()
            val policyJson = json.encodeToString(RiskPolicyDefinition.serializer(), request.policy)
            val created = dbService.createRiskPolicyVersion(
                username = username,
                policyJson = policyJson,
                createdBy = username,
                walletAddress = request.walletAddress
            )

            call.respond(
                HttpStatusCode.Created,
                mapOf(
                    "id" to created.id.toString(),
                    "username" to created.username,
                    "walletAddress" to created.walletAddress,
                    "version" to created.version,
                    "status" to created.status,
                    "policy" to created.policyJson,
                    "createdAt" to created.createdAt.toString()
                )
            )
        }

        post("/policies/{policyId}/activation-challenge") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@post
            val policyId = call.parameters["policyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid policyId"))
                    return@post
                }

            val request = call.receive<ActivationChallengeRequest>()
            val walletAddress = request.walletAddress.trim().lowercase()
            if (walletAddress.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "walletAddress is required"))
                return@post
            }

            val policy = dbService.getRiskPolicyById(username, policyId)
            if (policy == null || policy.username != username.lowercase()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Policy not found"))
                return@post
            }

            val nonce = UUID.randomUUID().toString().replace("-", "")
            val message = buildString {
                appendLine("Datamancy risk-policy activation")
                appendLine("user:$username")
                appendLine("policy:$policyId")
                appendLine("wallet:$walletAddress")
                appendLine("nonce:$nonce")
                append("timestamp:${Instant.now()}")
            }
            val expiresAt = Instant.now().plusSeconds(300)
            val challenge = dbService.createRiskActivationChallenge(
                username = username,
                policyId = policyId,
                walletAddress = walletAddress,
                nonce = nonce,
                challengeMessage = message,
                expiresAt = expiresAt
            )

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "policyId" to policyId.toString(),
                    "nonce" to challenge.nonce,
                    "message" to challenge.challengeMessage,
                    "expiresAt" to challenge.expiresAt.toString()
                )
            )
        }

        post("/policies/{policyId}/activate") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@post
            val policyId = call.parameters["policyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid policyId"))
                    return@post
                }

            val request = call.receive<ActivateRiskPolicyRequest>()
            val walletAddress = request.walletAddress.trim().lowercase()
            if (walletAddress.isBlank() || request.signature.isBlank() || request.nonce.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "walletAddress, signature, and nonce are required"))
                return@post
            }

            val challenge = dbService.consumeRiskActivationChallenge(
                username = username,
                policyId = policyId,
                walletAddress = walletAddress,
                nonce = request.nonce
            )
            if (challenge == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Activation challenge is invalid or expired"))
                return@post
            }

            if (challenge.challengeMessage != request.message) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Challenge message mismatch"))
                return@post
            }

            val signatureOk = walletSignatureService.verifyPersonalSignature(
                message = request.message,
                signatureHex = request.signature,
                expectedAddress = walletAddress
            )
            if (!signatureOk) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Wallet signature verification failed"))
                return@post
            }

            val activated = dbService.activateRiskPolicy(
                username = username,
                policyId = policyId,
                walletAddress = walletAddress,
                signature = request.signature,
                nonce = request.nonce,
                message = request.message
            )
            if (activated == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Policy activation failed"))
                return@post
            }

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "active",
                    "policyId" to activated.id.toString(),
                    "version" to activated.version,
                    "activatedAt" to activated.activatedAt?.toString(),
                    "activatedByWallet" to activated.activatedByWallet
                )
            )
        }

        get("/state") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val state = dbService.getOrCreateRiskAccountState(username)
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "username" to state.username,
                    "accountEquityUsd" to state.accountEquityUsd.toPlainString(),
                    "highWaterMarkUsd" to state.highWaterMarkUsd.toPlainString(),
                    "realizedPnlUsd" to state.realizedPnlUsd.toPlainString(),
                    "unrealizedPnlUsd" to state.unrealizedPnlUsd.toPlainString(),
                    "dailyRealizedPnlUsd" to state.dailyRealizedPnlUsd.toPlainString(),
                    "dailyUnrealizedPnlUsd" to state.dailyUnrealizedPnlUsd.toPlainString(),
                    "openExposureUsd" to state.openExposureUsd.toPlainString(),
                    "riskTier" to state.riskTier,
                    "tierReason" to state.tierReason,
                    "sentimentScore" to state.sentimentScore,
                    "sentimentConfidence" to state.sentimentConfidence,
                    "updatedAt" to state.updatedAt.toString()
                )
            )
        }

        put("/state") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@put
            val request = call.receive<RiskStatePatchRequest>()
            val state = dbService.upsertRiskAccountState(
                username = username,
                patch = org.datamancy.txgateway.services.RiskAccountStatePatch(
                    accountEquityUsd = request.accountEquityUsd.toBigDecimalOrNull(),
                    realizedPnlUsd = request.realizedPnlUsd.toBigDecimalOrNull(),
                    unrealizedPnlUsd = request.unrealizedPnlUsd.toBigDecimalOrNull(),
                    dailyRealizedPnlUsd = request.dailyRealizedPnlUsd.toBigDecimalOrNull(),
                    dailyUnrealizedPnlUsd = request.dailyUnrealizedPnlUsd.toBigDecimalOrNull(),
                    openExposureUsd = request.openExposureUsd.toBigDecimalOrNull(),
                    highWaterMarkUsd = request.highWaterMarkUsd.toBigDecimalOrNull()
                )
            )

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "username" to state.username,
                    "accountEquityUsd" to state.accountEquityUsd.toPlainString(),
                    "highWaterMarkUsd" to state.highWaterMarkUsd.toPlainString(),
                    "realizedPnlUsd" to state.realizedPnlUsd.toPlainString(),
                    "unrealizedPnlUsd" to state.unrealizedPnlUsd.toPlainString(),
                    "dailyRealizedPnlUsd" to state.dailyRealizedPnlUsd.toPlainString(),
                    "dailyUnrealizedPnlUsd" to state.dailyUnrealizedPnlUsd.toPlainString(),
                    "openExposureUsd" to state.openExposureUsd.toPlainString(),
                    "updatedAt" to state.updatedAt.toString()
                )
            )
        }

        post("/evaluate/order-preview") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@post
            val request = call.receive<RiskOrderPreviewRequest>()
            val notional = request.notionalUsd.toBigDecimalOrNull()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid notionalUsd"))
                    return@post
                }
            if (notional <= BigDecimal.ZERO) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "notionalUsd must be > 0"))
                return@post
            }

            val decision = riskEngineService.evaluateOrder(
                username = username,
                symbol = request.symbol,
                orderNotionalUsd = notional,
                reduceOnly = request.reduceOnly
            )
            call.respond(HttpStatusCode.OK, decision.toResponse())
        }

        get("/kill-switch") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val state = dbService.getRiskKillSwitchState(username)
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "username" to username,
                    "engaged" to (state?.engaged ?: false),
                    "reason" to state?.reason,
                    "engagedAt" to state?.engagedAt?.toString(),
                    "engagedBy" to state?.engagedBy,
                    "manualAckRequired" to (state?.manualAckRequired ?: true),
                    "acknowledgedAt" to state?.acknowledgedAt?.toString(),
                    "acknowledgedBy" to state?.acknowledgedBy,
                    "ackNote" to state?.ackNote
                )
            )
        }

        post("/kill-switch/engage") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@post
            val request = call.receive<EngageKillSwitchRequest>()
            if (request.reason.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason is required"))
                return@post
            }

            val state = dbService.engageRiskKillSwitch(
                username = username,
                reason = request.reason.trim(),
                engagedBy = username,
                manualAckRequired = request.manualAckRequired
            )
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "username" to state.username,
                    "engaged" to state.engaged,
                    "reason" to state.reason,
                    "engagedAt" to state.engagedAt?.toString(),
                    "manualAckRequired" to state.manualAckRequired
                )
            )
        }

        post("/kill-switch/ack") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@post
            val request = call.receive<AckKillSwitchRequest>()
            val acknowledged = dbService.acknowledgeRiskKillSwitch(
                username = username,
                acknowledgedBy = username,
                note = request.note
            )
            if (acknowledged == null) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "Kill switch is not engaged"))
                return@post
            }
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "username" to acknowledged.username,
                    "engaged" to acknowledged.engaged,
                    "acknowledgedAt" to acknowledged.acknowledgedAt?.toString(),
                    "acknowledgedBy" to acknowledged.acknowledgedBy,
                    "ackNote" to acknowledged.ackNote
                )
            )
        }
    }
}

private fun RiskDecision.toResponse(): RiskDecisionResponse {
    return RiskDecisionResponse(
        allowed = allowed,
        tier = tier.name.lowercase(),
        action = action.name.lowercase(),
        reason = reason,
        policyId = policyId,
        policyVersion = policyVersion,
        suggestedMaxOrderNotionalUsd = suggestedMaxOrderNotionalUsd?.toPlainString(),
        unwindSliceSeconds = unwindSliceSeconds,
        unwindMaxSlippageBps = unwindMaxSlippageBps,
        metrics = mapOf(
            "currentExposureUsd" to metrics.currentExposureUsd.toPlainString(),
            "projectedExposureUsd" to metrics.projectedExposureUsd.toPlainString(),
            "accountEquityUsd" to metrics.accountEquityUsd.toPlainString(),
            "highWaterMarkUsd" to metrics.highWaterMarkUsd.toPlainString(),
            "dailyLossUsd" to metrics.dailyLossUsd.toPlainString(),
            "leverage" to metrics.leverage.toString(),
            "exposureUtilization" to metrics.exposureUtilization.toString(),
            "drawdownPct" to metrics.drawdownPct.toString(),
            "drawdownUtilization" to metrics.drawdownUtilization.toString(),
            "dailyLossUtilization" to metrics.dailyLossUtilization.toString(),
            "leverageUtilization" to metrics.leverageUtilization.toString()
        ),
        sentiment = sentiment?.let {
            mapOf(
                "symbol" to it.symbol,
                "sentimentScore" to it.sentimentScore.toString(),
                "confidence" to it.confidence.toString(),
                "observedAt" to it.observedAt.toString(),
                "modelName" to (it.modelName ?: ""),
                "source" to (it.source ?: ""),
                "label" to (it.sentimentLabel ?: "")
            )
        }
    )
}

private fun String?.toBigDecimalOrNull(): BigDecimal? = runCatching {
    this?.trim()?.takeIf { it.isNotEmpty() }?.let { BigDecimal(it) }
}.getOrNull()

private suspend fun extractAuthenticatedUsername(
    call: RoutingCall,
    authService: AuthService
): String? {
    val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
    if (token == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token"))
        return null
    }

    val jwt = authService.validateToken(token)
    if (jwt == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        return null
    }

    val username = authService.extractUsername(jwt)
    if (username == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token claims"))
        return null
    }

    return username
}
