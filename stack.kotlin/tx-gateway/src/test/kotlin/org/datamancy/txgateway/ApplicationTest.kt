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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    private fun tradingUserInfo(
        username: String = "trader1",
        groups: List<String> = listOf("traders"),
        allowedChains: List<String> = listOf("base"),
        allowedExchanges: List<String> = listOf("hyperliquid"),
        allowedTradingModes: List<String> = listOf("backtest", "forward_paper", "testnet_live"),
        maxTxPerHour: Int = 100,
        maxTxValueUSD: Int = 25000
    ) = org.datamancy.txgateway.models.UserInfo(
        username = username,
        email = "$username@datamancy.net",
        groups = groups,
        evmAddress = null,
        allowedChains = allowedChains,
        allowedExchanges = allowedExchanges,
        allowedTradingModes = allowedTradingModes,
        maxTxPerHour = maxTxPerHour,
        maxTxValueUSD = maxTxValueUSD
    )

    private fun tradingAudit(
        username: String = "trader1",
        groups: List<String> = listOf("traders"),
        hasTradingProfile: Boolean = true,
        hasTradingObjectClass: Boolean = true,
        rawAllowedChains: List<String> = listOf("base"),
        allowedChains: List<String> = listOf("base"),
        rawAllowedExchanges: List<String> = listOf("hyperliquid"),
        allowedExchanges: List<String> = listOf("hyperliquid"),
        rawAllowedTradingModes: List<String> = listOf("forward_paper"),
        allowedTradingModes: List<String> = listOf("forward_paper"),
        maxTxPerHour: Int = 100,
        maxTxValueUSD: Int = 25000,
        findings: List<String> = emptyList()
    ) = TradingAccountAudit(
        username = username,
        email = "$username@datamancy.net",
        groups = groups,
        hasTradingProfile = hasTradingProfile,
        hasTradingObjectClass = hasTradingObjectClass,
        rawAllowedChains = rawAllowedChains,
        allowedChains = allowedChains,
        rawAllowedExchanges = rawAllowedExchanges,
        allowedExchanges = allowedExchanges,
        rawAllowedTradingModes = rawAllowedTradingModes,
        allowedTradingModes = allowedTradingModes,
        maxTxPerHour = maxTxPerHour,
        maxTxValueUSD = maxTxValueUSD,
        findings = findings
    )

    private fun freshQuoteTimestamp(): Instant = Instant.now().minusSeconds(5)

    private fun baselineRiskAccountState(
        username: String,
        openExposureUsd: String = "0",
        accountEquityUsd: String = "100000"
    ): RiskAccountStateRecord = RiskAccountStateRecord(
        username = username,
        accountEquityUsd = BigDecimal(accountEquityUsd),
        highWaterMarkUsd = BigDecimal(accountEquityUsd),
        realizedPnlUsd = BigDecimal.ZERO,
        unrealizedPnlUsd = BigDecimal.ZERO,
        dailyRealizedPnlUsd = BigDecimal.ZERO,
        dailyUnrealizedPnlUsd = BigDecimal.ZERO,
        openExposureUsd = BigDecimal(openExposureUsd),
        sentimentScore = 0.0,
        sentimentConfidence = 1.0,
        riskTier = "normal",
        tierReason = null,
        updatedAt = freshQuoteTimestamp()
    )

    private fun stubAllowingRiskState(
        dbService: DatabaseService,
        username: String = "trader1",
        openExposureUsd: String = "0"
    ) {
        every { dbService.getActiveRiskPolicyForUser(username) } returns null
        every { dbService.getOrCreateRiskAccountState(username) } returns baselineRiskAccountState(
            username = username,
            openExposureUsd = openExposureUsd
        )
        every { dbService.getRiskKillSwitchState(username) } returns null
        every { dbService.fetchLatestSentiment(any(), any()) } returns null
    }

    private fun quoteAwareDbService(): DatabaseService {
        val dbService = mockk<DatabaseService>(relaxed = true)
        every { dbService.fetchLatestQuote(any(), any(), any()) } answers {
            val exchange = args[0] as String
            val symbol = args[1] as String
            val executionMode = args[2] as String?
            val quote = dbService.fetchLatestQuote(exchange, symbol) ?: return@answers null
            if (exchange.lowercase() != "hyperliquid" || executionMode.isNullOrBlank()) {
                return@answers quote
            }

            val resolvedExchange = when (executionMode.trim().lowercase()) {
                "forward_paper", "mainnet_live" -> "hyperliquid_mainnet"
                "testnet_live" -> "hyperliquid_testnet"
                else -> null
            } ?: return@answers quote

            if (quote.source.contains("resolved_exchange=")) {
                quote
            } else {
                quote.copy(source = "${quote.source}:resolved_exchange=$resolvedExchange")
            }
        }
        return dbService
    }

    @Test
    fun testHealthEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()

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
            val dbService = quoteAwareDbService()

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
            val dbService = quoteAwareDbService()

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
            val dbService = quoteAwareDbService()

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
    fun testNonHyperliquidOrderEndpointReturnsPaperExecution() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns tradingUserInfo(
                allowedExchanges = listOf("binance", "hyperliquid")
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
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"forward_paper\"").containsMatchIn(body), body)
        assertTrue(Regex("\"exchange\"\\s*:\\s*\"binance\"").containsMatchIn(body), body)
    }

    @Test
    fun testUnifiedOrderRejectsUsersWithoutTradingModes() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns tradingUserInfo(
                allowedExchanges = listOf("binance"),
                allowedTradingModes = emptyList()
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"error\"\\s*:\\s*\"Execution mode not allowed: forward_paper\"").containsMatchIn(body), body)
    }

    @Test
    fun testHyperliquidOrderHonorsExplicitForwardPaperMode() = testApplication {
        lateinit var workerClient: WorkerClient
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            val dbService = quoteAwareDbService()
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
                allowedTradingModes = listOf("forward_paper"),
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
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25","executionMode":"forward_paper"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"forward_paper\"").containsMatchIn(body), body)
        assertTrue(Regex("\"simulated\"\\s*:\\s*true").containsMatchIn(body), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testHyperliquidOrderDefaultsToForwardPaperWhenLiveModeIsNotRequested() = testApplication {
        lateinit var workerClient: WorkerClient
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns tradingUserInfo(
                allowedExchanges = listOf("hyperliquid"),
                allowedTradingModes = listOf("forward_paper")
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
            stubAllowingRiskState(dbService)

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"forward_paper\"").containsMatchIn(body), body)
        assertTrue(Regex("\"simulated\"\\s*:\\s*true").containsMatchIn(body), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testNonHyperliquidOrderEmitsPaperExecutionMetrics() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
            every {
                dbService.logLiveBacktestDrift(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            } throws RuntimeException("drift table unavailable")

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val orderResponse = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25"}""")
        }

        assertEquals(HttpStatusCode.OK, orderResponse.status)

        val metricsResponse = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, metricsResponse.status)
        val metricsBody = metricsResponse.bodyAsText()
        assertTrue(metricsBody.contains("tx_gateway_trading_total_cost_bps"), metricsBody)
        assertTrue(metricsBody.contains("strategy=\"tx_gateway_paper_execution\""), metricsBody)
        assertTrue(metricsBody.contains("execution_mode=\"forward_paper\""), metricsBody)
    }

    @Test
    fun testPaperOrderRecordsAcceptedRiskExposureFromSimulatedFill() = testApplication {
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns tradingUserInfo(
                allowedExchanges = listOf("binance"),
                allowedTradingModes = listOf("forward_paper")
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
            stubAllowingRiskState(dbService)

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"LIMIT","size":"0.25","price":"73010","executionMode":"forward_paper"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify(exactly = 1) {
            dbService.adjustRiskOpenExposure(
                username = "trader1",
                deltaExposureUsd = match { it.compareTo(BigDecimal("18252.5")) == 0 }
            )
        }
    }

    @Test
    fun testNonHyperliquidLimitOrderEndpointReturnsPaperExecution() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"forward_paper\"").containsMatchIn(body), body)
    }

    @Test
    fun testNonHyperliquidOrderEndpointReturnsSimulatedExecutionDetails() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
        assertTrue(Regex("\"simulated\"\\s*:\\s*true").containsMatchIn(body), body)
        assertTrue(body.contains("\"costs\""), body)
        assertTrue(body.contains("\"telemetry\""), body)
        assertTrue(body.contains("\"simulation\""), body)
    }

    @Test
    fun testNonHyperliquidOrderEndpointAppliesPaperFeeSimulation() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
        assertTrue(Regex("\"feeTierAdjustmentBps\"\\s*:\\s*-1\\.5").containsMatchIn(body), body)
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"forward_paper\"").containsMatchIn(body), body)
    }

    @Test
    fun testNonHyperliquidOrderStillRejectsWhenEstimatedSlippageExceedsLimit() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"error\"\\s*:\\s*\"Estimated slippage").containsMatchIn(body), body)
    }

    @Test
    fun testLiveHyperliquidOrderBlockedWhenExecutionSafeguardsReject() = testApplication {
        lateinit var workerClient: WorkerClient
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            val dbService = quoteAwareDbService()
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
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live","maxSlippageBps":0.5}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"error\"\\s*:\\s*\"Estimated slippage").containsMatchIn(body), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testLiveHyperliquidOrderUsesWorkerNotionalAndReconcilesRiskState() = testApplication {
        lateinit var workerClient: WorkerClient
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            dbService = quoteAwareDbService()
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
            stubAllowingRiskState(dbService, username = "trader1", openExposureUsd = "0")
            every { workerClient.submitHyperliquidOrder(any()) } returns mapOf(
                "orderId" to "live-1",
                "status" to "FILLED",
                "executedNotionalUsd" to "3210.55"
            )
            every { workerClient.getHyperliquidBalance("trader1", "test-key") } returns mapOf(
                "accountValue" to "12000.25"
            )
            every { workerClient.getHyperliquidPositions("trader1", "test-key") } returns listOf(
                mapOf(
                    "size" to "0.25",
                    "entryPrice" to "70000",
                    "unrealizedPnl" to "125.5"
                ),
                mapOf(
                    "size" to "-0.1",
                    "marginUsed" to "800",
                    "unrealizedPnl" to "-20"
                )
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"orderId\""), body)

        verify(exactly = 1) {
            dbService.adjustRiskOpenExposure(
                username = "trader1",
                deltaExposureUsd = match { it.compareTo(BigDecimal("3210.55")) == 0 }
            )
        }
        verify(exactly = 1) { workerClient.getHyperliquidBalance("trader1", "test-key") }
        verify(exactly = 1) { workerClient.getHyperliquidPositions("trader1", "test-key") }

        val patches = mutableListOf<RiskAccountStatePatch>()
        verify(exactly = 1) { dbService.upsertRiskAccountState("trader1", capture(patches)) }
        val patch = patches.single()
        assertTrue(patch.accountEquityUsd?.compareTo(BigDecimal("12000.25")) == 0)
        assertTrue(patch.openExposureUsd?.compareTo(BigDecimal("18300")) == 0)
        assertTrue(patch.unrealizedPnlUsd?.compareTo(BigDecimal("105.5")) == 0)
    }

    @Test
    fun testLiveHyperliquidRiskReconciliationUsesLeverageWhenEntryPriceMissing() = testApplication {
        lateinit var workerClient: WorkerClient
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            dbService = quoteAwareDbService()
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
            stubAllowingRiskState(dbService, username = "trader1", openExposureUsd = "0")
            every { workerClient.submitHyperliquidOrder(any()) } returns mapOf(
                "orderId" to "live-2",
                "status" to "FILLED",
                "executedNotionalUsd" to "1100.00"
            )
            every { workerClient.getHyperliquidBalance("trader1", "test-key") } returns mapOf(
                "accountValue" to "12000.25"
            )
            every { workerClient.getHyperliquidPositions("trader1", "test-key") } returns listOf(
                mapOf(
                    "size" to "0.10",
                    "marginUsed" to "800",
                    "leverage" to "3.5",
                    "unrealizedPnl" to "42.0"
                )
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val patches = mutableListOf<RiskAccountStatePatch>()
        verify(exactly = 1) { dbService.upsertRiskAccountState("trader1", capture(patches)) }
        val patch = patches.single()
        assertTrue(patch.openExposureUsd?.compareTo(BigDecimal("2800.0")) == 0)
        assertTrue(patch.unrealizedPnlUsd?.compareTo(BigDecimal("42.0")) == 0)
    }

    @Test
    fun testLiveHyperliquidOrderFallsBackToFilledSizeTimesPriceForNotional() = testApplication {
        lateinit var workerClient: WorkerClient
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            dbService = quoteAwareDbService()
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
            stubAllowingRiskState(dbService)
            every { workerClient.submitHyperliquidOrder(any()) } returns mapOf(
                "orderId" to "live-2",
                "status" to "PARTIALLY_FILLED",
                "filledSize" to "0.05",
                "fillPrice" to "72000"
            )
            every { workerClient.getHyperliquidBalance("trader1", "test-key") } returns mapOf(
                "accountValue" to "10000"
            )
            every { workerClient.getHyperliquidPositions("trader1", "test-key") } returns emptyList()

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify(exactly = 1) {
            dbService.adjustRiskOpenExposure(
                username = "trader1",
                deltaExposureUsd = match { it.compareTo(BigDecimal("3600.00000000")) == 0 }
            )
        }
    }

    @Test
    fun testLiveHyperliquidOrderFallsBackToFillRatioWhenOnlyFilledSizePresent() = testApplication {
        lateinit var workerClient: WorkerClient
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            dbService = quoteAwareDbService()
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
            stubAllowingRiskState(dbService)
            every { workerClient.submitHyperliquidOrder(any()) } returns mapOf(
                "orderId" to "live-3",
                "status" to "PARTIALLY_FILLED",
                "filledSize" to "0.025"
            )
            every { workerClient.getHyperliquidBalance("trader1", "test-key") } returns mapOf(
                "accountValue" to "10000"
            )
            every { workerClient.getHyperliquidPositions("trader1", "test-key") } returns emptyList()

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify(exactly = 1) {
            dbService.adjustRiskOpenExposure(
                username = "trader1",
                deltaExposureUsd = match { it.compareTo(BigDecimal("1825.25000000")) == 0 }
            )
        }
    }

    @Test
    fun testLiveHyperliquidOrderFallsBackToEstimatedNotionalWhenStatusIsFilled() = testApplication {
        lateinit var workerClient: WorkerClient
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            dbService = quoteAwareDbService()
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
            stubAllowingRiskState(dbService)
            every { workerClient.submitHyperliquidOrder(any()) } returns mapOf(
                "orderId" to "live-4",
                "status" to "FILLED"
            )
            every { workerClient.getHyperliquidBalance("trader1", "test-key") } returns mapOf(
                "accountValue" to "10000"
            )
            every { workerClient.getHyperliquidPositions("trader1", "test-key") } returns emptyList()

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify(exactly = 1) {
            dbService.adjustRiskOpenExposure(
                username = "trader1",
                deltaExposureUsd = match { it.compareTo(BigDecimal("7301.00000000")) == 0 }
            )
        }
    }

    @Test
    fun testLiveHyperliquidOrderReservesEstimatedNotionalWhenWorkerReturnsPendingHandle() = testApplication {
        lateinit var workerClient: WorkerClient
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns tradingUserInfo(
                allowedExchanges = listOf("hyperliquid"),
                allowedTradingModes = listOf("forward_paper", "testnet_live")
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
            stubAllowingRiskState(dbService)
            every { workerClient.submitHyperliquidOrder(any()) } returns mapOf(
                "orderId" to "live-pending",
                "status" to "PENDING"
            )
            every { workerClient.getHyperliquidBalance("trader1", "test-key") } returns mapOf(
                "accountValue" to "10000"
            )
            every { workerClient.getHyperliquidPositions("trader1", "test-key") } returns emptyList()

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify(exactly = 1) {
            dbService.adjustRiskOpenExposure(
                username = "trader1",
                deltaExposureUsd = match { it.compareTo(BigDecimal("7301.0")) == 0 }
            )
        }
    }

    @Test
    fun testLiveHyperliquidOrderStillSucceedsWhenRiskReconciliationFails() = testApplication {
        lateinit var workerClient: WorkerClient
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            dbService = quoteAwareDbService()
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
            stubAllowingRiskState(dbService)
            every { workerClient.submitHyperliquidOrder(any()) } returns mapOf(
                "orderId" to "live-5",
                "status" to "FILLED",
                "executedNotionalUsd" to "100"
            )
            every {
                workerClient.getHyperliquidBalance("trader1", "test-key")
            } throws RuntimeException("balance unavailable")

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("live-5"), body)
        verify(exactly = 1) {
            dbService.adjustRiskOpenExposure(
                username = "trader1",
                deltaExposureUsd = match { it.compareTo(BigDecimal("100")) == 0 }
            )
        }
        verify(exactly = 0) { dbService.upsertRiskAccountState(any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsWhenOrderValueExceedsRiskLimit() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
    fun testNonHyperliquidOrderRejectsPostOnlyThatWouldTakeLiquidity() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"error\"\\s*:\\s*\"Post-only limit order would cross the spread").containsMatchIn(body), body)
    }

    @Test
    fun testPaperOrderRejectsStaleQuoteSnapshot() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
            val dbService = quoteAwareDbService()
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
            dbService = quoteAwareDbService()
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
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsInvalidSymbolEarly() = testApplication {
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            dbService = quoteAwareDbService()
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
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsInvalidSideEarly() = testApplication {
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            dbService = quoteAwareDbService()
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
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsMarketOrderPriceEarly() = testApplication {
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            dbService = quoteAwareDbService()
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
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsOversizedCancelWindowEarly() = testApplication {
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            dbService = quoteAwareDbService()
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
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any(), any()) }
    }

    @Test
    fun testUnifiedOrderRejectsPostOnlyMarketOrderEarly() = testApplication {
        lateinit var dbService: DatabaseService
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            dbService = quoteAwareDbService()
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
        verify(exactly = 0) { dbService.fetchLatestQuote(any(), any(), any()) }
    }

    @Test
    fun testLiveHyperliquidOrderRejectsBlankCredentialEarly() = testApplication {
        lateinit var workerClient: WorkerClient
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            val dbService = quoteAwareDbService()
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
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
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
            val dbService = quoteAwareDbService()
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
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
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
            val dbService = quoteAwareDbService()
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
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Orderbook-backed quote required for live order risk checks"), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testLiveHyperliquidOrderRejectsWhenQuoteExchangeDoesNotMatchConfiguredEnvironment() = testApplication {
        lateinit var workerClient: WorkerClient
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            val dbService = quoteAwareDbService()
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
            every { dbService.fetchLatestQuote("hyperliquid", "BTC", "testnet_live") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = freshQuoteTimestamp(),
                source = "orderbook_data:canonical:resolved_exchange=hyperliquid_mainnet"
            )

            configureApp(
                authService = authService,
                ldapService = ldapService,
                workerClient = workerClient,
                dbService = dbService,
                requiredHyperliquidQuoteExchange = "hyperliquid_testnet"
            )
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"testnet_live"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Quote exchange mismatch for live order risk checks"), body)
        verify(exactly = 0) { workerClient.submitHyperliquidOrder(any()) }
    }

    @Test
    fun testUnifiedOrderRejectsWhenRiskEngineBlocksNewExposure() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
    fun testMetricsEndpointExposesPrometheusPayload() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(contentType.contains("text/plain"), contentType)
        val body = response.bodyAsText()
        assertTrue(body.contains("ktor_http_server_requests"), body)
        assertTrue(body.contains("tx_gateway_trading_slippage_drift_bps"), body)
        assertTrue(body.contains("tx_gateway_trading_total_cost_bps"), body)
    }

    @Test
    fun testHyperliquidOrdersRouteForwardsSymbolFilterToWorker() = testApplication {
        lateinit var workerClient: WorkerClient
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            val dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { workerClient.getHyperliquidOrders("trader1", "test-key", "BTC") } returns listOf(
                mapOf(
                    "orderId" to "1",
                    "symbol" to "BTC"
                )
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/hyperliquid/orders?symbol=BTC") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"symbol\":\"BTC\"") || body.contains("\"symbol\": \"BTC\""), body)
        verify(exactly = 1) { workerClient.getHyperliquidOrders("trader1", "test-key", "BTC") }
    }

    @Test
    fun testHyperliquidCloseAllRouteInvokesWorker() = testApplication {
        lateinit var workerClient: WorkerClient
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            workerClient = mockk(relaxed = true)
            val dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { workerClient.closeAllHyperliquidPositions("trader1", "test-key") } returns mapOf(
                "status" to "completed",
                "closed" to 2
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/hyperliquid/close-all") {
            header(HttpHeaders.Authorization, "Bearer token")
            header("X-Credential-hyperliquid", "test-key")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"closed\":2") || body.contains("\"closed\": 2"), body)
        verify(exactly = 1) { workerClient.closeAllHyperliquidPositions("trader1", "test-key") }
    }

    @Test
    fun testUserTradingProfileEndpointReturnsAudit() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getTradingAccountAudit("trader1") } returns tradingAudit(
                rawAllowedExchanges = listOf("hyperliquid", "kraken"),
                allowedExchanges = listOf("hyperliquid"),
                findings = listOf("allowedExchanges contains unsupported values: kraken")
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/user/trading-profile") {
            header(HttpHeaders.Authorization, "Bearer token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("kraken"), body)
        assertTrue(body.contains("unsupported values"), body)
    }

    @Test
    fun testTradingHomogeneityEndpointRequiresReservedGroup() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns tradingUserInfo(groups = listOf("traders"))

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/accounts/trading/homogeneity") {
            header(HttpHeaders.Authorization, "Bearer token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Reserved trading role required"), body)
    }

    @Test
    fun testTradingHomogeneityEndpointReturnsAuditSummaryForReservedGroup() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns tradingUserInfo(groups = listOf("admins"))
            every { ldapService.listTradingAccountAudits() } returns listOf(
                tradingAudit(username = "trader1", groups = listOf("admins")),
                tradingAudit(
                    username = "trader2",
                    groups = listOf("traders"),
                    hasTradingObjectClass = false,
                    rawAllowedTradingModes = listOf("mainnet_live"),
                    allowedTradingModes = listOf("mainnet_live"),
                    findings = listOf("mainnet_live allowed without reserved group membership (admins)")
                )
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/accounts/trading/homogeneity") {
            header(HttpHeaders.Authorization, "Bearer token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"totalAccounts\":2") || body.contains("\"totalAccounts\": 2"), body)
        assertTrue(body.contains("\"accountsWithMainnetLive\":1") || body.contains("\"accountsWithMainnetLive\": 1"), body)
        assertTrue(body.contains("trader2"), body)
    }

    @Test
    fun testMainnetLiveOrderRequiresReservedGroup() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every {
                ldapService.getUserInfo("trader1")
            } returns tradingUserInfo(
                groups = listOf("traders"),
                allowedTradingModes = listOf("forward_paper", "mainnet_live")
            )
            every { dbService.checkRateLimit("trader1", any()) } returns true

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/hyperliquid/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.1","executionMode":"mainnet_live"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("requires reserved trading role"), body)
    }

    @Test
    fun testLegacyHyperliquidOrderEndpointIsDeprecated() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = quoteAwareDbService()
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
