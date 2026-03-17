package org.datamancy.txgateway

import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.every
import io.ktor.server.testing.*
import io.mockk.mockk
import io.mockk.verify
import org.datamancy.txgateway.services.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun testHealthEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("healthy"))
    }

    @Test
    fun testRootEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("tx-gateway"))
        assertTrue(body.contains("endpoints"))
    }

    @Test
    fun testApiV1HealthEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("healthy"))
    }

    @Test
    fun testUnifiedExchangesEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("hyperliquid"))
        assertTrue(body.contains("binance"))
        assertTrue(body.contains("swyftx"))
    }

    @Test
    fun testUnifiedExchangesEndpointIncludesRequiredVenueSet() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val expectedVenues = listOf("swyftx", "binance", "bybit", "coinbase", "dydx", "hyperliquid", "aster")
        expectedVenues.forEach { venue ->
            assertTrue(body.contains("\"apiName\":\"$venue\"") || body.contains("\"apiName\": \"$venue\""))
        }
        assertTrue(
            Regex("\\{[^}]*\"apiName\"\\s*:\\s*\"hyperliquid\"[^}]*\"liveOrder\"\\s*:\\s*true").containsMatchIn(body),
            body
        )
    }

    @Test
    fun testUnifiedQuoteEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "orderbook_data"
            )
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges/hyperliquid/quote?symbol=BTC")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"bid\""))
        assertTrue(body.contains("73000"))
    }

    @Test
    fun testUnifiedQuoteEndpointRejectsInvalidSnapshot() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 73010.0,
                ask = 73000.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "orderbook_data"
            )
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges/hyperliquid/quote?symbol=BTC")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Invalid quote snapshot"), body)
    }

    @Test
    fun testBestQuoteEndpointSelectsBySide() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)

            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 72990.0,
                ask = 73000.0,
                last = 72995.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73010.0,
                ask = 73020.0,
                last = 73015.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val buyResponse = client.get("/api/v1/exchanges/best-quote?symbol=BTC&side=buy&exchanges=hyperliquid,binance")
        assertEquals(HttpStatusCode.OK, buyResponse.status)
        val buyBody = buyResponse.bodyAsText()
        assertTrue(buyBody.contains("\"selectedExchange\": \"hyperliquid\""))
        assertTrue(buyBody.contains("\"side\": \"buy\""))

        val sellResponse = client.get("/api/v1/exchanges/best-quote?symbol=BTC&side=sell&exchanges=hyperliquid,binance")
        assertEquals(HttpStatusCode.OK, sellResponse.status)
        val sellBody = sellResponse.bodyAsText()
        assertTrue(sellBody.contains("\"selectedExchange\": \"binance\""))
        assertTrue(sellBody.contains("\"side\": \"sell\""))
    }

    @Test
    fun testBestQuoteEndpointSkipsInvalidSnapshots() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)

            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 73050.0,
                ask = 73000.0,
                last = 73020.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 72990.0,
                ask = 73005.0,
                last = 73000.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges/best-quote?symbol=BTC&side=buy&exchanges=hyperliquid,binance")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"selectedExchange\": \"binance\""), body)
    }

    @Test
    fun testBestQuoteEndpointReturnsNotFoundWhenAllSnapshotsInvalid() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)

            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 0.0,
                ask = 73000.0,
                last = 73000.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73100.0,
                ask = 73000.0,
                last = 73050.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges/best-quote?symbol=BTC&side=buy&exchanges=hyperliquid,binance")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Quote unavailable"), body)
    }

    @Test
    fun testPaperOrderEndpointForNonLiveExchange() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance", "hyperliquid"),
                maxTxPerHour = 100,
                maxTxValueUSD = 25000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"simulated\"\\s*:\\s*true").containsMatchIn(body), body)
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"paper\"").containsMatchIn(body), body)
        assertTrue(Regex("\"status\"\\s*:\\s*\"FILLED\"").containsMatchIn(body), body)
    }

    @Test
    fun testPaperLimitOrderCanRemainPending() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("coinbase"),
                maxTxPerHour = 100,
                maxTxValueUSD = 25000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("coinbase", "BTC") } returns LatestQuote(
                exchange = "coinbase",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/coinbase/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"LIMIT","size":"0.25","price":"72000"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"status\"\\s*:\\s*\"PENDING\"").containsMatchIn(body), body)
        assertTrue(Regex("\"filledSize\"\\s*:\\s*\"0\"").containsMatchIn(body), body)
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"paper\"").containsMatchIn(body), body)
    }

    @Test
    fun testPaperOrderCanBePartiallyFilledWithExecutionTelemetry() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance"),
                maxTxPerHour = 100,
                maxTxValueUSD = 500000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"5","urgencyClass":"low","cancelAfterMs":1500}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"status\"\\s*:\\s*\"PARTIALLY_FILLED\"").containsMatchIn(body), body)
        assertTrue(Regex("\"costs\"\\s*:").containsMatchIn(body), body)
        assertTrue(Regex("\"telemetry\"\\s*:").containsMatchIn(body), body)
        assertTrue(Regex("\"p50RoundTripMs\"\\s*:\\s*\\d+").containsMatchIn(body), body)
        assertTrue(Regex("\"p99RoundTripMs\"\\s*:\\s*\\d+").containsMatchIn(body), body)
        assertTrue(Regex("\"simulation\"\\s*:").containsMatchIn(body), body)
        assertTrue(Regex("\"queuePositionEstimate\"\\s*:\\s*\\d+").containsMatchIn(body), body)
        assertTrue(Regex("\"projectedPartialFills\"\\s*:").containsMatchIn(body), body)
        assertTrue(Regex("\"cancelCadenceMs\"\\s*:\\s*1500").containsMatchIn(body), body)
    }

    @Test
    fun testPaperOrderAppliesFeeTierAdjustments() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance"),
                maxTxPerHour = 100,
                maxTxValueUSD = 25000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","feeTier":"vip"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"feeTier\"\\s*:\\s*\"vip\"").containsMatchIn(body), body)
        assertTrue(Regex("\"feeTierAdjustmentBps\"\\s*:\\s*-1\\.5").containsMatchIn(body), body)
    }

    @Test
    fun testPaperOrderRejectsWhenEstimatedSlippageExceedsLimit() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance"),
                maxTxPerHour = 100,
                maxTxValueUSD = 25000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25","maxSlippageBps":0.5}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"status\"\\s*:\\s*\"REJECTED\"").containsMatchIn(body), body)
        assertTrue(Regex("\"filledSize\"\\s*:\\s*\"0\"").containsMatchIn(body), body)
        assertTrue(Regex("\"rejectionReason\"\\s*:\\s*\"Estimated slippage").containsMatchIn(body), body)
    }

    @Test
    fun testLiveHyperliquidOrderBlockedWhenExecutionSafeguardsReject() = testApplication {
        lateinit var workerClient: WorkerClient
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("hyperliquid"),
                maxTxPerHour = 100,
                maxTxValueUSD = 25000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )
            every { workerClient.submitHyperliquidOrder(any()) } returns mapOf("orderId" to "live-1")

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","maxSlippageBps":0.5}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"error\"\\s*:\\s*\"Estimated slippage").containsMatchIn(body), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testUnifiedOrderRejectsWhenOrderValueExceedsRiskLimit() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance"),
                maxTxPerHour = 100,
                maxTxValueUSD = 5000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"error\"\\s*:\\s*\"Order value exceeds maxTxValueUSD\"").containsMatchIn(body), body)
        assertTrue(Regex("\"maxTxValueUSD\"\\s*:\\s*\"5000\"").containsMatchIn(body), body)
    }

    @Test
    fun testPaperOrderRejectsPostOnlyOrderThatWouldTakeLiquidity() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance"),
                maxTxPerHour = 100,
                maxTxValueUSD = 25000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"LIMIT","size":"0.1","price":"73020","postOnly":true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"status\"\\s*:\\s*\"REJECTED\"").containsMatchIn(body), body)
        assertTrue(Regex("\"rejectionReason\"\\s*:\\s*\"Post-only limit order would cross the spread").containsMatchIn(body), body)
    }

    @Test
    fun testUnifiedOrderRejectsInvalidUrgencyClassEarly() = testApplication {
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            dbService = mockk(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance"),
                maxTxPerHour = 100,
                maxTxValueUSD = 25000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","urgencyClass":"immediate"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Invalid urgencyClass"), body)
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsOversizedCancelWindowEarly() = testApplication {
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            dbService = mockk(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance"),
                maxTxPerHour = 100,
                maxTxValueUSD = 25000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","cancelAfterMs":700001}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("cancelAfterMs must be <= 600000"), body)
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any()) }
    }
}
