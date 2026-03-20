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
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    private fun freshQuoteTimestamp(): Instant = Instant.now().minusSeconds(5)

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
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
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
    fun testUnifiedQuoteEndpointRejectsNonFiniteSnapshot() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = Double.POSITIVE_INFINITY,
                ask = 73010.0,
                last = 73005.0,
                timestamp = freshQuoteTimestamp(),
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
    fun testUnifiedQuoteEndpointRejectsStaleSnapshot() = testApplication {
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
                timestamp = Instant.now().minusSeconds(900),
                source = "orderbook_data"
            )
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges/hyperliquid/quote?symbol=BTC")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Stale quote snapshot"), body)
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
                timestamp = freshQuoteTimestamp(),
                source = "market_data:trade"
            )
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73010.0,
                ask = 73020.0,
                last = 73015.0,
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
                source = "market_data:trade"
            )
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 72990.0,
                ask = 73005.0,
                last = 73000.0,
                timestamp = freshQuoteTimestamp(),
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
    fun testBestQuoteEndpointSkipsStaleSnapshots() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)

            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73001.0,
                last = 73000.5,
                timestamp = Instant.now().minusSeconds(901),
                source = "market_data:trade"
            )
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 72990.0,
                ask = 73005.0,
                last = 73000.0,
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
                source = "market_data:trade"
            )
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73100.0,
                ask = 73000.0,
                last = 73050.0,
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
                source = "orderbook_data:canonical"
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
                timestamp = freshQuoteTimestamp(),
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
                timestamp = freshQuoteTimestamp(),
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
    fun testPaperOrderRejectsStaleQuoteSnapshot() = testApplication {
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
                timestamp = Instant.now().minusSeconds(901),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Stale quote snapshot"), body)
    }

    @Test
    fun testPaperOrderRejectsNonFiniteQuoteSnapshot() = testApplication {
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
                ask = Double.POSITIVE_INFINITY,
                last = 73005.0,
                timestamp = freshQuoteTimestamp(),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Invalid quote snapshot"), body)
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
    fun testUnifiedOrderRejectsInvalidSymbolEarly() = testApplication {
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
            setBody("""{"symbol":"BTC;DROP TABLE","side":"BUY","type":"MARKET","size":"0.1"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Invalid symbol"), body)
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsInvalidSideEarly() = testApplication {
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
            setBody("""{"symbol":"BTC","side":"HOLD","type":"MARKET","size":"0.1"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Invalid side"), body)
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsMarketOrderPriceEarly() = testApplication {
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
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","price":"73000"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Market orders must not include price"), body)
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

    @Test
    fun testUnifiedOrderRejectsPostOnlyMarketOrderEarly() = testApplication {
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
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","postOnly":true}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("postOnly is only valid for LIMIT orders"), body)
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any()) }
    }

    @Test
    fun testLiveHyperliquidOrderRejectsBlankCredentialEarly() = testApplication {
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
                timestamp = freshQuoteTimestamp(),
                source = "orderbook_data:canonical"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "   ")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Missing Hyperliquid credentials"), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testLiveHyperliquidOrderRejectsWhenFreshQuoteMissing() = testApplication {
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
            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns null

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Fresh quote snapshot required for live order risk checks"), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testLiveHyperliquidOrderRejectsWhenQuoteIsNotOrderbookBacked() = testApplication {
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
                timestamp = freshQuoteTimestamp(),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Orderbook-backed quote required for live order risk checks"), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testUnifiedOrderRejectsWhenRiskEngineBlocksNewExposure() = testApplication {
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
                maxTxValueUSD = 25_000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 99.0,
                ask = 100.0,
                last = 99.5,
                timestamp = freshQuoteTimestamp(),
                source = "orderbook_data:canonical"
            )
            every { dbService.getActiveRiskPolicyForUser("trader1") } returns RiskPolicyRecord(
                id = UUID.randomUUID(),
                username = "trader1",
                walletAddress = null,
                version = 2,
                status = "active",
                policyJson = """
                    {
                      "maxExposureUsd": 1000.0,
                      "maxLeverage": 5.0,
                      "maxDrawdownPct": 50.0,
                      "maxDailyLossUsd": 10000.0,
                      "approachTrigger": 0.8,
                      "unwindTrigger": 1.0,
                      "hardKillTrigger": 5.0
                    }
                """.trimIndent(),
                createdBy = "trader1",
                createdAt = freshQuoteTimestamp(),
                activatedAt = freshQuoteTimestamp(),
                activatedByWallet = null,
                activationSignature = null,
                activationNonce = null,
                activationMessage = null,
                isBootstrap = false
            )
            every { dbService.getOrCreateRiskAccountState("trader1") } returns RiskAccountStateRecord(
                username = "trader1",
                accountEquityUsd = java.math.BigDecimal("1000"),
                highWaterMarkUsd = java.math.BigDecimal("1000"),
                realizedPnlUsd = java.math.BigDecimal.ZERO,
                unrealizedPnlUsd = java.math.BigDecimal.ZERO,
                dailyRealizedPnlUsd = java.math.BigDecimal.ZERO,
                dailyUnrealizedPnlUsd = java.math.BigDecimal.ZERO,
                openExposureUsd = java.math.BigDecimal("790"),
                sentimentScore = 0.0,
                sentimentConfidence = 1.0,
                riskTier = "normal",
                tierReason = null,
                updatedAt = freshQuoteTimestamp()
            )
            every { dbService.getRiskKillSwitchState("trader1") } returns null
            every { dbService.fetchLatestSentiment("BTC", any()) } returns null

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"1"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Order blocked by risk engine"), body)
        assertTrue(body.contains("approaching"), body)
    }

    @Test
    fun testLegacyHyperliquidOrderEndpointIsDeprecated() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/hyperliquid/order") {
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"1"}""")
        }

        assertEquals(HttpStatusCode.Gone, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Endpoint deprecated"), body)
    }
}
