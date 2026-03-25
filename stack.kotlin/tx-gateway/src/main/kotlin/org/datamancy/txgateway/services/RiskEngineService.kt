package org.datamancy.txgateway.risk.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import org.datamancy.txgateway.services.DatabaseService
import org.datamancy.txgateway.services.RiskAccountStateRecord
import org.datamancy.txgateway.services.SentimentSnapshot

@Serializable
data class RiskPolicyDefinition(
    val maxExposureUsd: Double = 25_000.0,
    val maxLeverage: Double = 2.5,
    val maxDrawdownPct: Double = 8.0,
    val maxDailyLossUsd: Double = 2_500.0,
    val approachTrigger: Double = 0.8,
    val unwindTrigger: Double = 1.0,
    val hardKillTrigger: Double = 1.15,
    val unwindSliceSeconds: Int = 45,
    val unwindMaxSlippageBps: Double = 35.0,
    val manualAckRequired: Boolean = true,
    val requireSentimentSignal: Boolean = false,
    val sentimentLookbackMinutes: Int = 180,
    val sentimentEscalationScore: Double = -0.6,
    val sentimentEscalationConfidence: Double = 0.65
) {
    companion object {
        fun bootstrap(): RiskPolicyDefinition = RiskPolicyDefinition()
    }
}

enum class RiskTier {
    NORMAL,
    APPROACHING_LIMIT,
    BREACH_UNWIND,
    HARD_KILL
}

enum class RiskAction {
    ALLOW,
    REJECT_NEW_RISK,
    UNWIND_ONLY,
    BLOCK_ALL
}

data class RiskDecisionMetrics(
    val currentExposureUsd: BigDecimal,
    val projectedExposureUsd: BigDecimal,
    val accountEquityUsd: BigDecimal,
    val highWaterMarkUsd: BigDecimal,
    val dailyLossUsd: BigDecimal,
    val leverage: Double,
    val exposureUtilization: Double,
    val drawdownPct: Double,
    val drawdownUtilization: Double,
    val dailyLossUtilization: Double,
    val leverageUtilization: Double
)

data class RiskDecision(
    val allowed: Boolean,
    val tier: RiskTier,
    val action: RiskAction,
    val reason: String,
    val policyId: String?,
    val policyVersion: Int?,
    val suggestedMaxOrderNotionalUsd: BigDecimal?,
    val unwindSliceSeconds: Int?,
    val unwindMaxSlippageBps: Double?,
    val metrics: RiskDecisionMetrics,
    val sentiment: SentimentSnapshot?
)

class RiskEngineService(
    private val dbService: DatabaseService
) {
    private val logger = LoggerFactory.getLogger(RiskEngineService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun evaluateOrder(
        username: String,
        symbol: String,
        orderNotionalUsd: BigDecimal,
        reduceOnly: Boolean
    ): RiskDecision {
        var policyLookupFailed = false
        var accountStateLookupFailed = false
        var killSwitchLookupFailed = false
        val policyRecord = runCatching { dbService.getActiveRiskPolicyForUser(username) }
            .onFailure {
                policyLookupFailed = true
                logger.warn("Risk policy lookup failed: {}", it.message)
            }
            .getOrNull()
        val policy = decodePolicy(policyRecord?.policyJson)
        val accountState = runCatching { dbService.getOrCreateRiskAccountState(username) }
            .onFailure {
                accountStateLookupFailed = true
                logger.warn("Risk state lookup failed: {}", it.message)
            }
            .getOrElse { defaultAccountState(username) }
        val killSwitch = runCatching { dbService.getRiskKillSwitchState(username) }
            .onFailure {
                killSwitchLookupFailed = true
                logger.warn("Risk kill-switch lookup failed: {}", it.message)
            }
            .getOrNull()

        val currentExposureRaw = runCatching { accountState.openExposureUsd }.getOrDefault(BigDecimal.ZERO)
        val accountEquityRaw = runCatching { accountState.accountEquityUsd }.getOrDefault(BigDecimal("100000"))
        val highWaterRaw = runCatching { accountState.highWaterMarkUsd }.getOrDefault(accountEquityRaw)
        val dailyRealizedRaw = runCatching { accountState.dailyRealizedPnlUsd }.getOrDefault(BigDecimal.ZERO)
        val dailyUnrealizedRaw = runCatching { accountState.dailyUnrealizedPnlUsd }.getOrDefault(BigDecimal.ZERO)

        if (killSwitch?.engaged == true) {
            val killSwitchReason = if (killSwitch.manualAckRequired) {
                "Kill switch engaged for account; manual acknowledgement required"
            } else {
                "Kill switch engaged for account"
            }
            val blocked = blockedDecision(
                tier = RiskTier.HARD_KILL,
                action = RiskAction.BLOCK_ALL,
                reason = killSwitchReason,
                policyId = policyRecord?.id?.toString(),
                policyVersion = policyRecord?.version,
                currentExposure = currentExposureRaw,
                projectedExposure = currentExposureRaw,
                accountEquity = accountEquityRaw,
                highWaterMark = highWaterRaw,
                dailyLossUsd = calculateDailyLoss(dailyRealizedRaw, dailyUnrealizedRaw),
                policy = policy,
                sentiment = null
            )
            runCatching {
                dbService.updateRiskTierSnapshot(
                    username = username,
                    riskTier = blocked.tier.name.lowercase(),
                    tierReason = blocked.reason,
                    sentiment = null
                )
            }
            return blocked
        }
        if (policyLookupFailed || accountStateLookupFailed || killSwitchLookupFailed) {
            val degraded = blockedDecision(
                tier = RiskTier.BREACH_UNWIND,
                action = if (reduceOnly) RiskAction.ALLOW else RiskAction.UNWIND_ONLY,
                reason = "Risk backend unavailable; only exposure-reducing orders permitted",
                policyId = policyRecord?.id?.toString(),
                policyVersion = policyRecord?.version,
                currentExposure = currentExposureRaw,
                projectedExposure = currentExposureRaw,
                accountEquity = accountEquityRaw,
                highWaterMark = highWaterRaw,
                dailyLossUsd = calculateDailyLoss(dailyRealizedRaw, dailyUnrealizedRaw),
                policy = policy,
                sentiment = null
            )
            runCatching {
                dbService.updateRiskTierSnapshot(
                    username = username,
                    riskTier = degraded.tier.name.lowercase(),
                    tierReason = degraded.reason,
                    sentiment = null
                )
            }
            return degraded
        }

        val currentExposure = currentExposureRaw.max(BigDecimal.ZERO)
        val projectedExposure = if (reduceOnly) {
            (currentExposure - orderNotionalUsd).max(BigDecimal.ZERO)
        } else {
            currentExposure + orderNotionalUsd
        }

        val accountEquity = accountEquityRaw.max(BigDecimal.ONE)
        val highWaterMark = highWaterRaw.max(accountEquity)
        val drawdownUsd = (highWaterMark - accountEquity).max(BigDecimal.ZERO)
        val drawdownPct = pct(drawdownUsd, highWaterMark)
        val dailyLossUsd = calculateDailyLoss(dailyRealizedRaw, dailyUnrealizedRaw)

        val exposureUtilization = utilization(projectedExposure, policy.maxExposureUsd)
        val drawdownUtilization = utilization(BigDecimal.valueOf(drawdownPct), policy.maxDrawdownPct)
        val dailyLossUtilization = utilization(dailyLossUsd, policy.maxDailyLossUsd)
        val leverage = ratio(projectedExposure, accountEquity)
        val leverageUtilization = if (policy.maxLeverage <= 0.0) 0.0 else leverage / policy.maxLeverage

        val sentiment = runCatching {
            dbService.fetchLatestSentiment(symbol, lookbackMinutes = policy.sentimentLookbackMinutes)
        }.getOrNull()
        if (policy.requireSentimentSignal && sentiment == null) {
            val decision = blockedDecision(
                tier = RiskTier.BREACH_UNWIND,
                action = if (reduceOnly) RiskAction.ALLOW else RiskAction.UNWIND_ONLY,
                reason = "Sentiment signal required by policy but unavailable",
                policyId = policyRecord?.id?.toString(),
                policyVersion = policyRecord?.version,
                currentExposure = currentExposure,
                projectedExposure = projectedExposure,
                accountEquity = accountEquity,
                highWaterMark = highWaterMark,
                dailyLossUsd = dailyLossUsd,
                policy = policy,
                sentiment = null
            )
            runCatching {
                dbService.updateRiskTierSnapshot(
                    username = username,
                    riskTier = decision.tier.name.lowercase(),
                    tierReason = decision.reason,
                    sentiment = null
                )
            }
            return decision
        }

        var maxUtilization = max(exposureUtilization, max(drawdownUtilization, max(dailyLossUtilization, leverageUtilization)))
        val sentimentEscalationTriggered =
            sentiment != null &&
                sentiment.sentimentScore <= policy.sentimentEscalationScore &&
                sentiment.confidence >= policy.sentimentEscalationConfidence
        if (sentimentEscalationTriggered && !reduceOnly) {
            maxUtilization += 0.10
        }

        val tier = when {
            maxUtilization >= policy.hardKillTrigger -> RiskTier.HARD_KILL
            maxUtilization >= policy.unwindTrigger -> RiskTier.BREACH_UNWIND
            maxUtilization >= policy.approachTrigger -> RiskTier.APPROACHING_LIMIT
            else -> RiskTier.NORMAL
        }

        val riskIncreasing = !reduceOnly && projectedExposure > currentExposure
        val maxAdditionalExposure = (
            BigDecimal.valueOf(policy.maxExposureUsd)
                .multiply(BigDecimal.valueOf(policy.approachTrigger))
                - currentExposure
            ).max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP)

        val action = when (tier) {
            RiskTier.NORMAL -> RiskAction.ALLOW
            RiskTier.APPROACHING_LIMIT -> if (riskIncreasing) RiskAction.REJECT_NEW_RISK else RiskAction.ALLOW
            RiskTier.BREACH_UNWIND -> if (reduceOnly) RiskAction.ALLOW else RiskAction.UNWIND_ONLY
            RiskTier.HARD_KILL -> RiskAction.BLOCK_ALL
        }

        val hardKillReason = if (policy.manualAckRequired) {
            "Hard risk kill-switch triggered; manual acknowledgement required"
        } else {
            "Hard risk kill-switch triggered"
        }
        val reason = when (action) {
            RiskAction.ALLOW -> "Order allowed within active risk policy"
            RiskAction.REJECT_NEW_RISK -> "Account approaching risk limits; only exposure-reducing orders allowed"
            RiskAction.UNWIND_ONLY -> "Risk limits breached; slippage-managed unwind required"
            RiskAction.BLOCK_ALL -> hardKillReason
        }

        if (tier == RiskTier.HARD_KILL) {
            runCatching {
                dbService.engageRiskKillSwitch(
                    username = username,
                    reason = reason,
                    engagedBy = "risk-engine",
                    manualAckRequired = policy.manualAckRequired
                )
            }
        }

        val decision = RiskDecision(
            allowed = action == RiskAction.ALLOW,
            tier = tier,
            action = action,
            reason = reason,
            policyId = policyRecord?.id?.toString(),
            policyVersion = policyRecord?.version,
            suggestedMaxOrderNotionalUsd = if (riskIncreasing) maxAdditionalExposure else null,
            unwindSliceSeconds = if (tier == RiskTier.BREACH_UNWIND || tier == RiskTier.HARD_KILL) policy.unwindSliceSeconds else null,
            unwindMaxSlippageBps = if (tier == RiskTier.BREACH_UNWIND || tier == RiskTier.HARD_KILL) policy.unwindMaxSlippageBps else null,
            metrics = RiskDecisionMetrics(
                currentExposureUsd = currentExposure,
                projectedExposureUsd = projectedExposure,
                accountEquityUsd = accountEquity,
                highWaterMarkUsd = highWaterMark,
                dailyLossUsd = dailyLossUsd,
                leverage = leverage,
                exposureUtilization = exposureUtilization,
                drawdownPct = drawdownPct,
                drawdownUtilization = drawdownUtilization,
                dailyLossUtilization = dailyLossUtilization,
                leverageUtilization = leverageUtilization
            ),
            sentiment = sentiment
        )

        runCatching {
            dbService.updateRiskTierSnapshot(
                username = username,
                riskTier = decision.tier.name.lowercase(),
                tierReason = decision.reason,
                sentiment = sentiment
            )
        }
        return decision
    }

    fun recordAcceptedOrder(
        username: String,
        notionalUsd: BigDecimal,
        reduceOnly: Boolean
    ): RiskAccountStateRecord {
        val delta = if (reduceOnly) notionalUsd.negate() else notionalUsd
        return runCatching {
            dbService.adjustRiskOpenExposure(username = username, deltaExposureUsd = delta)
        }.getOrElse {
            logger.debug("Risk exposure update skipped: {}", it.message)
            defaultAccountState(username)
        }
    }

    private fun blockedDecision(
        tier: RiskTier,
        action: RiskAction,
        reason: String,
        policyId: String?,
        policyVersion: Int?,
        currentExposure: BigDecimal,
        projectedExposure: BigDecimal,
        accountEquity: BigDecimal,
        highWaterMark: BigDecimal,
        dailyLossUsd: BigDecimal,
        policy: RiskPolicyDefinition,
        sentiment: SentimentSnapshot?
    ): RiskDecision {
        val drawdownPct = pct((highWaterMark - accountEquity).max(BigDecimal.ZERO), highWaterMark)
        val leverage = ratio(projectedExposure, accountEquity.max(BigDecimal.ONE))
        return RiskDecision(
            allowed = action == RiskAction.ALLOW,
            tier = tier,
            action = action,
            reason = reason,
            policyId = policyId,
            policyVersion = policyVersion,
            suggestedMaxOrderNotionalUsd = BigDecimal.ZERO,
            unwindSliceSeconds = policy.unwindSliceSeconds,
            unwindMaxSlippageBps = policy.unwindMaxSlippageBps,
            metrics = RiskDecisionMetrics(
                currentExposureUsd = currentExposure,
                projectedExposureUsd = projectedExposure,
                accountEquityUsd = accountEquity,
                highWaterMarkUsd = highWaterMark,
                dailyLossUsd = dailyLossUsd,
                leverage = leverage,
                exposureUtilization = utilization(projectedExposure, policy.maxExposureUsd),
                drawdownPct = drawdownPct,
                drawdownUtilization = utilization(BigDecimal.valueOf(drawdownPct), policy.maxDrawdownPct),
                dailyLossUtilization = utilization(dailyLossUsd, policy.maxDailyLossUsd),
                leverageUtilization = if (policy.maxLeverage <= 0.0) 0.0 else leverage / policy.maxLeverage
            ),
            sentiment = sentiment
        )
    }

    private fun decodePolicy(rawJson: String?): RiskPolicyDefinition {
        if (rawJson.isNullOrBlank()) return RiskPolicyDefinition.bootstrap()
        return runCatching { json.decodeFromString(RiskPolicyDefinition.serializer(), rawJson) }
            .onFailure { logger.warn("Failed to parse risk policy JSON: {}", it.message) }
            .getOrDefault(RiskPolicyDefinition.bootstrap())
    }

    private fun calculateDailyLoss(
        dailyRealizedPnlUsd: BigDecimal,
        dailyUnrealizedPnlUsd: BigDecimal
    ): BigDecimal {
        val aggregate = dailyRealizedPnlUsd + dailyUnrealizedPnlUsd
        return if (aggregate < BigDecimal.ZERO) aggregate.abs() else BigDecimal.ZERO
    }

    private fun utilization(value: BigDecimal, limit: Double): Double {
        if (limit <= 0.0) return 0.0
        return value.divide(BigDecimal.valueOf(limit), 12, RoundingMode.HALF_UP)
            .toDouble()
            .coerceAtLeast(0.0)
    }

    private fun ratio(numerator: BigDecimal, denominator: BigDecimal): Double {
        if (denominator <= BigDecimal.ZERO) return 0.0
        return numerator.divide(denominator, 12, RoundingMode.HALF_UP).toDouble().coerceAtLeast(0.0)
    }

    private fun pct(numerator: BigDecimal, denominator: BigDecimal): Double {
        if (denominator <= BigDecimal.ZERO) return 0.0
        return numerator
            .multiply(BigDecimal.valueOf(100))
            .divide(denominator, 12, RoundingMode.HALF_UP)
            .toDouble()
            .coerceAtLeast(0.0)
    }

    private fun defaultAccountState(username: String): RiskAccountStateRecord {
        val now = java.time.Instant.now()
        return RiskAccountStateRecord(
            username = username.lowercase(),
            accountEquityUsd = BigDecimal("100000"),
            highWaterMarkUsd = BigDecimal("100000"),
            realizedPnlUsd = BigDecimal.ZERO,
            unrealizedPnlUsd = BigDecimal.ZERO,
            dailyRealizedPnlUsd = BigDecimal.ZERO,
            dailyUnrealizedPnlUsd = BigDecimal.ZERO,
            openExposureUsd = BigDecimal.ZERO,
            sentimentScore = null,
            sentimentConfidence = null,
            riskTier = RiskTier.NORMAL.name.lowercase(),
            tierReason = null,
            updatedAt = now
        )
    }
}
