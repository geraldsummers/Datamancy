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
    fun `evaluateOrder blocks when kill switch is engaged even without manual ack requirement`() {
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

    private fun baselineState(username: String) = RiskAccountStateRecord(
        username = username,
        accountEquityUsd = BigDecimal("1000"),
        highWaterMarkUsd = BigDecimal("1000"),
        realizedPnlUsd = BigDecimal.ZERO,
        unrealizedPnlUsd = BigDecimal.ZERO,
        dailyRealizedPnlUsd = BigDecimal.ZERO,
        dailyUnrealizedPnlUsd = BigDecimal.ZERO,
        openExposureUsd = BigDecimal("100"),
        sentimentScore = null,
        sentimentConfidence = null,
        riskTier = "normal",
        tierReason = null,
        updatedAt = Instant.now()
    )
}
