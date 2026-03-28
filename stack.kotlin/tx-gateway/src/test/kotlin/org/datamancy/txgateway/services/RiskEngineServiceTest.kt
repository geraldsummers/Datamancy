package org.datamancy.txgateway.risk.services

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.datamancy.txgateway.services.DatabaseService
import org.datamancy.txgateway.services.RiskAccountStateRecord
import org.datamancy.txgateway.services.RiskKillSwitchStateRecord
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskEngineServiceTest {

    @Test
    fun `evaluateOrder blocks new risk when kill switch is engaged even without manual ack requirement`() {
        val dbService = mockk<DatabaseService>()
        every { dbService.getActiveRiskPolicyForUser("trader1") } returns null
        every { dbService.getOrCreateRiskAccountState("trader1") } returns baselineState("trader1")
        every { dbService.getRiskKillSwitchState("trader1") } returns RiskKillSwitchStateRecord(
            username = "trader1",
            engaged = true,
            reason = "manual override",
            engagedAt = Instant.now(),
            engagedBy = "ops",
            manualAckRequired = false,
            acknowledgedAt = null,
            acknowledgedBy = null,
            ackNote = null,
            updatedAt = Instant.now()
        )

        val service = RiskEngineService(dbService)
        val decision = service.evaluateOrder(
            username = "trader1",
            symbol = "BTC",
            orderNotionalUsd = BigDecimal("100"),
            reduceOnly = false
        )

        assertFalse(decision.allowed)
        assertEquals(RiskTier.HARD_KILL, decision.tier)
        assertEquals(RiskAction.BLOCK_ALL, decision.action)
        assertTrue(decision.reason.contains("Kill switch engaged"))
    }

    @Test
    fun `evaluateOrder allows reduce-only unwind when kill switch is engaged`() {
        val dbService = mockk<DatabaseService>()
        every { dbService.getActiveRiskPolicyForUser("trader1") } returns null
        every { dbService.getOrCreateRiskAccountState("trader1") } returns baselineState("trader1")
        every { dbService.getRiskKillSwitchState("trader1") } returns RiskKillSwitchStateRecord(
            username = "trader1",
            engaged = true,
            reason = "manual override",
            engagedAt = Instant.now(),
            engagedBy = "ops",
            manualAckRequired = true,
            acknowledgedAt = null,
            acknowledgedBy = null,
            ackNote = null,
            updatedAt = Instant.now()
        )

        val service = RiskEngineService(dbService)
        val decision = service.evaluateOrder(
            username = "trader1",
            symbol = "BTC",
            orderNotionalUsd = BigDecimal("80"),
            reduceOnly = true
        )

        assertTrue(decision.allowed)
        assertEquals(RiskTier.HARD_KILL, decision.tier)
        assertEquals(RiskAction.ALLOW, decision.action)
        assertTrue(decision.reason.contains("only exposure-reducing orders allowed"))
    }

    @Test
    fun `evaluateOrder fails closed when risk backend policy lookup fails for risk increasing orders`() {
        val dbService = mockk<DatabaseService>()
        every { dbService.getActiveRiskPolicyForUser("trader1") } throws IllegalStateException("db unavailable")
        every { dbService.getOrCreateRiskAccountState("trader1") } returns baselineState("trader1")
        every { dbService.getRiskKillSwitchState("trader1") } returns null

        val service = RiskEngineService(dbService)
        val decision = service.evaluateOrder(
            username = "trader1",
            symbol = "BTC",
            orderNotionalUsd = BigDecimal("200"),
            reduceOnly = false
        )

        assertFalse(decision.allowed)
        assertEquals(RiskTier.BREACH_UNWIND, decision.tier)
        assertEquals(RiskAction.UNWIND_ONLY, decision.action)
        assertTrue(decision.reason.contains("Risk backend unavailable"))
    }

    @Test
    fun `evaluateOrder allows reduce-only unwind when risk backend is unavailable`() {
        val dbService = mockk<DatabaseService>()
        every { dbService.getActiveRiskPolicyForUser("trader1") } throws IllegalStateException("db unavailable")
        every { dbService.getOrCreateRiskAccountState("trader1") } returns baselineState("trader1")
        every { dbService.getRiskKillSwitchState("trader1") } returns null

        val service = RiskEngineService(dbService)
        val decision = service.evaluateOrder(
            username = "trader1",
            symbol = "BTC",
            orderNotionalUsd = BigDecimal("200"),
            reduceOnly = true
        )

        assertTrue(decision.allowed)
        assertEquals(RiskTier.BREACH_UNWIND, decision.tier)
        assertEquals(RiskAction.ALLOW, decision.action)
        assertTrue(decision.reason.contains("Risk backend unavailable"))
    }

    @Test
    fun `evaluateOrder allows reduce-only unwind when leverage breaches hard kill threshold`() {
        val dbService = mockk<DatabaseService>()
        every { dbService.getActiveRiskPolicyForUser("trader1") } returns null
        every { dbService.getOrCreateRiskAccountState("trader1") } returns baselineState(
            username = "trader1",
            accountEquityUsd = BigDecimal("50"),
            highWaterMarkUsd = BigDecimal("50"),
            openExposureUsd = BigDecimal("500")
        )
        every { dbService.getRiskKillSwitchState("trader1") } returns null
        every {
            dbService.engageRiskKillSwitch(
                username = "trader1",
                reason = any(),
                engagedBy = "risk-engine",
                manualAckRequired = true
            )
        } returns RiskKillSwitchStateRecord(
            username = "trader1",
            engaged = true,
            reason = "hard kill",
            engagedAt = Instant.now(),
            engagedBy = "risk-engine",
            manualAckRequired = true,
            acknowledgedAt = null,
            acknowledgedBy = null,
            ackNote = null,
            updatedAt = Instant.now()
        )

        val service = RiskEngineService(dbService)
        val decision = service.evaluateOrder(
            username = "trader1",
            symbol = "BTC",
            orderNotionalUsd = BigDecimal("100"),
            reduceOnly = true
        )

        assertTrue(decision.allowed)
        assertEquals(RiskTier.HARD_KILL, decision.tier)
        assertEquals(RiskAction.ALLOW, decision.action)
        assertTrue(decision.reason.contains("only exposure-reducing orders allowed"))
    }

    private fun baselineState(
        username: String,
        accountEquityUsd: BigDecimal = BigDecimal("1000"),
        highWaterMarkUsd: BigDecimal = accountEquityUsd,
        openExposureUsd: BigDecimal = BigDecimal("100")
    ) = RiskAccountStateRecord(
        username = username,
        accountEquityUsd = accountEquityUsd,
        highWaterMarkUsd = highWaterMarkUsd,
        realizedPnlUsd = BigDecimal.ZERO,
        unrealizedPnlUsd = BigDecimal.ZERO,
        dailyRealizedPnlUsd = BigDecimal.ZERO,
        dailyUnrealizedPnlUsd = BigDecimal.ZERO,
        openExposureUsd = openExposureUsd,
        sentimentScore = null,
        sentimentConfidence = null,
        riskTier = "normal",
        tierReason = null,
        updatedAt = Instant.now()
    )
}
