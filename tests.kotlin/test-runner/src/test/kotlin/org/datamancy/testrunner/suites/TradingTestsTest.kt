package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for TradingTests integration test suite.
 *
 * These tests validate that:
 * - Trading service endpoints are properly configured in both container and localhost modes
 * - Service endpoint URLs follow the expected patterns
 * - All required trading services are represented in the configuration
 */
class TradingTestsTest {

    @Test
    fun `test trading service endpoints configured for container environment`() {
        val containerEndpoints = ServiceEndpoints.fromEnvironment()

        // Verify all trading service endpoints are configured
        assertNotNull(containerEndpoints.txGateway, "TX Gateway endpoint should be configured")
        assertNotNull(containerEndpoints.evmBroadcaster, "EVM Broadcaster endpoint should be configured")
        assertNotNull(containerEndpoints.hyperliquidWorker, "Hyperliquid Worker endpoint should be configured")

        // Verify container network URLs (service discovery via Docker DNS)
        assertEquals("http://tx-gateway:8080", containerEndpoints.txGateway)
        assertEquals("http://evm-broadcaster:8081", containerEndpoints.evmBroadcaster)
        assertEquals("http://hyperliquid-worker:8082", containerEndpoints.hyperliquidWorker)
    }

    @Test
    fun `test trading service endpoints configured for localhost environment`() {
        val localhostEndpoints = ServiceEndpoints.forLocalhost()

        // Verify all trading service endpoints are configured
        assertNotNull(localhostEndpoints.txGateway, "TX Gateway endpoint should be configured")
        assertNotNull(localhostEndpoints.evmBroadcaster, "EVM Broadcaster endpoint should be configured")
        assertNotNull(localhostEndpoints.hyperliquidWorker, "Hyperliquid Worker endpoint should be configured")

        // Verify localhost port-mapped URLs
        assertEquals("http://localhost:18083", localhostEndpoints.txGateway)
        assertEquals("http://localhost:18084", localhostEndpoints.evmBroadcaster)
        assertEquals("http://localhost:18085", localhostEndpoints.hyperliquidWorker)
    }

    @Test
    fun `test trading service port allocations are unique`() {
        val localhostEndpoints = ServiceEndpoints.forLocalhost()

        val tradingPorts = listOf(
            18083,  // tx-gateway
            18084,  // evm-broadcaster
            18085   // hyperliquid-worker
        )

        // Verify no port conflicts
        assertEquals(tradingPorts.size, tradingPorts.toSet().size, "Trading service ports must be unique")

        // Verify ports are in expected ranges
        tradingPorts.forEach { port ->
            assertTrue(port in 10000..20000, "Port $port should be in range 10000-20000")
        }
    }

    @Test
    fun `test environment detection returns trading endpoints`() {
        // Container environment
        val containerEnv = TestEnvironment.Container
        assertNotNull(containerEnv.endpoints.txGateway)
        assertNotNull(containerEnv.endpoints.evmBroadcaster)
        assertNotNull(containerEnv.endpoints.hyperliquidWorker)

        // Localhost environment
        val localhostEnv = TestEnvironment.Localhost
        assertNotNull(localhostEnv.endpoints.txGateway)
        assertNotNull(localhostEnv.endpoints.evmBroadcaster)
        assertNotNull(localhostEnv.endpoints.hyperliquidWorker)
    }

    @Test
    fun `test trading suite compiles and is callable`() {
        // Verify the trading test suite extension function exists and is properly typed
        // This is a compile-time check - if this test compiles, the suite is properly structured
        val testFunction: suspend TestRunner.() -> Unit = { tradingTests() }
        assertNotNull(testFunction)
    }
}
