package org.datamancy.txgateway.risk.routes

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
import org.datamancy.txgateway.risk.services.RiskDecision
import org.datamancy.txgateway.risk.services.RiskEngineService
import org.datamancy.txgateway.risk.services.RiskPolicyDefinition
import org.datamancy.txgateway.services.AuthService
import org.datamancy.txgateway.services.DatabaseService
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

@Serializable
private data class RiskPolicyResponse(
    val id: String,
    val username: String,
    val walletAddress: String? = null,
    val version: Int,
    val status: String,
    val policy: String,
    val createdBy: String? = null,
    val createdAt: String,
    val activatedAt: String? = null,
    val activatedByWallet: String? = null,
    val isBootstrap: Boolean
)

@Serializable
private data class ActivationChallengeResponse(
    val policyId: String,
    val nonce: String,
    val message: String,
    val expiresAt: String
)

@Serializable
private data class ActivateRiskPolicyResponse(
    val status: String,
    val policyId: String,
    val version: Int,
    val activatedAt: String? = null,
    val activatedByWallet: String? = null
)

@Serializable
private data class RiskStateResponse(
    val username: String,
    val accountEquityUsd: String,
    val highWaterMarkUsd: String,
    val realizedPnlUsd: String,
    val unrealizedPnlUsd: String,
    val dailyRealizedPnlUsd: String,
    val dailyUnrealizedPnlUsd: String,
    val openExposureUsd: String,
    val riskTier: String? = null,
    val tierReason: String? = null,
    val sentimentScore: Double? = null,
    val sentimentConfidence: Double? = null,
    val updatedAt: String
)

@Serializable
private data class RiskKillSwitchResponse(
    val username: String,
    val engaged: Boolean,
    val reason: String? = null,
    val engagedAt: String? = null,
    val engagedBy: String? = null,
    val manualAckRequired: Boolean,
    val acknowledgedAt: String? = null,
    val acknowledgedBy: String? = null,
    val ackNote: String? = null
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
                RiskPolicyResponse(
                    id = active.id.toString(),
                    username = active.username,
                    walletAddress = active.walletAddress,
                    version = active.version,
                    status = active.status,
                    policy = active.policyJson,
                    createdBy = active.createdBy,
                    createdAt = active.createdAt.toString(),
                    activatedAt = active.activatedAt?.toString(),
                    activatedByWallet = active.activatedByWallet,
                    isBootstrap = active.isBootstrap
                )
            )
        }

        get("/policies") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val policies = dbService.listRiskPolicies(username = username, includeBootstrap = true)
            call.respond(
                HttpStatusCode.OK,
                policies.map { policy ->
                    RiskPolicyResponse(
                        id = policy.id.toString(),
                        username = policy.username,
                        walletAddress = policy.walletAddress,
                        version = policy.version,
                        status = policy.status,
                        policy = policy.policyJson,
                        createdBy = policy.createdBy,
                        createdAt = policy.createdAt.toString(),
                        activatedAt = policy.activatedAt?.toString(),
                        activatedByWallet = policy.activatedByWallet,
                        isBootstrap = policy.isBootstrap
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
                RiskPolicyResponse(
                    id = created.id.toString(),
                    username = created.username,
                    walletAddress = created.walletAddress,
                    version = created.version,
                    status = created.status,
                    policy = created.policyJson,
                    createdBy = created.createdBy,
                    createdAt = created.createdAt.toString(),
                    activatedAt = created.activatedAt?.toString(),
                    activatedByWallet = created.activatedByWallet,
                    isBootstrap = created.isBootstrap
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
                ActivationChallengeResponse(
                    policyId = policyId.toString(),
                    nonce = challenge.nonce,
                    message = challenge.challengeMessage,
                    expiresAt = challenge.expiresAt.toString()
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
                ActivateRiskPolicyResponse(
                    status = "active",
                    policyId = activated.id.toString(),
                    version = activated.version,
                    activatedAt = activated.activatedAt?.toString(),
                    activatedByWallet = activated.activatedByWallet
                )
            )
        }

        get("/state") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val state = dbService.getOrCreateRiskAccountState(username)
            call.respond(
                HttpStatusCode.OK,
                RiskStateResponse(
                    username = state.username,
                    accountEquityUsd = state.accountEquityUsd.toPlainString(),
                    highWaterMarkUsd = state.highWaterMarkUsd.toPlainString(),
                    realizedPnlUsd = state.realizedPnlUsd.toPlainString(),
                    unrealizedPnlUsd = state.unrealizedPnlUsd.toPlainString(),
                    dailyRealizedPnlUsd = state.dailyRealizedPnlUsd.toPlainString(),
                    dailyUnrealizedPnlUsd = state.dailyUnrealizedPnlUsd.toPlainString(),
                    openExposureUsd = state.openExposureUsd.toPlainString(),
                    riskTier = state.riskTier,
                    tierReason = state.tierReason,
                    sentimentScore = state.sentimentScore,
                    sentimentConfidence = state.sentimentConfidence,
                    updatedAt = state.updatedAt.toString()
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
                RiskStateResponse(
                    username = state.username,
                    accountEquityUsd = state.accountEquityUsd.toPlainString(),
                    highWaterMarkUsd = state.highWaterMarkUsd.toPlainString(),
                    realizedPnlUsd = state.realizedPnlUsd.toPlainString(),
                    unrealizedPnlUsd = state.unrealizedPnlUsd.toPlainString(),
                    dailyRealizedPnlUsd = state.dailyRealizedPnlUsd.toPlainString(),
                    dailyUnrealizedPnlUsd = state.dailyUnrealizedPnlUsd.toPlainString(),
                    openExposureUsd = state.openExposureUsd.toPlainString(),
                    updatedAt = state.updatedAt.toString()
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
                RiskKillSwitchResponse(
                    username = username,
                    engaged = state?.engaged ?: false,
                    reason = state?.reason,
                    engagedAt = state?.engagedAt?.toString(),
                    engagedBy = state?.engagedBy,
                    manualAckRequired = state?.manualAckRequired ?: true,
                    acknowledgedAt = state?.acknowledgedAt?.toString(),
                    acknowledgedBy = state?.acknowledgedBy,
                    ackNote = state?.ackNote
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
                RiskKillSwitchResponse(
                    username = state.username,
                    engaged = state.engaged,
                    reason = state.reason,
                    engagedAt = state.engagedAt?.toString(),
                    engagedBy = state.engagedBy,
                    manualAckRequired = state.manualAckRequired,
                    acknowledgedAt = state.acknowledgedAt?.toString(),
                    acknowledgedBy = state.acknowledgedBy,
                    ackNote = state.ackNote
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
                RiskKillSwitchResponse(
                    username = acknowledged.username,
                    engaged = acknowledged.engaged,
                    reason = acknowledged.reason,
                    engagedAt = acknowledged.engagedAt?.toString(),
                    engagedBy = acknowledged.engagedBy,
                    manualAckRequired = acknowledged.manualAckRequired,
                    acknowledgedAt = acknowledged.acknowledgedAt?.toString(),
                    acknowledgedBy = acknowledged.acknowledgedBy,
                    ackNote = acknowledged.ackNote
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
