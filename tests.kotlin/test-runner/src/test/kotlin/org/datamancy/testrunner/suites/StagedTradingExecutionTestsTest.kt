package org.datamancy.testrunner.suites

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.datamancy.testrunner.framework.ServiceEndpoints
import org.datamancy.testrunner.framework.TestRunner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StagedTradingExecutionTestsTest {

    @Test
    fun `staged trading fixtures are available on classpath`() {
        val fixturePaths = listOf(
            "fixtures/trading-staged/paper_full_fill.json",
            "fixtures/trading-staged/paper_partial_fill.json",
            "fixtures/trading-staged/worker_degraded.json"
        )

        fixturePaths.forEach { path ->
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
            assertNotNull(stream, "Fixture missing on classpath: $path")

            val json = stream.bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(json).jsonObject
            assertTrue(root.containsKey("name"), "Fixture '$path' missing 'name'")
            assertTrue(root.containsKey("sampleResponse"), "Fixture '$path' missing 'sampleResponse'")
        }
    }

    @Test
    fun `staged trading suite compiles and is callable`() {
        val testFunction: suspend TestRunner.() -> Unit = { stagedTradingExecutionTests() }
        assertNotNull(testFunction)
    }

    @Test
    fun `staged trading live defaults use external auth and internal trading service urls`() {
        val endpoints = ServiceEndpoints.forLocalhost()
        val config = stagedTradingConfig(
            endpoints = endpoints,
            env = mapOf(
                "TRADING_E2E_MODE" to "hybrid",
                "TRADING_E2E_TARGET_HOST" to "datamancy.net"
            )
        )

        assertEquals(endpoints.txGateway, config.txGatewayBaseUrl)
        assertEquals("https://auth.datamancy.net", config.autheliaBaseUrl)
        assertEquals(endpoints.hyperliquidWorker, config.hyperliquidWorkerBaseUrl)
    }

    @Test
    fun `staged trading live can opt into external edge urls`() {
        val endpoints = ServiceEndpoints.forLocalhost()
        val config = stagedTradingConfig(
            endpoints = endpoints,
            env = mapOf(
                "TRADING_E2E_MODE" to "live",
                "TRADING_E2E_TARGET_HOST" to "datamancy.net",
                "TRADING_E2E_LIVE_EXTERNAL_URLS" to "true"
            )
        )

        assertEquals("https://tx-gateway.datamancy.net", config.txGatewayBaseUrl)
        assertEquals("https://auth.datamancy.net", config.autheliaBaseUrl)
        assertEquals("https://hyperliquid-worker.datamancy.net", config.hyperliquidWorkerBaseUrl)
    }

    @Test
    fun `staged trading hyperliquid strict credential checks default on for live modes`() {
        val endpoints = ServiceEndpoints.forLocalhost()

        val nonStrictConfig = stagedTradingConfig(
            endpoints = endpoints,
            env = mapOf("TRADING_E2E_MODE" to "hybrid")
        )
        assertTrue(nonStrictConfig.strictHyperliquidCredentialChecks)

        val strictConfig = stagedTradingConfig(
            endpoints = endpoints,
            env = mapOf(
                "TRADING_E2E_MODE" to "hybrid",
                "TRADING_E2E_HYPERLIQUID_STRICT_CREDENTIALS" to "true"
            )
        )
        assertTrue(strictConfig.strictHyperliquidCredentialChecks)

        val replayConfig = stagedTradingConfig(
            endpoints = endpoints,
            env = mapOf("TRADING_E2E_MODE" to "replay")
        )
        assertFalse(replayConfig.strictHyperliquidCredentialChecks)

        val liveOverrideDisabled = stagedTradingConfig(
            endpoints = endpoints,
            env = mapOf(
                "TRADING_E2E_MODE" to "live",
                "TRADING_E2E_HYPERLIQUID_STRICT_CREDENTIALS" to "false"
            )
        )
        assertFalse(liveOverrideDisabled.strictHyperliquidCredentialChecks)
    }

    @Test
    fun `hyperliquid credential failure handling distinguishes malformed keys from provisioning blockers`() {
        val keyFailure = "Invalid signature for Hyperliquid API wallet"
        val provisioningBlocker = "User or API Wallet 0xabc does not exist."
        assertFalse(
            shouldFailOnHyperliquidCredentialError(
                strictCredentialChecks = false,
                errorText = keyFailure
            )
        )
        assertTrue(
            shouldFailOnHyperliquidCredentialError(
                strictCredentialChecks = true,
                errorText = keyFailure
            )
        )
        assertFalse(
            shouldFailOnHyperliquidCredentialError(
                strictCredentialChecks = true,
                errorText = provisioningBlocker
            )
        )
        assertFalse(
            shouldFailOnHyperliquidCredentialError(
                strictCredentialChecks = true,
                errorText = "insufficient margin for requested size"
            )
        )
    }

    @Test
    fun `managed trading users parser normalizes configured usernames`() {
        val users = csvEnvValues(
            name = "LDAP_MANAGED_TRADING_USERS",
            env = mapOf("LDAP_MANAGED_TRADING_USERS" to " traderbot ; Ops-Bot | traderbot ")
        )

        assertEquals(setOf("traderbot", "ops-bot"), users)
    }

    @Test
    fun `paper venue candidates include hyperliquid for homogeneous forward paper checks`() {
        val venues = paperVenueCandidates()

        assertTrue("hyperliquid" in venues)
        assertEquals(listOf("swyftx", "binance", "bybit", "coinbase", "dydx", "hyperliquid", "aster"), venues)
    }
}
